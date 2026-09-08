package com.karyo.orders.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.common.pagination.paginatedResponse
import com.karyo.events.outbox.OutboxService
import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnLine
import com.karyo.orders.domain.model.AsnUlAdvice
import com.karyo.orders.dto.AsnFinishResponse
import com.karyo.orders.dto.AsnLineResponse
import com.karyo.orders.dto.AsnLineShortage
import com.karyo.orders.dto.AsnResponse
import com.karyo.orders.dto.CreateAsnRequest
import com.karyo.orders.dto.ReceiveLineRequest
import com.karyo.orders.dto.UpdateAsnRequest
import com.karyo.orders.event.AsnStateChangedEvent
import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.AsnRepository
import com.karyo.orders.vo.OrderState
import com.karyo.product.spi.ProductLookup
import com.karyo.sequence.SequenceNumberService
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * ASN (Advance Shipping Notice) lifecycle for v1.2 receiving.
 *
 * State mapping (OrderState subset, see [Asn]):
 *  - **ASN**: CREATED→RELEASED on release; RELEASED→STARTED on the first receipt
 *    against it (driven by [recordReceipt], called from [GoodsReceiptService]);
 *    →FINISHED via [finish] (force-close — remaining short lines are FINISHED with
 *    receivedAmount < expectedAmount and reported as shortages). CANCELED is allowed
 *    pre-STARTED only (stricter than [OrderState.canAdvanceTo]'s pre-PICKED gate,
 *    because received stock cannot be "un-received").
 *  - **Line**: CREATED→STARTED on first receipt→FINISHED when fully received (or
 *    force-finished short). Line transitions emit no events (ASN-level only).
 *
 * Every ASN state change goes through [transition], which enforces
 * [OrderState.canAdvanceTo], fires the synchronous CDI event, and writes the outbox
 * row — the same chokepoint pattern as [OrderService].
 */
@ApplicationScoped
class AsnService(
    private val asnRepository: AsnRepository,
    private val productLookup: ProductLookup,
    private val outboxService: OutboxService,
    private val stateChangedEvent: Event<AsnStateChangedEvent>,
    private val overReceiptGuard: OverReceiptGuard,
    private val ulAdviceService: AsnUlAdviceService,
    private val sequenceNumberService: SequenceNumberService,
) {

    fun findById(id: Long, clientId: Long): AsnResponse =
        toResponse(findEntityById(id, clientId))

    fun list(clientId: Long, pagination: PaginationParams, state: Int?, q: String?): PaginatedResponse<AsnResponse> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.descending("created"))
        val query = asnRepository.search(clientId, state, q, sort)
            .page(Page.of(pagination.page, pagination.size))
        return paginatedResponse(query.list().map { toResponse(it) }, pagination.page, pagination.size, query.count())
    }

    @Transactional
    fun create(request: CreateAsnRequest, clientId: Long): AsnResponse {
        val asnNumber = resolveAsnNumber(request.asnNumber, clientId)

        val asn = Asn().apply {
            this.clientId = clientId
            this.asnNumber = asnNumber
            externalNumber = request.externalNumber
            carrierName = request.carrierName
            supplierName = request.supplierName
            senderName = request.senderName
            expectedDate = request.expectedDate
            notes = request.notes
        }
        request.lines.forEachIndexed { index, lineRequest ->
            val product = productLookup.findById(lineRequest.itemDataId)
                ?: throw OrderException.InvalidReference("Product", "id=${lineRequest.itemDataId}")
            asn.lines.add(
                AsnLine().apply {
                    this.asn = asn
                    lineNumber = index + 1
                    itemDataId = product.id
                    itemDataNumber = product.number
                    expectedAmount = lineRequest.expectedAmount
                    lotNumber = lineRequest.lotNumber
                    crossDockDeliveryOrderId = lineRequest.crossDockDeliveryOrderId
                    state = OrderState.CREATED.code
                }
            )
        }
        asnRepository.persist(asn)
        transition(asn, OrderState.CREATED)
        return toResponse(asn)
    }

    @Transactional
    fun update(id: Long, request: UpdateAsnRequest, clientId: Long): AsnResponse {
        val asn = findEntityById(id, clientId)
        if (asn.state != OrderState.CREATED.code) {
            throw OrderException.NotEditable(id, asn.state, "ASN")
        }
        request.externalNumber?.let { asn.externalNumber = it }
        request.carrierName?.let { asn.carrierName = it }
        request.supplierName?.let { asn.supplierName = it }
        request.senderName?.let { asn.senderName = it }
        request.expectedDate?.let { asn.expectedDate = it }
        request.notes?.let { asn.notes = it }
        return toResponse(asn)
    }

    /** Releases a CREATED ASN — it becomes receivable (goods receipts may bind to it). */
    @Transactional
    fun release(id: Long, clientId: Long): AsnResponse {
        val asn = findEntityById(id, clientId)
        if (asn.state != OrderState.CREATED.code) {
            throw OrderException.InvalidTransition(id, asn.state, OrderState.RELEASED.code)
        }
        transition(asn, OrderState.RELEASED)
        return toResponse(asn)
    }

    /**
     * Cancels an ASN — allowed pre-STARTED only (once goods were received against it,
     * the ASN must be force-closed via [finish] instead).
     */
    @Transactional
    fun cancel(id: Long, clientId: Long): AsnResponse {
        val asn = findEntityById(id, clientId)
        if (asn.state >= OrderState.STARTED.code) {
            throw OrderException.NotCancelable("ASN", id, "goods were already received against it (state ${asn.state})")
        }
        asn.lines.forEach { lineTransition(it, OrderState.CANCELED) }
        transition(asn, OrderState.CANCELED)
        return toResponse(asn)
    }

    /**
     * Force-closes a RELEASED/STARTED ASN: lines not yet FINISHED are closed short
     * (state FINISHED with receivedAmount < expectedAmount) and reported in the
     * returned shortage summary.
     */
    @Transactional
    fun finish(id: Long, clientId: Long): AsnFinishResponse {
        val asn = findEntityById(id, clientId)
        if (asn.state != OrderState.RELEASED.code && asn.state != OrderState.STARTED.code) {
            throw OrderException.InvalidTransition(id, asn.state, OrderState.FINISHED.code)
        }
        val shortages = asn.lines
            .filter { it.remaining.signum() > 0 }
            .map { line ->
                AsnLineShortage(
                    lineId = line.id!!,
                    lineNumber = line.lineNumber,
                    itemDataId = line.itemDataId,
                    itemDataNumber = line.itemDataNumber,
                    expectedAmount = line.expectedAmount,
                    receivedAmount = line.receivedAmount,
                    shortfall = line.remaining,
                )
            }
        asn.lines
            .filter { it.state != OrderState.FINISHED.code }
            .forEach { lineTransition(it, OrderState.FINISHED) }
        transition(asn, OrderState.FINISHED)
        return AsnFinishResponse(toResponse(asn), shortages)
    }

    // ── Collaboration API for GoodsReceiptService (same module) ──────────

    /** Loads an ASN entity for the receiving flow; throws NotFound outside the tenant. */
    fun findEntityById(id: Long, clientId: Long): Asn =
        asnRepository.findByIdAndClient(id, clientId)
            ?: throw OrderException.NotFound("Asn", "id=$id")

    /** Tenant-scoped batch load, used by [GoodsReceiptService.toResponse] to hydrate `asns` without one query per receipt. */
    fun findByIds(ids: Set<Long>, clientId: Long): List<Asn> = asnRepository.findByIds(ids, clientId)

    /**
     * Resolves the ASN + line for a given ASN-line id, tenant-scoped; null when the
     * line doesn't exist or belongs to a different tenant (collaboration API for
     * [GoodsReceiptService] receive-time auto-attach and B3 reversal retraction --
     * both only have a line id, not the owning ASN's id, until this runs).
     */
    fun findLineWithAsn(asnLineId: Long, clientId: Long): Pair<Asn, AsnLine>? {
        val asn = asnRepository.findByLineId(asnLineId, clientId) ?: return null
        val line = asn.lines.firstOrNull { it.id == asnLineId } ?: return null
        return asn to line
    }

    /** Tenant-scoped: true if ASN [asnId] owns any line in [asnLineIds] -- [GoodsReceiptService]'s detach guard. */
    fun anyLineBelongsTo(asnLineIds: Set<Long>, asnId: Long, clientId: Long): Boolean =
        asnRepository.anyLineBelongsTo(asnLineIds, asnId, clientId)

    /**
     * Over-receipt guard for [GoodsReceiptService.receiveLine], called BEFORE
     * [recordReceipt]. Hosted here (not on [GoodsReceiptService], which is AT the
     * detekt constructor-param limit) because [AsnService] already owns AsnLine
     * amount bookkeeping and has constructor headroom for [OverReceiptGuard]'s
     * [ReceivingConfig][com.karyo.orders.config.ReceivingConfig] dependency. See
     * [OverReceiptGuard] for the two-level (per-request AND instance-knob)
     * composition.
     */
    fun checkOverReceipt(asnLine: AsnLine, request: ReceiveLineRequest) = overReceiptGuard.check(asnLine, request)

    /**
     * Silent UL pre-advice match for [GoodsReceiptService.receiveLine] (Karyo-native,
     * see [AsnUlAdvice]): delegates to [AsnUlAdviceService.match] so the advice
     * CRUD/matching logic stays out of this class (which is at its detekt
     * function-count headroom) while [GoodsReceiptService] (at its constructor-param
     * ceiling) only ever needs the already-injected [AsnService].
     */
    fun matchUlAdvice(asnIds: Set<Long>, unitLoadLabel: String, receiptLineId: Long) =
        ulAdviceService.match(asnIds, unitLoadLabel, receiptLineId)

    /**
     * Reopen counterpart to [matchUlAdvice] for [GoodsReceiptService.reverseLine]:
     * delegates to [AsnUlAdviceService.reopen] so a reversed line's matched advice
     * (if any) goes back to CREATED with its pointer cleared, letting the re-receive
     * of the same physical UL match again.
     */
    fun reopenUlAdvice(receiptLineId: Long) = ulAdviceService.reopen(receiptLineId)

    /**
     * Applies one received amount to an expected line (called by
     * [GoodsReceiptService.receiveLine] inside its transaction, AFTER
     * [checkOverReceipt] has already passed): bumps receivedAmount, moves the line
     * CREATED→STARTED on first receipt and →FINISHED when fully received, and moves
     * the ASN RELEASED→STARTED on its first receipt.
     */
    fun recordReceipt(asn: Asn, line: AsnLine, amount: BigDecimal) {
        line.receivedAmount = line.receivedAmount.add(amount)
        if (line.state == OrderState.CREATED.code) {
            lineTransition(line, OrderState.STARTED)
        }
        if (line.remaining.signum() == 0 && line.state != OrderState.FINISHED.code) {
            lineTransition(line, OrderState.FINISHED)
        }
        if (asn.state == OrderState.RELEASED.code) {
            transition(asn, OrderState.STARTED)
        }
    }

    /**
     * B3 reversal (called by [GoodsReceiptService.reverseLine] inside its
     * transaction): subtracts a reversed receipt amount from [line]. Deliberately
     * does NOT walk line/ASN state backward — [OrderState] is forward-only, so a
     * FINISHED line may end with receivedAmount < expectedAmount after a reversal;
     * the quantity is the truth, the state is the history (six-hard-items §3.7 Q3,
     * the recommended answer).
     */
    fun retractReceipt(line: AsnLine, amount: BigDecimal) {
        if (line.receivedAmount < amount) {
            throw OrderException.ValidationFailed(
                "cannot retract ${amount.toPlainString()}: only ${line.receivedAmount.toPlainString()} received on line ${line.id}"
            )
        }
        line.receivedAmount = line.receivedAmount.subtract(amount)
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Single chokepoint for ASN state changes: enforces [OrderState.canAdvanceTo],
     * fires the synchronous CDI event, and writes the outbox row.
     *
     * Also stamps the lifecycle timestamps, mirroring [OrderService]'s rule for
     * [com.karyo.orders.domain.model.DeliveryOrder]: `started` on the transition to
     * RELEASED, `finished` on reaching a terminal state (FINISHED or CANCELED).
     * As in [OrderService], idempotency needs no null-guard — the forward-only state
     * machine means each stamping transition can happen at most once; a repeat
     * attempt throws before any stamp is touched.
     */
    private fun transition(asn: Asn, target: OrderState) {
        val current = OrderState.fromCode(asn.state)
        if (!current.canAdvanceTo(target)) {
            throw OrderException.InvalidTransition(asn.id ?: 0L, current.code, target.code)
        }
        asn.state = target.code
        when (target) {
            OrderState.RELEASED -> asn.started = Instant.now()
            OrderState.FINISHED, OrderState.CANCELED -> asn.finished = Instant.now()
            else -> Unit
        }
        val event = AsnStateChangedEvent(
            asnId = asn.id!!,
            asnNumber = asn.asnNumber,
            oldState = current.code,
            newState = target.code,
            clientId = asn.clientId,
            occurredAt = Instant.now(),
        )
        outboxService.publish("Asn", asn.id!!, "AsnStateChanged", event, asn.clientId)
        stateChangedEvent.fire(event)
    }

    /** Line state changes share the [OrderState.canAdvanceTo] guard but emit no events (ASN-level only). */
    private fun lineTransition(line: AsnLine, target: OrderState) {
        val current = OrderState.fromCode(line.state)
        if (!current.canAdvanceTo(target)) {
            throw OrderException.InvalidTransition(line.id ?: 0L, current.code, target.code)
        }
        line.state = target.code
    }

    private fun resolveAsnNumber(requested: String?, clientId: Long): String =
        if (requested != null) validateRequestedAsnNumber(requested, clientId) else generateAsnNumber(clientId)

    private fun validateRequestedAsnNumber(requested: String, clientId: Long): String {
        if (requested.isBlank()) {
            throw OrderException.ValidationFailed("asnNumber must not be blank")
        }
        asnRepository.findByAsnNumber(requested, clientId)?.let {
            throw OrderException.DuplicateName("Asn", requested)
        }
        return requested
    }

    /** asns.asn_number is VARCHAR(100). */
    private fun generateAsnNumber(clientId: Long): String =
        sequenceNumberService.next("asn.asnNumber", "ASN", clientId, MAX_NUMBER_LENGTH) { candidate ->
            asnRepository.findByAsnNumber(candidate, clientId) == null
        }

    fun toResponse(asn: Asn): AsnResponse =
        AsnResponse(
            id = asn.id!!,
            asnNumber = asn.asnNumber,
            externalNumber = asn.externalNumber,
            carrierName = asn.carrierName,
            supplierName = asn.supplierName,
            senderName = asn.senderName,
            expectedDate = asn.expectedDate,
            notes = asn.notes,
            state = asn.state,
            stateName = OrderState.fromCode(asn.state).name,
            clientId = asn.clientId,
            progressPercent = progressPercent(
                asn.lines.sumOf { it.receivedAmount },
                asn.lines.sumOf { it.expectedAmount },
            ),
            lines = asn.lines.map { toLineResponse(it) },
            created = asn.created.toString(),
            modified = asn.modified.toString(),
            ulAdvices = asn.ulAdvices.map { ulAdviceService.toResponse(it) },
        )

    private fun toLineResponse(line: AsnLine): AsnLineResponse =
        AsnLineResponse(
            id = line.id!!,
            lineNumber = line.lineNumber,
            itemDataId = line.itemDataId,
            itemDataNumber = line.itemDataNumber,
            expectedAmount = line.expectedAmount,
            receivedAmount = line.receivedAmount,
            remainingAmount = line.remaining,
            progressPercent = progressPercent(line.receivedAmount, line.expectedAmount),
            state = line.state,
            stateName = OrderState.fromCode(line.state).name,
            lotNumber = line.lotNumber,
            crossDockDeliveryOrderId = line.crossDockDeliveryOrderId,
        )

    /** received/expected as an integer percentage (floor; may exceed 100 on over-receipt). */
    private fun progressPercent(received: BigDecimal, expected: BigDecimal): Int =
        if (expected.signum() <= 0) 0
        else received.multiply(HUNDRED).divide(expected, 0, RoundingMode.FLOOR).toInt()

    companion object {
        val SORTABLE_FIELDS = setOf("id", "asnNumber", "externalNumber", "carrierName", "state", "expectedDate", "created")
        private val HUNDRED = BigDecimal(100)
        private const val MAX_NUMBER_LENGTH = 100
    }
}
