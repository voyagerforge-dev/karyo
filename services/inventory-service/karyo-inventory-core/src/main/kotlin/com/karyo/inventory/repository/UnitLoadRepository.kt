package com.karyo.inventory.repository

import com.karyo.inventory.domain.model.UnitLoad
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

@ApplicationScoped
class UnitLoadRepository : PanacheRepository<UnitLoad> {

    fun findByLabelId(labelId: String): UnitLoad? =
        find("labelId", labelId).firstResult()

    /**
     * PESSIMISTIC_WRITE load for mutation paths (SELECT ... FOR UPDATE). All of
     * [com.karyo.inventory.service.UnitLoadService]'s check-then-act guards (setCarrier's
     * busy-check, changeClient's assertUnencumbered, transferToCarrier's isCarrier read)
     * are only sound while the row is locked for the duration of the transaction.
     */
    fun findByIdForUpdate(id: Long): UnitLoad? = findById(id, LockModeType.PESSIMISTIC_WRITE)

    fun findByStorageLocationId(locationId: Long): List<UnitLoad> =
        list("storageLocationId", locationId)

    fun findByCarrierUnitLoadId(carrierId: Long): List<UnitLoad> =
        list("carrierUnitLoad.id", carrierId)

    fun countByUnitLoadTypeId(unitLoadTypeId: Long): Long =
        count("unitLoadType.id", unitLoadTypeId)

    /**
     * Row :1411 (defect-burndown-5): every unit load of [unitLoadTypeId] -- backs
     * [com.karyo.inventory.service.UnitLoadTypeService.update]'s tare-change batch recompute
     * (one query, feeding [com.karyo.inventory.service.UnitLoadWeightCalculator.recalculateAll]
     * in the same transaction). Unfiltered by tenant, same as [countByUnitLoadTypeId]: a unit
     * load type is a shared catalog row, not owned by one client.
     */
    fun findByUnitLoadTypeId(unitLoadTypeId: Long): List<UnitLoad> =
        list("unitLoadType.id", unitLoadTypeId)

    /**
     * Row 18 purge candidates: terminal ([states] -- `UnitLoadTerminator.GONE_STATES`) unit
     * loads for [clientId] now holding zero physical [com.karyo.inventory.domain.model.StockUnit]
     * rows AND not still the carrier of a live child unit load. Callers run this AFTER this
     * tick's stock-unit purge phase, so "zero rows" reflects that deletion, not a stale
     * pre-purge snapshot. Entities, not ids: the caller (`StockPurgeService`) reads
     * `labelId`/`storageLocationName` for the journal row it writes before each delete.
     *
     * Row 18 fix round 1 (Critical 2): `unit_loads.carrier_unit_load_id` is a SECOND real
     * foreign key (self-referencing, inventory V102, backing
     * [com.karyo.inventory.service.UnitLoadService.transferToCarrier]'s nested-container
     * feature) -- `stock_units.unit_load_id` is not the only one, and
     * [com.karyo.inventory.api.spi.PurgeBlockerLookup]'s KDoc was wrong to say so. Without this
     * `not exists` clause a terminal, stock-empty carrier still holding a live child would pass
     * every candidate/blocker check here, then hit the FK constraint at delete time and roll
     * back the WHOLE transactional `purge(clientId)` call for that tenant's tick -- the same
     * guard [com.karyo.inventory.service.UnitLoadService.delete] already has via
     * [findByCarrierUnitLoadId], carried across here so the reaper can't reintroduce the bug
     * that method was written to prevent. Deliberately unscoped by client, same as
     * [findByCarrierUnitLoadId] itself: nesting is a physical fact independent of ownership.
     */
    /**
     * Row 18 fix round 2 (I3, defect-burndown-5): [limit] caps this query too -- same
     * `karyo.inventory.purge.batch-size` bound [StockUnitRepository.findPurgeCandidates] gets,
     * so a unit-load-heavy tick is bounded the same way a stock-unit-heavy one is.
     */
    fun findEmptyTerminal(clientId: Long, states: Collection<Int>, limit: Int): List<UnitLoad> =
        getEntityManager()
            .createQuery(
                """
                select ul from UnitLoad ul
                where ul.clientId = :clientId and ul.state in :states
                  and not exists (select 1 from StockUnit su where su.unitLoad.id = ul.id)
                  and not exists (select 1 from UnitLoad child where child.carrierUnitLoad.id = ul.id)
                """.trimIndent(),
                UnitLoad::class.java,
            )
            .setParameter("clientId", clientId)
            .setParameter("states", states)
            .setMaxResults(limit)
            .resultList

    /**
     * Row 18 fix round 2 (I2, defect-burndown-5): [findEmptyTerminal]'s own candidate set,
     * projected down to distinct client ids -- same reapable predicate (terminal [states], zero
     * physical stock rows, not still a live carrier), same unscoped native-query shape as
     * [StockUnitRepository.clientIdsWithDeletableStock]. Exists because
     * [StockUnitRepository.clientIdsWithDeletableStock] alone cannot see a client whose ONLY
     * remaining purge work is an empty terminal unit load: [StockPurgeScheduler.runOnce]'s
     * tenant loop is built from that query, so before this method existed a client with no
     * DELETABLE stock candidate was never visited at all, and a unit load blocked on one tick
     * (a live carrier child, a shipping-unit reference, …) was never revisited once the block
     * cleared -- see `docs/WORKLIST.md` register row I2. [StockPurgeScheduler] unions this
     * method's result with [StockUnitRepository.clientIdsWithDeletableStock]'s.
     */
    fun clientIdsWithEmptyTerminal(states: Collection<Int>): List<Long> =
        getEntityManager()
            .createNativeQuery(
                """
                SELECT DISTINCT ul.client_id FROM karyo.unit_loads ul
                WHERE ul.state IN (:states)
                  AND NOT EXISTS (SELECT 1 FROM karyo.stock_units su WHERE su.unit_load_id = ul.id)
                  AND NOT EXISTS (SELECT 1 FROM karyo.unit_loads c WHERE c.carrier_unit_load_id = ul.id)
                """.trimIndent(),
            )
            .setParameter("states", states)
            .resultList
            .map { (it as Number).toLong() }
}
