package com.karyo.fulfillment.api.v1.dto

import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.service.PickWeightVolumeCalculator
import com.karyo.product.spi.ProductMeasures
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.LocalDate

data class ReleaseToPickingRequest(
    @field:NotNull val deliveryOrderId: Long,
    /**
     * Row 17: caller-supplied pick-bin UnitLoadType id, overriding
     * `karyo.fulfillment.pick-bin-unit-load-type-id`. Advisory/unvalidated beyond the existing
     * `UnitLoadType` existence check `UnitLoadService.create` already performs (no new
     * cross-module validation SPI — adjudication 4) — the caller is trusted to name a sane
     * aggregating PICKING type.
     */
    val targetUnitLoadTypeId: Long? = null,
)

data class ConfirmPickRequest(
    @field:NotNull @field:Positive val pickedAmount: BigDecimal,
    val targetUnitLoadId: Long? = null,
)

/**
 * Row 20 (V605): exactly one of [stockUnitIds]/[unitLoadId] is required — enforced in
 * [com.karyo.fulfillment.service.ExtinguishService.extinguish] (400 otherwise), not by bean
 * validation, since "exactly one of two optional fields" isn't expressible with a single
 * `@field:` annotation here.
 */
data class ExtinguishRequest(
    val stockUnitIds: List<Long>? = null,
    val unitLoadId: Long? = null,
    /**
     * Same override semantics as [ReleaseToPickingRequest.targetUnitLoadTypeId] (row 17) —
     * honored only when a new EXT order is minted; refused (409) if supplied while an open EXT
     * order for the owner would absorb the picks (its pick bin already exists, M2 fix).
     */
    val targetUnitLoadTypeId: Long? = null,
)

data class PickResponse(
    val id: Long,
    /** Null for an EXTINGUISH pick (V605, WORKLIST row 20) — no backing DeliveryOrder line. */
    val deliveryOrderLineId: Long?,
    val itemDataId: Long,
    val itemDataNumber: String,
    val sourceStockUnitId: Long,
    val plannedAmount: BigDecimal,
    val pickedAmount: BigDecimal,
    val state: Int,
    val lotNumber: String?,
    val pickingType: String,
    val followUpForPickId: Long?,
    val substitutedItemDataId: Long?,
    val pickedLotNumber: String?,
    val pickedBestBefore: LocalDate?,
) {
    companion object {
        fun from(p: Pick) = PickResponse(
            p.id!!, p.deliveryOrderLineId, p.itemDataId, p.itemDataNumber, p.sourceStockUnitId,
            p.plannedAmount, p.pickedAmount, p.state, p.lotNumber, p.pickingType,
            p.followUpForPickId, p.substitutedItemDataId,
            p.pickedLotNumber, p.pickedBestBefore,
        )
    }
}

data class PickOrderResponse(
    val id: Long,
    val pickOrderNumber: String,
    /** Null for an EXTINGUISH order (V605, WORKLIST row 20) — no backing DeliveryOrder. */
    val deliveryOrderId: Long?,
    /** Null in lockstep with [deliveryOrderId]; FE renders "—" (Task 6). */
    val deliveryOrderNumber: String?,
    val state: Int,
    val targetUnitLoadId: Long?,
    val picks: List<PickResponse>,
    /**
     * Row 19: order-level weight/volume, computed on read via
     * [PickWeightVolumeCalculator.compute] -- see its KDoc for the myWMS two-aggregate rule and
     * the null-iff-zero-sum semantics. Never a stored column.
     */
    val weight: BigDecimal?,
    val volume: BigDecimal?,
    /**
     * Row 8: resolved once at release time (`order.destinationLocationId ?:
     * strategy.defaultDestinationLocationId`) and stamped on the PickOrder itself. Null when
     * neither the order nor its strategy names one.
     */
    val destinationLocationId: Long?,
    /** Sprint B (V611): BULK-mode batch PickOrder -- aggregated presentation + bulk-confirm fan-out. */
    val bulk: Boolean,
    /**
     * Row :1470 (adjudication A8): derived, non-persisted signal for the pick-order detail page
     * -- never a stored column, since a STORED reason would go stale the moment a later sibling
     * completion or manual open succeeds. `true` iff the order's strategy has
     * `createShippingOrder` on, this pick order is PICKED, and no non-canceled shipment
     * currently exists for its delivery order -- see [PickOrderService.isAutoOpenPending]'s KDoc
     * for the exact derivation and the WARN-log precedent it self-heals over.
     *
     * **DETAIL-ONLY, by design (see [PickOrderResource]'s `list()`/`toResponse` split).**
     * [com.karyo.orders.spi.OrderStrategyLookup.findPickingStrategy] and the shipment-existence
     * check are both single-order reads with no batch variant; computing this for every row of
     * `GET /pick-orders` would cost 1 to 4 extra queries PER ORDER on that page (see
     * [PickOrderService.isAutoOpenPending]'s KDoc for the exact, verified per-branch count --
     * usually just 1, since the check is ordered cheapest-first), which the batching discipline
     * (one query per concern per operation) rules out at list-page scale. `list()` therefore
     * always reports `false` here -- callers needing the real signal must read the DETAIL
     * endpoint (`GET /pick-orders/{id}`, also reached via `toResponse` from release/cancel/
     * add-picks/extinguish, all small-N single-request paths where even the rare 4-query branch
     * is proportionate).
     */
    val autoOpenPending: Boolean,
) {
    companion object {
        /**
         * [measuresByItemId] must be pre-fetched by the caller via ONE
         * [com.karyo.product.spi.ProductLookup.findMeasuresByIds] call per response batch (see
         * [com.karyo.fulfillment.api.v1.PickOrderResource]'s single-get vs. list-path callers) --
         * this factory performs no lookups itself. [autoOpenPending] is likewise caller-computed
         * (or hardcoded `false` on the list path) -- see the field's own KDoc.
         */
        fun from(
            po: PickOrder,
            picks: List<Pick>,
            measuresByItemId: Map<Long, ProductMeasures>,
            autoOpenPending: Boolean,
        ): PickOrderResponse {
            val wv = PickWeightVolumeCalculator.compute(picks, measuresByItemId)
            return PickOrderResponse(
                po.id!!, po.pickOrderNumber, po.deliveryOrderId, po.deliveryOrderNumber, po.state,
                po.targetUnitLoadId, picks.map { PickResponse.from(it) },
                wv.weight, wv.volume, po.destinationLocationId, po.bulk, autoOpenPending,
            )
        }
    }
}

/** Sprint B: one SKU-aggregated pick unit of a bulk PickOrder (one source stock unit). */
data class BulkLineResponse(
    val sourceStockUnitId: Long,
    val locationName: String,
    val unitLoadLabel: String,
    val itemDataId: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val plannedTotal: BigDecimal,
    val pickedTotal: BigDecimal,
    val openSlices: Int,
)

data class BulkConfirmRequest(
    @field:NotNull val sourceStockUnitId: Long,
    @field:NotNull @field:Positive val pickedAmount: BigDecimal,
    val targetUnitLoadId: Long? = null,
)

data class BulkSliceOutcome(val pickId: Long, val deliveryOrderLineId: Long?, val picked: BigDecimal, val planned: BigDecimal)

data class BulkConfirmResponse(
    val pickOrderId: Long,
    val sourceStockUnitId: Long,
    val pickedAmount: BigDecimal,
    val filledSlices: Int,
    val shortSlices: Int,
    val slices: List<BulkSliceOutcome>,
)
