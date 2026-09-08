package com.karyo.inventory.service

import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Row 18: opt-in scheduled sweep hard-deleting DELETABLE(1000) stock units past their SC16
 * retention window, then the unit loads they leave empty. See [StockPurgeService]'s KDoc for
 * why this cannot reuse [UnitLoadService.delete], and
 * [com.karyo.inventory.api.spi.PurgeBlockerLookup]'s KDoc for what counts as a live reference.
 *
 * Defaults OFF ([enabled] = false): this is the one irreversible operation in this codebase, so
 * an existing deployment must opt in explicitly (`KARYO_INVENTORY_PURGE=true`).
 *
 * No `TenantContext` priming here, deliberately -- same doctrine as
 * [com.karyo.replenishment.service.ReplenishmentScheduler] and
 * [com.karyo.monitors.service.MonitorEvaluator] (see either KDoc for the bug this prevents: a
 * `@Scheduled` thread has request scope active but never PRIMED with a real tenant, so an
 * ambient TenantContext read silently sees clientId 0, not an exception). Every hop on
 * [StockPurgeService.purge]'s call graph -- every
 * [com.karyo.inventory.api.spi.PurgeBlockerLookup] implementation and every repository method
 * they call -- takes `clientId` as an explicit parameter; this was walked by hand (task 6 recon)
 * to confirm none of them reads TenantContext. [StockPurgeSchedulerIntegrationTest] is the
 * real-bean regression guard a mocked-collaborator unit test cannot provide, the same shape as
 * the other two schedulers' integration tests.
 *
 * The tenant set is the UNION of [StockUnitRepository.clientIdsWithDeletableStock] and
 * [UnitLoadRepository.clientIdsWithEmptyTerminal] (I2, defect-burndown-5): a client whose only
 * remaining purge work is an empty terminal unit load -- no DELETABLE stock candidate left --
 * would otherwise never be visited at all, and a unit load blocked on one tick would never get
 * a retry once the block cleared. Both queries are deliberately unscoped, the same shape as
 * `TenantEnumerator.activeClientIds`/`FixAssignmentRepository.distinctClientIds`.
 *
 * Split-body ([scheduled] vs [runOnce]) so `StockPurgeSchedulerIntegrationTest` can call
 * [runOnce] directly without waiting on the timer -- same shape as the other two schedulers.
 */
@ApplicationScoped
class StockPurgeScheduler(
    private val purgeService: StockPurgeService,
    private val stockUnitRepository: StockUnitRepository,
    private val unitLoadRepository: UnitLoadRepository,
    @ConfigProperty(name = "karyo.inventory.purge.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    private val log = Logger.getLogger(StockPurgeScheduler::class.java)

    @Scheduled(every = "{karyo.inventory.purge.interval}", concurrentExecution = ConcurrentExecution.SKIP)
    fun scheduled() = runOnce()

    /** The scheduled body, directly callable from tests without waiting on the timer. */
    fun runOnce() {
        if (!enabled) return
        val clientIds = stockUnitRepository.clientIdsWithDeletableStock().toSet() +
            unitLoadRepository.clientIdsWithEmptyTerminal(UnitLoadTerminator.GONE_STATES).toSet()
        for (clientId in clientIds) {
            try {
                purgeService.purge(clientId)
            } catch (e: Exception) {
                log.warnf(e, "DELETABLE reaper failed for client %d", clientId)
            }
        }
    }
}
