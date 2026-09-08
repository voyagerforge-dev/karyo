package com.karyo.stocktaking.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.paginatedResponse
import com.karyo.events.outbox.OutboxService
import com.karyo.inventory.api.spi.CountableStock
import com.karyo.inventory.api.spi.StockCountingPort
import com.karyo.layout.spi.LocationLockPort
import com.karyo.security.TenantContext
import com.karyo.sequence.SequenceNumberService
import com.karyo.work.exception.WorkClaimConflictException
import com.karyo.stocktaking.domain.event.CountOrderReleasedEvent
import com.karyo.stocktaking.domain.model.CountCampaign
import com.karyo.stocktaking.domain.model.CountLine
import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.domain.model.CountSession
import com.karyo.stocktaking.dto.CountEntryLine
import com.karyo.stocktaking.dto.CountEntryView
import com.karyo.stocktaking.dto.CountInput
import com.karyo.stocktaking.dto.CountLineView
import com.karyo.stocktaking.dto.CountOrderView
import com.karyo.stocktaking.dto.CountSessionSummaryView
import com.karyo.stocktaking.dto.CountSessionView
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.exception.StocktakingException
import com.karyo.stocktaking.repository.CountCampaignRepository
import com.karyo.stocktaking.repository.CountLineRepository
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.repository.CountSessionRepository
import com.karyo.stocktaking.spi.CountScopeRequest
import com.karyo.stocktaking.vo.CountCampaignState
import com.karyo.stocktaking.vo.CountLineState
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import com.karyo.stocktaking.vo.CountType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Instant

/**
 * Core orchestrator for the count lifecycle (cycle counts and full inventories alike).
 *
 * **startCount:** resolves a scope to locations, then per location: enumerates stock via
 * [StockCountingPort], creates a [CountOrder] with the location name from
 * [LocationLockPort.locationName], locks both the location ([LocationLockPort.lockForCount])
 * and all stock units ([StockCountingPort.lockForCount]), and persists one [CountLine] per
 * stock unit as the planned snapshot.
 *
 * **Two count types (St5).** [CountType.CYCLE] (the default) counts a caller-selected scope and
 * refuses the WHOLE start if any location in it holds reserved stock, or is already locked — the
 * operator is expected to have picked a countable set. [CountType.END_OF_PERIOD] counts every
 * location the tenant owns, empty locations included (zero-line orders, closed out through
 * [locationEmpty]) — a shared (`client_id = 0`) location is never in scope, same as every other
 * tenant-facing layout read; there, a location that cannot be counted — for the very same two reasons —
 * is SKIPPED and reported in [CountSessionView.skippedLocations] instead, because one
 * reservation must not be able to veto an annual inventory. The rule is identical either way
 * (*a location that cannot be counted is not counted*); only the remedy differs.
 *
 * **Freeze semantics.** There is no separate "freeze the warehouse" switch: the freeze IS the
 * per-location [LockType.STOCKTAKING][com.karyo.layout.vo.LockType] lock (plus the per-stock-unit
 * lock) that every generated order takes at start and releases at [finishOrder]/[cancelOrder] —
 * the same mechanism a single-location cycle count has always used, applied warehouse-wide.
 * A full inventory is therefore frozen for exactly as long as its orders are open, location by
 * location, and a skipped location is never frozen at all.
 *
 * That guarantee is **enforced, not merely asserted**: because
 * [com.karyo.layout.service.LocationService.lockLocation] overwrites a lock unconditionally, a
 * second count session generated for an already-frozen location would have released the freeze
 * out from under the first session the moment it finished — a silent thaw mid-inventory, plus a
 * double count. `startCount` therefore reads the pre-existing locks BEFORE it takes any of its
 * own ([requireNoLockedLocation]) and a CYCLE start refuses the whole thing with 409
 * [StocktakingException.LocationLocked]; END_OF_PERIOD skips. Either way no session can begin
 * counting a location another session has frozen.
 *
 * This is behavioral parity with legacy myWMS's end-of-period concept via an independent
 * implementation, and deliberately exceeds it: legacy's END_OF_PERIOD was vestigial — no freeze,
 * no close-out. Karyo's is a real, lockable, closable session.
 *
 * **Scale ceiling (honest).** All of this is one `@Transactional` call: N location reads, N
 * order inserts and one line insert per stock unit, in a single transaction that also holds the
 * write locks. At the scale this targets (a demo warehouse is ~40 locations; a real single-site
 * WMS is hundreds) that is fine. It is NOT batched or chunked, so a warehouse in the tens of
 * thousands of locations would need that work — deliberately not built ahead of a caller
 * (YAGNI), not overlooked.
 *
 * An empty scope, or a CYCLE reserved-stock condition, rolls back the whole session creation.
 */
@ApplicationScoped
class StocktakingService(
    private val scopeResolver: CountScopeStrategyResolver,
    private val stockCountingPort: StockCountingPort,
    private val locationLockPort: LocationLockPort,
    private val sessionRepository: CountSessionRepository,
    private val orderRepository: CountOrderRepository,
    private val countLineRepository: CountLineRepository,
    private val campaignRepository: CountCampaignRepository,
    private val tenantContext: TenantContext,
    private val sequenceNumberService: SequenceNumberService,
    private val outboxService: OutboxService,
) {
    private val log = Logger.getLogger(StocktakingService::class.java)

    /**
     * Starts a count session. See the class KDoc for the CYCLE-vs-END_OF_PERIOD split, the
     * freeze semantics and the single-transaction scale ceiling.
     *
     * The scope strategy is ALWAYS resolved by name — `"EXPLICIT"` (or the caller's
     * [StartCountRequest.scopeStrategy]) for a cycle count, forced to `"FULL_WAREHOUSE"` for an
     * end-of-period count. Never unnamed: [FullWarehouseScope] outranks [ExplicitLocationScope]
     * on priority, so an unnamed `resolve()` would silently escalate every plain cycle count
     * into a warehouse-wide freeze (see [FullWarehouseScope]'s priority-trap note).
     *
     * **Generation-time walking order (St6).** Whatever the scope strategy resolves is
     * immediately re-sorted by [walkingOrder] — `orderIndex NULLS LAST, name`, the same key
     * [FullWarehouseScope] already returns its ids in (so this sort is a no-op there) — before
     * a single [CountOrder] is generated, so their `created` timestamps land in walking order.
     * [CountOrderRepository]'s claimable query itself carries no `ORDER BY` — the ordering
     * guarantee comes from [com.karyo.work.service.StrictPriorityDispatchStrategy] (today's
     * default dispatch strategy), whose `createdAt ASC` tiebreak re-sorts the merged pool
     * in-memory after every `WorkProvider.listOpen` call. Since every generated `CountOrder`
     * carries the same [com.karyo.work.dto.WorkItem.priority] (`CountWorkProvider`'s
     * `DEFAULT_COUNT_PRIORITY`), that tiebreak is the whole ordering for a COUNT-only pool — so
     * today's default dispatch (no `TRAVEL_PATH` config needed) already hands an operator counts
     * in walking order for a single [startCount] batch. Legacy myWMS ordered its next-location
     * suggestion alphabetically by name; this is a Karyo-native spatial ordering on the existing
     * seam (provenance: karyo-invented).
     *
     * `created ASC` only preserves walking order WITHIN one [startCount] call's batch, though —
     * the dispatch pool an operator actually pulls from merges every OPEN order across every
     * session/`startCount` call for the tenant, and two overlapping sessions' batches can
     * interleave by `created` in a way that no longer walks the floor. See
     * [com.karyo.work.service.TravelPathDispatchStrategy] (opt-in via
     * `karyo.work.dispatch-strategy=TRAVEL_PATH`) for the dispatch-time seam that re-derives
     * spatial order over the WHOLE merged pool instead of relying on insertion order.
     */
    @Transactional
    fun startCount(request: StartCountRequest, clientId: Long): CountSessionSummaryView {
        val countType = countTypeOf(request)
        val campaign = request.campaignId?.let { validateCampaignForStart(it, countType, clientId) }
        val strategyName = if (countType == CountType.END_OF_PERIOD) {
            FullWarehouseScope.NAME
        } else {
            request.scopeStrategy ?: ExplicitLocationScope.NAME
        }

        val resolvedLocationIds = scopeResolver.resolve(strategyName).resolveLocations(
            CountScopeRequest(request.locationIds, request.areaId, request.locationNamePattern),
            clientId,
        )
        if (resolvedLocationIds.isEmpty()) {
            throw StocktakingException.InvalidState("no locations in scope")
        }
        val locationIds = walkingOrder(resolvedLocationIds, clientId)

        // Snapshot the pre-existing location locks ONCE, BEFORE this session takes any lock of
        // its own -- otherwise the locations it freezes would read back as "already locked" on
        // later iterations. Read before the session row is even created so a CYCLE refusal
        // aborts the transaction with nothing written.
        //
        // A locked location is never counted, by either type -- they differ only in the remedy:
        // CYCLE refuses the whole start (409, below), END_OF_PERIOD skips and reports.
        val preLocked = locationLockPort.lockedLocationIds(locationIds, clientId)
        // Same snapshot-before-locking rationale as preLocked (row 16): read ONCE, before this
        // session generates anything, so a reservation that lands mid-loop can't flip a
        // location's countability out from under an in-progress END_OF_PERIOD start. Unused by
        // CYCLE starts -- only isCountable (END_OF_PERIOD-only) reads it.
        val preReserved = locationLockPort.reservedLocationIds(locationIds)
        if (countType != CountType.END_OF_PERIOD) {
            requireNoLockedLocation(locationIds, preLocked, clientId, locationLockPort)
        }

        val session = CountSession().apply {
            this.clientId = clientId
            sessionNumber = sequenceNumberService.next("count.sessionNumber", "CS-$clientId", clientId, MAX_NUMBER_LENGTH) { candidate ->
                sessionRepository.find("sessionNumber = ?1 and clientId = ?2", candidate, clientId).firstResult() == null
            }
            type = countType.name
            state = CountSessionState.OPEN.code
            started = Instant.now()
            campaignId = campaign?.id
        }
        sessionRepository.persist(session)

        val orders = mutableListOf<CountOrder>()
        val skipped = mutableListOf<String>()
        val skippedIds = mutableListOf<Long>()
        for (locId in locationIds) {
            val locName = locationLockPort.locationName(locId, clientId) ?: "LOC-$locId"
            val stock = stockCountingPort.findStockAtLocation(locId)
            if (countType == CountType.END_OF_PERIOD && !isCountable(locId, stock, preLocked, preReserved)) {
                skipped.add(locName)
                skippedIds.add(locId)
                log.debugf("startCount: skipping location %d (%s) -- reserved or already locked", locId, locName)
                continue
            }
            val order = generateOrderForLocation(session.id!!, locId, locName, request.blindCount, clientId, stock)
            orders.add(order)
            log.debugf(
                "startCount: session %s → order %s for location %d (%s)",
                session.sessionNumber, order.orderNumber, locId, locName,
            )
        }
        // Degenerate full inventory: every location in scope was skipped. The session is a real
        // (empty) audit record of that attempt, so it is created -- but it must not linger OPEN
        // forever with nothing that could ever close it.
        if (orders.isEmpty()) maybeCloseSession(session.id!!)

        // Built in memory from the orders just generated -- no re-query. Every order here is
        // freshly GENERATED, so countedCount/finishedCount are computed generically rather than
        // hardcoded 0, staying correct if that ever stops being true.
        return toSummaryView(
            session,
            orderCount = orders.size,
            countedCount = orders.count { it.state == CountOrderState.COUNTED.code },
            finishedCount = orders.count { it.state == CountOrderState.FINISHED.code },
            skipped = skipped,
            skippedIds = skippedIds,
        )
    }

    /**
     * Creates and persists a [CountOrder] for [locationId] with planned snapshot [CountLine]s,
     * then locks both the location and the stock. Rejects (409) if any unit is reserved.
     * Used by both [startCount] (per location in the scope) and [recount] (re-generate after
     * cancel).
     *
     * [stock] defaults to a fresh [StockCountingPort.findStockAtLocation] read; [startCount]
     * passes the list it already read for its END_OF_PERIOD countability check so a full
     * inventory does not read every location's stock twice. The reserved-stock guard stays HERE
     * (rather than moving to the callers) so it can never be skipped by accident: the
     * END_OF_PERIOD path simply never reaches it, having filtered those locations out first.
     */
    private fun generateOrderForLocation(
        sessionId: Long,
        locationId: Long,
        locationName: String,
        blindCount: Boolean,
        clientId: Long,
        stock: List<CountableStock> = stockCountingPort.findStockAtLocation(locationId),
    ): CountOrder {
        if (stock.any { it.reservedAmount.signum() > 0 }) {
            throw StocktakingException.ReservedStock(locationId)
        }

        val order = CountOrder().apply {
            this.clientId = clientId
            this.sessionId = sessionId
            orderNumber = sequenceNumberService.next("count.orderNumber", "CO-$clientId", clientId, MAX_NUMBER_LENGTH) { candidate ->
                orderRepository.find("orderNumber = ?1 and clientId = ?2", candidate, clientId).firstResult() == null
            }
            this.locationId = locationId
            this.locationName = locationName
            state = CountOrderState.GENERATED.code
            this.blindCount = blindCount
        }
        orderRepository.persist(order)

        locationLockPort.lockForCount(locationId, clientId)
        stockCountingPort.lockForCount(stock.map { it.stockUnitId })

        stock.forEach { cs ->
            CountLine().apply {
                this.clientId = clientId
                countOrderId = order.id!!
                stockUnitId = cs.stockUnitId
                itemDataId = cs.itemDataId
                itemDataNumber = cs.itemDataNumber
                lotNumber = cs.lotNumber
                serialNumber = cs.serialNumber
                plannedAmount = cs.amount
                state = CountLineState.PLANNED.code
                unitLoadId = cs.unitLoadId
                unitLoadLabel = cs.unitLoadLabel
            }.also { countLineRepository.persist(it) }
        }

        return order
    }

    // ── Campaign validation (St1) ────────────────────────────────────────
    //
    // CRUD + close + rollup for CountCampaign live in the sibling CountCampaignService
    // (kept out of this class to stay under detekt's TooManyFunctions ceiling) — this class
    // only owns the one check startCount needs: does this campaignId accept a plain start.
    // Its state/type half is the top-level `requireCampaignAcceptsStart` (pure, no service state);
    // only the repository lookup below is a member.

    /**
     * Validates [campaignId] for [StartCountRequest.campaignId]: must exist for [clientId]
     * (404), must be OPEN (409), and must be a campaign of the SAME [countType] as the session
     * being started (409) -- a cycle count cannot be filed under an annual-inventory campaign,
     * nor the reverse. St5 generalized this from the original CYCLE-only check.
     */
    private fun validateCampaignForStart(campaignId: Long, countType: CountType, clientId: Long): CountCampaign {
        val campaign = campaignRepository.findByIdAndClient(campaignId, clientId)
            ?: throw StocktakingException.NotFound("CountCampaign", campaignId)
        requireCampaignAcceptsStart(campaign, countType)
        return campaign
    }

    // ── Read queries ─────────────────────────────────────────────────────

    /**
     * Returns a paginated page of [CountSessionSummaryView]s belonging to [clientId] -- the
     * summary projection behind GET /count-sessions (defect-burndown row 8; see
     * [CountSessionSummaryView]'s KDoc for why the full nested graph was replaced here).
     * [size] is clamped locally to [MAX_PAGE_SIZE] (docstore F1 precedent,
     * `DocumentStoreService.list`) -- [com.karyo.common.pagination.PaginationParams] itself
     * carries no max-size clamp and is shared by every paginated endpoint.
     *
     * The order-state rollup behind `orderCount`/`countedCount`/`finishedCount` is ONE grouped
     * query for the whole page ([CountOrderRepository.rollupStateCountsBySessions]), never one
     * query per session.
     */
    fun listSessions(clientId: Long, page: Int, size: Int): PaginatedResponse<CountSessionSummaryView> {
        val effectiveSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val query = sessionRepository.findByClientIdPaginated(clientId, page, effectiveSize)
        val sessions = query.list()
        val total = query.count()
        val rollups = orderRepository.rollupStateCountsBySessions(sessions.mapNotNull { it.id })
        val content = sessions.map { session ->
            val counts = rollups[session.id] ?: emptyMap()
            toSummaryView(
                session,
                orderCount = counts.values.sum().toInt(),
                countedCount = (counts[CountOrderState.COUNTED.code] ?: 0L).toInt(),
                finishedCount = (counts[CountOrderState.FINISHED.code] ?: 0L).toInt(),
            )
        }
        return paginatedResponse(content, page, effectiveSize, total)
    }

    /**
     * Returns a single [CountSessionView] or throws [StocktakingException.NotFound].
     *
     * `@Transactional` is load-bearing, not decorative: without it, a direct (non-HTTP)
     * caller that reads an entity here, then writes it via a DIFFERENT `@Transactional` method,
     * then reads it again via THIS method, gets the pre-write value back -- the plain read
     * reuses one ambient no-active-tx Hibernate session across the whole call chain, and that
     * session's L1 cache wins over the fresh row a repeat query would otherwise see, even though
     * the write's own (separate, transaction-scoped) session committed correctly. Verified
     * empirically (identity-hash probe) chasing a real flake this pattern caused in
     * `UnitLoadMissingTest`/`CancelOrderTest`/`LocationEmptyTest` (defect-burndown row 8 task).
     * Forcing this method onto its own transaction gives it the same fresh, isolated session a
     * real HTTP request always gets.
     */
    @Transactional
    fun getSession(id: Long, clientId: Long): CountSessionView {
        val session = sessionRepository.findById(id)?.takeIf { it.clientId == clientId }
            ?: throw StocktakingException.NotFound("CountSession", id)
        val orders = orderRepository.findBySession(id)
        return toSessionView(session, orders)
    }

    /** Returns the full review [CountOrderView] (with planned + counted amounts).
     *  `@Transactional` for the same stale-ambient-session reason as [getSession] -- see its KDoc. */
    @Transactional
    fun orderView(id: Long, clientId: Long): CountOrderView = toOrderView(loadOrder(id, clientId))

    /**
     * Returns the blind [CountEntryView] — omits [CountLineView.plannedAmount] and
     * [CountLineView.countedAmount] so operators cannot see expected quantities before counting.
     * `@Transactional` for the same stale-ambient-session reason as [getSession] -- see its KDoc.
     */
    @Transactional
    fun entryView(id: Long, clientId: Long): CountEntryView {
        val order = loadOrder(id, clientId)
        val lines = countLineRepository.findByOrder(id)
        return CountEntryView(
            id = order.id!!,
            orderNumber = order.orderNumber,
            locationName = order.locationName,
            lines = lines.map { line ->
                CountEntryLine(
                    lineId = line.id!!,
                    itemDataNumber = line.itemDataNumber,
                    lotNumber = line.lotNumber,
                    serialNumber = line.serialNumber,
                    unitLoadId = line.unitLoadId,
                    unitLoadLabel = line.unitLoadLabel,
                    counted = line.state != CountLineState.PLANNED.code,
                )
            },
        )
    }

    // ── submitCount ───────────────────────────────────────────────────────

    /**
     * Operator submits counted amounts. An input is REQUIRED only for a line still
     * [CountLineState.PLANNED] — a line already [CountLineState.COUNTED] (zeroed by
     * [unitLoadMissing]) is left as-is, no input needed (St4). Plain path regression: with no
     * prior [unitLoadMissing] call every line is still PLANNED, so every line still needs an
     * input, exactly as before.
     *
     * Per-line (PLANNED or already-COUNTED alike): if the counted amount matches
     * [CountLine.plannedAmount] exactly, the line is FINISHED and a COUNTED journal entry is
     * written via [StockCountingPort.recordMatchCounted]; otherwise the line stays COUNTED
     * (flagged for review) — this is also how an already-COUNTED zero from [unitLoadMissing]
     * becomes a discrepancy unless the planned amount was itself 0. The order advances to
     * COUNTED. If all lines matched, [finishOrder] is called immediately — releasing all locks
     * and potentially closing the session.
     */
    @Suppress("ThrowsCount")
    @Transactional
    fun submitCount(orderId: Long, inputs: List<CountInput>, clientId: Long): CountOrderView {
        val order = loadOrder(orderId, clientId)
        if (order.state != CountOrderState.GENERATED.code) {
            throw StocktakingException.InvalidState("order ${order.id} is not awaiting counts")
        }
        val byLine = inputs.associateBy { it.lineId }
        val lines = countLineRepository.findByOrder(orderId)

        // St3 fix: a zero-line order (location was already empty at snapshot time) used to
        // fall through this whole method as a silent no-op auto-finish -- an empty `lines` list
        // trivially satisfies every loop below and `allMatched` stays true, so the order
        // FINISHED with no operator intent recorded anywhere. Refuse it instead and point at
        // the sanctioned confirm-empty op ([locationEmpty]), which leaves an honest audit trail
        // (FINISHED order + lastCountedAt, see its KDoc).
        if (lines.isEmpty()) {
            throw StocktakingException.InvalidCount(
                "order ${order.id} has no lines to count; use POST /count-orders/${order.id}/location-empty " +
                    "to confirm the location is empty",
            )
        }
        val linesById = lines.associateBy { it.id }

        // Important 3 (stocktaking-block T5 review): an input targeting a line already off
        // PLANNED (e.g. zeroed by unitLoadMissing) must be REFUSED, not silently discarded. The
        // floor PWA submits an amount for every entry line with no notion of "already counted" --
        // without this guard, a real post-missing-op count from the PWA would vanish underneath
        // the web-triggered zero and accept() would delete stock that was actually there.
        for (input in inputs) {
            val line = linesById[input.lineId] ?: continue
            if (line.state != CountLineState.PLANNED.code) {
                throw StocktakingException.InvalidCount(
                    "line ${input.lineId} is already counted (state=${line.state}); cannot submit a new count for it",
                )
            }
        }

        var allMatched = true
        for (line in lines) {
            if (line.state == CountLineState.PLANNED.code) {
                val counted = byLine[line.id]?.countedAmount
                    ?: throw StocktakingException.InvalidCount("missing count for line ${line.id}")
                line.countedAmount = counted
                line.state = CountLineState.COUNTED.code
            }
            val counted = line.countedAmount
            if (counted != null && counted.compareTo(line.plannedAmount) == 0) {
                stockCountingPort.recordMatchCounted(line.stockUnitId, "ST ${order.id}")
                line.state = CountLineState.FINISHED.code
            } else {
                allMatched = false
            }
        }
        advanceOrder(order, CountOrderState.COUNTED)
        if (allMatched) finishOrder(order, clientId)
        return toOrderView(order)
    }

    // ── Unit-load-missing (St4) ─────────────────────────────────────────────

    /**
     * Operator reports a whole unit load missing from the location: every still-PLANNED line
     * whose [CountLine.unitLoadId] matches [unitLoadId] is counted at zero and moved to
     * [CountLineState.COUNTED] — but **the order state is deliberately left unchanged**, so the
     * operator can keep counting the rest of the location. [submitCount] later accepts those
     * pre-COUNTED lines without requiring a fresh input (see its KDoc).
     *
     * Idempotent against lines outside PLANNED (a second call for the same UL is a no-op on
     * those lines) but 404s if [unitLoadId] never had any line on this order at all — that's the
     * "not on this order" case the caller actually needs surfaced, as opposed to "already
     * handled".
     *
     * @throws StocktakingException.NotFound if no line on this order carries [unitLoadId] (404).
     * @throws StocktakingException.InvalidState if the order is not GENERATED (409).
     */
    @Transactional
    fun unitLoadMissing(orderId: Long, unitLoadId: Long, clientId: Long): CountEntryView {
        val order = loadOrder(orderId, clientId)
        if (order.state != CountOrderState.GENERATED.code) {
            throw StocktakingException.InvalidState("order ${order.id} is not awaiting counts")
        }
        val lines = countLineRepository.findByOrder(orderId)
        val matching = lines.filter { it.unitLoadId == unitLoadId }
        if (matching.isEmpty()) {
            throw StocktakingException.NotFound("UnitLoad", unitLoadId)
        }
        zeroPlannedLines(matching)
        return entryView(orderId, clientId)
    }

    // ── Location-empty (St3) ─────────────────────────────────────────────────

    /**
     * Operator confirms a location holds no stock at all -- the sanctioned way to close out an
     * order the finder generated for a location that had already gone empty by count time.
     * Guard: the order must be [CountOrderState.GENERATED] (409 otherwise, same idiom as
     * [submitCount]/[unitLoadMissing]).
     *
     * **Branch 1 -- zero-line order:** nothing was ever there to count, so this finishes the
     * order directly via [finishOrder] (locks released, `lastCountedAt` stamped, session closed
     * if this was the last open order) -- the same terminal path [submitCount] takes on an
     * all-match count, just reached in one hop instead of via COUNTED. **Deliberately no
     * [com.karyo.inventory.api.spi.StockCountingPort] journal call here**: every
     * `InventoryJournal` row is written against a stock unit, and there is none to write
     * against on a zero-line order -- the FINISHED [CountOrder] row plus the stamped
     * `lastCountedAt` on the location *are* the audit trail for this confirmation, honestly, not
     * a gap being papered over.
     *
     * **Branch 2 -- order has lines:** every still-PLANNED line is zeroed via [zeroPlannedLines]
     * (the same helper [unitLoadMissing] uses) and the order advances to
     * [CountOrderState.COUNTED] for manager review -- consistent with Task 5's mechanics
     * (a UL-missing zero never auto-finishes) and with the review path in general: a discrepancy
     * this large still needs [accept] to actually apply the zeros and write the per-line COUNTED
     * journal rows.
     */
    @Transactional
    fun locationEmpty(orderId: Long, clientId: Long): CountOrderView {
        val order = loadOrder(orderId, clientId)
        if (order.state != CountOrderState.GENERATED.code) {
            throw StocktakingException.InvalidState("order ${order.id} is not awaiting counts")
        }
        val lines = countLineRepository.findByOrder(orderId)
        if (lines.isEmpty()) {
            finishOrder(order, clientId)
        } else {
            zeroPlannedLines(lines)
            advanceOrder(order, CountOrderState.COUNTED)
        }
        return toOrderView(order)
    }

    // ── accept + recount ──────────────────────────────────────────────────

    /**
     * Manager accepts the discrepant count: applies inventory adjustments for every COUNTED
     * (discrepant) line via [StockCountingPort.applyCount], marks those lines FINISHED, then
     * calls [finishOrder] to release locks and potentially close the session.
     *
     * Guard: the order must be in state [CountOrderState.COUNTED]. Lines already FINISHED
     * (matched exactly during submit) are left untouched.
     */
    @Transactional
    fun accept(orderId: Long, clientId: Long): CountOrderView {
        val order = loadOrder(orderId, clientId)
        if (order.state != CountOrderState.COUNTED.code) {
            throw StocktakingException.InvalidState("order $orderId is not awaiting review (state=${order.state})")
        }
        countLineRepository.findByOrder(orderId)
            .filter { it.state == CountLineState.COUNTED.code }   // discrepancies (matched lines already FINISHED)
            .forEach { line ->
                stockCountingPort.applyCount(line.stockUnitId, line.countedAmount!!, "ST ${order.id}")
                line.state = CountLineState.FINISHED.code
            }
        finishOrder(order, clientId)
        return toOrderView(order)
    }

    /**
     * Manager requests a recount: cancels the current COUNTED order (releasing all locks) and
     * re-generates a fresh [CountOrderState.GENERATED] order for the same location under the same
     * session. The session remains OPEN — [maybeCloseSession] is deliberately NOT called here,
     * since a fresh non-terminal order is generated in the same breath (there is never a moment
     * where the session's order set is all-terminal).
     *
     * No inventory adjustments are made — the original stock amounts are preserved.
     */
    @Transactional
    fun recount(orderId: Long, clientId: Long): CountOrderView {
        val old = loadOrder(orderId, clientId)
        if (old.state != CountOrderState.COUNTED.code) {
            throw StocktakingException.InvalidState("order $orderId is not awaiting review (state=${old.state})")
        }

        cancelOrderLinesAndLocks(old, clientId)

        // Re-generate a fresh order for the same location under the same session
        val fresh = generateOrderForLocation(
            sessionId = old.sessionId,
            locationId = old.locationId,
            locationName = old.locationName,
            blindCount = old.blindCount,
            clientId = clientId,
        )
        log.debugf("recount: cancelled order %d → new order %d for location %d", orderId, fresh.id, old.locationId)

        return toOrderView(fresh)
    }

    /**
     * Manager drops a single location from the session — the location-level cancel with **no
     * replacement order** (contrast [recount], which cancels and immediately regenerates for the
     * same location: `recount === cancelOrder + generateOrderForLocation`).
     *
     * Allowed from GENERATED(50) (nothing counted yet) or COUNTED(500) (counted but not yet
     * reviewed) — guarded by [CountOrderState.canAdvanceTo] inside [cancelOrderLinesAndLocks];
     * a FINISHED or already-CANCELLED order throws [StocktakingException.InvalidState] (409).
     *
     * Unlike [finishOrder], [com.karyo.layout.spi.LocationLockPort.markCounted] is deliberately
     * NOT called — nothing was actually counted at this location. The session auto-close check
     * runs after cancelling ([maybeCloseSession]) — a cancelled last order closes the session,
     * the same rule [finishOrder] applies for a finished last order.
     */
    @Transactional
    fun cancelOrder(orderId: Long, clientId: Long): CountOrderView {
        val order = loadOrder(orderId, clientId)
        cancelOrderLinesAndLocks(order, clientId)
        maybeCloseSession(order.sessionId)
        return toOrderView(order)
    }

    /**
     * Shared cancel core for [recount] and [cancelOrder]: transitions [order] to CANCELLED
     * (guarded by [advanceOrder]/[CountOrderState.canAdvanceTo] — this is where the illegal-jump
     * 409 actually fires, e.g. cancelling an already-FINISHED order), cancels every line, and
     * releases the stock + location locks. Deliberately does NOT touch session state or
     * [CountOrder.finished] — callers decide whether/when a session-close check applies (recount
     * never does; cancelOrder always does).
     */
    private fun cancelOrderLinesAndLocks(order: CountOrder, clientId: Long) {
        advanceOrder(order, CountOrderState.CANCELLED)
        val lines = countLineRepository.findByOrder(order.id!!)
        val stockIds = lines.map { it.stockUnitId }
        lines.forEach { it.state = CountLineState.CANCELLED.code }
        stockCountingPort.releaseCount(stockIds)
        locationLockPort.releaseCount(order.locationId, clientId)
    }

    // ── Work-inbox claim / release ────────────────────────────────────────

    /**
     * Claims a GENERATED [CountOrder] for [operatorId]. Sets [CountOrder.operatorId] and
     * [CountOrder.startedBy]; state remains GENERATED so [submitCount] still transitions it.
     *
     * Throws [WorkClaimConflictException] for both the not-found case and the already-claimed /
     * wrong-state case — [WorkDispatchService.getNext] catches only that exception to skip a
     * lost-race candidate safely.
     */
    @Transactional
    fun claim(orderId: Long, operatorId: String): CountOrder {
        val clientId = tenantContext.clientId
        val order = orderRepository.findByIdAndClient(orderId, clientId)
            ?: throw WorkClaimConflictException("Count order $orderId not found")
        if (order.state != CountOrderState.GENERATED.code || order.operatorId != null) {
            throw WorkClaimConflictException("Count order $orderId already taken (state=${order.state}, operatorId=${order.operatorId})")
        }
        order.operatorId = operatorId
        order.startedBy = operatorId
        order.started = Instant.now()
        return order
    }

    /**
     * Releases a claimed GENERATED [CountOrder] back to the unclaimed pool.
     *
     * [operatorId] is the ACTOR performing the release, not necessarily the holder: with
     * [asManager] a manager may release a count claimed by someone else. Both identities land in
     * the [CountOrderReleasedEvent] outbox row -- see that event's KDoc for why `managerOverride`
     * is derived from the two operators rather than from [asManager].
     *
     * @throws [StocktakingException.NotFound] if the order does not exist for this tenant.
     * @throws [StocktakingException.InvalidState] if the order is not GENERATED or the operator does not match.
     */
    @Transactional
    fun release(orderId: Long, operatorId: String, asManager: Boolean = false) {
        val clientId = tenantContext.clientId
        val order = orderRepository.findByIdAndClient(orderId, clientId)
            ?: throw StocktakingException.NotFound("CountOrder", orderId)
        if (order.state != CountOrderState.GENERATED.code) {
            throw StocktakingException.InvalidState("Only an un-counted order can be released (state=${order.state})")
        }
        if (order.operatorId == null) {
            throw StocktakingException.InvalidState("Count order $orderId is not claimed")
        }
        if (order.operatorId != operatorId && !asManager) {
            throw StocktakingException.InvalidState("Count order $orderId claimed by a different operator")
        }
        val releasedFrom = order.operatorId
        order.operatorId = null
        order.startedBy = null
        order.started = null
        outboxService.publish(
            "CountOrder", order.id!!, "CountOrderReleased",
            CountOrderReleasedEvent(
                countOrderId = order.id!!,
                orderNumber = order.orderNumber,
                locationId = order.locationId,
                locationName = order.locationName,
                releasedFrom = releasedFrom,
                releasedBy = operatorId,
                managerOverride = releasedFrom != null && releasedFrom != operatorId,
                clientId = clientId,
                occurredAt = Instant.now(),
            ),
            clientId,
        )
    }

    /**
     * Shared finish helper — called by [submitCount] on all-match, and by [accept].
     *
     * Sets the order FINISHED, releases stock and location locks, stamps
     * [com.karyo.layout.spi.LocationLockPort.markCounted] for the order's location (the real
     * `lastCountedAt` writer, L3 of the locations-layout sprint — covers BOTH this
     * no-discrepancy auto-finish path and [accept]'s discrepancy path, since both funnel
     * through here), and closes the session if every order in it has reached a terminal
     * state (FINISHED or CANCELLED).
     *
     * Deliberately NOT called from [recount]'s cancel branch — a cancelled/recounted order
     * never reaches FINISHED, so its location is never stamped as counted.
     */
    private fun finishOrder(order: CountOrder, clientId: Long) {
        advanceOrder(order, CountOrderState.FINISHED)
        order.finished = Instant.now()

        val lines = countLineRepository.findByOrder(order.id!!)
        val stockUnitIds = lines.map { it.stockUnitId }
        stockCountingPort.releaseCount(stockUnitIds)
        locationLockPort.releaseCount(order.locationId, clientId)
        locationLockPort.markCounted(listOf(order.locationId), Instant.now())

        maybeCloseSession(order.sessionId)
    }

    /**
     * Closes the [CountSession] if every order in it has reached a terminal state (FINISHED or
     * CANCELLED). Shared by [finishOrder] (an order finishing) and [cancelOrder] (a location
     * dropped without a replacement) — either can be the event that empties the session's
     * still-open-order set.
     */
    private fun maybeCloseSession(sessionId: Long) {
        val sessionOrders = orderRepository.findBySession(sessionId)
        val terminalCodes = setOf(CountOrderState.FINISHED.code, CountOrderState.CANCELLED.code)
        if (sessionOrders.all { it.state in terminalCodes }) {
            val session = sessionRepository.findById(sessionId)
            if (session != null && session.state != CountSessionState.CLOSED.code) {
                session.state = CountSessionState.CLOSED.code
                session.ended = Instant.now()
            }
        }
    }

    /**
     * Guards and performs an order-state transition via [CountOrderState.canAdvanceTo]. Every
     * order-lifecycle state write funnels through here so the VO is the sole authority on legal
     * jumps; an illegal jump throws [StocktakingException.InvalidState] (409) uniformly.
     *
     * Callers with a narrower domain precondition (e.g. [accept]/[recount] require the order to
     * be exactly COUNTED, not merely "any earlier state" — [CountOrderState.canAdvanceTo] alone
     * would also permit a raw GENERATED→FINISHED jump, which is not a legal *business* transition
     * even though it is a forward one) validate that precondition themselves before calling
     * this; this guard is the VO-authoritative check on top, never a replacement for it.
     */
    private fun advanceOrder(order: CountOrder, target: CountOrderState) {
        val current = CountOrderState.fromCode(order.state)
        if (!current.canAdvanceTo(target)) {
            throw StocktakingException.InvalidState(
                "count order ${order.id} cannot advance from $current to $target",
            )
        }
        order.state = target.code
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Re-sorts [locationIds] into walking order — `orderIndex NULLS LAST, name` — before
     * [startCount] generates one [CountOrder] per location (St6). See [startCount]'s KDoc for
     * why this matters even though [FullWarehouseScope] already returns its ids pre-sorted:
     * this call is what makes the sort a genuine reorder for [ExplicitLocationScope] (whose
     * caller-supplied `locationIds`/area-expansion/name-pattern union has no inherent spatial
     * order) rather than a no-op that only happens to hold for the one scope that already sorts
     * itself.
     *
     * One batched [LocationLockPort.orderIndexFor] call regardless of [locationIds] size; the
     * per-id [LocationLockPort.locationName] tie-break call is not batched (no batch name port
     * exists), same honest N-calls-in-one-transaction shape [startCount]'s class KDoc already
     * documents as this service's scale ceiling.
     */
    private fun walkingOrder(locationIds: List<Long>, clientId: Long): List<Long> {
        val orderIndexes = locationLockPort.orderIndexFor(locationIds)
        return locationIds.sortedWith(
            compareBy<Long, Int?>(nullsLast()) { orderIndexes[it] }
                .thenBy { locationLockPort.locationName(it, clientId) ?: "" },
        )
    }

    /** Loads a [CountOrder] by id, scoped to [clientId]. Throws [StocktakingException.NotFound] if absent. */
    private fun loadOrder(orderId: Long, clientId: Long): CountOrder =
        orderRepository.findById(orderId)?.takeIf { it.clientId == clientId }
            ?: throw StocktakingException.NotFound("CountOrder", orderId)

    /** [skipped] is supplied only by [startCount] (END_OF_PERIOD); it is not persisted, so the
     *  read paths ([listSessions]/[getSession]) legitimately return an empty list -- see
     *  [CountSessionView.skippedLocations]. */
    private fun toSessionView(
        session: CountSession,
        orders: List<CountOrder>,
        skipped: List<String> = emptyList(),
    ): CountSessionView {
        val orderViews = orders.map { order ->
            val lines = countLineRepository.findByOrder(order.id!!)
            CountOrderView(
                id = order.id!!,
                orderNumber = order.orderNumber,
                sessionId = order.sessionId,
                locationId = order.locationId,
                locationName = order.locationName,
                state = order.state,
                lines = lines.map(::toLineView),
            )
        }
        return CountSessionView(
            id = session.id!!,
            sessionNumber = session.sessionNumber,
            type = session.type,
            state = session.state,
            orders = orderViews,
            campaignId = session.campaignId,
            skippedLocations = skipped,
        )
    }

    private fun toOrderView(order: CountOrder): CountOrderView {
        val lines = countLineRepository.findByOrder(order.id!!)
        return CountOrderView(
            id = order.id!!,
            orderNumber = order.orderNumber,
            sessionId = order.sessionId,
            locationId = order.locationId,
            locationName = order.locationName,
            state = order.state,
            lines = lines.map(::toLineView),
        )
    }

    private fun toLineView(line: CountLine): CountLineView = CountLineView(
        id = line.id!!,
        stockUnitId = line.stockUnitId,
        itemDataNumber = line.itemDataNumber,
        lotNumber = line.lotNumber,
        plannedAmount = line.plannedAmount,
        countedAmount = line.countedAmount,
        state = line.state,
        unitLoadId = line.unitLoadId,
        unitLoadLabel = line.unitLoadLabel,
    )

    companion object {
        /** count_sessions.session_number / count_orders.order_number are VARCHAR(40). */
        private const val MAX_NUMBER_LENGTH = 40

        /** Local clamp on [listSessions]' `size` (docstore F1 precedent, `DocumentStoreService.
         *  MAX_PAGE_SIZE`) -- [com.karyo.common.pagination.PaginationParams] itself carries no
         *  max-size clamp and is shared by every paginated endpoint, so it is deliberately NOT
         *  touched here. */
        const val MAX_PAGE_SIZE: Int = 200
    }
}

/**
 * Maps a [CountSession] to its lightweight [CountSessionSummaryView] -- the projection behind
 * both [StocktakingService.listSessions] (order counts from
 * [com.karyo.stocktaking.repository.CountOrderRepository.rollupStateCountsBySessions]) and
 * [StocktakingService.startCount]'s response (order counts computed in memory from the orders
 * just generated, no re-query). Callers supply the three counts directly so this stays a pure
 * mapper with no repository dependency of its own.
 *
 * Top-level (not a member of [StocktakingService], which is already at detekt's
 * TooManyFunctions ceiling) -- same idiom as [isCountable]/[zeroPlannedLines].
 */
private fun toSummaryView(
    session: CountSession,
    orderCount: Int,
    countedCount: Int,
    finishedCount: Int,
    skipped: List<String> = emptyList(),
    skippedIds: List<Long> = emptyList(),
): CountSessionSummaryView = CountSessionSummaryView(
    id = session.id!!,
    sessionNumber = session.sessionNumber,
    type = session.type,
    state = session.state,
    campaignId = session.campaignId,
    orderCount = orderCount,
    countedCount = countedCount,
    finishedCount = finishedCount,
    skippedLocations = skipped,
    skippedLocationIds = skippedIds,
)

/**
 * Resolves [StartCountRequest.type] to a [CountType] and enforces the type's own input rules.
 * `null`/blank means [CountType.CYCLE] (every pre-St5 caller); anything that is not a
 * [CountType] name is a 422 rather than a silent downgrade to CYCLE.
 *
 * Top-level (not a class member) to stay under detekt's TooManyFunctions ceiling on
 * [StocktakingService], which is already at it -- same reason as [zeroPlannedLines]; it touches
 * no service state.
 */
private fun countTypeOf(request: StartCountRequest): CountType {
    val raw = request.type?.takeIf { it.isNotBlank() }
    val countType = when {
        raw == null -> CountType.CYCLE
        else -> CountType.entries.firstOrNull { it.name == raw }
            ?: throw StocktakingException.InvalidCount(
                "unknown count type '$raw' (expected ${CountType.entries.joinToString("|") { it.name }})",
            )
    }
    // The type <-> scope invariant is BICONDITIONAL: END_OF_PERIOD may not narrow its scope, and
    // a CYCLE count may not widen its scope to the whole warehouse BY STRATEGY NAME. Enforcing
    // only the first direction left `{"scopeStrategy":"FULL_WAREHOUSE"}` (no type) as a back door
    // to a warehouse-wide STOCKTAKING freeze mislabelled CYCLE -- and therefore with none of
    // END_OF_PERIOD's semantics: no skip list, a CYCLE campaign check, no full-inventory badge.
    // The name check alone does NOT close every route to that freeze: `locationNamePattern` is a
    // raw SQL LIKE pattern, so `"%"` reaches the same whole-warehouse set through the EXPLICIT
    // scope. requireNonWildcardNamePattern closes that route separately.
    if (countType == CountType.END_OF_PERIOD) {
        requireNoCallerScope(request)
    } else {
        requireNoFullWarehouseScope(request)
        requireNonWildcardNamePattern(request)
    }
    return countType
}

/**
 * Refuses a [StartCountRequest.locationNamePattern] made up ENTIRELY of SQL `LIKE` wildcards
 * (`%`/`_` in any combination -- `"%"`, `"%%"`, `"_"`, `"%_%"`).
 *
 * Such a pattern matches every location name for the tenant, so it is the whole-warehouse scope
 * arriving through the EXPLICIT strategy's back door: a session labelled CYCLE that freezes the
 * entire warehouse under a STOCKTAKING lock, with none of END_OF_PERIOD's semantics (no
 * skip-reserved list, a CYCLE campaign type check, no full-inventory badge) -- the exact outcome
 * [requireNoFullWarehouseScope] refuses when it is asked for by strategy name. 422: ask for
 * `type=END_OF_PERIOD` if a full inventory is what you mean.
 *
 * A pattern that merely contains wildcards stays legal -- `"A-01-%"` is the documented usage.
 * Only an all-wildcard pattern (nothing to narrow on) is refused.
 */
private fun requireNonWildcardNamePattern(request: StartCountRequest) {
    val pattern = request.locationNamePattern?.takeIf { it.isNotBlank() } ?: return
    if (pattern.all { it == '%' || it == '_' }) {
        throw StocktakingException.InvalidCount(
            "locationNamePattern '$pattern' is all wildcards and matches every location; " +
                "that is a full inventory -- start one with type=${CountType.END_OF_PERIOD.name}",
        )
    }
}

/**
 * The campaign half of [StocktakingService.startCount]'s input rules: a [campaign] accepts a start
 * only while it is OPEN (409 otherwise), and its type check is ONE-WAY, not the biconditional
 * match it once was (defect-burndown-3, row 4): a CYCLE session under an END_OF_PERIOD campaign
 * is the sanctioned remediation for locations an END_OF_PERIOD start SKIPPED (reserved stock, or
 * an existing lock, see [isCountable]) -- count them individually once their reservations clear,
 * filed under the SAME campaign so its rollup covers the whole annual inventory. Without this, an
 * END_OF_PERIOD campaign with even one skipped location could never be completed: the skip is
 * permanent (there is no persisted skip table to retry from -- see
 * [CountSessionSummaryView.skippedLocationIds]) and the campaign type gate refused every
 * remediating CYCLE start. The other direction stays refused: an END_OF_PERIOD session may not be
 * filed under a CYCLE campaign, same message as before.
 *
 * [generateOrderForLocation]'s ReservedStock guard is untouched by this relaxation -- a location
 * still cannot be counted while its reservation is live; this only lets the remediating count be
 * filed under the right campaign once that reservation clears.
 *
 * Top-level (not a class member), like its [requireNoCallerScope]/[requireNoFullWarehouseScope]/
 * [requireNonWildcardNamePattern]/[requireNoLockedLocation] siblings and for the same reason: it
 * touches no service state (its whole input is the two arguments), and [StocktakingService] sits
 * ON detekt's TooManyFunctions ceiling, so every pure check that CAN live out here MUST. Its
 * caller [StocktakingService.validateCampaignForStart] stays a member -- that one does hit a
 * repository.
 */
private fun requireCampaignAcceptsStart(campaign: CountCampaign, countType: CountType) {
    if (campaign.state != CountCampaignState.OPEN.code) {
        throw StocktakingException.InvalidState("campaign ${campaign.id} is not OPEN")
    }
    val compatible = campaign.type == countType.name ||
        (campaign.type == CountType.END_OF_PERIOD.name && countType == CountType.CYCLE)
    if (!compatible) {
        throw StocktakingException.InvalidState(
            "campaign ${campaign.id} is type ${campaign.type}, not ${countType.name}",
        )
    }
}

/**
 * An END_OF_PERIOD count owns its own scope (every location the tenant owns; a shared
 * `client_id = 0` location is never included). Any caller-supplied narrowing input is refused
 * (422) rather than silently ignored -- a caller who believed they were counting one aisle must
 * not discover they froze every location the tenant owns.
 */
private fun requireNoCallerScope(request: StartCountRequest) {
    if (request.locationIds.isNotEmpty() || request.areaId != null || !request.locationNamePattern.isNullOrBlank()) {
        throw StocktakingException.InvalidCount(
            "an END_OF_PERIOD count always covers every location the owner holds; " +
                "locationIds/areaId/locationNamePattern must be empty",
        )
    }
    if (request.scopeStrategy != null && request.scopeStrategy != FullWarehouseScope.NAME) {
        throw StocktakingException.InvalidCount(
            "an END_OF_PERIOD count always uses the ${FullWarehouseScope.NAME} scope; " +
                "scopeStrategy '${request.scopeStrategy}' cannot be applied",
        )
    }
}

/**
 * The other direction of the same invariant ([requireNoCallerScope]'s sibling): a CYCLE count may
 * not select the full-warehouse scope BY NAME. Counting the whole warehouse is END_OF_PERIOD's
 * job and carries END_OF_PERIOD's semantics (skip-reserved, matching campaign type, the
 * full-inventory badge); letting `scopeStrategy` smuggle that scope into a CYCLE session would
 * produce a warehouse-wide freeze with none of them. 422 -- ask for the type you mean.
 *
 * Scope: this closes the `scopeStrategy` route ONLY. The equivalent set is also reachable through
 * an all-wildcard [StartCountRequest.locationNamePattern] under the EXPLICIT scope; that route is
 * closed by [requireNonWildcardNamePattern], not here.
 */
private fun requireNoFullWarehouseScope(request: StartCountRequest) {
    if (request.scopeStrategy == FullWarehouseScope.NAME) {
        throw StocktakingException.InvalidCount(
            "the ${FullWarehouseScope.NAME} scope belongs to an END_OF_PERIOD count; " +
                "start one with type=${CountType.END_OF_PERIOD.name} instead of scoping a CYCLE count to it",
        )
    }
}

/**
 * Refuses (409 [StocktakingException.LocationLocked]) a CYCLE start whose scope contains a
 * location that is ALREADY locked -- typically frozen by another open count session.
 *
 * Without this, two overlapping counts of the same location were possible, and the damage was
 * not merely a duplicate order: [com.karyo.layout.service.LocationService.lockLocation]
 * overwrites the lock unconditionally, so whichever session finished first would RELEASE the
 * location out from under the other one, silently ending the survivor's freeze while its order
 * was still open. The check is the cheap, symmetric counterpart of END_OF_PERIOD's skip: a
 * locked location is never counted either way, the types differ only in the remedy (refuse the
 * start vs. walk past it). This also matches the behavioral fact that a locked location is
 * always excluded from count-order generation.
 *
 * Reports the FIRST locked location in scope order (not `Set` iteration order, which is not
 * stable) and names it, so the operator can go unlock or wait for it.
 * Top-level for the same TooManyFunctions reason as [countTypeOf].
 */
private fun requireNoLockedLocation(
    locationIds: List<Long>,
    preLocked: Set<Long>,
    clientId: Long,
    locationLockPort: LocationLockPort,
) {
    val locked = locationIds.firstOrNull { it in preLocked } ?: return
    val name = locationLockPort.locationName(locked, clientId) ?: "LOC-$locked"
    throw StocktakingException.LocationLocked(
        locked,
        "location '$name' is locked (another count session, or an operator lock) -- " +
            "release it or wait for that session to finish before counting it",
    )
}

/**
 * Whether an END_OF_PERIOD start may generate an order for [locationId]: no stock at the
 * location is reserved, the location carried no lock before this session started ([preLocked]
 * is the pre-start snapshot), and the location itself carries no live inbound
 * [com.karyo.layout.domain.model.LocationReservation] ([preReserved] is the same
 * pre-start-snapshot pattern, row 16 defect-burndown-4 -- work already in flight toward the
 * location invalidates a blind count same as a lock does). All three are things a *cycle* count
 * refuses the whole start over; a full inventory walks past them instead and reports them.
 * Top-level for the same TooManyFunctions reason as [countTypeOf].
 */
private fun isCountable(
    locationId: Long,
    stock: List<CountableStock>,
    preLocked: Set<Long>,
    preReserved: Set<Long>,
): Boolean =
    locationId !in preLocked && locationId !in preReserved && stock.none { it.reservedAmount.signum() > 0 }

/**
 * Zeroes every still-[CountLineState.PLANNED] line in [lines]: `countedAmount = 0`, state moves
 * to [CountLineState.COUNTED]. Lines already off PLANNED (e.g. zeroed by an earlier call) are
 * left untouched -- idempotent w.r.t. its own effect. Shared by [StocktakingService.unitLoadMissing]
 * (a UL-scoped subset of an order's lines) and [StocktakingService.locationEmpty] branch 2 (every
 * remaining line on the order). Top-level (not a class member) purely to stay under detekt's
 * TooManyFunctions ceiling on [StocktakingService] -- it touches no service state.
 */
private fun zeroPlannedLines(lines: List<CountLine>) {
    lines.filter { it.state == CountLineState.PLANNED.code }
        .forEach { line ->
            line.countedAmount = BigDecimal.ZERO
            line.state = CountLineState.COUNTED.code
        }
}
