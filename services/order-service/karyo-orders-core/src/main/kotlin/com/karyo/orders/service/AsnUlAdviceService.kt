package com.karyo.orders.service

import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnUlAdvice
import com.karyo.orders.dto.CreateUlAdviceRequest
import com.karyo.orders.dto.UlAdviceResponse
import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.AsnRepository
import com.karyo.orders.repository.AsnUlAdviceRepository
import com.karyo.orders.vo.OrderState
import com.karyo.product.spi.ProductLookup
import com.karyo.sequence.SequenceNumberService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * UL pre-advice CRUD + the receive-time silent match — split out of [AsnService]
 * (already at its detekt function-count ceiling headroom) rather than growing it
 * further. Karyo-native capability: see [AsnUlAdvice] for why there is no myWMS
 * behavioral reference here.
 *
 * Depends on [AsnRepository] directly (not [AsnService]) to avoid a circular CDI
 * dependency, since [AsnService.matchUlAdvice] delegates into this service.
 */
@ApplicationScoped
class AsnUlAdviceService(
    private val repository: AsnUlAdviceRepository,
    private val asnRepository: AsnRepository,
    private val productLookup: ProductLookup,
    private val sequenceNumberService: SequenceNumberService,
) {

    /** Only while the ASN is still advisable (CREATED or RELEASED) — 409 otherwise. */
    @Transactional
    fun create(asnId: Long, request: CreateUlAdviceRequest, clientId: Long): UlAdviceResponse {
        val asn = findAsn(asnId, clientId)
        requireAdvisable(asn)
        val (itemDataId, itemDataNumber) = resolveItem(request.itemDataId)
        val advice = AsnUlAdvice().apply {
            this.asn = asn
            labelId = resolveLabelId(request.labelId, asnId, clientId)
            unitLoadTypeId = request.unitLoadTypeId
            this.itemDataId = itemDataId
            this.itemDataNumber = itemDataNumber
            expectedAmount = request.expectedAmount
            reasonForReturn = request.reasonForReturn
            state = OrderState.CREATED.code
        }
        repository.persist(advice)
        return toResponse(advice)
    }

    /** Only a still-OPEN (CREATED) advice may be deleted — a matched one is receiving history (409). */
    @Transactional
    fun delete(asnId: Long, adviceId: Long, clientId: Long) {
        val advice = repository.findByIdAndAsn(adviceId, asnId, clientId)
            ?: throw OrderException.NotFound("AsnUlAdvice", "id=$adviceId")
        if (advice.state != OrderState.CREATED.code) {
            throw OrderException.NotCancelable(
                "AsnUlAdvice", adviceId, "advice was already matched (state ${advice.state})"
            )
        }
        repository.delete(advice)
    }

    /** ZPL rendering source ([AsnDocumentService]) — tenant scope already enforced by the caller's ASN load. */
    fun listForAsn(asnId: Long): List<AsnUlAdvice> = repository.findByAsnId(asnId)

    /**
     * Silent receive-time match, called from [GoodsReceiptService.receiveLine] via
     * [AsnService.matchUlAdvice]: when [unitLoadLabel] equals an OPEN advice's
     * labelId on any of the receipt's attached [asnIds], stamps it FINISHED with
     * [receiptLineId]. A no-op — no exception, no signal — when nothing matches:
     * un-advised arrivals are first-class, never warned about.
     */
    fun match(asnIds: Set<Long>, unitLoadLabel: String, receiptLineId: Long) {
        val advice = repository.findOpenByLabel(asnIds, unitLoadLabel) ?: return
        advice.state = OrderState.FINISHED.code
        advice.matchedReceiptLineId = receiptLineId
    }

    /**
     * [GoodsReceiptService.reverseLine]'s reopen hook: a reversed receipt line can no
     * longer be the thing an advice points at, or the re-receive of the same physical
     * unit load would never re-match (the FINISHED advice + its stale
     * [AsnUlAdvice.matchedReceiptLineId] would just sit there). A no-op when
     * [receiptLineId] was never matched (blind lines, un-advised arrivals) — same
     * silent-when-nothing-to-do shape as [match].
     */
    fun reopen(receiptLineId: Long) {
        val advice = repository.findByMatchedReceiptLineId(receiptLineId) ?: return
        advice.state = OrderState.CREATED.code
        advice.matchedReceiptLineId = null
    }

    private fun findAsn(asnId: Long, clientId: Long): Asn =
        asnRepository.findByIdAndClient(asnId, clientId) ?: throw OrderException.NotFound("Asn", "id=$asnId")

    private fun requireAdvisable(asn: Asn) {
        if (asn.state != OrderState.CREATED.code && asn.state != OrderState.RELEASED.code) {
            throw OrderException.InvalidTransition(asn.id ?: 0L, asn.state, OrderState.CREATED.code)
        }
    }

    private fun resolveLabelId(requested: String?, asnId: Long, clientId: Long): String =
        if (requested != null) validateRequestedLabel(requested, asnId) else generateLabelId(asnId, clientId)

    private fun validateRequestedLabel(requested: String, asnId: Long): String {
        if (requested.isBlank()) {
            throw OrderException.ValidationFailed("labelId must not be blank")
        }
        if (repository.existsByAsnAndLabel(asnId, requested)) {
            throw OrderException.DuplicateName("AsnUlAdvice", requested)
        }
        return requested
    }

    /** asn_ul_advices.label_id is VARCHAR(100). */
    private fun generateLabelId(asnId: Long, clientId: Long): String =
        sequenceNumberService.next("asn.ulAdviceLabel", "ULA", clientId, MAX_LABEL_LENGTH) { candidate ->
            !repository.existsByAsnAndLabel(asnId, candidate)
        }

    private fun resolveItem(itemDataId: Long?): Pair<Long?, String?> {
        if (itemDataId == null) return null to null
        val product = productLookup.findById(itemDataId)
            ?: throw OrderException.InvalidReference("Product", "id=$itemDataId")
        return product.id to product.number
    }

    /** Widened (not private): [AsnService.toResponse] reuses this as the single mapper for `ulAdvices`. */
    internal fun toResponse(advice: AsnUlAdvice): UlAdviceResponse =
        UlAdviceResponse(
            id = advice.id!!,
            labelId = advice.labelId,
            unitLoadTypeId = advice.unitLoadTypeId,
            itemDataId = advice.itemDataId,
            itemDataNumber = advice.itemDataNumber,
            expectedAmount = advice.expectedAmount,
            reasonForReturn = advice.reasonForReturn,
            state = advice.state,
            stateName = OrderState.fromCode(advice.state).name,
            matchedReceiptLineId = advice.matchedReceiptLineId,
        )

    companion object {
        private const val MAX_LABEL_LENGTH = 100
    }
}
