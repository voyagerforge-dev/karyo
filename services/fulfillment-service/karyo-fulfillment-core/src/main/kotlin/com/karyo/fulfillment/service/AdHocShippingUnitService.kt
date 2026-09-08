package com.karyo.fulfillment.service

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.spi.PlannedShippingUnit
import com.karyo.fulfillment.spi.PlannedShippingUnitLine
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.inventory.api.dto.StockUnitResponse
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.api.spi.UnitLoadInfo
import com.karyo.inventory.api.spi.UnitLoadLookup
import com.karyo.inventory.api.vo.StockState
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * S5 (outbound-completion sprint, register row S5): attaches an ad-hoc `ShippingUnit` to a
 * shipment straight from an ON_STOCK unit load, with no pick order behind it (e.g. a warehouse
 * find, a return-to-stock item added to an outgoing box). Register-adjudicated endpoint owner is
 * [PackingService] ("it owns unit creation + numbering"); this class exists as a SEPARATE bean
 * purely to respect [PackingService]'s constructor budget (already 12 params, at its documented
 * ceiling) -- `addAdHocUnit` needs [UnitLoadLookup] and [StockUnitLookup], two dependencies
 * [PackingService] does not otherwise need, which would push it to 14. Rather than grow that
 * ctor, this small collaborator holds the two new dependencies itself and reuses
 * [PackingService.persistUnits] (now `internal`) for the actual unit/line persistence and
 * `positionIndex` numbering -- so "PackingService owns unit creation and numbering" stays true of
 * the actual code path, not just in spirit.
 *
 * **Validation order (adjudicated, binding):** tenant existence first (404), then every
 * integrity check as a 409, in this fixed sequence: (1) the unit load exists for this tenant,
 * (2) every stock unit on it is ON_STOCK and carries no live reservation (IMPORTANT 4, final-
 * review fix wave), (3) the unit load itself is not DELETABLE, (4) it is
 * not already riding a unit on some OTHER live (non-CANCELED) shipment, (5) the target shipment
 * is not paused (`requireShipmentNotPaused`, the same "paused-is-parked" guard [PackingService.pack]/
 * [ShippingService.manifest]/[ShippingService.dispatch] all share, fixed post-review: mutating a
 * shipment's units while it is paused broke that doctrine), (6) the target shipment is still
 * pre-manifest (PACKING or PACKED).
 */
@ApplicationScoped
class AdHocShippingUnitService(
    private val packingService: PackingService,
    private val shippingUnitRepository: ShippingUnitRepository,
    private val unitLoadLookup: UnitLoadLookup,
    private val stockUnitLookup: StockUnitLookup,
    private val stockPicker: StockPicker,
    private val tenantContext: TenantContext,
) {

    /**
     * Attaches unit load [unitLoadId] to shipment [shipmentId] as a new AD_HOC [ShippingUnit]:
     * lines mirror the unit load's own stock 1:1 (`sourcePickId = null` -- there is no pick to
     * point at), and its stock is flipped ON_STOCK(300) -> PACKED(650) via
     * [StockPicker.packAdHocContainer]. `positionIndex` continues the shipment's existing
     * sequence (via [PackingService.persistUnits]), so a shipment that already has PACKOUT units
     * gets the ad-hoc one numbered right after them.
     */
    @Transactional
    fun addAdHocUnit(shipmentId: Long, unitLoadId: Long): Shipment {
        val clientId = tenantContext.clientId
        val unitLoad = requireUnitLoad(unitLoadId, clientId)
        val stockUnits = requireAllOnStock(unitLoadId, clientId)
        requireAttachable(unitLoad, unitLoadId, clientId)
        val shipment = packingService.getShipment(shipmentId)
        requireShipmentNotPaused(shipment)
        requireOpenForAdHoc(shipment)

        val planned = toPlannedUnit(unitLoad, unitLoadId, stockUnits)
        packingService.persistUnits(shipment, clientId, listOf(planned), origin = ShippingUnit.ORIGIN_AD_HOC)
        stockPicker.packAdHocContainer(unitLoadId)
        return shipment
    }

    /** Validation (1): the unit load exists for this tenant -- 404, checked first. */
    private fun requireUnitLoad(unitLoadId: Long, clientId: Long): UnitLoadInfo =
        unitLoadLookup.findById(unitLoadId, clientId)
            ?: throw FulfillmentException.NotFound("UnitLoad", unitLoadId)

    /**
     * Validation (2): every stock unit on the unit load is ON_STOCK AND unreserved -- 409.
     *
     * **Reserved-stock guard (final-review fix wave, IMPORTANT 4, outbound-completion sprint):**
     * ON_STOCK alone isn't enough -- a stock unit can be ON_STOCK and still carry a live
     * reservation (e.g. an order's `stockReserver` claimed it ahead of picking). Attaching it
     * ad-hoc packs it into a DIFFERENT shipment out from under that reservation, so
     * `reservedAmount.signum() == 0` joins the state check; the message names the reservation
     * rather than lumping it into the generic "not entirely ON_STOCK" wording.
     */
    private fun requireAllOnStock(unitLoadId: Long, clientId: Long): List<StockUnitResponse> {
        val stockUnits = stockUnitLookup.findByUnitLoadId(unitLoadId, clientId)
        if (stockUnits.isEmpty() || stockUnits.any { it.state != StockState.ON_STOCK.code }) {
            throw FulfillmentException.ValidationFailed(
                "UnitLoad $unitLoadId is not entirely ON_STOCK and cannot be attached ad-hoc",
            )
        }
        val reserved = stockUnits.firstOrNull { it.reservedAmount.signum() != 0 }
        if (reserved != null) {
            throw FulfillmentException.ValidationFailed(
                "UnitLoad $unitLoadId has a reservation (stock unit ${reserved.id}, " +
                    "reservedAmount=${reserved.reservedAmount}) and cannot be attached ad-hoc",
            )
        }
        return stockUnits
    }

    /**
     * Validations (3)+(4): the unit load itself is not DELETABLE, and it is not already riding a
     * unit on some other live (non-CANCELED) shipment -- both 409. Split just for [requireUnitLoad]/
     * [requireAllOnStock] symmetry (each private helper owns exactly one throw site) is not needed
     * here since these two together stay under detekt's `ThrowsCount` (max 2 per function).
     */
    private fun requireAttachable(unitLoad: UnitLoadInfo, unitLoadId: Long, clientId: Long) {
        if (unitLoad.state == StockState.DELETABLE.code) {
            throw FulfillmentException.ValidationFailed("UnitLoad $unitLoadId is DELETABLE")
        }
        if (shippingUnitRepository.findByUnitLoadIdOnLiveShipment(unitLoadId, clientId) != null) {
            throw FulfillmentException.ValidationFailed(
                "UnitLoad $unitLoadId is already on a shipping unit of a live shipment",
            )
        }
    }

    /** Validation (5): the target shipment is still pre-manifest (PACKING or PACKED) -- 409. */
    private fun requireOpenForAdHoc(shipment: Shipment) {
        if (shipment.state != ShipmentState.PACKING.code && shipment.state != ShipmentState.PACKED.code) {
            throw FulfillmentException.ValidationFailed(
                "Shipment ${shipment.id} is not open for ad-hoc units (state ${shipment.state})",
            )
        }
    }

    private fun toPlannedUnit(
        unitLoad: UnitLoadInfo,
        unitLoadId: Long,
        stockUnits: List<StockUnitResponse>,
    ): PlannedShippingUnit {
        val lines = stockUnits.map { su ->
            PlannedShippingUnitLine(
                itemDataId = su.itemDataId,
                itemDataNumber = su.itemDataNumber,
                amount = su.amount,
                sourcePickId = null,
                lotNumber = su.lotNumber,
            )
        }
        return PlannedShippingUnit(
            type = "CARTON",
            weight = unitLoad.weight,
            unitLoadId = unitLoadId,
            lines = lines,
        )
    }
}
