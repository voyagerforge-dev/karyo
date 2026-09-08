package com.karyo.layout.service

import com.karyo.layout.domain.model.LocationReservation
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.domain.model.StorageStrategy
import com.karyo.layout.domain.model.TypeCapacityConstraint
import com.karyo.layout.repository.LocationReservationRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.repository.StorageStrategyAreaRepository
import com.karyo.layout.repository.StorageStrategyRepository
import com.karyo.layout.repository.TypeCapacityConstraintRepository
import com.karyo.layout.spi.AddToLocationRequest
import com.karyo.layout.spi.AddToLocationResult
import com.karyo.layout.spi.LocationCandidate
import com.karyo.layout.spi.LocationFilter
import com.karyo.layout.spi.LocationFinder
import com.karyo.layout.spi.LocationFinderRequest
import com.karyo.layout.spi.LocationFinderResult
import com.karyo.layout.spi.PutawayLocationStrategy
import com.karyo.layout.vo.AreaUsage
import com.karyo.product.spi.ProductLookup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * The built-in putaway location finder: 14 built-in filter passes (numbered below; count
 * corrected 2026-08-16, the old "9 of 19" prose had drifted from its own list). See
 * `docs/functional/location-finder.md` for the full FSD, the myWMS 19-filter mapping and
 * the remaining gap list. It plays two roles:
 *
 *  1. The lowest-priority [PutawayLocationStrategy] — the default placement algorithm,
 *     pre-emptable by any extension strategy at a lower priority.
 *  2. The public [LocationFinder] facade — drives the strategy chain, applies the
 *     soft-reservation, and exposes release + the scheduled expiry sweep.
 *
 * **Filter order (each either a SQL predicate or an in-service post-filter):**
 *  1. area usage = STORAGE (or PICKING when `pickingOnly`)              — SQL (`area.usages LIKE`)
 *  2. location unlocked (`lockType = 0`)                                — SQL
 *  3. effective allocation < 100 (base + active reservations)          — SQL base, then in-service for reservations
 *  4. zone match (preferredZoneId, else strategy zone, else any)       — SQL when a zone is resolved
 *  5. lifting capacity >= weight (null/0 capacity = unlimited)         — SQL
 *  6. client ownership (owner-only, `strategy.onlyClientLocation`      -- SQL
 *     defaults `true` since row 17/defect-burndown-4; `false` is an explicit
 *     per-strategy opt-in that also allows shared client_id=0 locations)
 *  7. [TypeCapacityConstraint] compatibility + capacity                — in-service, batched (Task 4)
 *  8. client mixing (strategy.mixClient == false)                      — in-service (cheap first gate; the real occupancy+transport upgrade shipped as passes 13-14, LF9)
 *  9. [StorageArea][com.karyo.layout.domain.model.StorageArea] restriction + hiding      — in-service, built-in (Task 3)
 * 10. field/section group lifting-capacity ([GroupCapacityReader])                     — in-service, batched (Task 7)
 * 11. allocation-state = 0 (operator "mark full/blocked", L3/Task 6)                   -- SQL (runs with predicates 1-6)
 * 12. fixed-assignment exclusion (any FixAssignment on the location)              -- SQL (LF8, corpus filter 4)
 * 13. occupancy + in-flight-transport client mixing ([OccupancyMixReader], LF9)  -- in-service, batched
 * 14. occupancy + in-flight-transport item mixing ([OccupancyMixReader], LF8)    -- in-service, batched
 *
 * **`strategy.manualSearch`:** short-circuits the whole search — see [tryFindPutawayLocation].
 *
 * **Filter 7 — TypeCapacityConstraint (locations-layout sprint Task 4):** myWMS's
 * (LocationType, UnitLoadType) compatibility/capacity matrix, replacing the old permissive
 * UL-type stub. Loaded in ONE batched query per `findPutawayLocation` call (never
 * per-candidate) via [TypeCapacityConstraintRepository.findByLocationTypes] — see
 * [typeCapacityCompatible] for the full semantics (no-rows-at-all = unrestricted;
 * rows-but-no-match = excluded; matching row gates on effective allocation; oversize
 * (`allocation > 100`) rows exclude any non-empty candidate; Karyo deliberately diverges
 * from myWMS's multi-position placement rule).
 *
 * **Filter 9 — StorageArea (locations-layout sprint Task 3):** if the resolved strategy has
 * [com.karyo.layout.domain.model.StorageStrategyArea]s configured, candidates are restricted to
 * locations whose `locationCluster` is a member of the union of those areas' clusters. **No
 * areas configured means NO restriction at all** — a deliberate deviation from myWMS's
 * `locationCluster IS NOT NULL` fallback, which would silently exclude every cluster-less
 * location in an existing deployment; see [applyAreaConstraints]. When areas ARE configured,
 * `strategy.useAreaStrategyDate` (cross-area FIFO, >1 area only) and `strategy.useItemDataArea`
 * (full-area) can additionally hide whole areas — see [AreaOccupancyReader]. This filter runs
 * as a built-in step, alongside the effective-allocation sort, strictly BEFORE the SPI
 * [LocationFilter] chain (never after) — it is not itself an [LocationFilter] extension point.
 *
 * **Filter 10 — field/section group lifting capacity (locations-layout sprint Task 7, L4):**
 * candidates whose [com.karyo.layout.domain.model.LocationType] defines a `fieldLiftingCapacity`
 * and/or `sectionLiftingCapacity` are excluded when the incoming unit load's weight plus the
 * ALREADY-OCCUPIED weight across every location sharing that candidate's (area, rack, field) or
 * (area, section) group would exceed the cap — see [GroupCapacityReader] for the batching and
 * honest weight-degradation semantics. A `LocationType` with neither column set (every existing
 * row, on a freshly migrated DB) is unaffected — regression pin.
 *
 * **Ordering:** effective allocation ASC (emptiest first), then location name ASC
 * (stable tie-break) — UNLESS `strategy.useAreaStrategyDate` is active (>1 configured area),
 * in which case the area's `orderIndex` in the strategy's ordered area list is prepended as the
 * primary sort key (areas earlier in the list preferred). The SQL orders by base allocation;
 * reservations can only *raise* effective allocation, so the service re-sorts the small
 * surviving set by effective value (and, when active, area order) before handing it to the SPI
 * filters.
 *
 * **`strategy.sorts` + `nearPickingLocation` (locations-layout sprint Task 5):** layered on
 * TOP of the ordering above, still strictly before the SPI chain — see [CandidateOrdering]
 * for the full comparator-chain semantics (per-[com.karyo.layout.vo.StorageStrategySortType]
 * mapping, `STORAGEAREA` suppression under `useAreaStrategyDate`, and the `nearPickingLocation`
 * fix-assignment distance prepend). An empty `sorts` list with `nearPickingLocation == false`
 * is a no-op — the ordering above is the entire story (regression pin).
 *
 * **After the SPI filters run their order is honored verbatim — the finder takes
 * `first()` and does NOT re-sort** (the stock-selection FSD lesson; see [LocationFilter]).
 *
 * **LF10 (location-finder sprint Task 7):** [findAddToLocation] is a sibling search MODE on
 * this same facade, not another pass in the putaway chain above -- it searches existing stock
 * for a consolidation target, not an empty location, and delegates its whole algorithm to the
 * [AddToLocationFinder] collaborator bean. See that class and [findAddToLocation]'s own KDoc.
 */
@ApplicationScoped
@Priority(PutawayLocationStrategy.DEFAULT_BUILTIN_PRIORITY)
class LocationFinderService(
    private val locationRepository: StorageLocationRepository,
    private val reservationRepository: LocationReservationRepository,
    private val strategyRepository: StorageStrategyRepository,
    private val strategyAreaRepository: StorageStrategyAreaRepository,
    private val areaOccupancyReader: AreaOccupancyReader,
    private val groupCapacityReader: GroupCapacityReader,
    private val occupancyMixReader: OccupancyMixReader,
    private val typeCapacityConstraintRepository: TypeCapacityConstraintRepository,
    private val candidateOrdering: CandidateOrdering,
    private val strategies: Instance<PutawayLocationStrategy>,
    private val filters: Instance<LocationFilter>,
    private val productLookup: ProductLookup,
    private val addToLocationFinder: AddToLocationFinder,
) : LocationFinder, PutawayLocationStrategy {

    private val log = Logger.getLogger(LocationFinderService::class.java)

    // ── LocationFinder facade ─────────────────────────────────────────────

    /**
     * Runs the [PutawayLocationStrategy] chain (lowest-priority built-in last). The
     * first non-null result wins. The built-in strategy writes its own
     * [LocationReservation] for a [Found] (so concurrent putaways see the location as
     * taken); a custom strategy owns its own reservation semantics.
     */
    @Transactional
    override fun findPutawayLocation(request: LocationFinderRequest): LocationFinderResult {
        val ordered = strategies.sortedBy { it.priority() }
        for (strategy in ordered) {
            val result = strategy.tryFindPutawayLocation(request)
            if (result != null) {
                return result
            }
        }
        return LocationFinderResult.NoLocation("no putaway strategy produced a location")
    }

    @Transactional
    override fun releaseReservation(transportOrderId: Long) {
        val removed = reservationRepository.deleteByTransportOrderId(transportOrderId)
        if (removed > 0) {
            log.debugf("Released %d location reservation(s) for transport order %d", removed, transportOrderId)
        }
    }

    /**
     * LF10 facade entry point -- resolves the strategy the same way [resolveStrategy] does for
     * putaway (own id, else the product's default, both ownership-checked via
     * [resolveStrategyById]), applies the same `manualSearch` bypass BEFORE any candidate
     * search, then delegates the whole two-priority algorithm to [addToLocationFinder]. See
     * [LocationFinder.findAddToLocation]'s KDoc for the advisory (no soft-reserve) contract.
     */
    @Transactional
    override fun findAddToLocation(request: AddToLocationRequest): AddToLocationResult {
        val strategyId = request.storageStrategyId ?: productLookup.findById(request.itemDataId)?.defaultStorageStrategyId
        val strategy = strategyId?.let { resolveStrategyById(it, request.clientId) }
        if (strategy?.manualSearch == true) {
            return AddToLocationResult.None(
                "storage strategy '${strategy.name}' requires manual placement (manualSearch)"
            )
        }
        return addToLocationFinder.search(request)
    }

    /**
     * Sweeps expired soft reservations. TTL is enforced two ways: callers release on
     * task complete/cancel, and this job reaps anything past [LocationReservation.expiresAt]
     * so a crashed/abandoned putaway never permanently blocks a location.
     */
    @Scheduled(every = "60s")
    @Transactional
    fun sweepExpiredReservations() {
        val removed = reservationRepository.deleteExpired(Instant.now())
        if (removed > 0) {
            log.debugf("Swept %d expired location reservation(s)", removed)
        }
    }

    // ── Built-in PutawayLocationStrategy ──────────────────────────────────

    /**
     * The default placement algorithm (filters 1-9). Returns [Found] (and writes the soft
     * [LocationReservation] keyed by [LocationFinderRequest.reservationKey]) for the
     * emptiest surviving location after the SPI [LocationFilter] chain, or [NoLocation]
     * with a reason naming the constraint that emptied the set. Never null (the built-in
     * always has an opinion — it is the terminal strategy). Marked @Transactional so the
     * reservation persists when called directly via the [PutawayLocationStrategy] chain.
     */
    @Transactional
    override fun tryFindPutawayLocation(request: LocationFinderRequest): LocationFinderResult? {
        // Resolved once and reused for zone, manualSearch, onlyClientLocation, and client
        // mixing — see resolveStrategy's KDoc for the ownership check it applies.
        val strategy = resolveStrategy(request)
        return manualSearchResult(strategy) ?: findCandidateLocation(request, strategy)
    }

    /**
     * myWMS `manualSearch`: the strategy opts this putaway out of automatic placement
     * entirely. The finder's "no location found" result already models this — the
     * caller (tasks module) falls back to its manual placement flow. Checked BEFORE any
     * candidate query is built, per the flag's intent (skip the search, not just its
     * result). Split out of [tryFindPutawayLocation] to keep that function's return count
     * within the project's Detekt limit.
     */
    private fun manualSearchResult(strategy: StorageStrategy?): LocationFinderResult.NoLocation? =
        if (strategy?.manualSearch == true) {
            LocationFinderResult.NoLocation("storage strategy '${strategy.name}' requires manual placement (manualSearch)")
        } else {
            null
        }

    private fun findCandidateLocation(request: LocationFinderRequest, strategy: StorageStrategy?): LocationFinderResult {
        val usageToken = (if (request.pickingOnly) AreaUsage.PICKING else AreaUsage.STORAGE).name
        val zoneId = request.preferredZoneId ?: strategy?.zone?.id

        // Filters 1-6 as SQL predicates; over-fetch so reservation load (filter 3 tail)
        // can drop some without starving the result.
        val raw = locationRepository.findPutawayCandidates(
            usageToken = usageToken,
            zoneId = zoneId,
            weight = request.weight,
            clientId = request.clientId,
            onlyClientLocation = strategy?.onlyClientLocation ?: DEFAULT_ONLY_CLIENT_LOCATION,
            limit = CANDIDATE_FETCH_LIMIT,
        )
        if (raw.isEmpty()) {
            return LocationFinderResult.NoLocation(noBaseCandidateReason(request, zoneId))
        }

        // Filter 3 tail: fold active reservation load into effective allocation, drop >= 100.
        val reservationLoad = reservationRepository.activeLoadByLocation(raw.mapNotNull { it.id }, Instant.now())
        val withCapacity = raw
            .map { it to effectiveAllocation(it, reservationLoad) }
            .filter { (_, eff) -> eff < ALLOCATION_FULL }
        if (withCapacity.isEmpty()) {
            return LocationFinderResult.NoLocation("all matching locations are full once active reservations are counted")
        }

        // Filter 7 (TypeCapacityConstraint compatibility+capacity) + filter 8 (client mixing,
        // static check) + filters 13/14 (Task 5, real occupancy + in-flight-transport
        // client/item mixing).
        val capacityResult = applyCapacityConstraints(withCapacity, request)
        val survivors = applyMixingConstraints(capacityResult.survivors, request, strategy)
        if (survivors.isEmpty()) {
            return LocationFinderResult.NoLocation(
                "matching locations were excluded by unit-load-type, client-mixing, or item-mixing constraints"
            )
        }

        // Filter 9 (Task 3): StorageArea restriction + FIFO/full-area hiding, ordered by
        // effective allocation ASC / name ASC, with area-order prepended when active.
        val areaFiltered = applyAreaConstraints(survivors, request, strategy)
        if (areaFiltered.isEmpty()) {
            return LocationFinderResult.NoLocation(
                "matching locations are outside the storage strategy's configured areas, or those areas are full/FIFO-hidden"
            )
        }

        return finalizeCandidates(areaFiltered, request, strategy, capacityResult, zoneId)
    }

    /**
     * Filter 10 (Task 7, field/section group lifting-capacity) through to the SPI
     * [LocationFilter] chain and reservation. Split out of [findCandidateLocation] purely to
     * keep that function's return count within the project's Detekt limit (same reasoning as
     * [manualSearchResult]/[resolveViaSpiFilters]'s splits).
     */
    private fun finalizeCandidates(
        areaFiltered: List<Pair<StorageLocation, BigDecimal>>,
        request: LocationFinderRequest,
        strategy: StorageStrategy?,
        capacityResult: CapacityFilterResult,
        zoneId: Long?,
    ): LocationFinderResult {
        val groupFiltered = applyGroupCapacityConstraints(areaFiltered, request)
        if (groupFiltered.isEmpty()) {
            return LocationFinderResult.NoLocation(
                "matching locations would exceed their location type's field/section lifting-capacity group cap"
            )
        }

        // Task 5: strategy.sorts comparator chain + nearPickingLocation distance, applied
        // BEFORE the SPI filter chain (see CandidateOrdering's KDoc for the regression pin —
        // an empty sorts list and no resolvable fix assignment leave this list untouched).
        val ordered = candidateOrdering.apply(groupFiltered, request, strategy, capacityResult.constraintsByType, zoneId)

        // Assign ordinals for the SPI contract (order already final post-ordering).
        val candidates = ordered.mapIndexed { idx, (loc, eff) -> toCandidate(loc, eff, idx) }
        return resolveViaSpiFilters(candidates, request)
    }

    /**
     * Applies the SPI [LocationFilter] chain (HONORING its returned order — no re-sort, see
     * class KDoc) and reserves + returns the winner, or [LocationFinderResult.NoLocation] if
     * every candidate was vetoed. Split out of [findCandidateLocation] purely to keep that
     * function's return count within the project's Detekt limit (same reasoning as
     * [manualSearchResult]'s split).
     */
    private fun resolveViaSpiFilters(candidates: List<LocationCandidate>, request: LocationFinderRequest): LocationFinderResult {
        val filtered = applyFilters(candidates, request)
        if (filtered.isEmpty()) {
            return LocationFinderResult.NoLocation("all candidates were vetoed by a LocationFilter extension")
        }
        val chosen = filtered.first()
        reserve(chosen.locationId, request.reservationKey)
        return LocationFinderResult.Found(chosen.locationId, chosen.locationName)
    }

    override fun priority(): Int = PutawayLocationStrategy.DEFAULT_BUILTIN_PRIORITY

    /** PT15: standalone reservation write — see [LocationFinder.reserve]'s KDoc. Also the
     *  same helper [resolveViaSpiFilters] uses internally after a search picks a winner. */
    @Transactional
    override fun reserve(locationId: Long, transportOrderId: Long) {
        reservationRepository.persist(
            LocationReservation().apply {
                this.locationId = locationId
                this.transportOrderId = transportOrderId
                percent = ALLOCATION_PER_UL
                expiresAt = Instant.now().plus(RESERVATION_TTL)
            }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Resolve the [StorageStrategy] to apply, but only if it belongs to the requesting client.
     *
     * **Resolution order:**
     * 1. [LocationFinderRequest.storageStrategyId], if supplied. Auto-putaway carries the receipt
     *    line's validated override and persists it on the transport order for later searches.
     * 2. Else, the incoming stock's product-level default —
     *    [com.karyo.product.dto.ProductResponse.defaultStorageStrategyId] via [productLookup],
     *    keyed off [LocationFinderRequest.itemDataId]. This activates strategy-driven finder
     *    behavior when no per-request or receipt-line override was supplied.
     * 3. Else `null` — **the system-default rung (myWMS's `StorageStrategyEntityService.getDefault()`,
     *    an auto-created fallback strategy) is deliberately NOT built here.** That is a bigger
     *    product decision (what an auto-created default strategy's fields should be, whether one
     *    is seeded per client) than this closing round's scope; see WORKLIST for the follow-up.
     *
     * Once an id is resolved (by either rung), it is *trusted*: `mixClient` drives filter 8
     * (client mixing), `zone` drives the zone predicate, and `manualSearch`/`onlyClientLocation`
     * drive this task's L6 flags. Without the ownership check below, naming (or inheriting, via
     * rung 2) another client's strategy with `mixClient = true` disables the segregation guard
     * outright and returns that client's dedicated locations. The sibling
     * [StorageStrategyService.findById] has always applied the same check — and it applies here
     * identically regardless of which rung produced the id, since both funnel through the same
     * `strategyRepository.findById(id)` + ownership comparison below.
     *
     * A foreign or missing strategy resolves to `null`, which falls back to
     * `DEFAULT_MIX_CLIENT = false` (segregation enforced) and no zone constraint — fail-closed.
     */
    private fun resolveStrategy(request: LocationFinderRequest): StorageStrategy? {
        val id = request.storageStrategyId ?: defaultStrategyId(request) ?: return null
        return resolveStrategyById(id, request.clientId)
    }

    /**
     * Rung 2 of [resolveStrategy]'s chain: the incoming stock's product-level default strategy,
     * via [ProductLookup] (already consumed by [ItemDataAreaService]/[FixAssignmentService] for
     * the same in-process, tenant-scoped, no-Gradle-edge pattern). `null` when the request
     * carries no `itemDataId` (empty/mixed-SKU unit load — same honest degradation as the area
     * logic) or the product itself has no default configured.
     */
    private fun defaultStrategyId(request: LocationFinderRequest): Long? =
        request.itemDataId?.let { productLookup.findById(it)?.defaultStorageStrategyId }

    /**
     * The ownership check [resolveStrategy]'s KDoc documents, extracted so [findAddToLocation]
     * (LF10, Task 7) shares it instead of duplicating it. Takes `id`/`requestClientId` directly
     * rather than a [LocationFinderRequest]: [findAddToLocation]'s [AddToLocationRequest] is a
     * different shape entirely, and the ownership rule only ever needed these two values. A
     * foreign or missing strategy resolves to `null` -- fail-closed, same as [resolveStrategy].
     */
    private fun resolveStrategyById(id: Long, requestClientId: Long?): StorageStrategy? {
        val strategy = strategyRepository.findById(id) ?: return null
        if (requestClientId == null) return strategy
        if (strategy.clientId != requestClientId) {
            log.warnf(
                "Ignoring storage strategy %d: belongs to client %d, request is for client %d",
                id, strategy.clientId, requestClientId,
            )
            return null
        }
        return strategy
    }

    private fun effectiveAllocation(loc: StorageLocation, reservationLoad: Map<Long, BigDecimal>): BigDecimal =
        loc.allocation + (reservationLoad[loc.id] ?: BigDecimal.ZERO)

    /**
     * Filter 7 — [TypeCapacityConstraint] compatibility + capacity (locations-layout sprint
     * Task 4). Batches ONE query for every distinct `locationType` among [withCapacity]
     * (never per-candidate — see [TypeCapacityConstraintRepository.findByLocationTypes]),
     * classifies each candidate via [capacityVerdict], and logs the oversize WARN (see
     * [CapacityVerdict.ExcludedOversize]) at most once per call. Also returns the batched
     * `constraintsByType` map itself (Task 5's `CAPACITY` sort key reuses it — never a
     * second query for the same data).
     */
    private fun applyCapacityConstraints(
        withCapacity: List<Pair<StorageLocation, BigDecimal>>,
        request: LocationFinderRequest,
    ): CapacityFilterResult {
        val locationTypeIds = withCapacity.map { it.first.locationType.id!! }.distinct()
        val constraintsByType = typeCapacityConstraintRepository.findByLocationTypes(locationTypeIds)
            .groupBy { it.locationType.id!! }
        if (constraintsByType.isEmpty()) return CapacityFilterResult(withCapacity, constraintsByType)

        var oversizeWarned = false
        val survivors = withCapacity.filter { (loc, eff) ->
            when (val verdict = capacityVerdict(loc, eff, request, constraintsByType)) {
                CapacityVerdict.Allowed -> true
                CapacityVerdict.Excluded -> false
                is CapacityVerdict.ExcludedOversize -> {
                    if (!oversizeWarned) {
                        oversizeWarned = true
                        logOversizeExclusion(verdict.constraint)
                    }
                    false
                }
            }
        }
        return CapacityFilterResult(survivors, constraintsByType)
    }

    /** Result of [applyCapacityConstraints]: the surviving candidates plus the batched
     * constraint map, threaded through to [CandidateOrdering] for the `CAPACITY` sort key. */
    private data class CapacityFilterResult(
        val survivors: List<Pair<StorageLocation, BigDecimal>>,
        val constraintsByType: Map<Long, List<TypeCapacityConstraint>>,
    )

    private fun logOversizeExclusion(constraint: TypeCapacityConstraint) {
        log.warnf(
            "TypeCapacityConstraint %d (locationType=%d, unitLoadType=%d, allocation=%s > 100) excludes " +
                "non-empty locations for this find — myWMS's multi-position oversize placement rule is not carried over",
            constraint.id, constraint.locationType.id, constraint.unitLoadTypeId, constraint.allocation,
        )
    }

    /**
     * Per-candidate compatibility classification against the batched constraint map.
     *
     * - No constraint rows at all for `loc.locationType` → [CapacityVerdict.Allowed] (today's
     *   behavior — the sprint's regression pin for an empty matrix).
     * - Rows exist but none match [LocationFinderRequest.unitLoadTypeId] (including when that
     *   field is `null`, i.e. the incoming UL's type is unknown) → [CapacityVerdict.Excluded].
     * - A matching row with `allocation <= 100` → allowed only when `eff <= 100 - allocation`
     *   (the same effective-allocation value filter 3 already computed — no extra query).
     * - A matching row with `allocation > 100` (myWMS oversize/multi-position) → Karyo
     *   **deliberately diverges** from the multi-position placement rule (it needs adjacent-slot
     *   semantics Karyo lacks): allowed only if the candidate is completely empty, otherwise
     *   [CapacityVerdict.ExcludedOversize] (an honest partial match — logged once by the caller).
     */
    private fun capacityVerdict(
        loc: StorageLocation,
        eff: BigDecimal,
        request: LocationFinderRequest,
        constraintsByType: Map<Long, List<TypeCapacityConstraint>>,
    ): CapacityVerdict {
        val rows = constraintsByType[loc.locationType.id!!] ?: return CapacityVerdict.Allowed
        val matching = rows.firstOrNull { it.unitLoadTypeId == request.unitLoadTypeId } ?: return CapacityVerdict.Excluded
        if (matching.allocation > ALLOCATION_FULL) {
            return if (eff <= BigDecimal.ZERO) CapacityVerdict.Allowed else CapacityVerdict.ExcludedOversize(matching)
        }
        return if (eff <= (ALLOCATION_FULL - matching.allocation)) CapacityVerdict.Allowed else CapacityVerdict.Excluded
    }

    /** Classification result for [capacityVerdict] — see that function's KDoc for the rules. */
    private sealed class CapacityVerdict {
        data object Allowed : CapacityVerdict()
        data object Excluded : CapacityVerdict()
        data class ExcludedOversize(val constraint: TypeCapacityConstraint) : CapacityVerdict()
    }

    /**
     * Filter 8 -- client mixing, cheap static check. When `mixClient == false`, a location
     * already dedicated to a different goods owner must be excluded. Reasons only from the
     * location's own `clientId` attribute: a non-shared location (clientId != 0) owned by a
     * *different* client is excluded; shared (0) and same-client locations are allowed. **The
     * v1.2 layout-local simplification this KDoc used to describe is CLOSED as of the
     * location-finder sprint's Task 5 (LF9/LF8, 2026-08-16):** this static check remains as the first, cheap gate,
     * but is no longer the whole story -- real occupancy-by-client (and item) mixing now runs
     * immediately after it, as filters 13/14 via [OccupancyMixReader], still before filter 9's
     * area constraints. See that class's KDoc for the batching and unscoped-source rationale.
     */
    private fun clientMixingAllowed(loc: StorageLocation, request: LocationFinderRequest, mixClient: Boolean): Boolean {
        if (mixClient) return true
        val requestClient = request.clientId ?: return true
        if (loc.clientId == 0L) return true
        return loc.clientId == requestClient
    }

    /**
     * Filter 8's static check, then filters 13/14 (Task 5, LF9 + LF8): real occupancy +
     * in-flight-transport client/item mixing via [OccupancyMixReader] -- ONE batched occupants
     * call and ONE batched demand call for the whole [candidates] set, never per-candidate.
     * Split out of [findCandidateLocation] purely to keep that function's return count within
     * the project's Detekt limit (same reasoning as [manualSearchResult]'s split).
     *
     * Passes [request]'s own `reservationKey` through as `requestingOrderId` -- row :1614's
     * self-exclusion guard: [OccupancyMixReader.blockedLocationIds]'s KDoc for why this is the
     * caller's own transport order id, not a fresh/unrelated correlation value.
     */
    private fun applyMixingConstraints(
        candidates: List<Pair<StorageLocation, BigDecimal>>,
        request: LocationFinderRequest,
        strategy: StorageStrategy?,
    ): List<Pair<StorageLocation, BigDecimal>> {
        val mixClient = strategy?.mixClient ?: DEFAULT_MIX_CLIENT
        val staticSurvivors = candidates.filter { (loc, _) -> clientMixingAllowed(loc, request, mixClient) }
        if (staticSurvivors.isEmpty()) return staticSurvivors

        val mixItem = strategy?.mixItem ?: DEFAULT_MIX_ITEM
        val blocked = occupancyMixReader.blockedLocationIds(
            locationIds = staticSurvivors.mapNotNull { (loc, _) -> loc.id }.toSet(),
            requestClientId = request.clientId,
            requestItemDataId = request.itemDataId,
            mixClient = mixClient,
            mixItem = mixItem,
            requestingOrderId = request.reservationKey,
        )
        return staticSurvivors.filter { (loc, _) -> loc.id !in blocked }
    }

    /**
     * Filter 9 — StorageArea restriction + hiding (Task 3). No areas configured on the
     * resolved strategy means NO restriction whatsoever — [survivors] passes through with just
     * the pre-existing effective-allocation/name sort (byte-for-byte the pre-Task-3 ordering;
     * this is the regression pin). Areas configured means candidates are narrowed to
     * [AreaConstraints.allowedClusterIds] (already net of any FIFO/full-area hidden areas —
     * see [AreaOccupancyReader]) and, when `useAreaStrategyDate` is active, area order is
     * prepended to the sort key.
     */
    private fun applyAreaConstraints(
        survivors: List<Pair<StorageLocation, BigDecimal>>,
        request: LocationFinderRequest,
        strategy: StorageStrategy?,
    ): List<Pair<StorageLocation, BigDecimal>> {
        val orderedAreas = strategy?.let { strategyAreaRepository.orderedByStrategy(it.id!!) } ?: emptyList()
        if (orderedAreas.isEmpty()) {
            return survivors.sortedWith(compareBy({ it.second }, { it.first.name }))
        }

        val constraints = areaOccupancyReader.resolve(
            orderedAreas = orderedAreas,
            useAreaStrategyDate = strategy?.useAreaStrategyDate ?: false,
            useItemDataArea = strategy?.useItemDataArea ?: false,
            itemDataId = request.itemDataId,
            strategyDate = request.strategyDate,
        )

        return survivors
            .filter { (loc, _) -> loc.locationCluster?.id?.let { it in constraints.allowedClusterIds } ?: false }
            .sortedWith(
                compareBy(
                    { (loc, _) -> loc.locationCluster?.id?.let { constraints.areaOrderByCluster[it] } ?: Int.MAX_VALUE },
                    { (_, eff) -> eff },
                    { (loc, _) -> loc.name },
                )
            )
    }

    /**
     * Filter 10 — field/section group lifting capacity (locations-layout sprint Task 7, L4).
     * Delegates entirely to [GroupCapacityReader] for the batched group-membership + weight
     * resolution; this method is just the candidate-list filter step, mirroring the shape of
     * [applyCapacityConstraints]/[applyAreaConstraints]. A no-op (zero queries) when none of
     * [candidates]' location types define either group cap.
     */
    private fun applyGroupCapacityConstraints(
        candidates: List<Pair<StorageLocation, BigDecimal>>,
        request: LocationFinderRequest,
    ): List<Pair<StorageLocation, BigDecimal>> {
        val groups = groupCapacityReader.resolve(candidates.map { it.first })
        return candidates.filter { (loc, _) -> groups.allows(loc, request.weight) }
    }

    private fun toCandidate(loc: StorageLocation, eff: BigDecimal, ordinal: Int): LocationCandidate =
        LocationCandidate(
            locationId = loc.id!!,
            locationName = loc.name,
            zoneId = loc.zone?.id,
            areaId = loc.area.id!!,
            locationTypeId = loc.locationType.id!!,
            effectiveAllocation = eff,
            clientId = loc.clientId,
            ordinal = ordinal,
        )

    private fun applyFilters(
        candidates: List<LocationCandidate>,
        request: LocationFinderRequest,
    ): List<LocationCandidate> {
        var current = candidates
        filters.sortedBy { it.priority() }.forEach { filter ->
            current = filter.apply(current, request)
            if (current.isEmpty()) return current
        }
        return current
    }

    private fun noBaseCandidateReason(request: LocationFinderRequest, zoneId: Long?): String {
        val area = if (request.pickingOnly) "PICKING" else "STORAGE"
        val zonePart = if (zoneId != null) " in zone $zoneId" else ""
        return "no unlocked $area location with free capacity$zonePart (weight ${request.weight})"
    }

    companion object {
        private const val CANDIDATE_FETCH_LIMIT = 200
        private val ALLOCATION_FULL = BigDecimal("100")
        private val ALLOCATION_PER_UL = BigDecimal("100")
        private val RESERVATION_TTL: Duration = Duration.ofMinutes(10)
        private const val DEFAULT_MIX_CLIENT = false
        // Mirrors StorageStrategy.mixItem's own default (true) for the no-strategy-resolved
        // case -- see OccupancyMixReader's regression pin for the item-mixing pass staying
        // inert by default.
        private const val DEFAULT_MIX_ITEM = true
        // Row 17 (defect-burndown-4, Task 11): flipped true -- a strategy-less putaway now
        // stays owner-scoped by default; reaching a shared (client_id=0) location is an
        // explicit opt-in (strategy.onlyClientLocation = false), never the silent fallback.
        private const val DEFAULT_ONLY_CLIENT_LOCATION = true
    }
}
