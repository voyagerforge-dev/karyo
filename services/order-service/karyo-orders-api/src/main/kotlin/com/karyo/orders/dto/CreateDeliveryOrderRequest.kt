package com.karyo.orders.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Creates a delivery order with its lines. When [orderNumber] is omitted, a unique
 * number is generated (DO-prefixed). [prio] follows the myWMS convention where 50
 * is NORMAL priority.
 */
data class CreateDeliveryOrderRequest(
    @field:Size(max = 100) val orderNumber: String? = null,
    @field:Size(max = 255) val customerName: String? = null,
    @field:Size(max = 255) val street: String? = null,
    @field:Size(max = 40) val streetNumber: String? = null,
    @field:Size(max = 40) val zipCode: String? = null,
    @field:Size(max = 120) val city: String? = null,
    @field:Size(max = 80) val country: String? = null,
    @field:Size(max = 60) val phone: String? = null,
    @field:Size(max = 120) val email: String? = null,
    @field:Size(max = 100) val externalNumber: String? = null,
    val deliveryDate: LocalDate? = null,
    val prio: Int = DEFAULT_PRIO,
    @field:Size(max = 2000) val notes: String? = null,
    @field:Size(max = 500) val pickingHint: String? = null,
    @field:Size(max = 500) val packingHint: String? = null,
    @field:Size(max = 500) val shippingHint: String? = null,
    val orderStrategyId: Long? = null,
    /** Row 10: which StorageLocation inside this warehouse the order's work is bound for; validated via StorageLocationLookup. */
    val destinationLocationId: Long? = null,
    /** Row 10: the party named as sender on this order's outbound paperwork (Karyo-native, the outbound counterpart of Asn.senderName). */
    @field:Size(max = 255) val senderName: String? = null,
    /** MANUAL|WAVE|STREAM, case-insensitive; null inherits the strategy (order streaming, B3). */
    @field:Size(max = 10) val releaseModeOverride: String? = null,
    @field:NotEmpty @field:Valid val lines: List<CreateDeliveryOrderLineRequest>,
) {
    companion object {
        /** NORMAL priority per myWMS convention. */
        const val DEFAULT_PRIO = 50
    }
}

data class CreateDeliveryOrderLineRequest(
    @field:NotNull val itemDataId: Long,
    @field:NotNull @field:Positive val amount: BigDecimal,
    @field:Size(max = 255) val lotNumber: String? = null,
    /** Caller's line reference (D2) — orders-module naming, matching the header's externalNumber. */
    @field:Size(max = 100) val externalNumber: String? = null,
)
