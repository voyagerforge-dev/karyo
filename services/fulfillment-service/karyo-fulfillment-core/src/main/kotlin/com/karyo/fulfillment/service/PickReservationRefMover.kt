package com.karyo.fulfillment.service

import com.karyo.fulfillment.repository.PickRepository
import com.karyo.inventory.api.spi.ReservationRefMover
import com.karyo.inventory.api.spi.ReservationSourceState
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

/**
 * Row 13, fulfillment half of [ReservationRefMover]: repoints open picks. Terminal picks
 * (PICKED(600) or CANCELED(800)) are deliberately left pointing at the stock unit they actually
 * consumed: they are a record of what happened, not a live claim. Open picks are never stale (see
 * [OpenPickSourceState]) so this mover never splits -- [moveRefs] is always a full bulk repoint,
 * and [readSourceState] needs no query at all: the answer is fixed, not merely cheap.
 */
@ApplicationScoped
class PickReservationRefMover(
    private val pickRepository: PickRepository,
) : ReservationRefMover {

    override fun readSourceState(stockUnitId: Long, clientId: Long): ReservationSourceState = OpenPickSourceState

    override fun countRefs(stockUnitId: Long, clientId: Long): Int =
        pickRepository.countOpenBySource(stockUnitId, clientId)

    override fun moveRefs(fromStockUnitId: Long, toStockUnitId: Long, clientId: Long, state: ReservationSourceState) {
        pickRepository.repointOpenPickSource(fromStockUnitId, toStockUnitId, clientId)
    }

    /**
     * The fixed state for this mover: [staleCount] is always 0 because [countRefs] already
     * filters to non-terminal state (`state < PICKED`), so a pick this mover reports as a live
     * reference can never also be a stale one -- staleness is only possible on the orders side,
     * where a reservation row survives its backing pick going terminal. [liveAmount] is always
     * ZERO because an open pick reference has no reservation *amount* of its own -- the amount
     * lives on the `OrderLineReservation` row it is derived from, in the orders module. Counting
     * it here would double-count the same live amount the orders-side mover already reports.
     */
    private object OpenPickSourceState : ReservationSourceState {
        override val staleCount: Int = 0
        override val liveAmount: BigDecimal = BigDecimal.ZERO
    }
}
