package com.karyo.layout.spi

/**
 * Read seam for area-usage-derived location sets.
 *
 * Two consumers, both in the replenishment sprint: `ReplenishmentService.scan` (R14, Task 3)
 * calls [pickingLocationIds] directly to derive each fix-face's own
 * `com.karyo.inventory.api.spi.SourceQuery.targetIsPickingFace`; `DefaultReplenishmentSourceSelector`
 * (R15, Task 4) consumes both: a candidate source stock's own location usage decides which
 * strictness rule applies (strict "reserved=0, non-mixed UL" for a non-picking/storage location
 * vs. the looser "amount > reservedAmount" for a picking location, reachable only when the caller
 * opts into `fromPicking`), and `targetIsPickingFace` itself now drives the storage-face-vs-
 * picking-face strictness split (Task 3 left it informational; Task 4 wired the read).
 *
 * A single-method mechanism port, not a strategy seam — deliberately narrow, mirroring
 * [FixAssignmentLookup]'s shape rather than [LocationLockPort]'s (whose own KDoc flags that it
 * "has grown into the general... seam" as a pattern later additions should be wary of repeating).
 */
interface LocationAreaUsageLookup {
    /**
     * Ids of every [com.karyo.layout.domain.model.StorageLocation] owned by [clientId] whose
     * [com.karyo.layout.domain.model.Area.usages] contains `PICKING` — mirrors
     * [com.karyo.layout.repository.StorageLocationRepository.findPutawayCandidates]'s
     * `area.usages LIKE '%<token>%'` predicate. Tenant-scoped by strict `clientId` equality (no
     * `client_id = 0` shared fallback), the same convention [LocationLockPort]'s methods use —
     * and deliberately an explicit parameter rather than an ambient `TenantContext` read: this is
     * reachable from `ReplenishmentScheduler`'s `@Scheduled` multi-tenant loop, where request
     * scope IS active (no `ContextNotActiveException`) but never PRIMED with a real tenant, so
     * an ambient read would silently see the unassigned default (`clientId = 0`) rather than
     * fail loudly (see that class's KDoc for the full correction). Taking `clientId` explicitly
     * makes that ambient state irrelevant to this SPI regardless of caller thread.
     */
    fun pickingLocationIds(clientId: Long): Set<Long>

    /**
     * Ids of every [com.karyo.layout.domain.model.StorageLocation] owned by [clientId] whose
     * [com.karyo.layout.domain.model.Area.usages] contains `CROSS_DOCK_STAGING` -- mirrors
     * [pickingLocationIds]'s `area.usages LIKE '%<token>%'` predicate and tenant-scoping
     * convention (strict `clientId` equality, no `client_id = 0` shared fallback).
     */
    fun crossDockStagingLocationIds(clientId: Long): Set<Long>
}
