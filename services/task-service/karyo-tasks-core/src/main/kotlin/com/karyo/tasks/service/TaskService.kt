package com.karyo.tasks.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.common.pagination.paginatedResponse
import com.karyo.inventory.api.spi.UnitLoadInfo
import com.karyo.inventory.api.spi.UnitLoadLookup
import com.karyo.inventory.api.spi.UnitLoadMover
import com.karyo.layout.spi.LocationFinder
import com.karyo.layout.spi.LocationFinderRequest
import com.karyo.layout.spi.LocationFinderResult
import com.karyo.orders.event.GoodsReceiptLineReceivedEvent
import com.karyo.orders.event.GoodsReceiptLineReversedEvent
import com.karyo.orders.vo.OrderState
import com.karyo.sequence.SequenceNumberService
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.dto.CompleteTransportOrderRequest
import com.karyo.tasks.dto.CreateTransportOrderRequest
import com.karyo.tasks.dto.TransportOrderResponse
import com.karyo.tasks.exception.TaskException
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.spi.CrossDockLookup
import com.karyo.tasks.vo.TransportType
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.event.TransactionPhase
import jakarta.enterprise.inject.Instance
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Instant

/**
 * TransportOrder lifecycle: v1.2 putaway & manual moves, plus REPLENISH ([createReplenishment])
 * and — PT15 — TRANSFER chain successors, auto-minted mid-[complete] by [ChainContinuationService]
 * rather than through any create* function of this class's own.
 *
 * **Auto-putaway:** [onGoodsReceiptLineReceived] observes the receiving event and, for
 * every non-QA-held line, looks up the unit load, asks the [LocationFinder] for a
 * destination, and creates a PUTAWAY task (RELEASED into the queue). QA-held lines are
 * skipped — that stock is not yet ready to put away. A NoLocation result still creates
 * the task (CREATED, no suggestion, with a note) so the work is never lost; the operator
 * resolves it manually. Idempotent: one task per goodsReceiptLineId.
 *
 * **Manual move:** [createManualMove] creates a MOVE task (RELEASED immediately) with the
 * source resolved from the unit load's current location.
 *
 * **Operator flow:** [assign] RELEASED→RESERVED, [start] RESERVED→STARTED (re-resolving a
 * stale/None suggestion), [complete] performs the inventory move via [UnitLoadMover] — or,
 * PT16/PT17, confirm-merges into an EXISTING unit load or requests a PARTIAL confirm, both of
 * which live entirely on [ConfirmVariantService] (see its KDoc for why) — releases the layout
 * reservation, →FINISHED + completion event. [cancel] (pre-STARTED) →CANCELED + reservation
 * release.
 *
 * Every state change goes through [TransportOrderEmitter.transition] (canAdvanceTo guard + CDI
 * event + outbox).
 */
@ApplicationScoped
class TaskService(
    private val repository: TransportOrderRepository,
    private val unitLoadLookup: UnitLoadLookup,
    private val unitLoadMover: UnitLoadMover,
    private val locationFinder: LocationFinder,
    private val chainContinuation: ChainContinuationService,
    /**
     * PT16/PT17: owns confirm-merge and partial-confirm branches of [complete], plus the
     * moved-stock denorm-at-creation helper every create* function below calls — kept out of
     * this class entirely because it is already at Detekt's `TooManyFunctions` ceiling (see
     * [ConfirmVariantService]'s own KDoc).
     */
    private val confirmVariantService: ConfirmVariantService,
    private val sequenceNumberService: SequenceNumberService,
    private val emitter: TransportOrderEmitter,
    /**
     * Cross-docking sprint: answered by the paid crossdock module ([CrossDockLookup]). No bean
     * exists at all when that module is absent/unlicensed, so this is `Instance<T>` rather than
     * a direct injection; see [onGoodsReceiptLineReceived]'s guard for the resolvability check.
     */
    private val crossDockLookup: Instance<CrossDockLookup>,
) {
    private val log = Logger.getLogger(TaskService::class.java)

    // ── Queries ───────────────────────────────────────────────────────────

    fun findById(id: Long, clientId: Long): TransportOrderResponse =
        emitter.toResponse(findEntityById(id, clientId))

    @Suppress("LongParameterList")
    fun list(
        clientId: Long,
        pagination: PaginationParams,
        state: Int?,
        type: TransportType?,
        operatorId: String?,
        q: String?,
        /** Row 22: `true`/`false` filter on `pausedAt` non-null/null; absent (`null`) applies no clause. */
        paused: Boolean? = null,
    ): PaginatedResponse<TransportOrderResponse> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.by("prio").and("created"))
        val query = repository.search(clientId, state, type, operatorId, q, paused, sort)
            .page(Page.of(pagination.page, pagination.size))
        return paginatedResponse(query.list().map { emitter.toResponse(it) }, pagination.page, pagination.size, query.count())
    }

    // ── Auto-putaway (receiving observer) ─────────────────────────────────

    /**
     * Creates a PUTAWAY task for a received line. Runs AFTER the receiving transaction
     * commits ([TransactionPhase.AFTER_SUCCESS]) so the unit load/stock are visible to
     * the inventory lookup, and starts its own transaction.
     *
     * **Configuration:** auto-putaway-on-receipt is currently *always-on* for
     * non-QA-held lines — hardcoded by design for v1.2/v1.3, not a configurable knob.
     * Making it tunable (disable / immediate vs deferred / per-item-category) belongs on a
     * future `ReceivingStrategy` (tier-2 JSONB or an SPI); deferred to v2.x.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun onGoodsReceiptLineReceived(
        @Observes(during = TransactionPhase.AFTER_SUCCESS) event: GoodsReceiptLineReceivedEvent,
    ) {
        if (event.qaHold) {
            log.debugf("Skipping putaway for QA-held receipt line %d (stock not ready)", event.goodsReceiptLineId)
            return
        }
        // Cross-docking (Advanced Fulfillment): the interceptor ran synchronously inside the
        // receiving transaction; this observer runs strictly after it commits, so the lookup is
        // authoritative. Empty Instance = paid module absent = never intercepted.
        if (crossDockLookup.isResolvable && crossDockLookup.get().existsForReceiptLine(event.goodsReceiptLineId)) {
            log.debugf("Skipping putaway: cross-dock order exists for receipt line %d", event.goodsReceiptLineId)
            return
        }
        if (repository.findByGoodsReceiptLineId(event.goodsReceiptLineId) != null) {
            log.debugf("Putaway task already exists for receipt line %d; skipping (idempotent)", event.goodsReceiptLineId)
            return
        }
        val ulInfo = unitLoadLookup.findById(event.unitLoadId)
        if (ulInfo == null) {
            log.warnf("Unit load %d not found for receipt line %d; skipping putaway", event.unitLoadId, event.goodsReceiptLineId)
            return
        }
        createPutawayTask(event, ulInfo)
    }

    /**
     * B3: cancels the putaway task for a reversed receipt line — synchronous
     * observer, DEFAULT phase (no `during=AFTER_SUCCESS`, no `REQUIRES_NEW`, unlike
     * [onGoodsReceiptLineReceived]) so it joins the caller's transaction
     * (`GoodsReceiptService.reverseLine`) and can abort it. Terminal tasks
     * (FINISHED/CANCELED) permit reversal since no live work can conflict — myWMS permits
     * undo after completed putaway. Only in-flight tasks (STARTED) block reversal.
     * No task for the line (e.g. it was QA-held at receipt, so no putaway was ever created)
     * is a silent no-op.
     */
    fun onGoodsReceiptLineReversed(@Observes event: GoodsReceiptLineReversedEvent) {
        val task = repository.findByGoodsReceiptLineId(event.goodsReceiptLineId) ?: return
        // D4: terminal tasks are not live work — nothing to cancel, nothing an operator
        // loses. Only a genuinely in-flight task blocks the reversal.
        if (task.state == OrderState.FINISHED.code || task.state == OrderState.CANCELED.code) return
        if (task.state >= OrderState.STARTED.code) {
            throw TaskException.NotCancelable(
                task.id!!,
                "putaway is in progress for reversed receipt line ${event.goodsReceiptLineId}",
            )
        }
        cancel(task.id!!, task.clientId)
    }

    private fun createPutawayTask(event: GoodsReceiptLineReceivedEvent, ulInfo: UnitLoadInfo) {
        val order = TransportOrder().apply {
            clientId = event.clientId
            orderNumber = generateOrderNumber("TO", event.clientId)
            transportType = TransportType.PUTAWAY
            unitLoadId = event.unitLoadId
            unitLoadLabel = event.unitLoadLabel
            sourceLocationId = event.locationId
            sourceLocationName = event.locationName
            goodsReceiptLineId = event.goodsReceiptLineId
            storageStrategyId = event.storageStrategyId
        }
        confirmVariantService.denormalizeAtCreation(order, event.unitLoadId)
        repository.persist(order)
        emitter.transition(order, OrderState.CREATED)

        when (val result = findLocation(ulInfo, event.clientId, order.id!!, order.storageStrategyId)) {
            is LocationFinderResult.Found -> {
                order.suggestedLocationId = result.locationId
                order.suggestedLocationName = result.locationName
                emitter.transition(order, OrderState.RELEASED)
            }
            is LocationFinderResult.NoLocation -> {
                // Keep the work: surface it in the queue (CREATED, unreleased) with a reason.
                order.note = "No location suggested: ${result.reason}"
                log.infof("Putaway task %s created without suggestion: %s", order.orderNumber, result.reason)
            }
        }
    }

    // ── Manual move ───────────────────────────────────────────────────────

    @Transactional
    @Suppress("ThrowsCount")
    fun createManualMove(request: CreateTransportOrderRequest, clientId: Long): TransportOrderResponse {
        val unitLoadId = request.unitLoadId
            ?: throw TaskException.ValidationFailed("unitLoadId is required")
        val destinationLocationId = request.destinationLocationId
            ?: throw TaskException.ValidationFailed("destinationLocationId is required")
        val ulInfo = unitLoadLookup.findById(unitLoadId)
            ?: throw TaskException.InvalidReference("UnitLoad", "id=$unitLoadId")

        val order = TransportOrder().apply {
            this.clientId = clientId
            orderNumber = generateOrderNumber("MV", clientId)
            transportType = TransportType.MOVE
            this.unitLoadId = unitLoadId
            unitLoadLabel = ulInfo.label
            sourceLocationId = ulInfo.locationId
            sourceLocationName = ulInfo.locationName
            this.destinationLocationId = destinationLocationId
            destinationLocationName = request.destinationLocationName
            suggestedLocationId = destinationLocationId
            suggestedLocationName = request.destinationLocationName
            prio = request.prio
            externalNumber = request.externalNumber
            externalId = request.externalId
        }
        confirmVariantService.denormalizeAtCreation(order, unitLoadId)
        repository.persist(order)
        emitter.transition(order, OrderState.CREATED)
        emitter.transition(order, OrderState.RELEASED)
        return emitter.toResponse(order)
    }

    // ── Replenishment ─────────────────────────────────────────────────────

    /**
     * Mints a REPLENISH transport order (RELEASED immediately) for the given unit load,
     * linking it to the [com.karyo.tasks.spi.ReplenishmentTaskCommand.fixAssignmentId] so
     * the open-task guard ([hasOpenReplenishment]) can prevent duplicates.
     *
     * Mirrors [createManualMove]: resolves label + source location from the unit load,
     * constructs the order, and transitions CREATED→RELEASED through the single
     * [TransportOrderEmitter.transition] chokepoint (canAdvanceTo guard + CDI event + outbox row).
     *
     * Order-number prefix is "RP".
     */
    @Transactional
    fun createReplenishment(command: com.karyo.tasks.spi.ReplenishmentTaskCommand): TransportOrderResponse {
        // Task 3 review CRITICAL-1 (replenishment sprint): explicit-clientId overload, not
        // findById(unitLoadId)'s ambient-TenantContext one -- this is reachable from
        // ReplenishmentScheduler's @Scheduled multi-tenant loop, which never primes TenantContext.
        // command.clientId is already the correct, known-good tenant for this call (see
        // UnitLoadLookup.findById's two-arg overload KDoc).
        val ulInfo = unitLoadLookup.findById(command.unitLoadId, command.clientId)
            ?: throw TaskException.InvalidReference("UnitLoad", "id=${command.unitLoadId}")
        val order = TransportOrder().apply {
            clientId = command.clientId
            orderNumber = generateOrderNumber("RP", command.clientId)
            transportType = TransportType.REPLENISH
            unitLoadId = command.unitLoadId
            unitLoadLabel = ulInfo.label
            sourceLocationId = ulInfo.locationId
            sourceLocationName = ulInfo.locationName
            destinationLocationId = command.destinationLocationId
            destinationLocationName = command.destinationLocationName
            suggestedLocationId = command.destinationLocationId
            suggestedLocationName = command.destinationLocationName
            fixAssignmentId = command.fixAssignmentId
        }
        confirmVariantService.denormalizeAtCreation(order, command.unitLoadId)
        // R13: a non-null command amount is the scan's computed top-up quantity (Karyo-original
        // fill-to-max — see ReplenishmentTaskCommand.amount's KDoc) and OVERWRITES whatever the
        // denorm above stamped (the source stock's full amount). A null command amount leaves the
        // denorm's whole-UL amount as-is, so [complete]'s default-to-order.amount rule harmlessly
        // resolves back to a full-stock "partial" that ConfirmVariantService.completePartialIfApplicable
        // declines (amount >= stock.amount), falling through to the ordinary whole-UL move.
        command.amount?.let { order.amount = it }
        repository.persist(order)
        emitter.transition(order, OrderState.CREATED)
        emitter.transition(order, OrderState.RELEASED)
        return emitter.toResponse(order)
    }

    /** Returns true when there is already an open (non-finished/non-cancelled) REPLENISH
     *  task for the given [fixAssignmentId] within the tenant [clientId]. */
    fun hasOpenReplenishment(fixAssignmentId: Long, clientId: Long): Boolean =
        repository.findOpenReplenishment(fixAssignmentId, clientId) != null

    /**
     * R12b (replenishment sprint Task 6): mints an area-level (Mode 2) REPLENISH transport
     * order. Mirrors [createReplenishment] closely — REUSES [TransportType.REPLENISH] (no new
     * enum value/`valueOf` blast radius: every existing `TransportType.REPLENISH` filter, e.g.
     * [hasOpenReplenishment]/[com.karyo.tasks.repository.TransportOrderRepository.
     * findOpenAreaReplenishment], keeps working unchanged) and the same "RP" order-number prefix
     * (SC17 shared `transport.orderNumber` sequence, see [generateOrderNumber]'s KDoc). Area vs.
     * fix-face orders are distinguished by which provenance column is set:
     * [TransportOrder.itemDataAreaId] here, [TransportOrder.fixAssignmentId] left `null`
     * (the inverse of [createReplenishment]).
     *
     * Destination is NOT searched here — [command].destinationLocationId/Name are the location
     * [com.karyo.replenishment.service.ReplenishmentService.scanAreas] already chose within the
     * deficient area's cluster set. This method still soft-[LocationFinder.reserve]s it (keyed
     * by this order's own id, same as [ChainContinuationService]'s known-target reservation) so
     * the PUTAWAY [LocationFinder] avoids this location; the area scan's own destination choice
     * (`chooseDestination`) is occupancy- and reservation-blind in v1 (defect filed), so
     * successive area scans can still re-pick the same location — [complete]/
     * [cancel] already call [LocationFinder.releaseReservation] unconditionally for every
     * transport order type, so the release half needs no new code. **TTL caveat** (same one
     * [pause]'s KDoc documents for the putaway path): the reservation expires after
     * `LocationFinderService.RESERVATION_TTL` (10 minutes) and is swept regardless of whether
     * this order is still open — an operator who leaves an area-replenishment task
     * RELEASED/RESERVED past that window loses the destination's soft hold, same
     * bounded-not-indefinite caveat every other [LocationFinder] reservation carries.
     */
    @Transactional
    fun createAreaReplenishment(command: com.karyo.tasks.spi.AreaReplenishmentTaskCommand): TransportOrderResponse {
        val ulInfo = unitLoadLookup.findById(command.unitLoadId, command.clientId)
            ?: throw TaskException.InvalidReference("UnitLoad", "id=${command.unitLoadId}")
        val order = TransportOrder().apply {
            clientId = command.clientId
            orderNumber = generateOrderNumber("RP", command.clientId)
            transportType = TransportType.REPLENISH
            unitLoadId = command.unitLoadId
            unitLoadLabel = ulInfo.label
            sourceLocationId = ulInfo.locationId
            sourceLocationName = ulInfo.locationName
            destinationLocationId = command.destinationLocationId
            destinationLocationName = command.destinationLocationName
            suggestedLocationId = command.destinationLocationId
            suggestedLocationName = command.destinationLocationName
            itemDataAreaId = command.itemDataAreaId
        }
        confirmVariantService.denormalizeAtCreation(order, command.unitLoadId)
        command.amount?.let { order.amount = it }
        repository.persist(order)
        locationFinder.reserve(command.destinationLocationId, order.id!!)
        emitter.transition(order, OrderState.CREATED)
        emitter.transition(order, OrderState.RELEASED)
        return emitter.toResponse(order)
    }

    // ── Cross-docking ────────────────────────────────────────────────────

    /**
     * Cross-docking sprint: mints a CROSS_DOCK transport order (RELEASED immediately) from the
     * receiving dock to a staging location the (paid) cross-docking engine already chose.
     * Mirrors [createReplenishment]'s body (order-number minting, state RELEASED, journal/
     * outbox side effects via [TransportOrderEmitter.transition]), with `transportType =
     * CROSS_DOCK` and the explicit staging destination carried straight through from [command]
     * rather than searched. Explicit-clientId [UnitLoadLookup] overload, same doctrine as
     * [createReplenishment]: this port method must stay scheduler-safe (no ambient
     * `TenantContext`) since sibling [createPutawayFromStaging] is reachable from a
     * `@Scheduled` sweep, and both share this class's call graph.
     *
     * Order-number prefix is "XD".
     */
    @Transactional
    fun createCrossDock(command: com.karyo.tasks.spi.CrossDockTaskCommand): TransportOrderResponse {
        val ulInfo = unitLoadLookup.findById(command.unitLoadId, command.clientId)
            ?: throw TaskException.InvalidReference("UnitLoad", "id=${command.unitLoadId}")
        val order = TransportOrder().apply {
            clientId = command.clientId
            orderNumber = generateOrderNumber("XD", command.clientId)
            transportType = TransportType.CROSS_DOCK
            unitLoadId = command.unitLoadId
            unitLoadLabel = ulInfo.label
            sourceLocationId = ulInfo.locationId
            sourceLocationName = ulInfo.locationName
            destinationLocationId = command.destinationLocationId
            destinationLocationName = command.destinationLocationName
            suggestedLocationId = command.destinationLocationId
            suggestedLocationName = command.destinationLocationName
            goodsReceiptLineId = command.goodsReceiptLineId
            note = command.note
        }
        confirmVariantService.denormalizeAtCreation(order, command.unitLoadId)
        repository.persist(order)
        emitter.transition(order, OrderState.CREATED)
        emitter.transition(order, OrderState.RELEASED)
        return emitter.toResponse(order)
    }

    /**
     * Cross-docking sprint: expiry/cancel fallback. Mints a normal PUTAWAY transport order for
     * a unit load left sitting on a cross-dock staging location. Mirrors [createPutawayTask]'s
     * location-finder resolution (Found → RELEASED with a suggestion; NoLocation → CREATED with
     * a note, work never lost) rather than [createReplenishment]'s known-destination shape,
     * since, unlike the cross-dock mint above, there is no chosen destination here at all.
     * Explicit-clientId [UnitLoadLookup] overload: this is the method the doctrine comment on
     * [createCrossDock] refers to, the one actually reachable from a `@Scheduled`
     * multi-tenant sweep (the cross-dock expiry sweep), which never primes `TenantContext`.
     *
     * Order-number prefix is "TO", same prefix as every other PUTAWAY task ([createPutawayTask]).
     */
    @Transactional
    fun createPutawayFromStaging(command: com.karyo.tasks.spi.PutawayFromStagingCommand): TransportOrderResponse {
        val ulInfo = unitLoadLookup.findById(command.unitLoadId, command.clientId)
            ?: throw TaskException.InvalidReference("UnitLoad", "id=${command.unitLoadId}")
        val order = TransportOrder().apply {
            clientId = command.clientId
            orderNumber = generateOrderNumber("TO", command.clientId)
            transportType = TransportType.PUTAWAY
            unitLoadId = command.unitLoadId
            unitLoadLabel = ulInfo.label
            sourceLocationId = ulInfo.locationId
            sourceLocationName = ulInfo.locationName
            note = command.note
        }
        confirmVariantService.denormalizeAtCreation(order, command.unitLoadId)
        repository.persist(order)
        emitter.transition(order, OrderState.CREATED)

        when (val result = findLocation(ulInfo, command.clientId, order.id!!, order.storageStrategyId)) {
            is LocationFinderResult.Found -> {
                order.suggestedLocationId = result.locationId
                order.suggestedLocationName = result.locationName
                emitter.transition(order, OrderState.RELEASED)
            }
            is LocationFinderResult.NoLocation -> {
                order.note = "No location suggested: ${result.reason}"
                log.infof("Putaway-from-staging task %s created without suggestion: %s", order.orderNumber, result.reason)
            }
        }
        return emitter.toResponse(order)
    }

    // ── Operator lifecycle ────────────────────────────────────────────────

    @Transactional
    fun assign(id: Long, operatorId: String, clientId: Long): TransportOrderResponse {
        val order = findEntityById(id, clientId)
        if (order.state != OrderState.RELEASED.code) {
            throw TaskException.InvalidTransition(id, order.state, OrderState.RESERVED.code)
        }
        if (order.pausedAt != null) {
            throw TaskException.TransportPaused(id)
        }
        order.operatorId = operatorId
        emitter.transition(order, OrderState.RESERVED)
        return emitter.toResponse(order)
    }

    /**
     * PT18 pause — mirrors [com.karyo.orders.service.GoodsReceiptService.pause]'s Option B7-P1:
     * stamps the ORTHOGONAL [TransportOrder.pausedAt]; `state` NEVER moves and
     * [OrderState.canAdvanceTo] is untouched. Pausable window is CREATED/RELEASED/RESERVED/
     * STARTED — every pre-terminal state a task can sit in — wider than GoodsReceipt's
     * CREATED/STARTED because the queue-and-claim lifecycle here has two intermediate parking
     * points (RELEASED = queued, RESERVED = claimed-but-not-started) that are equally valid
     * places for an operator to step away. [operatorId] and the layout destination
     * [TransportOrder.suggestedLocationId]/reservation are BOTH KEPT while paused: resume must
     * find its slot again, and the capacity cost of holding that reservation open is the
     * pauser's explicit choice, not something this method second-guesses. **Caveat:** "kept" is
     * bounded by the reservation's own TTL, not indefinite — [LocationFinder]'s soft reservation
     * expires after `LocationFinderService.RESERVATION_TTL` (10 minutes) and is swept regardless
     * of pause state, so a pause that outlasts the TTL loses the slot to the next putaway anyway;
     * resume then finds a stale/gone suggestion, same as any other TTL expiry. NO event fires and
     * NO outbox row is written on pause/resume (deliberate observability gap, matching the GR
     * precedent), transport orders otherwise stream every [TransportOrderEmitter.transition] to
     * the outbox, but
     * pause is orthogonal to state by design and was never meant to appear in that stream.
     *
     * A second pause is a 409, matching the claim's fail-loud non-idempotence: a pause of an
     * already-paused task means the caller's view is stale, and silently succeeding would hide
     * that. Pausing a FINISHED/CANCELED task is a 409 (outside the pausable window).
     */
    @Transactional
    fun pause(id: Long, clientId: Long): TransportOrderResponse {
        val order = findEntityById(id, clientId)
        if (order.state !in PAUSABLE_STATES) {
            throw TaskException.TransportPauseConflict(id, "task is closed (state ${order.state})")
        }
        if (order.pausedAt != null) {
            throw TaskException.TransportPauseConflict(id, "already paused since ${order.pausedAt}")
        }
        order.pausedAt = Instant.now()
        return emitter.toResponse(order)
    }

    /** PT18: clears the pause stamp (409 if not paused — same fail-loud symmetry as [pause]). */
    @Transactional
    fun resume(id: Long, clientId: Long): TransportOrderResponse {
        val order = findEntityById(id, clientId)
        if (order.pausedAt == null) {
            throw TaskException.TransportPauseConflict(id, "task is not paused")
        }
        order.pausedAt = null
        return emitter.toResponse(order)
    }

    /**
     * Releases a task back to the pool (RESERVED→RELEASED, clears operatorId).
     *
     * [TransportOrderEmitter.transition] is forward-only (target.code > current.code) so
     * RESERVED(400)→RELEASED(100) would be rejected. We set state directly and replicate the
     * event+outbox that [TransportOrderEmitter.transition] fires, same payload, same consumers,
     * bypassing the forward-only guard intentionally.
     *
     * Clearing [TransportOrder.operatorId] here is intentional even when the task is currently
     * paused: a release is an explicit act by (or on behalf of) the claiming operator to give up
     * the task, same as [cancel] — pause only protects against the SYSTEM silently pulling a
     * parked task out from under its operator, not against the operator's own deliberate release.
     */
    @Transactional
    fun release(id: Long, operatorId: String, clientId: Long, asManager: Boolean = false): TransportOrderResponse {
        val order = findEntityById(id, clientId)
        if (order.state != OrderState.RESERVED.code) {
            throw TaskException.InvalidTransition(id, order.state, OrderState.RELEASED.code)
        }
        if (order.operatorId != operatorId && !asManager) {
            throw TaskException.ValidationFailed("Order is claimed by a different operator")
        }
        order.operatorId = null
        val old = order.state
        order.state = OrderState.RELEASED.code
        emitter.emitStateChange(order, old, OrderState.RELEASED.code)
        return emitter.toResponse(order)
    }

    /**
     * Starts a task (RESERVED→STARTED). For PUTAWAY tasks with no current suggestion (or
     * a since-gone one), re-runs the location finder so the operator gets a fresh
     * destination if stock moved around while the task waited in the queue.
     */
    @Transactional
    fun start(id: Long, clientId: Long): TransportOrderResponse {
        val order = findEntityById(id, clientId)
        if (order.state != OrderState.RESERVED.code) {
            throw TaskException.InvalidTransition(id, order.state, OrderState.STARTED.code)
        }
        if (order.pausedAt != null) {
            throw TaskException.TransportPaused(id)
        }
        if (order.transportType == TransportType.PUTAWAY && order.suggestedLocationId == null) {
            reResolveSuggestion(order)
        }
        emitter.transition(order, OrderState.STARTED)
        order.started = Instant.now()
        return emitter.toResponse(order)
    }

    /**
     * Completes a task: the operator either accepts the suggestion or overrides the
     * destination location, OR (PT16) confirm-merges onto an EXISTING unit load via
     * [CompleteTransportOrderRequest.destinationUnitLoadId], OR (PT17) requests a PARTIAL
     * confirm via [CompleteTransportOrderRequest.amount] — all three of those branches live on
     * [ConfirmVariantService] (see its KDoc for why) and return immediately, skipping everything
     * below. The two destination shapes are mutually exclusive (400 if both are supplied). The
     * ordinary whole-UL path performs the inventory move (fires UnitLoadTransferred → layout
     * allocation), records the destination and [TransportOrder.confirmedAmount] (Task 3,
     * defect-burndown-4, row 6: the LIVE single-live-stock amount on the order's own unit load at
     * confirm time -- [ConfirmVariantService.liveSingleStockAmount] -- not the mint-time
     * [TransportOrder.amount] denorm; falls back to that denorm only when the unit load carries
     * zero or multiple live stocks, e.g. it was already multi-stock at creation), checks for a
     * PT15 chain continuation (before the reservation is released -- see [ChainContinuationService]'s
     * KDoc), releases the layout reservation, →FINISHED, and fires the completion event.
     *
     * **R13 default-amount rule:** a REPLENISH order minted with a top-up amount
     * (createReplenishment stamped [TransportOrder.amount] from
     * [com.karyo.tasks.spi.ReplenishmentTaskCommand.amount]) completes with that quantity
     * WITHOUT the operator re-specifying it — but ONLY when the request supplies neither its own
     * `amount` NOR an explicit `destinationLocationId`. This default is deliberately the
     * mint-time [TransportOrder.amount] snapshot, not a live re-read (Task 3, defect-burndown-4,
     * row 31 review finding: a live re-read here would discard an R13-computed top-up cap in
     * favor of the whole live stock, since a genuine cap is indistinguishable from a stale
     * whole-UL denorm at this call site -- see the inline comment above this rule's `if` for the
     * full reasoning and the IMPORTANT-1 regression pin it protects). That second guard is
     * deliberate: an
     * explicit `destinationLocationId` is the "drop at a specific spot" gesture (e.g. a
     * transfer-staging location) that the pre-R13 whole-UL path + PT15 chain continuation
     * already handles correctly — defaulting an amount there would silently turn a whole-UL
     * relocation into a partial, and partials never chain
     * ([ConfirmVariantService.completePartialIfApplicable]'s own KDoc), stranding the moved
     * quantity at staging with the order already FINISHED (and the next scan minting a fresh,
     * compounding order). The default is computed ABOVE the `destinationUnitLoadId` dispatch
     * below, not after, so the merge gesture (operator scans the destination face unit load
     * directly, `amount` omitted — the natural gesture on an aggregating face) also receives the
     * top-up quantity instead of merging the WHOLE source stock past the face's `maxAmount`
     * ([ConfirmVariantService.completeAsMerge] already supports a partial `amount`; this only
     * supplies the default). Three gestures after this rule: (a) no fields at all → partial
     * top-up to the suggested face; (b) `destinationUnitLoadId` only → merge honors the top-up
     * amount (the 400 guard just above already forces `destinationLocationId` null on this
     * gesture); (c) an explicit `destinationLocationId` (e.g. a staging drop) → no default,
     * whole-UL + chain, unchanged from pre-R13 behavior.
     */
    @Transactional
    fun complete(id: Long, request: CompleteTransportOrderRequest, clientId: Long): TransportOrderResponse {
        val order = findEntityById(id, clientId)
        requireStartedNotPaused(order, id)

        if (request.destinationUnitLoadId != null && request.destinationLocationId != null) {
            throw TaskException.ValidationFailed(
                "destinationUnitLoadId and destinationLocationId are mutually exclusive",
            )
        }

        // See this function's KDoc ("R13 default-amount rule") for the full rationale. Task 3
        // (defect-burndown-4, row 31) review finding: this default is DELIBERATELY still the
        // mint-time order.amount snapshot, NOT confirmVariantService.liveSingleStockAmount(order)
        // -- switching it to the live amount here would move the WHOLE source stock instead of an
        // R13-computed top-up cap (order.amount < the live stock's amount BY DESIGN whenever R13
        // capped it), reintroducing the exact bug IMPORTANT-1's regression pin
        // (ReplenishmentTopUpFlowTest "R13(a2)") exists to prevent -- a genuine cap and a stale
        // whole-UL denorm are indistinguishable from order.amount alone (TransportOrder carries no
        // "was this capped by R13" flag), so this call site cannot safely prefer live over
        // snapshot. The live-amount fix IS applied at the whole-UL confirmedAmount step below,
        // which is unambiguous (see that line's comment) and covers the row 6 staging-drop case
        // and row 31's shrink direction; row 31's growth direction reaching THIS default-gesture
        // path specifically remains an accepted residual gap (recoverable via cancel + rescan,
        // same as the pre-existing multi-SKU-drift 409 gap).
        val effectiveRequest = if (
            order.transportType == TransportType.REPLENISH &&
            request.amount == null &&
            request.destinationLocationId == null
        ) {
            request.copy(amount = order.amount)
        } else {
            request
        }

        effectiveRequest.destinationUnitLoadId?.let {
            return confirmVariantService.completeAsMerge(order, it, effectiveRequest.amount)
        }
        confirmVariantService.completePartialIfApplicable(order, effectiveRequest)?.let { return it }

        val destId = request.destinationLocationId ?: order.suggestedLocationId
            ?: throw TaskException.NoDestination(id)
        val destName = request.destinationLocationName
            ?: (if (request.destinationLocationId == null) order.suggestedLocationName else null)
            ?: destId.toString()

        unitLoadMover.move(order.unitLoadId, destId, destName)
        order.destinationLocationId = destId
        order.destinationLocationName = destName
        // Task 3 (defect-burndown-4, row 6): confirmedAmount records what actually moved -- the
        // LIVE single-live-stock amount on the order's own unit load at confirm time, not the
        // mint-time order.amount snapshot (which can legitimately differ from the whole-UL move's
        // actual quantity, e.g. an R13 top-up order completed via an explicit destinationLocationId
        // staging-drop gesture that bypasses the R13 default-amount rule). Falls back to
        // order.amount only for a multi-SKU unit load (see liveSingleStockAmount's KDoc).
        order.confirmedAmount = confirmVariantService.liveSingleStockAmount(order) ?: order.amount

        chainContinuation.maybeChain(order, destId, destName)

        locationFinder.releaseReservation(order.id!!)
        emitter.transition(order, OrderState.FINISHED)
        order.finished = Instant.now()
        emitter.fireCompleted(order, destId, destName)
        return emitter.toResponse(order)
    }

    /**
     * Deliberately NOT guarded against [TransportOrder.pausedAt] (unlike [assign]/[start]/
     * [complete]) — a paused task stays cancelable. Pause is an operator's "step away", not a
     * lock against the task being pulled from the queue entirely; forcing a resume before
     * cancel would be needless friction for work nobody intends to resume. Matches
     * [com.karyo.orders.service.GoodsReceiptService.cancel], which is likewise unguarded.
     */
    @Transactional
    fun cancel(id: Long, clientId: Long): TransportOrderResponse {
        val order = findEntityById(id, clientId)
        if (order.state >= OrderState.STARTED.code) {
            throw TaskException.NotCancelable(id, "task is already started or closed (state ${order.state})")
        }
        if (!OrderState.fromCode(order.state).canAdvanceTo(OrderState.CANCELED)) {
            throw TaskException.InvalidTransition(id, order.state, OrderState.CANCELED.code)
        }
        performCancel(order)
        return emitter.toResponse(order)
    }

    /**
     * Cross-docking sprint (final fix wave): non-throwing counterpart to [cancel], for callers
     * that must not blow up their own transaction over a transport order that may already be
     * STARTED or terminal -- the cross-dock expiry handler's AUTO_PUTAWAY action and
     * `CrossDockLifecycleService.cancel`, both minting their own fallback PUTAWAY transport right
     * after this call and neither able to afford propagating an exception over a stale reference.
     * Applies the SAME guard [cancel] does (pre-STARTED and forward-advanceable to CANCELED) but
     * returns `false` and logs a warning instead of throwing when the guard fails, or when
     * [id] does not resolve for [clientId] at all. Tenant-scoped, like every other explicit-
     * clientId port method in this class.
     */
    @Transactional
    fun cancelIfOpen(id: Long, clientId: Long): Boolean {
        val order = repository.findByIdAndClient(id, clientId)
        if (order == null) {
            log.warnf("cancelIfOpen: transport order %d not found for client %d; leaving alone", id, clientId)
            return false
        }
        if (order.state >= OrderState.STARTED.code || !OrderState.fromCode(order.state).canAdvanceTo(OrderState.CANCELED)) {
            log.warnf(
                "cancelIfOpen: transport order %d (state %d) is already started or terminal; leaving alone",
                id, order.state,
            )
            return false
        }
        performCancel(order)
        return true
    }

    /** Shared cancel body for [cancel] and [cancelIfOpen] -- both callers have already applied
     *  their own guard (throwing vs. non-throwing) before reaching here. */
    private fun performCancel(order: TransportOrder) {
        locationFinder.releaseReservation(order.id!!)
        emitter.transition(order, OrderState.CANCELED)
        order.finished = Instant.now()
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Validates the task is STARTED and not paused. Split from [complete] so no single
     * function's throw count trips detekt's `ThrowsCount` (max 2) — mirrors
     * `GoodsReceiptService.requireReversibleReceipt`.
     */
    private fun requireStartedNotPaused(order: TransportOrder, id: Long) {
        if (order.state != OrderState.STARTED.code) {
            throw TaskException.InvalidTransition(id, order.state, OrderState.FINISHED.code)
        }
        if (order.pausedAt != null) {
            throw TaskException.TransportPaused(id)
        }
    }

    /**
     * [storageStrategyId] (inbound-completion row 7 residual): the receipt line's validated
     * per-line putaway strategy override, if any -- rung 1 of `LocationFinderService.resolveStrategy`'s
     * resolution chain. `null` here degrades to the finder's own rung-2 (product default) /
     * rung-3 (system default) resolution, unchanged.
     */
    private fun findLocation(
        ulInfo: UnitLoadInfo,
        clientId: Long,
        transportOrderId: Long,
        storageStrategyId: Long?,
    ): LocationFinderResult =
        locationFinder.findPutawayLocation(
            LocationFinderRequest(
                unitLoadId = ulInfo.id,
                unitLoadTypeId = ulInfo.unitLoadTypeId,
                weight = ulInfo.weight,
                clientId = clientId,
                reservationKey = transportOrderId,
                storageStrategyId = storageStrategyId,
                // Task 3 (locations-layout sprint): threads the incoming stock's product +
                // FIFO date through to the finder's StorageArea logic (useAreaStrategyDate /
                // useItemDataArea). Both are null when the unit load is empty or mixed-SKU
                // (see UnitLoadInfo's KDoc) -- the finder degrades to area-restriction-only.
                itemDataId = ulInfo.itemDataId,
                strategyDate = ulInfo.strategyDate,
            ),
        )

    /**
     * Re-run the finder on start; on success update the suggestion, on failure leave a note.
     * Passes [TransportOrder.storageStrategyId] -- the PERSISTED per-line override (if any) --
     * so a task that got no suggestion at auto-putaway time still honors the override when the
     * operator starts it later (the persisted-id trap: an unpersisted override would silently
     * vanish on this exact path).
     */
    private fun reResolveSuggestion(order: TransportOrder) {
        val ulInfo = unitLoadLookup.findById(order.unitLoadId) ?: return
        when (val result = findLocation(ulInfo, order.clientId, order.id!!, order.storageStrategyId)) {
            is LocationFinderResult.Found -> {
                order.suggestedLocationId = result.locationId
                order.suggestedLocationName = result.locationName
                order.note = null
            }
            is LocationFinderResult.NoLocation ->
                order.note = "No location suggested on start: ${result.reason}"
        }
    }

    /**
     * `transport_orders.order_number` is VARCHAR(100). SC17: shares the single
     * `transport.orderNumber` sequence name across the PUTAWAY/MOVE/REPLENISH prefixes
     * ("TO"/"MV"/"RP") — deliberate, per this method's own multi-prefix shape ever since
     * v1.2/PT15/replenishment all funneled through it.
     */
    private fun generateOrderNumber(prefix: String, clientId: Long): String =
        sequenceNumberService.next("transport.orderNumber", prefix, clientId, MAX_NUMBER_LENGTH) { candidate ->
            repository.findByOrderNumber(candidate, clientId) == null
        }

    private fun findEntityById(id: Long, clientId: Long): TransportOrder =
        repository.findByIdAndClient(id, clientId)
            ?: throw TaskException.NotFound("TransportOrder", "id=$id")

    companion object {
        val SORTABLE_FIELDS = setOf("id", "orderNumber", "state", "prio", "transportType", "created")
        private const val MAX_NUMBER_LENGTH = 100

        /** PT18 pause window: every pre-terminal state (see [pause]'s KDoc for rationale). */
        private val PAUSABLE_STATES = setOf(
            OrderState.CREATED.code, OrderState.RELEASED.code, OrderState.RESERVED.code, OrderState.STARTED.code,
        )
    }
}
