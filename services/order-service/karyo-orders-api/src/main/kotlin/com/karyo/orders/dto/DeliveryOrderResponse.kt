package com.karyo.orders.dto

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class DeliveryOrderResponse(
    val id: Long,
    val orderNumber: String,
    val externalNumber: String?,
    val customerName: String?,
    val street: String?,
    val streetNumber: String?,
    val zipCode: String?,
    val city: String?,
    val country: String?,
    val phone: String?,
    val email: String?,
    val deliveryDate: LocalDate?,
    val prio: Int,
    val notes: String?,
    val state: Int,
    val stateName: String,
    val orderStrategyId: Long?,
    val clientId: Long,
    val lines: List<DeliveryOrderLineResponse>,
    val created: String,
    val modified: String,
    // Carrier/service/tracking, read via the ShipmentLookup SPI. Null until a shipment
    // exists for this order (honest gap -- the frontend keeps "--" for un-shipped orders).
    val carrierName: String?,
    val carrierService: String?,
    val trackingNumber: String?,
    val shippedAt: String?,
    // Per-order operator instruction hints (D3); null = no hint for that phase.
    val pickingHint: String? = null,
    val packingHint: String? = null,
    val shippingHint: String? = null,
    // Row 10: which StorageLocation inside this warehouse the order's work is bound for.
    // destinationLocationName is resolved via StorageLocationLookup; null when destinationLocationId
    // is null OR the id no longer resolves (a location deleted after being set -- honest gap).
    val destinationLocationId: Long? = null,
    val destinationLocationName: String? = null,
    // Row 10: claiming operator (pure metadata; never coupled to state). Null = unclaimed.
    val operatorId: String? = null,
    // Row 10: the party named as sender on this order's outbound paperwork (Karyo-native).
    val senderName: String? = null,
    // Order streaming (B3): per-order override echo + the engine's claim/escalation stamps.
    val releaseModeOverride: String? = null,
    val streamFirstAttemptAt: Instant? = null,
    val streamEscalatedAt: Instant? = null,
    val streamStalledAt: Instant? = null,
    // Row 10: derived, computed-on-read document links -- no columns. documentUrl always points
    // at this order's delivery note route; labelUrl is the shipping-unit label route once a
    // shipment (with a shipping unit) exists, else null (honest gap, never a link that 404s).
    val documentUrl: String = "",
    val labelUrl: String? = null,
)

/**
 * Line with reservation status. [shortage] is the raw reservation shortfall
 * (amount - reservedAmount, zero when fully reserved; a line with shortage > 0 after release
 * sits in PENDING(550)) — it mirrors the entity property exactly, with no netting.
 *
 * Analysis 2026-07-31: substitution can never reduce this value, because substitution coverage is
 * reservation-backed (a substitute follow-up reserves fresh stock out of the shortfall the parent
 * pick released) while `reservedAmount` is written only by reserve/cancel — so
 * `pickedAmount + substitutedAmount <= reservedAmount` always. Netting [substitutedAmount] out of
 * this field would therefore only ever hide genuinely undelivered units.
 */
data class DeliveryOrderLineResponse(
    val id: Long,
    val lineNumber: Int,
    val itemDataId: Long,
    val itemDataNumber: String,
    val amount: BigDecimal,
    val reservedAmount: BigDecimal,
    val shortage: BigDecimal,
    val state: Int,
    val stateName: String,
    val lotNumber: String?,
    val itemDataName: String?,
    // Per-line unit price at order time; null = honest gap (un-priced line, e.g. legacy data).
    val unitPrice: BigDecimal?,
    // Caller's line reference (D2) — orders-module naming (externalNumber, like the header),
    // deliberately NOT inventory's externalId.
    val externalNumber: String? = null,
    // Derived picked quantity (D4) — no stored column; PICKED-state picks of the ORDERED SKU
    // (substitutedItemDataId IS NULL), read via the PickRollupLookup SPI. pickedAmount < amount
    // at order finish is legitimate (PARTIAL_SHIP is the default).
    val pickedAmount: BigDecimal = BigDecimal.ZERO,
    // Substitute-SKU quantity credited to this line by substitution follow-up picks — kept
    // separate from pickedAmount because the follow-ups carry a DIFFERENT SKU.
    val substitutedAmount: BigDecimal = BigDecimal.ZERO,
)
