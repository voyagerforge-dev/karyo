package com.karyo.orders.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Creates an ASN (Advance Shipping Notice) with its expected lines. When
 * [asnNumber] is omitted, a unique number is generated (ASN-prefixed, same
 * scheme as DeliveryOrder numbers).
 */
data class CreateAsnRequest(
    @field:Size(max = 100) val asnNumber: String? = null,
    @field:Size(max = 100) val externalNumber: String? = null,
    @field:Size(max = 255) val carrierName: String? = null,
    /** Who supplies the goods (the commercial party). Distinct from [senderName]. */
    @field:Size(max = 255) val supplierName: String? = null,
    /**
     * Who dispatched this shipment — differs from [supplierName] whenever the
     * supplier ships from a 3PL or another site.
     */
    @field:Size(max = 255) val senderName: String? = null,
    val expectedDate: LocalDate? = null,
    @field:Size(max = 2000) val notes: String? = null,
    @field:NotEmpty @field:Valid val lines: List<CreateAsnLineRequest>,
)

data class CreateAsnLineRequest(
    @field:NotNull val itemDataId: Long,
    @field:NotNull @field:Positive val expectedAmount: BigDecimal,
    @field:Size(max = 255) val lotNumber: String? = null,
    /** Pre-distributed cross-docking target (Advanced Fulfillment); null = not pre-assigned. */
    val crossDockDeliveryOrderId: Long? = null,
)

/**
 * Registers a UL pre-advice on an ASN (Karyo-native, see
 * [com.karyo.orders.domain.model.AsnUlAdvice]). [labelId] is caller-supplied or
 * server-generated (`ULA-` prefixed) when omitted; unique per ASN. [itemDataId],
 * when supplied, is validated against the product module.
 */
data class CreateUlAdviceRequest(
    @field:Size(max = 100) val labelId: String? = null,
    val unitLoadTypeId: Long? = null,
    val itemDataId: Long? = null,
    val expectedAmount: BigDecimal? = null,
    @field:Size(max = 255) val reasonForReturn: String? = null,
)

/**
 * Pre-release header edits only. ASNs are editable while in CREATED state; once
 * released the header (and lines) are frozen — the service rejects the update
 * with 409. Line edits are not supported in v1.2 (cancel + recreate).
 */
data class UpdateAsnRequest(
    @field:Size(max = 100) val externalNumber: String? = null,
    @field:Size(max = 255) val carrierName: String? = null,
    /** Who supplies the goods (the commercial party). Distinct from [senderName]. */
    @field:Size(max = 255) val supplierName: String? = null,
    /**
     * Who dispatched this shipment — differs from [supplierName] whenever the
     * supplier ships from a 3PL or another site.
     */
    @field:Size(max = 255) val senderName: String? = null,
    val expectedDate: LocalDate? = null,
    @field:Size(max = 2000) val notes: String? = null,
)

/**
 * ASN with expected-vs-received progress. [progressPercent] is
 * totalReceived/totalExpected (rounded down; may exceed 100 on over-receipt).
 */
data class AsnResponse(
    val id: Long,
    val asnNumber: String,
    val externalNumber: String?,
    val carrierName: String?,
    /** Who supplies the goods (the commercial party). Distinct from [senderName]. */
    val supplierName: String? = null,
    /** Who dispatched this shipment (differs from [supplierName] on 3PL/off-site dispatch). */
    val senderName: String? = null,
    val expectedDate: LocalDate?,
    val notes: String?,
    val state: Int,
    val stateName: String,
    val clientId: Long,
    val progressPercent: Int,
    val lines: List<AsnLineResponse>,
    val created: String,
    val modified: String,
    /** UL pre-advices registered on this ASN (Karyo-native, see [CreateUlAdviceRequest]). */
    val ulAdvices: List<UlAdviceResponse> = emptyList(),
)

/**
 * Expected line with receipt progress. [remainingAmount] = expected - received
 * (zero when fully received); [progressPercent] mirrors the ASN-level math per line.
 */
data class AsnLineResponse(
    val id: Long,
    val lineNumber: Int,
    val itemDataId: Long,
    val itemDataNumber: String,
    val expectedAmount: BigDecimal,
    val receivedAmount: BigDecimal,
    val remainingAmount: BigDecimal,
    val progressPercent: Int,
    val state: Int,
    val stateName: String,
    val lotNumber: String?,
    /** Pre-distributed cross-docking target (Advanced Fulfillment); null = not pre-assigned. */
    val crossDockDeliveryOrderId: Long? = null,
)

/**
 * Result of force-finishing an ASN: the updated ASN plus a shortage summary.
 * [shortages] lists every line closed short (receivedAmount < expectedAmount);
 * empty when all lines were fully received.
 */
data class AsnFinishResponse(
    val asn: AsnResponse,
    val shortages: List<AsnLineShortage>,
)

data class AsnLineShortage(
    val lineId: Long,
    val lineNumber: Int,
    val itemDataId: Long,
    val itemDataNumber: String,
    val expectedAmount: BigDecimal,
    val receivedAmount: BigDecimal,
    val shortfall: BigDecimal,
)

/**
 * A registered UL pre-advice (Karyo-native, see
 * [com.karyo.orders.domain.model.AsnUlAdvice]). [state]/[stateName] follow the
 * CREATED -> FINISHED (match) / CANCELED (delete) subset; [matchedReceiptLineId]
 * is set only once FINISHED.
 */
data class UlAdviceResponse(
    val id: Long,
    val labelId: String,
    val unitLoadTypeId: Long?,
    val itemDataId: Long?,
    val itemDataNumber: String?,
    val expectedAmount: BigDecimal?,
    val reasonForReturn: String?,
    val state: Int,
    val stateName: String,
    val matchedReceiptLineId: Long?,
)
