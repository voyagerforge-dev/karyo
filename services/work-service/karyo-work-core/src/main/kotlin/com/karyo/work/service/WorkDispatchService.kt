package com.karyo.work.service

import com.karyo.layout.spi.WorkingAreaLookup
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkItem
import com.karyo.work.dto.WorkRef
import com.karyo.work.exception.UnknownWorkingAreaException
import com.karyo.work.exception.WorkClaimConflictException
import com.karyo.work.spi.WorkDispatchStrategy
import com.karyo.work.spi.WorkEligibilityResolver
import com.karyo.work.spi.WorkProvider
import com.karyo.work.vo.WorkType
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

@ApplicationScoped
class WorkDispatchService @Inject constructor(
    private val providers: Instance<WorkProvider>,
    private val strategies: Instance<WorkDispatchStrategy>,
    private val resolvers: Instance<WorkEligibilityResolver>,
    private val workingAreaLookup: WorkingAreaLookup,
    private val dispatchConfig: WorkDispatchConfig,
) {
    // Test-friendly secondary ctor -- same shape as ExponentialBackoffRetryPolicy's. A first
    // pass tried a Kotlin DEFAULT VALUE on the primary constructor's last param instead of this
    // secondary ctor -- that compiles to a SECOND, synthetic constructor (the $default
    // bitmask-flag overload). With two constructors present, ArC boot failed outright ("does not
    // declare a valid bean constructor") until `@Inject` was added to the primary one, which
    // *did* then boot -- but only because @Inject happens to break the tie between two
    // constructors ArC otherwise can't choose between, an unspecified/undocumented resolution
    // detail to depend on, not a real fix for the ambiguity. An explicit secondary constructor
    // (below), by contrast, is real Kotlin/JVM constructor overloading: no synthetic bitmask
    // constructor is generated at all, so ArC has exactly ONE constructor in the class to begin
    // with -- no tiebreak needed. It exists purely so WorkDispatchServiceTest's pre-existing
    // 4-arg direct constructions (plain unit tests, no CDI) stay source-compatible; CDI itself
    // always goes through the primary constructor and supplies the real WorkDispatchConfig bean,
    // which reads the config live -- see its KDoc.
    constructor(
        providers: Instance<WorkProvider>,
        strategies: Instance<WorkDispatchStrategy>,
        resolvers: Instance<WorkEligibilityResolver>,
        workingAreaLookup: WorkingAreaLookup,
    ) : this(providers, strategies, resolvers, workingAreaLookup, WorkDispatchConfig("STRICT_PRIORITY"))

    /** Fails app boot when karyo.work.dispatch-strategy names no registered bean, or two beans
     *  share a name. Boot-time (not only dispatch-time) because the name is DEPLOYMENT CONFIG,
     *  fixed for the process lifetime — unlike CountScopeStrategyResolver's per-request
     *  caller-supplied name, where request-time 422 is the only possible answer. The
     *  dispatch-time resolution in [strategy] stays as the belt. This is the repo's FIRST
     *  `StartupEvent` use. */
    fun validateOnStartup(@Observes ignored: StartupEvent) {
        strategy()
        checkForDuplicateStrategyNames()
    }

    private fun checkForDuplicateStrategyNames() {
        val duplicates = strategies.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            error(
                "Duplicate WorkDispatchStrategy name(s) $duplicates -- two beans registering the same " +
                    "name resolve in CDI discovery order; rename one so selection is deterministic.",
            )
        }
    }

    /**
     * Resolves the ACTIVE [WorkDispatchStrategy] by name against
     * [WorkDispatchConfig.dispatchStrategyName] (St6). Mirrors
     * [com.karyo.stocktaking.service.CountScopeStrategyResolver]'s shape: a name that answers to
     * no registered bean is a hard failure (`error` -> uncaught -> 500), never a silent fallback
     * to some other registered strategy — a lower-priority bean must not be able to take over
     * the default just by being deployed (that was the pre-St6 bug: `minByOrNull { it.priority }`
     * let ANY custom strategy under `Int.MAX_VALUE` silently win).
     *
     * Boot validation is primary ([validateOnStartup]); dispatch-time resolution here stays as
     * the belt. The name is deployment config, fixed for the process lifetime.
     */
    private fun strategy(): WorkDispatchStrategy =
        strategies.firstOrNull { it.name == dispatchConfig.dispatchStrategyName }
            ?: error(
                "No WorkDispatchStrategy registered for name '${dispatchConfig.dispatchStrategyName}' " +
                    "(karyo.work.dispatch-strategy / KARYO_WORK_DISPATCH_STRATEGY) -- refusing to silently " +
                    "fall back to a different registered strategy; register a bean whose `name` matches, " +
                    "or fix the configured value.",
            )

    private fun resolver(): WorkEligibilityResolver = resolvers.minByOrNull { it.priority }!!

    private fun providerFor(type: WorkType): WorkProvider =
        providers.first { it.workTypes().contains(type) }

    private fun effectiveTypes(operatorId: String, clientId: Long, filter: WorkFilter): Set<WorkType> {
        val eligible = resolver().resolve(operatorId, clientId)
        return filter.types?.let { eligible.intersect(it) } ?: eligible
    }

    /**
     * CONTINGENT working-area narrowing (Task 8). `null` is "no constraint" (pass through
     * unchanged — the regression pin for pre-existing callers). Items lacking a
     * `primaryLocationId` are dropped, not passed through, whenever a working area IS given
     * — see `WorkItem.primaryLocationId` KDoc for why.
     */
    private fun applyWorkingArea(items: List<WorkItem>, workingAreaId: Long?): List<WorkItem> {
        if (workingAreaId == null) return items
        if (!workingAreaLookup.exists(workingAreaId)) throw UnknownWorkingAreaException(workingAreaId)
        val locationIds = workingAreaLookup.locationIdsFor(workingAreaId)
        return items.filter { it.primaryLocationId != null && it.primaryLocationId in locationIds }
    }

    private fun poolFor(operatorId: String, clientId: Long, filter: WorkFilter): List<WorkItem> {
        val types = effectiveTypes(operatorId, clientId, filter)
        if (types.isEmpty()) return emptyList()
        val merged = providers
            .filter { it.workTypes().intersect(types).isNotEmpty() }
            .flatMap { it.listOpen(WorkFilter(types, filter.zones)) }
        val scoped = applyWorkingArea(merged, filter.workingAreaId)
        return strategy().order(scoped)
    }

    fun available(operatorId: String, clientId: Long, filter: WorkFilter): List<WorkItem> =
        poolFor(operatorId, clientId, filter)

    fun getNext(operatorId: String, clientId: Long, filter: WorkFilter): WorkItem? {
        for (item in poolFor(operatorId, clientId, filter)) {
            try {
                return providerFor(item.ref.type).claim(item.ref, operatorId)
            } catch (e: WorkClaimConflictException) {
                continue // lost the race; try the next candidate
            }
        }
        return null
    }

    fun mine(operatorId: String): List<WorkItem> =
        strategy().order(providers.flatMap { it.listClaimedBy(operatorId) })

    fun eligibleTypes(operatorId: String, clientId: Long): Set<WorkType> = resolver().resolve(operatorId, clientId)

    fun claimSpecific(ref: WorkRef, operatorId: String): WorkItem =
        providerFor(ref.type).claim(ref, operatorId)

    fun release(ref: WorkRef, operatorId: String, asManager: Boolean = false) =
        providerFor(ref.type).release(ref, operatorId, asManager)
}
