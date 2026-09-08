package com.karyo.inventory.service

import com.karyo.auth.spi.RuntimePropertyLookup
import com.karyo.events.outbox.OutboxService
import com.karyo.inventory.api.event.StockUnitPurgedEvent
import com.karyo.inventory.api.event.UnitLoadPurgedEvent
import com.karyo.inventory.api.spi.PurgeBlockerLookup
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The SC16 catalog key the per-client retention window lives under. */
private const val RETENTION_DAYS_KEY = "karyo.inventory.purge.retention-days"
private const val DEFAULT_RETENTION_DAYS = 30

/**
 * Row 18 fix round 1 (Important 2): the floor applied to whatever [RETENTION_DAYS_KEY] resolves
 * to, at the point of use in [StockPurgeService.purge]. `SystemPropertyService.validate` accepts
 * 0 and negative values for an INTEGER catalog property -- it has no domain-specific knowledge
 * that this particular key backs an irreversible delete -- so an operator (SYS-level write only;
 * `ownerWritable = false`) could otherwise collapse the grace period to zero for every
 * already-DELETABLE row of a tenant on the very next tick. An absent or unparseable stored value
 * still fails safe to [DEFAULT_RETENTION_DAYS] (that ladder is [RuntimePropertyLookup]'s, not
 * touched here); this floor only clamps an explicitly-stored value that undercuts the whole
 * point of having a retention window at all.
 */
private const val MIN_RETENTION_DAYS = 1

/** [StockPurgeService.purge]'s result: how many rows it actually removed, how many
 *  otherwise-eligible candidates a live reference kept alive this tick, and how many candidates
 *  [failed] their own per-candidate transaction (I3, defect-burndown-5) -- those are neither
 *  removed nor [blocked]; they retry on the next tick, same as an uncapped remainder. */
data class PurgeResult(val stockUnits: Int, val unitLoads: Int, val blocked: Int, val failed: Int = 0)

/**
 * Row 18 (myWMS `cleanupDeleted`): hard-removes stock units left in DELETABLE(1000) past the
 * retention window, then the unit loads they leave empty.
 *
 * `StockState.DELETABLE(1000)` is a real lifecycle state and terminal entities have a
 * physical-removal mechanism. The public contract is
 * `docs/functional/inventory-operations.md#5-stock-lifecycle-and-purge`. Stock-unit cleanup is
 * explicit because `stock_units.unit_load_id` is a real foreign key, so stock rows must be
 * removed before their unit load can be removed.
 *
 * This deliberately does not call [UnitLoadService.delete]. That method counts dependents with
 * `StockUnitRepository.findByUnitLoadId` and does not filter by state, so it throws
 * `HasDependents` on precisely the unit loads [UnitLoadTerminator.trashIfEmpty] produces (their
 * soft-deleted stock rows still physically exist until this service removes them). The ordering
 * here -- stock rows first, then the unit load -- is what makes the removal possible at all.
 *
 * Order of operations per [clientId] (rewritten in fix round 2, I3, defect-burndown-5 --
 * see adjudication A9): read the retention window from SC16, select up to [batchSize]
 * DELETABLE candidates older than the cutoff, subtract every [PurgeBlockerLookup]'s
 * [PurgeBlockerLookup.blockedStockUnitIds] (ONE call per lookup for the whole batch, never
 * per-candidate -- batching discipline holds even though the deletes below no longer share a
 * transaction with this read), then journal + outbox + delete each survivor in its OWN new JTA
 * transaction ([QuarkusTransaction.requiringNew]). Then do the same for up to [batchSize]
 * terminal, zero-stock unit loads and [PurgeBlockerLookup.blockedUnitLoadIds]. A blocked
 * candidate is counted and skipped without ever starting a transaction for it. A candidate whose
 * own transaction throws is caught, logged at WARN, and counted in [PurgeResult.failed] --
 * neither purged nor blocked, so it is offered again (re-selected by the same query) on the next
 * `purge(clientId)` call, and a single consistently-failing candidate wedges only itself, never
 * the rest of the tenant's tick. [purge] itself carries no `@Transactional` of its own: the read
 * phase (candidate selection + blocker unions) is plain queries, and every write is inside one
 * of the per-candidate transactions instead. One consequence of that split, verified empirically
 * while building it: the read phase's persistence context does not automatically learn about a
 * delete performed inside a per-candidate [QuarkusTransaction.requiringNew] transaction, so a
 * successfully purged entity is explicitly `detach()`ed from the read phase's own context right
 * after -- otherwise its identity map would keep serving the now-deleted row to any later by-id
 * lookup within the same call.
 *
 * **Rows :1537/M5 (defect-burndown-5, adjudication A1):** before this sprint, shipped stock
 * never reached DELETABLE at all -- [DefaultStockPicker.shipContainer] left it at SHIPPED(680)
 * forever, so [StockUnitRepository.findPurgeCandidates]'s `state = DELETABLE` filter could never
 * select it and this reaper's stock-unit half was unreachable for anything that had shipped.
 * `shipContainer` now promotes SHIPPED stock to DELETABLE in the same transaction it ships, and
 * that promotion is what stamps `modified` -- so the retention window computed above now reads
 * exactly as "shipped stock purges N days after ship" for that stock, the same semantics it
 * already had for stock gone DELETABLE via [StockService.deleteStock] or a zero-count. Once the
 * stock rows are purged, the unit load they leave empty is picked up as a terminal-empty
 * candidate on a later tick, same as any other producer.
 *
 * **Unit-load half retry, fixed in round 2 (I2, defect-burndown-5).** [StockPurgeScheduler]'s
 * tenant loop used to be built only from [StockUnitRepository.clientIdsWithDeletableStock], so a
 * client whose only remaining purge work was a blocked terminal unit load (a live carrier child,
 * a shipping-unit reference, ...) was never revisited once the block cleared -- the unit-load
 * phase below was unreachable on any tick where that client had no DELETABLE stock candidate
 * left. [StockPurgeScheduler] now unions that tenant set with
 * [UnitLoadRepository.clientIdsWithEmptyTerminal], so every client with EITHER kind of purge
 * work is visited every tick, and a blocked unit load gets the same next-tick retry the
 * stock-unit half always had. See `docs/WORKLIST.md` register row I2.
 *
 * Takes [clientId] explicitly, never an ambient TenantContext read: it is called from a
 * `@Scheduled` tenant loop ([StockPurgeScheduler]) which never primes one. See
 * `StockPurgeSchedulerIntegrationTest`.
 */
@ApplicationScoped
class StockPurgeService(
    private val stockUnitRepository: StockUnitRepository,
    private val unitLoadRepository: UnitLoadRepository,
    private val journalService: JournalService,
    private val outboxService: OutboxService,
    private val runtimeProperties: RuntimePropertyLookup,
    private val blockerLookups: Instance<PurgeBlockerLookup>,
    @ConfigProperty(name = "karyo.inventory.purge.batch-size", defaultValue = "500")
    private val batchSize: Int,
) {
    private val log = Logger.getLogger(StockPurgeService::class.java)

    fun purge(clientId: Long): PurgeResult {
        val resolvedRetentionDays = runtimeProperties.getInt(RETENTION_DAYS_KEY, clientId, DEFAULT_RETENTION_DAYS)
        // Fix round 1 (Important 2): floor applied at the point of use -- see MIN_RETENTION_DAYS's KDoc.
        val retentionDays = maxOf(MIN_RETENTION_DAYS, resolvedRetentionDays)
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)

        val stockCandidates = stockUnitRepository.findPurgeCandidates(clientId, cutoff, batchSize)
        val blockedStockIds = unionBlockedStockUnitIds(stockCandidates.map { it.id!! }, clientId)
        val stockSurvivors = stockCandidates.filterNot { it.id in blockedStockIds }
        var stockPurged = 0
        var failed = 0
        for (su in stockSurvivors) {
            if (purgeStockUnitInNewTransaction(su)) {
                stockPurged++
                // I3 note (verified empirically during this fix): su was read by THIS method's
                // own (non-transactional, "ambient") read phase. That read phase's persistence
                // context does not automatically learn about a delete performed inside
                // QuarkusTransaction.requiringNew() -- without this explicit detach, the ambient
                // context's identity map keeps serving su as still-present, so a later by-id
                // lookup within the SAME purge(clientId) call (or a caller sharing this request
                // scope) would silently return the stale, already-deleted instance instead of
                // re-querying the now-empty row.
                stockUnitRepository.getEntityManager().detach(su)
            } else {
                failed++
            }
        }

        val unitLoadCandidates = unitLoadRepository.findEmptyTerminal(clientId, UnitLoadTerminator.GONE_STATES, batchSize)
        val blockedUnitLoadIds = unionBlockedUnitLoadIds(unitLoadCandidates.map { it.id!! }, clientId)
        val unitLoadSurvivors = unitLoadCandidates.filterNot { it.id in blockedUnitLoadIds }
        var unitLoadPurged = 0
        for (ul in unitLoadSurvivors) {
            if (purgeUnitLoadInNewTransaction(ul)) {
                unitLoadPurged++
                // Same ambient-vs-transactional session staleness as the stock-unit loop above.
                unitLoadRepository.getEntityManager().detach(ul)
            } else {
                failed++
            }
        }

        return PurgeResult(
            stockUnits = stockPurged,
            unitLoads = unitLoadPurged,
            blocked = blockedStockIds.size + blockedUnitLoadIds.size,
            failed = failed,
        )
    }

    /** Every [PurgeBlockerLookup] bean is called with the WHOLE candidate batch, never once per
     *  candidate; an empty batch short-circuits without calling any lookup at all. */
    private fun unionBlockedStockUnitIds(candidateIds: Collection<Long>, clientId: Long): Set<Long> {
        if (candidateIds.isEmpty()) return emptySet()
        return blockerLookups.flatMap { it.blockedStockUnitIds(candidateIds, clientId) }.toSet()
    }

    /** Unit-load half of [unionBlockedStockUnitIds] -- same batching contract. */
    private fun unionBlockedUnitLoadIds(candidateIds: Collection<Long>, clientId: Long): Set<Long> {
        if (candidateIds.isEmpty()) return emptySet()
        return blockerLookups.flatMap { it.blockedUnitLoadIds(candidateIds, clientId) }.toSet()
    }

    /**
     * I3 (adjudication A9): runs [purgeStockUnit] inside its own new JTA transaction so a
     * failure here rolls back only this one candidate's journal/outbox/delete, never the rest
     * of [purge]'s batch. Returns whether it succeeded; a failure is logged at WARN and left for
     * the next tick's `findPurgeCandidates` to re-select -- it is not re-thrown.
     */
    private fun purgeStockUnitInNewTransaction(su: StockUnit): Boolean =
        try {
            QuarkusTransaction.requiringNew().run { purgeStockUnit(su) }
            true
        } catch (e: Exception) {
            log.warnf(e, "Failed to purge stock unit %d for client %d; leaving for the next tick", su.id, su.clientId)
            false
        }

    /** Unit-load half of [purgeStockUnitInNewTransaction] -- same isolation contract. */
    private fun purgeUnitLoadInNewTransaction(ul: UnitLoad): Boolean =
        try {
            QuarkusTransaction.requiringNew().run { purgeUnitLoad(ul) }
            true
        } catch (e: Exception) {
            log.warnf(e, "Failed to purge unit load %d for client %d; leaving for the next tick", ul.id, ul.clientId)
            false
        }

    /**
     * `su`'s fields, including `su.unitLoad.labelId`/`storageLocationName`, were already loaded
     * by [StockUnitRepository.findPurgeCandidates]'s `join fetch`, so nothing here triggers a
     * lazy load. The delete goes through `stockUnitRepository.delete(su)` (an
     * `EntityManager.remove`), not a bulk `deleteById` (`DELETE ... WHERE id = ?`) -- tried and
     * reverted during this fix: a bulk delete issues the right SQL, but it operates purely at
     * the database level and never touches ANY persistence context's identity map, including
     * [purge]'s own read-phase session -- see that method's `detach()` call for why the read
     * phase must be told about a successful delete explicitly regardless of which delete style
     * is used here.
     */
    private fun purgeStockUnit(su: StockUnit) {
        journalService.recordStockUnitPurged(su)
        outboxService.publish(
            aggregateType = "StockUnit",
            aggregateId = su.id!!,
            eventType = "StockUnitPurged",
            payload = StockUnitPurgedEvent(
                stockUnitId = su.id!!,
                itemDataId = su.itemDataId,
                itemDataNumber = su.itemDataNumber,
                unitLoadId = su.unitLoad.id!!,
                amount = su.amount,
            ),
            tenantId = su.clientId,
        )
        stockUnitRepository.delete(su)
    }

    /** Unit-load half of [purgeStockUnit] -- same `remove()`-over-bulk-`deleteById` reasoning. */
    private fun purgeUnitLoad(ul: UnitLoad) {
        journalService.recordUnitLoadPurged(ul.clientId, ul.labelId, ul.storageLocationName)
        outboxService.publish(
            aggregateType = "UnitLoad",
            aggregateId = ul.id!!,
            eventType = "UnitLoadPurged",
            payload = UnitLoadPurgedEvent(
                unitLoadId = ul.id!!,
                labelId = ul.labelId,
            ),
            tenantId = ul.clientId,
        )
        unitLoadRepository.delete(ul)
    }
}
