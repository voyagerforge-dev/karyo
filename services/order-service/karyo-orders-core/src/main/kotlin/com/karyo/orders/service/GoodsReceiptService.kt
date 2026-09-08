package com.karyo.orders.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.common.pagination.paginatedResponse
import com.karyo.events.outbox.OutboxService
import com.karyo.inventory.api.spi.ReceiveStockRequest
import com.karyo.inventory.api.spi.StockReceiver
import com.karyo.inventory.api.vo.LockType
import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnLine
import com.karyo.orders.domain.model.GoodsReceipt
import com.karyo.orders.domain.model.GoodsReceiptLine
import com.karyo.orders.dto.AsnRefResponse
import com.karyo.orders.dto.CreateGoodsReceiptRequest
import com.karyo.orders.dto.GoodsReceiptLineResponse
import com.karyo.orders.dto.GoodsReceiptResponse
import com.karyo.orders.dto.ReceiveLineRequest
import com.karyo.orders.dto.ReceiveLineResponse
import com.karyo.orders.dto.UpdateGoodsReceiptRequest
import com.karyo.orders.event.GoodsReceiptLineReceivedEvent
import com.karyo.orders.event.GoodsReceiptLineReversedEvent
import com.karyo.orders.event.GoodsReceiptStateChangedEvent
import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.GoodsReceiptAsnRepository
import com.karyo.orders.repository.GoodsReceiptLineRepository
import com.karyo.orders.repository.GoodsReceiptRepository
import com.karyo.orders.vo.GoodsReceiptType
import com.karyo.orders.vo.OrderState
import com.karyo.product.dto.ProductResponse
import com.karyo.sequence.SequenceNumberService
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * GoodsReceipt lifecycle for v1.2 receiving (receipt-against-expectation + blind).
 *
 * State mapping (OrderState subset, see [GoodsReceipt]):
 *  - **Receipt**: CREATED→STARTED on the first received line; →FINISHED via
 *    [finish], which promotes all non-locked stock INCOMING→ON_STOCK through
 *    [StockReceiver.markOnStock]. CANCELED only while NO lines were received.
 *  - **Receive-time lock (B5; known limitation):** stock received with a lockType
 *    (e.g. QUALITY_FAULT for a QA hold) stays INCOMING + locked after finish.
 *    Release is a manual inventory unlock, and releasing the stock also requires
 *    a manual stock state change to ON_STOCK — there is no release workflow yet.
 *
 * Every receipt state change goes through [transition] (canAdvanceTo guard + CDI
 * event + outbox row — the [OrderService] chokepoint pattern); every received line
 * additionally fires [GoodsReceiptLineReceivedEvent] (CDI + outbox), the putaway
 * trigger for sub-phase 2.3.
 */
// B7 added the receipt CONTROL surface (update/claim/release/pause/resume) — cohesive
// operations on the one GoodsReceipt aggregate, kept together like TaskService.
@Suppress("TooManyFunctions")
@ApplicationScoped
class GoodsReceiptService(
    private val receiptRepository: GoodsReceiptRepository,
    private val lineRepository: GoodsReceiptLineRepository,
    private val goodsReceiptAsnRepository: GoodsReceiptAsnRepository,
    private val asnService: AsnService,
    private val receiveLineValidator: ReceiveLineValidator,
    private val stockReceiver: StockReceiver,
    private val outboxService: OutboxService,
    private val stateChangedEvent: Event<GoodsReceiptStateChangedEvent>,
    private val lineReceivedEvent: Event<GoodsReceiptLineReceivedEvent>,
    private val lineReversedEvent: Event<GoodsReceiptLineReversedEvent>,
    private val sequenceNumberService: SequenceNumberService,
) {

    fun findById(id: Long, clientId: Long): GoodsReceiptResponse =
        toResponse(findEntityById(id, clientId))

    /** V424: batches ASN refs for the WHOLE page (2 extra queries total) -- fixes the former per-receipt N+1. */
    fun list(
        clientId: Long,
        pagination: PaginationParams,
        state: Int?,
        asnId: Long?,
    ): PaginatedResponse<GoodsReceiptResponse> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.descending("created"))
        val query = receiptRepository.search(clientId, state, asnId, sort)
            .page(Page.of(pagination.page, pagination.size))
        val receipts = query.list()
        val receiptIds = receipts.mapNotNull { it.id }.toSet()
        val asnIdsByReceipt = goodsReceiptAsnRepository.findAsnIdsFor(receiptIds)
        val asnsById = asnService.findByIds(asnIdsByReceipt.values.flatten().toSet(), clientId).associateBy { it.id!! }
        val content = receipts.map { toResponse(it, asnIdsByReceipt[it.id] ?: emptyList(), asnsById) }
        return paginatedResponse(content, pagination.page, pagination.size, query.count())
    }

    /**
     * Opens a receipt. [CreateGoodsReceiptRequest.asnIds] (plus the legacy scalar
     * [CreateGoodsReceiptRequest.asnId] alias, folded in) bind it to any number of
     * ASNs of the same client -- each must be RELEASED or STARTED, or the whole
     * create is refused with 409 [OrderException.AsnNotReceivable] (all-or-nothing:
     * no receipt is left half-bound).
     *
     * [CreateGoodsReceiptRequest.receiptType] (B2, myWMS `GoodsReceiptType` parity)
     * is fixed here forever — there is no update path. RETOUR + any requested ASN
     * is a 422: customer returns do not arrive on supplier ASNs; they ride the blind path.
     */
    @Transactional
    fun create(request: CreateGoodsReceiptRequest, clientId: Long): GoodsReceiptResponse {
        val asnIds = resolveRequestedAsnIds(request)
        val receiptType = validateReceiptType(request, asnIds)
        val receiptNumber = resolveReceiptNumber(request.receiptNumber, clientId)
        asnIds.forEach { loadAttachableAsn(receiptType.code, it, clientId) }
        val receipt = GoodsReceipt().apply {
            this.clientId = clientId
            this.receiptNumber = receiptNumber
            this.receiptType = receiptType.code
            carrierName = request.carrierName
            deliveryNoteNumber = request.deliveryNoteNumber
            notes = request.notes
            prio = request.prio
            receiptDate = request.receiptDate
            dockLocationId = request.dockLocationId
            dockLocationName = request.dockLocationName
        }
        receiptRepository.persist(receipt)
        asnIds.forEach { goodsReceiptAsnRepository.attach(receipt.id!!, it) }
        transition(receipt, OrderState.CREATED)
        return toResponse(receipt)
    }

    /**
     * V424: binds one more ASN under the SAME guards as [create] (RETOUR 422, ASN state
     * 409, tenant match) -- plus the same receipt-state gate every other control op
     * enforces: a closed (FINISHED/CANCELED) receipt cannot gain new ASN links.
     */
    @Transactional
    fun attachAsn(receiptId: Long, asnId: Long, clientId: Long): GoodsReceiptResponse {
        val receipt = findEntityById(receiptId, clientId)
        requireReceiptReceivable(receipt)
        loadAttachableAsn(receipt.receiptType, asnId, clientId)
        goodsReceiptAsnRepository.attach(receiptId, asnId)
        return toResponse(receipt)
    }

    /**
     * V424: unbinds an ASN. Refused (409 [OrderException.AsnDetachConflict]) while
     * any NON-REVERSED line still references one of the ASN's lines -- detaching
     * would orphan a live receive-time link. 404 when the pair was never attached
     * (a bad caller-supplied target, not a conflict). Gated the same way as
     * [attachAsn]/every other control op: a closed receipt refuses with 409
     * [OrderException.ReceiptNotReceivable] before either of the above checks run.
     */
    @Transactional
    fun detachAsn(receiptId: Long, asnId: Long, clientId: Long) {
        val receipt = findEntityById(receiptId, clientId)
        requireReceiptReceivable(receipt)
        val liveAsnLineIds = receipt.lines.filterNot { it.reversed }.mapNotNull { it.asnLineId }.toSet()
        if (liveAsnLineIds.isNotEmpty() && asnService.anyLineBelongsTo(liveAsnLineIds, asnId, clientId)) {
            throw OrderException.AsnDetachConflict(receiptId, asnId)
        }
        if (!goodsReceiptAsnRepository.detach(receiptId, asnId)) {
            throw OrderException.NotFound("GoodsReceiptAsn", "receiptId=$receiptId,asnId=$asnId")
        }
    }

    /**
     * B7: updates the header scalars (prio / receiptDate / dock pair; null = leave
     * unchanged, the [AsnService.update] convention). Allowed while CREATED **or**
     * STARTED — wider than the ASN's CREATED-only window, because the physical
     * arrival date and the dock door are typically discovered DURING receiving and
     * receiptDate must stay backdatable after the first line lands. [GoodsReceiptType]
     * stays immutable: the update request simply has no such field.
     */
    @Transactional
    fun update(id: Long, request: UpdateGoodsReceiptRequest, clientId: Long): GoodsReceiptResponse {
        val receipt = findEntityById(id, clientId)
        if (receipt.state != OrderState.CREATED.code && receipt.state != OrderState.STARTED.code) {
            throw OrderException.NotEditable(id, receipt.state, "GoodsReceipt")
        }
        request.prio?.let { receipt.prio = it }
        request.receiptDate?.let { receipt.receiptDate = it }
        request.dockLocationId?.let { receipt.dockLocationId = it }
        request.dockLocationName?.let { receipt.dockLocationName = it }
        return toResponse(receipt)
    }

    /**
     * B7: claims the receipt for [operatorId]. Mirrors [PickOrderService.claim]
     * SEMANTICS — any existing claim refuses with a 409, INCLUDING the caller's own
     * (claim is not idempotent), and a closed receipt is not claimable — but NOT its
     * state mechanics: the pick precedent also moves RELEASED→STARTED (and back on
     * release), which it only gets away with by assigning `state` directly, bypassing
     * its own guard. Per the B7 design doc, that precedent is "the claim pattern
     * living OUTSIDE the state machine in the one place it exists", not "backward
     * transitions are sanctioned" — so here [GoodsReceipt.operatorId] is PURE
     * METADATA and `state` (a real [OrderState.canAdvanceTo] chokepoint) never moves.
     */
    @Transactional
    fun claim(id: Long, operatorId: String, clientId: Long): GoodsReceiptResponse {
        val receipt = findEntityById(id, clientId)
        requireClaimable(receipt, id)
        receipt.operatorId = operatorId
        return toResponse(receipt)
    }

    /**
     * Row 20 (defect-burndown-4): mirrors the closed/paused predicate [GoodsReceiptRepository.findClaimable]/
     * [GoodsReceiptRepository.findClaimedBy] already enforce -- claim was missing the paused
     * refusal, so pausing a claimed-nobody receipt let a claim silently succeed and vanish from
     * "mine". Split into two helpers so neither trips detekt's `ThrowsCount` (max 2), the
     * [requireReversibleReceipt]/[requireReversibleLine] precedent.
     */
    private fun requireClaimable(receipt: GoodsReceipt, id: Long) {
        if (receipt.state != OrderState.CREATED.code && receipt.state != OrderState.STARTED.code) {
            throw OrderException.ReceiptClaimConflict(id, "receipt is closed (state ${receipt.state})")
        }
        if (receipt.pausedAt != null) {
            throw OrderException.ReceiptClaimConflict(id, "receipt is paused")
        }
        requireNotAlreadyClaimed(receipt, id)
    }

    private fun requireNotAlreadyClaimed(receipt: GoodsReceipt, id: Long) {
        if (receipt.operatorId != null) {
            throw OrderException.ReceiptClaimConflict(id, "already claimed by '${receipt.operatorId}'")
        }
    }

    /**
     * B7: releases the claim. Mirrors [PickOrderService.release]: a non-owner needs
     * [asManager] (the caller holds MANAGER) or the release is a 409 — the same
     * status the pick's operator mismatch maps to. No state gate: unlike the pick
     * (whose claim moved state, so release must un-move it), the GR claim is pure
     * metadata, and blocking release after FINISHED would strand the claim forever.
     *
     * A6/:1550: releasing an UNCLAIMED receipt (`operatorId == null`) is a no-op success, not a
     * conflict -- recon found this bug is identical to [OrderService.releaseOperator]'s (same
     * inline check, same misleading message), so the fix lands here too. The refusal only
     * fires when a DIFFERENT operator genuinely holds the claim and the caller isn't a manager.
     */
    @Transactional
    fun release(id: Long, operatorId: String, asManager: Boolean, clientId: Long): GoodsReceiptResponse {
        val receipt = findEntityById(id, clientId)
        if (receipt.operatorId != null && receipt.operatorId != operatorId && !asManager) {
            throw OrderException.ReceiptClaimConflict(id, "claimed by a different operator")
        }
        receipt.operatorId = null
        return toResponse(receipt)
    }

    /**
     * B7 pause — Option B7-P1: stamps the ORTHOGONAL [GoodsReceipt.pausedAt]; `state`
     * NEVER moves and [OrderState.canAdvanceTo] is untouched (it is shared by six
     * entity families — and PAUSE(200) is unreachable from STARTED(500) anyway).
     * This also fixes myWMS's lossy resume: because state never moved, resuming
     * restores the receipt exactly where it was. While paused, [receiveLine] and
     * [finish] refuse with 409 — otherwise pause would be decorative.
     *
     * A second pause is a 409, matching the claim's fail-loud non-idempotence: a
     * pause of an already-paused receipt means the caller's view is stale, and
     * silently succeeding would hide that. Pausing a FINISHED/CANCELED receipt is a
     * 409 (the myWMS gate: pause only in [CREATED, FINISHED)).
     */
    @Transactional
    fun pause(id: Long, clientId: Long): GoodsReceiptResponse {
        val receipt = findEntityById(id, clientId)
        if (receipt.state != OrderState.CREATED.code && receipt.state != OrderState.STARTED.code) {
            throw OrderException.ReceiptPauseConflict(id, "receipt is closed (state ${receipt.state})")
        }
        if (receipt.pausedAt != null) {
            throw OrderException.ReceiptPauseConflict(id, "already paused since ${receipt.pausedAt}")
        }
        receipt.pausedAt = Instant.now()
        return toResponse(receipt)
    }

    /** B7: clears the pause stamp (409 if not paused — same fail-loud symmetry as [pause]). */
    @Transactional
    fun resume(id: Long, clientId: Long): GoodsReceiptResponse {
        val receipt = findEntityById(id, clientId)
        if (receipt.pausedAt == null) {
            throw OrderException.ReceiptPauseConflict(id, "receipt is not paused")
        }
        receipt.pausedAt = null
        return toResponse(receipt)
    }

    /**
     * Receives one line: validates the receipt/ASN state, runs the over-receipt
     * guard and the product constraint checks ([validateReceiptConstraints], B6),
     * creates INCOMING stock via [StockReceiver.receive], records the line,
     * updates the ASN expectation, and fires [GoodsReceiptLineReceivedEvent].
     * On a RETOUR receipt an omitted lockType defaults to QUALITY_FAULT — see
     * [resolveLineLockType].
     *
     * **Configuration:** `lockType` is deliberately a *per-request* input (the
     * caller/operator decides per line), not a strategy-entity gap. `allowOverReceipt` is
     * now a TWO-LEVEL composition (inbound-completion row 10): the per-request flag is
     * still honored, but only when the instance-level `karyo.receiving.allow-over-receipt`
     * knob (default `true`, [com.karyo.orders.config.ReceivingConfig], enforced by
     * [OverReceiptGuard] via [AsnService.checkOverReceipt]) also allows it — the instance
     * knob is the STRICTER gate and a per-request override can never widen it. If a further
     * strategic pattern emerges (e.g. vendor-X always allows over-receipt, SKU-Y always
     * QA-holds), elevate `lockType` (and/or `allowOverReceipt`) to a `ReceivingStrategy`
     * with a resolver seam — deferred to v2.x.
     */
    @Transactional
    fun receiveLine(receiptId: Long, request: ReceiveLineRequest, clientId: Long): ReceiveLineResponse {
        val receipt = findEntityById(receiptId, clientId)
        if (receipt.state != OrderState.CREATED.code && receipt.state != OrderState.STARTED.code) {
            throw OrderException.ReceiptNotReceivable(receiptId, receipt.state)
        }
        if (receipt.pausedAt != null) {
            throw OrderException.ReceiptPaused(receiptId)
        }
        receiveLineValidator.validateLockType(request.lockType)
        receiveLineValidator.validateStorageStrategy(request.storageStrategyId, clientId)
        val effectiveLockType = resolveLineLockType(receipt, request)
        val context = resolveLineContext(receipt, request, clientId)
        receiveLineValidator.validateReceiptConstraints(context.product, request)

        val received = stockReceiver.receive(
            ReceiveStockRequest(
                clientId = receipt.clientId,
                itemDataId = context.itemDataId,
                itemDataNumber = context.itemDataNumber,
                amount = request.amount,
                locationId = request.locationId,
                locationName = request.locationName,
                unitLoadLabel = request.unitLoadLabel,
                unitLoadTypeId = request.unitLoadTypeId,
                lotNumber = request.lotNumber,
                serialNumber = request.serialNumber,
                packagingUnitId = request.packagingUnitId,
                bestBefore = request.bestBefore,
                lockType = effectiveLockType,
                lockNote = request.note,
            )
        )

        val line = GoodsReceiptLine().apply {
            goodsReceipt = receipt
            asnLineId = context.asnLine?.id
            itemDataId = context.itemDataId
            itemDataNumber = context.itemDataNumber
            amount = request.amount
            locationId = request.locationId
            locationName = request.locationName
            unitLoadLabel = received.unitLoadLabel
            stockUnitId = received.stockUnitId
            unitLoadId = received.unitLoadId
            lotNumber = request.lotNumber
            serialNumber = request.serialNumber
            packagingUnitId = request.packagingUnitId
            bestBefore = request.bestBefore
            lockType = effectiveLockType
            note = request.note
            storageStrategyId = request.storageStrategyId
        }
        lineRepository.persist(line)
        receipt.lines.add(line)

        if (receipt.state == OrderState.CREATED.code) {
            transition(receipt, OrderState.STARTED)
        }
        if (context.asn != null && context.asnLine != null) {
            asnService.recordReceipt(context.asn, context.asnLine, request.amount)
        }
        matchUlAdvice(receipt, received.unitLoadLabel, line.id!!)
        fireLineReceived(receipt, line, context.asn?.id)

        return ReceiveLineResponse(
            receipt = toResponse(receipt),
            lineId = line.id!!,
            stockUnitId = received.stockUnitId,
            unitLoadId = received.unitLoadId,
            unitLoadLabel = received.unitLoadLabel,
        )
    }

    /**
     * Finishes the receipt and promotes all non-locked stock INCOMING→ON_STOCK via
     * [StockReceiver.markOnStock] — the filter reads the DERIVED [GoodsReceiptLine.qaHold]
     * ("any lock is set") and [GoodsReceiptLine.reversed] (B3: a reversed line's stock
     * is already soft-deleted, promoting it would be a no-op at best). Locked units
     * stay INCOMING + locked (manual release, see class KDoc). markOnStock
     * independently skips locked/reversed stock, belt-and-braces.
     */
    @Transactional
    fun finish(id: Long, clientId: Long): GoodsReceiptResponse {
        val receipt = findEntityById(id, clientId)
        if (receipt.state != OrderState.CREATED.code && receipt.state != OrderState.STARTED.code) {
            throw OrderException.InvalidTransition(id, receipt.state, OrderState.FINISHED.code)
        }
        if (receipt.pausedAt != null) {
            throw OrderException.ReceiptPaused(id)
        }
        stockReceiver.markOnStock(receipt.lines.filterNot { it.qaHold || it.reversed }.map { it.stockUnitId })
        transition(receipt, OrderState.FINISHED)
        return toResponse(receipt)
    }

    /**
     * B3 removeGoodsReceiptLineWithStocks (six-hard-items §3, option B3-2): total
     * undo of a received line — refuses unless it can fully reverse. Soft-reverses
     * the line ([GoodsReceiptLine.reversedAt]), soft-deletes the stock via
     * [StockReceiver.unreceive] (myWMS amount-equality guard), decrements the bound
     * ASN line WITHOUT walking its state backward ([AsnService.retractReceipt]), and
     * cancels the putaway [com.karyo.tasks.domain.model.TransportOrder] in the SAME
     * transaction via `TaskService.onGoodsReceiptLineReversed` (synchronous
     * observer, default phase): if that task is already STARTED the observer throws
     * and the whole reversal rolls back. Also reopens any UL pre-advice this line
     * matched ([AsnService.reopenUlAdvice]) — otherwise a re-receive of the same
     * physical UL would find the advice already FINISHED and never re-match.
     */
    @Transactional
    fun reverseLine(receiptId: Long, lineId: Long, clientId: Long): GoodsReceiptResponse {
        val receipt = findEntityById(receiptId, clientId)
        requireReversibleReceipt(receipt, receiptId, lineId)
        val line = requireReversibleLine(receipt, lineId)

        stockReceiver.unreceive(line.stockUnitId, line.amount, receipt.clientId)
        retractAsnLine(receipt, line)
        line.reversedAt = Instant.now()
        asnService.reopenUlAdvice(line.id!!)
        fireLineReversed(receipt, line)

        return toResponse(receipt)
    }

    /** Cancels a receipt — only while no lines were received (stock cannot be "un-received"). */
    @Transactional
    fun cancel(id: Long, clientId: Long): GoodsReceiptResponse {
        val receipt = findEntityById(id, clientId)
        if (receipt.lines.isNotEmpty()) {
            throw OrderException.NotCancelable("GoodsReceipt", id, "${receipt.lines.size} line(s) were already received")
        }
        if (receipt.state >= OrderState.FINISHED.code) {
            throw OrderException.NotCancelable("GoodsReceipt", id, "receipt is already closed (state ${receipt.state})")
        }
        transition(receipt, OrderState.CANCELED)
        return toResponse(receipt)
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * B2: resolves and validates the inbound reason at create — the only moment the
     * type is ever set (immutable afterwards; no update path exists). An unknown
     * code is a 422, and RETOUR + any requested ASN is a 422: customer returns do
     * not arrive on supplier ASNs, so they ride the existing blind path.
     */
    private fun validateReceiptType(request: CreateGoodsReceiptRequest, asnIds: Set<Long>): GoodsReceiptType {
        val receiptType = GoodsReceiptType.fromCode(request.receiptType)
            ?: throw OrderException.UnsupportedReceiptType(request.receiptType)
        if (receiptType == GoodsReceiptType.RETOUR && asnIds.isNotEmpty()) {
            throw OrderException.RetourWithAsn(asnIds.first())
        }
        return receiptType
    }

    /** V424: folds the legacy scalar [CreateGoodsReceiptRequest.asnId] alias into the [CreateGoodsReceiptRequest.asnIds] set. */
    private fun resolveRequestedAsnIds(request: CreateGoodsReceiptRequest): Set<Long> =
        (request.asnIds + listOfNotNull(request.asnId)).toSet()

    /**
     * Attach guard shared by [create]/[attachAsn]/receive-time auto-attach: a RETOUR
     * receipt refuses any ASN (422 [OrderException.RetourWithAsn]), and the ASN must
     * be RELEASED or STARTED (409 [OrderException.AsnNotReceivable]).
     */
    private fun requireAsnAttachable(receiptType: Int, asn: Asn) {
        if (receiptType == GoodsReceiptType.RETOUR.code) {
            throw OrderException.RetourWithAsn(asn.id!!)
        }
        if (asn.state != OrderState.RELEASED.code && asn.state != OrderState.STARTED.code) {
            throw OrderException.AsnNotReceivable(asn.id!!, asn.state)
        }
    }

    /** Tenant-scoped load + [requireAsnAttachable] guard — used by [create]/[attachAsn]. */
    private fun loadAttachableAsn(receiptType: Int, asnId: Long, clientId: Long): Asn {
        val asn = asnService.findEntityById(asnId, clientId)
        requireAsnAttachable(receiptType, asn)
        return asn
    }

    /**
     * The same closed-receipt gate every other control op enforces (409
     * [OrderException.ReceiptNotReceivable]) — shared by [attachAsn]/[detachAsn].
     */
    private fun requireReceiptReceivable(receipt: GoodsReceipt) {
        if (receipt.state != OrderState.CREATED.code && receipt.state != OrderState.STARTED.code) {
            throw OrderException.ReceiptNotReceivable(receipt.id ?: 0L, receipt.state)
        }
    }

    /**
     * B2: the RETOUR default-lock POLICY lives HERE at the service, not in the
     * [StockReceiver] SPI — the SPI stays a policy-free mechanism ("create stock,
     * optionally locked") that inventory honours without knowing receiving rules.
     * A RETOUR line with no caller lockType defaults to QUALITY_FAULT(103):
     * returned goods are inspected before restocking. An explicit caller lockType
     * (any allowed value) wins, and NORMAL receipts never get a default. The
     * derived qaHold then does the right thing everywhere downstream (finish
     * filter, putaway skip) with zero further changes — the point of B5's design.
     */
    private fun resolveLineLockType(receipt: GoodsReceipt, request: ReceiveLineRequest): Int? =
        request.lockType
            ?: LockType.QUALITY_FAULT.code.takeIf { receipt.receiptType == GoodsReceiptType.RETOUR.code }

    /** Resolved product/ASN-line context for one receive-line call. */
    private data class LineContext(
        val asn: Asn?,
        val asnLine: AsnLine?,
        val itemDataId: Long,
        val itemDataNumber: String,
        val product: ProductResponse,
    )

    /**
     * Resolves the ASN line (auto-attaching its parent ASN to the receipt, see
     * [resolveAsnLineForReceive]; itemDataId defaults from it) or the blind-line
     * product, and runs the over-receipt guard. BOTH branches load the product —
     * the constraint checks (B6) need its flags.
     */
    private fun resolveLineContext(receipt: GoodsReceipt, request: ReceiveLineRequest, clientId: Long): LineContext {
        val requestedAsnLineId = request.asnLineId
        if (requestedAsnLineId != null) {
            val (asn, asnLine) = resolveAsnLineForReceive(receipt, requestedAsnLineId, clientId)
            validateLineItem(asnLine, request.itemDataId)
            asnService.checkOverReceipt(asnLine, request)
            // B6: this path used to trust the AsnLine's denormalized item fields and never
            // loaded the product, which made lotMandatory/bestBeforeMandatory unenforceable
            // exactly where most receiving happens. The per-line load is cheap: a primary-key
            // read, and the product module additionally Caffeine-caches product reads
            // ("products-by-id").
            val product = receiveLineValidator.resolveAsnLineProduct(asnLine.itemDataId, asnLine.itemDataNumber)
            return LineContext(asn, asnLine, asnLine.itemDataId, asnLine.itemDataNumber, product)
        }
        return blindLineContext(request)
    }

    /**
     * V424: resolves the ASN line purely by its own id (no longer "must belong to
     * the receipt's single linked ASN" — a receipt may span several). When the
     * line's parent ASN is not yet attached to [receipt], auto-attaches it here
     * under the SAME guards as the explicit attach endpoint (legacy-faithful:
     * receiving against an expected line was always enough to bind the ASN).
     * Idempotent — a repeat receive against an already-attached ASN's line is a
     * no-op on the join row ([com.karyo.orders.repository.GoodsReceiptAsnRepository.attach]).
     */
    private fun resolveAsnLineForReceive(receipt: GoodsReceipt, asnLineId: Long, clientId: Long): Pair<Asn, AsnLine> {
        val (asn, asnLine) = asnService.findLineWithAsn(asnLineId, clientId)
            ?: throw OrderException.ValidationFailed("ASN line $asnLineId does not exist")
        val asnId = asn.id!!
        if (!goodsReceiptAsnRepository.exists(receipt.id!!, asnId)) {
            requireAsnAttachable(receipt.receiptType, asn)
            goodsReceiptAsnRepository.attach(receipt.id!!, asnId)
        }
        return asn to asnLine
    }

    /** A request itemDataId on an ASN-bound line must match the expected line's item. */
    private fun validateLineItem(asnLine: AsnLine, requestItemDataId: Long?) {
        if (requestItemDataId != null && requestItemDataId != asnLine.itemDataId) {
            throw OrderException.ValidationFailed(
                "itemDataId $requestItemDataId does not match ASN line ${asnLine.id} (item ${asnLine.itemDataId})"
            )
        }
    }

    private fun blindLineContext(request: ReceiveLineRequest): LineContext {
        val itemDataId = request.itemDataId
            ?: throw OrderException.ValidationFailed("either asnLineId or itemDataId must be given")
        val product = receiveLineValidator.resolveBlindProduct(itemDataId)
        return LineContext(
            asn = null, asnLine = null,
            itemDataId = product.id, itemDataNumber = product.number,
            product = product,
        )
    }

    /**
     * Karyo-native UL pre-advice silent match (row 2, see
     * [com.karyo.orders.domain.model.AsnUlAdvice]): when the receipt has ANY ASN
     * attached, checks the resolved [unitLoadLabel] against every attached ASN's
     * OPEN advices via [AsnService.matchUlAdvice] -- a blind receipt (no ASNs
     * attached at all) never matches, and an unmatched label is silently a no-op,
     * by design (un-advised arrivals are first-class, never warned about).
     */
    private fun matchUlAdvice(receipt: GoodsReceipt, unitLoadLabel: String, receiptLineId: Long) {
        val attachedAsnIds = goodsReceiptAsnRepository.findAsnIdsFor(receipt.id!!)
        if (attachedAsnIds.isNotEmpty()) {
            asnService.matchUlAdvice(attachedAsnIds.toSet(), unitLoadLabel, receiptLineId)
        }
    }

    /**
     * B3: subtracts the reversed amount from the bound ASN line, if any (blind
     * lines have no [GoodsReceiptLine.asnLineId] and are skipped). V424: the line's
     * own ASN is resolved via [AsnService.findLineWithAsn] — it no longer needs
     * [GoodsReceipt] to carry a single bound ASN.
     */
    private fun retractAsnLine(receipt: GoodsReceipt, line: GoodsReceiptLine) {
        val asnLineId = line.asnLineId ?: return
        val (_, asnLine) = asnService.findLineWithAsn(asnLineId, receipt.clientId) ?: return
        asnService.retractReceipt(asnLine, line.amount)
    }

    /** Fires the line-reversed event (CDI + outbox) — B3's same-tx putaway-cancel trigger. */
    private fun fireLineReversed(receipt: GoodsReceipt, line: GoodsReceiptLine) {
        val event = GoodsReceiptLineReversedEvent(
            goodsReceiptLineId = line.id!!,
            goodsReceiptId = receipt.id!!,
            stockUnitId = line.stockUnitId,
            unitLoadId = line.unitLoadId,
            itemDataId = line.itemDataId,
            amount = line.amount,
            clientId = receipt.clientId,
        )
        outboxService.publish("GoodsReceipt", receipt.id!!, "GoodsReceiptLineReversed", event, receipt.clientId)
        lineReversedEvent.fire(event) // synchronous CDI — joins this tx (putaway cancel can roll it back)
    }

    /**
     * Fires the line-received event (CDI + outbox) — sub-phase 2.3's putaway trigger.
     * V424: [asnId] is the LINE's own ASN (resolved by [resolveAsnLineForReceive]),
     * not a receipt-level scalar.
     */
    private fun fireLineReceived(receipt: GoodsReceipt, line: GoodsReceiptLine, asnId: Long?) {
        val event = GoodsReceiptLineReceivedEvent(
            goodsReceiptId = receipt.id!!,
            goodsReceiptLineId = line.id!!,
            asnId = asnId,
            itemDataId = line.itemDataId,
            amount = line.amount,
            stockUnitId = line.stockUnitId,
            unitLoadId = line.unitLoadId,
            unitLoadLabel = line.unitLoadLabel,
            locationId = line.locationId,
            locationName = line.locationName,
            qaHold = line.qaHold,
            clientId = receipt.clientId,
            occurredAt = Instant.now(),
            storageStrategyId = line.storageStrategyId,
        )
        outboxService.publish("GoodsReceipt", receipt.id!!, "GoodsReceiptLineReceived", event, receipt.clientId)
        lineReceivedEvent.fire(event)
    }

    /**
     * Validates that the receipt is in a reversible state (not closed and not paused).
     * Split from [reverseLine] so no single function's throw count trips detekt's
     * `ThrowsCount` (max 2).
     */
    private fun requireReversibleReceipt(receipt: GoodsReceipt, receiptId: Long, lineId: Long) {
        if (receipt.state != OrderState.CREATED.code && receipt.state != OrderState.STARTED.code) {
            throw OrderException.NotCancelable("GoodsReceiptLine", lineId, "receipt is closed (state ${receipt.state})")
        }
        if (receipt.pausedAt != null) {
            throw OrderException.ReceiptPaused(receiptId)
        }
    }

    /**
     * Validates that a line exists and has not already been reversed.
     * Split from [reverseLine] so no single function's throw count trips detekt's
     * `ThrowsCount` (max 2).
     */
    private fun requireReversibleLine(receipt: GoodsReceipt, lineId: Long): GoodsReceiptLine {
        val line = receipt.lines.firstOrNull { it.id == lineId }
            ?: throw OrderException.NotFound("GoodsReceiptLine", "id=$lineId")
        if (line.reversed) {
            throw OrderException.NotCancelable("GoodsReceiptLine", lineId, "line is already reversed")
        }
        return line
    }

    /**
     * Single chokepoint for receipt state changes: enforces [OrderState.canAdvanceTo],
     * fires the synchronous CDI event, and writes the outbox row.
     */
    private fun transition(receipt: GoodsReceipt, target: OrderState) {
        val current = OrderState.fromCode(receipt.state)
        if (!current.canAdvanceTo(target)) {
            throw OrderException.InvalidTransition(receipt.id ?: 0L, current.code, target.code)
        }
        receipt.state = target.code
        val event = GoodsReceiptStateChangedEvent(
            goodsReceiptId = receipt.id!!,
            receiptNumber = receipt.receiptNumber,
            oldState = current.code,
            newState = target.code,
            clientId = receipt.clientId,
            occurredAt = Instant.now(),
        )
        outboxService.publish("GoodsReceipt", receipt.id!!, "GoodsReceiptStateChanged", event, receipt.clientId)
        stateChangedEvent.fire(event)
    }

    private fun resolveReceiptNumber(requested: String?, clientId: Long): String =
        if (requested != null) validateRequestedReceiptNumber(requested, clientId) else generateReceiptNumber(clientId)

    private fun validateRequestedReceiptNumber(requested: String, clientId: Long): String {
        if (requested.isBlank()) {
            throw OrderException.ValidationFailed("receiptNumber must not be blank")
        }
        receiptRepository.findByReceiptNumber(requested, clientId)?.let {
            throw OrderException.DuplicateName("GoodsReceipt", requested)
        }
        return requested
    }

    /** goods_receipts.receipt_number is VARCHAR(100). */
    private fun generateReceiptNumber(clientId: Long): String =
        sequenceNumberService.next("goodsreceipt.receiptNumber", "GR", clientId, MAX_NUMBER_LENGTH) { candidate ->
            receiptRepository.findByReceiptNumber(candidate, clientId) == null
        }

    private fun findEntityById(id: Long, clientId: Long): GoodsReceipt =
        receiptRepository.findByIdAndClient(id, clientId)
            ?: throw OrderException.NotFound("GoodsReceipt", "id=$id")

    /** Single-receipt convenience overload — batches its own (small) ASN-ref lookup. Use [toResponse] with pre-batched maps on a page. */
    private fun toResponse(receipt: GoodsReceipt): GoodsReceiptResponse {
        val asnIds = goodsReceiptAsnRepository.findAsnIdsFor(receipt.id!!)
        val asnsById = asnService.findByIds(asnIds.toSet(), receipt.clientId).associateBy { it.id!! }
        return toResponse(receipt, asnIds, asnsById)
    }

    private fun toResponse(receipt: GoodsReceipt, asnIds: List<Long>, asnsById: Map<Long, Asn>): GoodsReceiptResponse =
        GoodsReceiptResponse(
            id = receipt.id!!,
            receiptNumber = receipt.receiptNumber,
            asns = asnIds.mapNotNull { asnId -> asnsById[asnId]?.let { AsnRefResponse(asnId, it.asnNumber) } },
            carrierName = receipt.carrierName,
            deliveryNoteNumber = receipt.deliveryNoteNumber,
            notes = receipt.notes,
            receiptType = receipt.receiptType,
            state = receipt.state,
            stateName = OrderState.fromCode(receipt.state).name,
            clientId = receipt.clientId,
            lines = receipt.lines.map { toLineResponse(it) },
            created = receipt.created.toString(),
            modified = receipt.modified.toString(),
            prio = receipt.prio,
            receiptDate = receipt.receiptDate,
            dockLocationId = receipt.dockLocationId,
            dockLocationName = receipt.dockLocationName,
            operatorId = receipt.operatorId,
            pausedAt = receipt.pausedAt?.toString(),
        )

    private fun toLineResponse(line: GoodsReceiptLine): GoodsReceiptLineResponse =
        GoodsReceiptLineResponse(
            id = line.id!!,
            asnLineId = line.asnLineId,
            itemDataId = line.itemDataId,
            itemDataNumber = line.itemDataNumber,
            amount = line.amount,
            locationId = line.locationId,
            locationName = line.locationName,
            unitLoadLabel = line.unitLoadLabel,
            stockUnitId = line.stockUnitId,
            unitLoadId = line.unitLoadId,
            lotNumber = line.lotNumber,
            serialNumber = line.serialNumber,
            packagingUnitId = line.packagingUnitId,
            bestBefore = line.bestBefore,
            lockType = line.lockType,
            note = line.note,
            qaHold = line.qaHold,
            reversed = line.reversed,
            reversedAt = line.reversedAt?.toString(),
            storageStrategyId = line.storageStrategyId,
        )

    companion object {
        val SORTABLE_FIELDS = setOf("id", "receiptNumber", "state", "created")
        private const val MAX_NUMBER_LENGTH = 100
    }
}
