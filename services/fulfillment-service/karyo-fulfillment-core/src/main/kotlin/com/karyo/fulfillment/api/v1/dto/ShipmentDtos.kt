package com.karyo.fulfillment.api.v1.dto

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShippingUnit
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

data class OpenPackingRequest(
    @field:NotNull val deliveryOrderId: Long,
)

data class ManifestRequestDto(
    @field:NotBlank val carrierName: String,
    @field:NotBlank val carrierService: String,
    val trackingNumber: String? = null,
)

data class PackRequest(
    @field:NotNull @field:Positive val weight: BigDecimal,
    val type: String = "CARTON",
)

/** S5: attaches an ad-hoc ON_STOCK unit load to a shipment, no pick order behind it. */
data class AddAdHocUnitRequest(
    @field:NotNull val unitLoadId: Long,
)

/** Sprint C: one packed line, attributed back to its order when known -- only populated on the shipment detail (`get`) route. */
data class ShippingUnitLineResponse(
    val id: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val amount: BigDecimal,
    val deliveryOrderId: Long?,
)

data class ShippingUnitResponse(
    val id: Long,
    val shippingUnitNumber: String,
    val type: String,
    val weight: BigDecimal,
    val state: Int,
    val unitLoadId: Long?,
    val positionIndex: Int,
    val carrierLabel: String?,
    val trackingNumber: String?,
    val shipToName: String?,
    val shipToStreet: String?,
    val shipToStreetNumber: String?,
    val shipToZip: String?,
    val shipToCity: String?,
    val shipToCountry: String?,
    /** S5 (post-review fix, closing a Task-6 gap): PACKOUT (from a pick) or AD_HOC (Task 7). */
    val origin: String,
    /** Sprint C: populated by [com.karyo.fulfillment.api.v1.ShipmentResource.get] only (detail path); empty on `list`. */
    val lines: List<ShippingUnitLineResponse> = emptyList(),
) {
    companion object {
        fun from(u: ShippingUnit, lines: List<ShippingUnitLineResponse> = emptyList()) = ShippingUnitResponse(
            u.id!!, u.shippingUnitNumber, u.type, u.weight, u.state, u.unitLoadId,
            u.positionIndex, u.carrierLabel, u.trackingNumber,
            u.shipToName, u.shipToStreet, u.shipToStreetNumber, u.shipToZip, u.shipToCity, u.shipToCountry,
            u.origin, lines,
        )
    }
}

/** Sprint C: a member order of a GROUP shipment. */
data class ShipmentOrderRef(val id: Long, val number: String)

data class ShipmentResponse(
    val id: Long,
    val shipmentNumber: String,
    /** Null on a group (cross-order) shipment; see [orders]. */
    val deliveryOrderId: Long?,
    val deliveryOrderNumber: String?,
    val state: Int,
    val carrierName: String?,
    val carrierService: String?,
    val trackingNumber: String?,
    val shippingUnits: List<ShippingUnitResponse>,
    /** Task 5: claiming operator -- pure metadata, never coupled to [state]. */
    val operatorId: String? = null,
    /** Task 5: orthogonal pause stamp; non-null = paused ([state] does not move). */
    val pausedAt: String? = null,
    /** Sprint C: group shipment provenance and members (empty list on per-order shipments). */
    val consolidationGroupId: Long? = null,
    val waveId: Long? = null,
    val orders: List<ShipmentOrderRef> = emptyList(),
) {
    companion object {
        fun from(s: Shipment, units: List<ShippingUnit>, orders: List<ShipmentOrderRef> = emptyList()) = ShipmentResponse(
            s.id!!, s.shipmentNumber, s.deliveryOrderId, s.deliveryOrderNumber, s.state,
            s.carrierName, s.carrierService, s.trackingNumber,
            units.map { ShippingUnitResponse.from(it) },
            s.operatorId, s.pausedAt?.toString(),
            s.consolidationGroupId, s.waveId, orders,
        )
    }
}
