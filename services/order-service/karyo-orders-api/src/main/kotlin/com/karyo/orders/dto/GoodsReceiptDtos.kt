package com.karyo.orders.dto

import com.karyo.orders.vo.GoodsReceiptType
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Opens a goods receipt. [asnIds] binds it to any number of RELEASED/STARTED ASNs of
 * the same client (V424 many-to-many, receipt-against-expectation); empty = blind
 * receipt. [asnId] is a LEGACY scalar alias, still accepted and folded into the
 * [asnIds] set server-side (both may be given; duplicates are deduped). When
 * [receiptNumber] is omitted, a unique number is generated (GR-prefixed).
 *
 * [receiptType] is the inbound reason as a [GoodsReceiptType] code (0 NORMAL,
 * 1 RETOUR; myWMS codes), defaulting to NORMAL. RETOUR + any requested ASN is a 422
 * (customer returns ride the blind path), an unknown code is a 422, and the
 * type is immutable after create — [UpdateGoodsReceiptRequest] does not carry it.
 *
 * B7 header scalars: [prio] follows the DeliveryOrder convention (default 50);
 * [receiptDate] is the date the goods PHYSICALLY arrived — operator-entered and
 * deliberately backdatable; the dock pair ([dockLocationId]/[dockLocationName])
 * is denormalized and UNVALIDATED against layout, the same convention as the
 * per-line locationId/locationName.
 */
data class CreateGoodsReceiptRequest(
    @field:Size(max = 100) val receiptNumber: String? = null,
    val asnIds: List<Long> = emptyList(),
    /** Legacy alias for a single [asnIds] entry — kept for backward compatibility. */
    val asnId: Long? = null,
    @field:Size(max = 255) val carrierName: String? = null,
    @field:Size(max = 100) val deliveryNoteNumber: String? = null,
    @field:Size(max = 2000) val notes: String? = null,
    val receiptType: Int = GoodsReceiptType.NORMAL.code,
    val prio: Int = DEFAULT_PRIO,
    val receiptDate: LocalDate? = null,
    val dockLocationId: Long? = null,
    @field:Size(max = 100) val dockLocationName: String? = null,
) {
    companion object {
        const val DEFAULT_PRIO = 50
    }
}

/**
 * Updates the B7 header scalars while the receipt is still open (CREATED/STARTED —
 * receiptDate and the dock door are typically discovered DURING receiving, so the
 * window is wider than the ASN's CREATED-only update). null = leave unchanged
 * (the [UpdateAsnRequest] convention). [receiptType] is deliberately absent —
 * the inbound reason is immutable after create.
 */
data class UpdateGoodsReceiptRequest(
    val prio: Int? = null,
    val receiptDate: LocalDate? = null,
    val dockLocationId: Long? = null,
    @field:Size(max = 100) val dockLocationName: String? = null,
)

/**
 * The receive-line action. Either [asnLineId] (line of the receipt's linked ASN —
 * [itemDataId] defaults from it) or [itemDataId] (blind line) must be given.
 *
 * Location is passed by the caller as id + name; v1.2 does not validate it against
 * the layout module (sub-phase 2.3's LocationLookup will add that).
 *
 * [allowOverReceipt] overrides the over-receipt guard (received + amount may then
 * exceed the ASN line's expectedAmount).
 *
 * [lockType] applies an inventory-LockType stock lock to the received unit — the
 * stock then stays INCOMING (excluded from putaway and from the finish promotion)
 * until manually released. Only the receive-appropriate subset {GENERAL(1),
 * QUALITY_FAULT(103), LOT_EXPIRED(202), LOT_TOO_YOUNG(203)} is accepted; null =
 * no lock. An explicit UNLOCKED(0), STOCKTAKING(7) or unknown code is a 422 —
 * omission is the only way to say "no lock". [note] is the free-text reason,
 * kept in full on the GR line and visibly truncated to 50 on the journal hop.
 *
 * [serialNumber] and [packagingUnitId] are recorded on the GR line and threaded
 * onto the created stock unit. [packagingUnitId] is an ID-only reference into the
 * product module, validated at stock creation via the PackagingUnitLookup SPI (A5,
 * hard-parity-sprint) — see [com.karyo.orders.domain.model] GoodsReceiptLine KDoc.
 *
 * [storageStrategyId] (inbound-completion row 7 residual) is an optional per-line
 * override of the putaway location finder's `StorageStrategy` — an ID-only reference
 * into the layout module, validated against [com.karyo.layout.spi.StorageStrategyLookup]
 * (unknown or foreign-tenant → 422). `null` (the default) leaves the finder's normal
 * resolution (product default, then system default) untouched.
 */
data class ReceiveLineRequest(
    val asnLineId: Long? = null,
    val itemDataId: Long? = null,
    @field:NotNull @field:Positive val amount: BigDecimal,
    @field:NotNull val locationId: Long,
    @field:NotNull @field:Size(max = 100) val locationName: String,
    @field:Size(max = 255) val unitLoadLabel: String? = null,
    val unitLoadTypeId: Long? = null,
    @field:Size(max = 255) val lotNumber: String? = null,
    @field:Size(max = 255) val serialNumber: String? = null,
    val packagingUnitId: Long? = null,
    val bestBefore: LocalDate? = null,
    val lockType: Int? = null,
    @field:Size(max = 255) val note: String? = null,
    val allowOverReceipt: Boolean = false,
    val storageStrategyId: Long? = null,
)

/** M2M attach body (V424): binds one more ASN, re-running the create-time guards. */
data class AttachAsnRequest(
    @field:NotNull val asnId: Long,
)

/** A bound ASN's id + number — the [GoodsReceiptResponse.asns] element shape (V424). */
data class AsnRefResponse(
    val id: Long,
    val asnNumber: String,
)

data class GoodsReceiptResponse(
    val id: Long,
    val receiptNumber: String,
    /** V424 many-to-many: every ASN currently bound to this receipt (empty = blind). */
    val asns: List<AsnRefResponse> = emptyList(),
    val carrierName: String?,
    val deliveryNoteNumber: String?,
    val notes: String?,
    /** Inbound reason — [GoodsReceiptType] code (0 NORMAL, 1 RETOUR); immutable after create. */
    val receiptType: Int = GoodsReceiptType.NORMAL.code,
    val state: Int,
    val stateName: String,
    val clientId: Long,
    val lines: List<GoodsReceiptLineResponse>,
    val created: String,
    val modified: String,
    /** B7: priority (DeliveryOrder convention, default 50). */
    val prio: Int = CreateGoodsReceiptRequest.DEFAULT_PRIO,
    /** B7: operator-entered physical-arrival date (backdatable); null = never entered. */
    val receiptDate: LocalDate? = null,
    /** B7: unvalidated denormalized dock pair (per-line locationId/Name convention). */
    val dockLocationId: Long? = null,
    val dockLocationName: String? = null,
    /** B7: claiming operator — pure metadata, never coupled to [state]. */
    val operatorId: String? = null,
    /** B7: orthogonal pause stamp; non-null = paused ([state] does not move). */
    val pausedAt: String? = null,
)

data class GoodsReceiptLineResponse(
    val id: Long,
    val asnLineId: Long?,
    val itemDataId: Long,
    val itemDataNumber: String,
    val amount: BigDecimal,
    val locationId: Long,
    val locationName: String,
    val unitLoadLabel: String,
    val stockUnitId: Long,
    val unitLoadId: Long,
    val lotNumber: String?,
    val serialNumber: String? = null,
    val packagingUnitId: Long? = null,
    val bestBefore: LocalDate?,
    /** Inventory LockType code applied at receipt; null = received unlocked. */
    val lockType: Int? = null,
    /** Full operator note as recorded on the line (journal copy is truncated to 50). */
    val note: String? = null,
    /** DERIVED: any lock was applied at receipt (not only a QA fault). */
    val qaHold: Boolean,
    /** B3: true once the line has been totally reversed (stock deleted, ASN decremented). */
    val reversed: Boolean = false,
    /** B3: when the line was reversed; null = never reversed. */
    val reversedAt: String? = null,
    /** Inbound-completion row 7: the validated per-line putaway strategy override, if any. */
    val storageStrategyId: Long? = null,
)

/** Result of receiving a line: the updated receipt plus the created identifiers. */
data class ReceiveLineResponse(
    val receipt: GoodsReceiptResponse,
    val lineId: Long,
    val stockUnitId: Long,
    val unitLoadId: Long,
    val unitLoadLabel: String,
)
