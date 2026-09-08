package com.karyo.orders.service

import com.karyo.fulfillment.spi.PickRollupLookup
import com.karyo.inventory.api.spi.PurgeBlockerLookup
import com.karyo.orders.domain.model.OrderLineReservation
import com.karyo.orders.repository.OrderLineReservationRepository
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

/**
 * Row 18, orders half of [PurgeBlockerLookup]: a candidate stock unit blocks its purge only while
 * at least one of its `order_line_reservations` slices still has a LIVE remainder -- a wholly
 * terminal-consumed slice no longer counts.
 *
 * Row 1 fix (defect-tail-2, 2026-08-17, closing the final-adversarial-review row filed against
 * defect-burndown-5): [blockedStockUnitIds] used to block on row EXISTENCE alone, with no
 * terminal-netting arithmetic -- see git history for the earlier version of this KDoc, which
 * documented that as intentional. It was not: [OrderLineReservationRefMover] formalized that a
 * reservation row can carry a slice that is entirely terminal-consumed -- `readSourceState`'s own
 * per-slice fold subtracts a slice's terminal total and floors at zero, and its `splitBySlice`
 * path leaves such a row on the source stock unit PERMANENTLY once the remaining terminal
 * allocation fully covers it. That row will never again represent a live claim, so counting it as
 * a block could combine with `StockUnitRepository.findPurgeCandidates`'s batch cap to starve the
 * reaper's stock half for a tenant whose permanently-blocked, terminal-only rows filled every
 * tick's candidate window (the row's other half, an `ORDER BY` on `findPurgeCandidates`, is fixed
 * separately -- see that method's KDoc).
 *
 * **Safety argument** (same one [OrderLineReservationRefMover]'s KDoc makes for repointing, applied
 * here to purging instead): a slice is `(lineId, stockUnitId)`. `OrderService.unhandledRemainders`
 * -- the ONLY code path that ever releases/deletes an `order_line_reservations` row -- nets each
 * slice's row-amount total against that slice's terminal-planned total (PICKED/CANCELED picks
 * whose `sourceStockUnitId` equals the slice's stock unit) and releases exactly the difference. A
 * slice whose fold is already zero therefore has NOTHING left for a future cancel to release
 * against this stock unit -- purging it destroys no pending release, because there is no pending
 * release. A terminal pick's own `sourceStockUnitId` is never moved (rows are ids-only, no FK), so
 * the netting is stable under purge: nothing downstream will ever again point a terminal pick's
 * planned amount at a DIFFERENT stock unit and expect this slice to still be here.
 *
 * **Batching discipline:** [blockedStockUnitIds] issues exactly TWO queries for the WHOLE
 * candidate batch, regardless of its size -- never one per candidate:
 *  1. [OrderLineReservationRepository.findByStockUnitIds] -- every reservation row for every
 *     candidate, grouped in memory by `stockUnitId`.
 *  2. [PickRollupLookup.terminalPlannedBySlice] -- already batched by `lineId` across every
 *     stock unit involved (it returns each slice's OWN `sourceStockUnitId`), so the involved
 *     line ids from query 1 feed ONE call here, not one per stock unit.
 * Both are keyed in memory afterward; no further round trips.
 *
 * Orders never references a unit load id directly -- only stock units, via reservations -- and
 * `goods_receipt_lines.unit_load_id` is explicitly historical (see [PurgeBlockerLookup]'s KDoc),
 * so [blockedUnitLoadIds] is always empty.
 */
@ApplicationScoped
class OrderPurgeBlockerLookup(
    private val repository: OrderLineReservationRepository,
    private val pickRollupLookup: PickRollupLookup,
) : PurgeBlockerLookup {

    override fun blockedStockUnitIds(candidateIds: Collection<Long>, clientId: Long): Set<Long> {
        val rows = repository.findByStockUnitIds(candidateIds, clientId)
        if (rows.isEmpty()) return emptySet()

        val lineIds = rows.map { it.lineId }.toSet()
        val terminalBySlice = pickRollupLookup
            .terminalPlannedBySlice(lineIds, clientId)
            .associate { (it.sourceStockUnitId to it.deliveryOrderLineId) to it.plannedAmount }

        return rows.groupBy { it.stockUnitId }
            .filterValues { stockUnitRows -> hasLiveSlice(stockUnitRows, terminalBySlice) }
            .keys
    }

    override fun blockedUnitLoadIds(candidateIds: Collection<Long>, clientId: Long): Set<Long> = emptySet()

    /**
     * Per-slice fold for ONE stock unit's rows (grouped by `lineId`): sums each slice's row
     * amounts, subtracts that slice's terminal-planned total from [terminalBySlice] once, floors
     * at zero, exactly the same arithmetic
     * [OrderLineReservationRefMover.readSourceState]'s `liveAmount` fold performs. Blocks
     * ([true]) as soon as ANY slice's live remainder is positive -- a stock unit with several
     * slices, some fully terminal-consumed and one still live, still blocks (see the "mixed
     * slice" scenario in the test suite).
     */
    private fun hasLiveSlice(
        rows: List<OrderLineReservation>,
        terminalBySlice: Map<Pair<Long, Long>, BigDecimal>,
    ): Boolean {
        val stockUnitId = rows.first().stockUnitId
        return rows.groupBy { it.lineId }.any { (lineId, sliceRows) ->
            val sliceTotal = sliceRows.fold(BigDecimal.ZERO) { sum, row -> sum.add(row.amount) }
            val terminal = terminalBySlice[stockUnitId to lineId] ?: BigDecimal.ZERO
            sliceTotal.subtract(terminal).max(BigDecimal.ZERO).signum() > 0
        }
    }
}
