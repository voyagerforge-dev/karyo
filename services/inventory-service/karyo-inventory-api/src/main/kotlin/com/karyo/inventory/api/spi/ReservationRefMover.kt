package com.karyo.inventory.api.spi

import java.math.BigDecimal

/**
 * Row 13: moves a stock unit's reservation *references* when the reserved amount itself moves
 * (`ReservationTransferService.transferReservation`). A reservation is not one row: the amount
 * lives on the stock unit here in inventory, while the order line's claim lives in the orders
 * module and the open pick's claim lives in fulfillment. All three must move inside one
 * transaction or none.
 *
 * Declared here and implemented by the foreign cores, the same direction as
 * [OpenPickGuard]: inventory owns the contract, so no module gains a dependency on another
 * module's core. Implementations are discovered as CDI beans and every one is called; SPI calls
 * join the caller's transaction (ADR-037), which is what makes the move atomic.
 *
 * Every method takes [clientId] explicitly rather than reading an ambient TenantContext, per the
 * standing doctrine for cross-module reads.
 *
 * defect-burndown-5 review fix (findings 1 and 3): a reservation's "live" figure and its
 * terminal/split bookkeeping are computed per **slice** -- a `(lineId, stockUnitId)` pair, the
 * SAME grouping key `OrderService.unhandledRemainders` folds by, because a slice can be backed by
 * MORE THAN ONE bookkeeping row (release-then-retry-reservation records a second row for the same
 * slice; see that method's KDoc). Folding by slice BEFORE subtracting the slice's terminal total
 * once is required for correctness: two rows of 15 each on a slice whose terminal total is 20
 * fold to 30, leaving 10 truly live -- subtracting 20 from EACH row independently (the pre-fix
 * shape) would floor both at zero and strand 10 units as permanently untransferable. [readSourceState]
 * computes this ONCE per transfer (one row fetch, one terminal-plan query) and the resulting
 * [ReservationSourceState] is threaded into both the guard checks and [moveRefs], so the
 * validated live figure and the actual row surgery are, by construction, the same computation --
 * never two independent ones that could drift apart.
 */
interface ReservationRefMover {
    /**
     * One-shot read of everything [com.karyo.inventory.service.ReservationTransferService] needs
     * to validate a transfer FROM [stockUnitId] -- computed with one row fetch and one
     * terminal-plan query (never a separate query per validation concern). The returned state is
     * opaque to the caller: hold it and hand it back, unchanged, to [moveRefs] if and when the
     * transfer proceeds. A state is valid only for the [stockUnitId]/[clientId] it was read for,
     * within the SAME transaction (no other writer can run between the read and the move, since
     * both happen inside one `@Transactional` method) -- passing a state to a DIFFERENT mover, or
     * one read for a different stock unit, is a caller bug, not a supported use.
     */
    fun readSourceState(stockUnitId: Long, clientId: Long): ReservationSourceState

    /** Live references this implementation holds on [stockUnitId]. Used to refuse a partial move. */
    fun countRefs(stockUnitId: Long, clientId: Long): Int

    /**
     * Moves every reference from [fromStockUnitId] to [toStockUnitId], using [state] (from
     * [readSourceState] on this SAME [fromStockUnitId]) instead of re-reading. When
     * [ReservationSourceState.staleCount] is zero, every reference is live and the whole set of
     * rows repoints in one bulk statement. When it is greater than zero (this implementation
     * carries a mix of terminal-consumed and live history on the same slice), the implementation
     * splits per SLICE instead: within a slice, rows are ordered deterministically (by id) and the
     * terminal portion is allocated to the EARLIEST rows first -- a row fully covered by the
     * remaining terminal allocation stays on the source untouched, the row where the terminal
     * allocation runs out shrinks in place to its terminal share (creating a NEW row for its live
     * remainder), and every row after that repoints in full. A mover whose references can never be
     * stale (e.g. [PickReservationRefMover], since it only tracks non-terminal state to begin
     * with) has nothing to split and always performs a full repoint regardless of [state].
     */
    fun moveRefs(fromStockUnitId: Long, toStockUnitId: Long, clientId: Long, state: ReservationSourceState)
}

/**
 * Opaque per-mover snapshot returned by [ReservationRefMover.readSourceState]. The two aggregate
 * figures a transfer's guards need, computed once and shared with [ReservationRefMover.moveRefs]
 * so validation and the actual row surgery can never disagree (defect-burndown-5 review finding 3).
 */
interface ReservationSourceState {
    /**
     * Count of "stale" references on this state's stock unit: references whose backing pick
     * already went terminal, yet whose bookkeeping row is still recorded against this stock unit.
     * [ReservationRefMover.moveRefs] uses this to choose the fast full-repoint path (zero) versus
     * the per-slice split path (greater than zero). Zero for a mover whose [ReservationRefMover.countRefs]
     * already filters to non-terminal state, since a non-terminal reference cannot also be stale.
     */
    val staleCount: Int

    /**
     * The sum of this implementation's LIVE reservation, folded per slice: for a mover whose rows
     * can carry mixed terminal/live history (the orders-side mover), this groups rows by
     * `lineId` (the slice within this stock unit), sums each slice's row amounts, subtracts that
     * slice's terminal total ONCE, floors at zero, and sums across slices -- the same grouping
     * `OrderService.unhandledRemainders` performs, so this figure is genuinely authoritative, not
     * an approximation of it. A mover whose references can never be stale returns ZERO here: an
     * open pick reference does not itself carry a reservation *amount* (the amount lives on the
     * `OrderLineReservation` row it is derived from), so it has nothing to add to the sum --
     * asymmetric with [ReservationRefMover.countRefs], which counts references, not amounts.
     *
     * [com.karyo.inventory.service.ReservationTransferService.transferReservation] sums this
     * across every mover and, on a source with any stale reference, requires the requested
     * transfer amount to equal that sum exactly (409 otherwise) -- the operation always moves
     * ALL live reservation on a mixed source, never a sub-slice of it.
     */
    val liveAmount: BigDecimal
}
