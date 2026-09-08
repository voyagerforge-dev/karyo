package com.karyo.orders.service

import com.karyo.fulfillment.spi.PickRollupLookup
import com.karyo.inventory.api.spi.ReservationRefMover
import com.karyo.inventory.api.spi.ReservationSourceState
import com.karyo.orders.domain.model.OrderLineReservation
import com.karyo.orders.repository.OrderLineReservationRepository
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

/**
 * Row 13, orders half of [ReservationRefMover]: repoints `order_line_reservations` rows. Every
 * such row is a live claim by definition (they are deleted, not marked terminal, when an order
 * cancels), so [countRefs] applies no state filter -- but a row can still be STALE (its backing
 * pick already went terminal while the row itself was never cleaned up), and moving the terminal
 * portion of such a row is what corrupts [OrderService.unhandledRemainders]'s netting join (see
 * [ReservationRefMover]'s KDoc).
 *
 * defect-burndown-5 (:1485), reviewed and fixed twice:
 *  - **Split, not refuse-whole**: [moveRefs] used to be an unconditional bulk repoint; it now
 *    splits when the source carries stale history, keeping the terminal-consumed portion of a
 *    slice keyed to the stock unit that actually consumed it, forever.
 *  - **Per-SLICE, not per-row, arithmetic** (review finding 1): a slice -- a `(lineId,
 *    stockUnitId)` pair -- can be backed by MORE THAN ONE `OrderLineReservation` row (release
 *    then retry-reservation records a second row for the same slice; see
 *    `OrderService.unhandledRemainders`'s KDoc). Subtracting a slice's terminal total from EACH of
 *    its rows independently under-counts: two rows of 15 with a terminal total of 20 would floor
 *    BOTH at zero and strand the true 10-unit live remainder as permanently untransferable.
 *    [readSourceState] groups rows by `lineId`, folds each slice's row amounts, subtracts the
 *    slice's terminal ONCE, and floors at zero -- matching `unhandledRemainders`'s own grouping
 *    exactly. [moveRefs]'s split allocates a slice's terminal portion across its rows
 *    deterministically: rows are ordered by id, and the terminal amount is consumed against the
 *    EARLIEST rows first -- a row entirely covered by the remaining terminal allocation stays on
 *    the source untouched, the row where the allocation runs out shrinks in place to its terminal
 *    share (its live remainder becomes a NEW row on the target), and every row after that repoints
 *    in full. This allocation rule is arbitrary in the sense that any deterministic rule preserves
 *    the invariant (only the SLICE-level split amount matters to `unhandledRemainders`, not which
 *    row carries which piece) -- earliest-first was chosen for simplicity, no other reason.
 *  - **One read, shared** (review finding 3): [countStaleRefs] and `liveAmount` used to be
 *    independent methods, each re-fetching the same rows and re-deriving the same terminal map --
 *    up to three round trips per stock unit per transfer. [readSourceState] now computes both
 *    figures from ONE row fetch and ONE [PickRollupLookup.terminalPlannedBySlice] call, and the
 *    resulting [ReservationSourceState] is what [moveRefs] operates on -- the validated live
 *    figure and the actual row surgery are the same computation, not two that could drift apart.
 */
@ApplicationScoped
class OrderLineReservationRefMover(
    private val repository: OrderLineReservationRepository,
    private val pickRollupLookup: PickRollupLookup,
) : ReservationRefMover {

    override fun readSourceState(stockUnitId: Long, clientId: Long): ReservationSourceState {
        val rows = repository.findByStockUnit(stockUnitId, clientId)
        if (rows.isEmpty()) return OrderReservationSourceState(rows, emptyMap(), 0, BigDecimal.ZERO)
        val terminalByLine = terminalPlannedByLine(rows, stockUnitId, clientId)
        val staleCount = rows.count { (terminalByLine[it.lineId] ?: BigDecimal.ZERO).signum() > 0 }
        val liveAmount = slicesLiveTotal(rows, terminalByLine)
        return OrderReservationSourceState(rows, terminalByLine, staleCount, liveAmount)
    }

    override fun countRefs(stockUnitId: Long, clientId: Long): Int =
        repository.countByStockUnit(stockUnitId, clientId)

    override fun moveRefs(fromStockUnitId: Long, toStockUnitId: Long, clientId: Long, state: ReservationSourceState) {
        require(state is OrderReservationSourceState) {
            "state must come from this mover's own readSourceState on the same stock unit"
        }
        if (state.rows.isEmpty()) return
        if (state.staleCount == 0) {
            // Fast path: nothing on this stock unit is stale, so every row is a full live claim.
            repository.repointStockUnit(fromStockUnitId, toStockUnitId, clientId)
            return
        }
        splitBySlice(state.rows, state.terminalByLine, toStockUnitId)
    }

    /**
     * Per-slice fold: groups [rows] by `lineId`, sums each slice's row amounts, subtracts that
     * slice's [terminalByLine] total once, floors at zero, and sums across slices -- the same
     * grouping `OrderService.unhandledRemainders` performs. See the class KDoc for why this must
     * be per slice, not per row.
     */
    private fun slicesLiveTotal(rows: List<OrderLineReservation>, terminalByLine: Map<Long, BigDecimal>): BigDecimal =
        rows.groupBy { it.lineId }.entries.fold(BigDecimal.ZERO) { acc, (lineId, sliceRows) ->
            val sliceTotal = sliceRows.fold(BigDecimal.ZERO) { sum, row -> sum.add(row.amount) }
            val terminal = terminalByLine[lineId] ?: BigDecimal.ZERO
            acc.add(sliceTotal.subtract(terminal).max(BigDecimal.ZERO))
        }

    /**
     * The row-surgery counterpart of [slicesLiveTotal]: per slice (grouped by `lineId`), allocates
     * the slice's terminal total across its rows ordered by id, earliest first. See the class
     * KDoc for the allocation rule and why it is safe.
     */
    private fun splitBySlice(rows: List<OrderLineReservation>, terminalByLine: Map<Long, BigDecimal>, toStockUnitId: Long) {
        rows.groupBy { it.lineId }.forEach { (lineId, sliceRows) ->
            var remainingTerminal = terminalByLine[lineId] ?: BigDecimal.ZERO
            sliceRows.sortedBy { it.id }.forEach { row ->
                when {
                    remainingTerminal.signum() <= 0 -> row.stockUnitId = toStockUnitId
                    remainingTerminal >= row.amount -> remainingTerminal = remainingTerminal.subtract(row.amount)
                    else -> {
                        val keep = remainingTerminal
                        val live = row.amount.subtract(keep)
                        row.amount = keep
                        repository.persist(
                            OrderLineReservation().apply {
                                this.lineId = lineId
                                stockUnitId = toStockUnitId
                                amount = live
                            },
                        )
                        remainingTerminal = BigDecimal.ZERO
                    }
                }
            }
        }
    }

    /**
     * Batched terminal-planned lookup for [rows]' line ids, filtered to entries whose own
     * `sourceStockUnitId` equals [stockUnitId] (a terminal pick's source is never moved). One
     * lookup call per [readSourceState] invocation, never one per row or one per slice.
     */
    private fun terminalPlannedByLine(
        rows: List<OrderLineReservation>,
        stockUnitId: Long,
        clientId: Long,
    ): Map<Long, BigDecimal> =
        pickRollupLookup
            .terminalPlannedBySlice(rows.map { it.lineId }.toSet(), clientId)
            .filter { it.sourceStockUnitId == stockUnitId }
            .associate { it.deliveryOrderLineId to it.plannedAmount }

    /**
     * This mover's own [ReservationSourceState]: carries the already-loaded, still-managed [rows]
     * and the [terminalByLine] map they were computed from, so [moveRefs] performs zero additional
     * reads. Private on purpose -- an outside caller has no business constructing one.
     */
    private class OrderReservationSourceState(
        val rows: List<OrderLineReservation>,
        val terminalByLine: Map<Long, BigDecimal>,
        override val staleCount: Int,
        override val liveAmount: BigDecimal,
    ) : ReservationSourceState
}
