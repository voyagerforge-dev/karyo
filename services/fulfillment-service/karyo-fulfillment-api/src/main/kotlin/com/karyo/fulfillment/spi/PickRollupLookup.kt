package com.karyo.fulfillment.spi

import com.karyo.fulfillment.vo.PickRollup
import com.karyo.fulfillment.vo.TerminalSliceAmount

/**
 * In-process pick-rollup lookup contract. Implemented by fulfillment-core and consumed by
 * other modules (e.g. orders, for the derived pickedAmount read on a delivery-order line)
 * instead of a cross-service REST client.
 */
interface PickRollupLookup {
    /**
     * Batched picked-quantity rollup for delivery-order lines. One grouped query per call --
     * absent line ids simply have no entry (callers render zero, never fabricate).
     * Counts ONLY picks at [com.karyo.fulfillment.vo.PickState.PICKED] -- pickedAmount is
     * written solely at confirm. pickedAmount = ordered-SKU picks (substitutedItemDataId IS
     * NULL); substitutedAmount = substitute-SKU picks credited to the line. They are split
     * because substitution follow-ups share the parent's deliveryOrderLineId with a DIFFERENT
     * SKU -- a naive sum would silently mix SKUs.
     */
    fun pickedAmountsByLineIds(lineIds: Set<Long>): Map<Long, PickRollup>

    /**
     * Batched per-(line, source stock unit) sums of `plannedAmount` over TERMINAL picks
     * (PICKED(600)/CANCELED(800)) for [lineIds] — the "already resolved on the stock side" figure
     * `OrderService.cancel` subtracts from its recorded reservation slices (see
     * [TerminalSliceAmount]). One grouped query per call; an empty [lineIds] returns an empty list.
     *
     * Unlike [pickedAmountsByLineIds], [clientId] is EXPLICIT rather than read from the ambient
     * [com.karyo.security.TenantContext]: the caller passes the ORDER's owning client, so a cancel
     * driven by an OPS/system principal (whose ambient clientId is 0) is scoped to the goods owner
     * that actually holds the reservations, not silently to client 0.
     */
    fun terminalPlannedBySlice(lineIds: Set<Long>, clientId: Long): List<TerminalSliceAmount>
}
