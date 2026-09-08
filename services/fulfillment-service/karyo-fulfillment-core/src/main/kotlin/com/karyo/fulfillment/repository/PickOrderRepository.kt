package com.karyo.fulfillment.repository

import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.vo.PickState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PickOrderRepository : PanacheRepository<PickOrder> {

    fun findByIdAndClient(id: Long, clientId: Long): PickOrder? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()

    /**
     * Task 2 (Bulk Allocation Sprint A, sort station): batched by-id lookup scoped to [clientId],
     * used by [com.karyo.fulfillment.service.WavePickService.pickedByLines] to resolve the
     * PickOrder owning each candidate Pick in one round trip. Empty input -> empty.
     */
    fun findByIds(ids: Collection<Long>, clientId: Long): List<PickOrder> =
        if (ids.isEmpty()) emptyList() else list("id in ?1 and clientId = ?2", ids, clientId)

    /**
     * Task 2 (Bulk Allocation Sprint A, sort station): the wave batch PickOrder (`deliveryOrderId`
     * null, `waveId` set) whose pick container is [unitLoadId] -- the cart a sort-station operator
     * scans to resolve "what's on this cart".
     */
    fun findBatchByTargetUnitLoad(unitLoadId: Long, clientId: Long): PickOrder? =
        find(
            "targetUnitLoadId = ?1 and deliveryOrderId is null and waveId is not null and clientId = ?2",
            unitLoadId, clientId,
        ).firstResult()

    fun findByClient(clientId: Long): List<PickOrder> =
        list("clientId = ?1", Sort.by("created").descending(), clientId)

    fun findByNumber(pickOrderNumber: String, clientId: Long): PickOrder? =
        find("pickOrderNumber = ?1 and clientId = ?2", pickOrderNumber, clientId).firstResult()

    fun findByDeliveryOrderId(deliveryOrderId: Long, clientId: Long): PickOrder? =
        find("deliveryOrderId = ?1 and clientId = ?2", deliveryOrderId, clientId).firstResult()

    /**
     * Task 8 review fix (register row 8, Critical): `createTypeOrders` can split one release into
     * more than one PickOrder, so [findByDeliveryOrderId] (singular, `firstResult()`) silently
     * picks an arbitrary sibling when more than one exists -- a real bug for `confirmPick`
     * completion, packing, and delivery-order cancel, all of which need EVERY sibling, not one.
     * [findByDeliveryOrderId] itself is left unchanged: its own callers (`PackingService`,
     * `DefaultPickCancelPort`) have been migrated to this method where the single-row assumption
     * was wrong; no other caller of the singular method remains that needed migrating.
     */
    fun findAllByDeliveryOrderId(deliveryOrderId: Long, clientId: Long): List<PickOrder> =
        list("deliveryOrderId = ?1 and clientId = ?2", deliveryOrderId, clientId)

    /**
     * Task 4 (wave bulk fulfillment): every PickOrder (both per-order COMPLETE and cross-order
     * batch) minted by [com.karyo.fulfillment.service.WavePickService.generateForWave] for
     * [waveId] -- the shared basis for [WaveTerminalChecker], `waveStats`, and
     * `cancelOpenForWave`.
     */
    fun findByWaveId(waveId: Long, clientId: Long): List<PickOrder> =
        list("waveId = ?1 and clientId = ?2", waveId, clientId)

    fun findClaimable(clientId: Long): List<PickOrder> =
        list("clientId = ?1 and state = ?2 and operatorId is null", clientId, PickState.RELEASED.code)

    fun findClaimedBy(clientId: Long, operatorId: String): List<PickOrder> =
        list("clientId = ?1 and operatorId = ?2 and state = ?3", clientId, operatorId, PickState.STARTED.code)

    /**
     * WORKLIST row 20 (V605): the OPEN (state <= RELEASED) EXTINGUISH order for [clientId], if
     * one exists — `deliveryOrderId is null` IS the EXT marker (see [com.karyo.fulfillment.domain.model.PickOrder]'s
     * KDoc). A second `extinguish` call merges into this order via `PickTopUpService.addPicksToOrder`
     * instead of minting a new one. `firstResult` tolerates the (accepted, unenforced) race of two
     * concurrent extinguish calls each finding none and minting their own — same non-guarantee as
     * every other find-or-create path in this module.
     */
    fun findOpenExtinguishOrder(clientId: Long): PickOrder? =
        find("clientId = ?1 and deliveryOrderId is null and state <= ?2", clientId, PickState.RELEASED.code)
            .firstResult()

    /**
     * Row 18, fulfillment half of [com.karyo.inventory.api.spi.PurgeBlockerLookup.blockedUnitLoadIds]:
     * distinct [PickOrder.targetUnitLoadId] among [unitLoadIds] referenced by ANY pick order for
     * [clientId] -- regardless of the pick order's own state. Unlike a pick or a transport
     * order, this reference is never treated as merely historical: see
     * [com.karyo.inventory.api.spi.PurgeBlockerLookup]'s KDoc. Batched over the whole candidate
     * set. Callers must not pass an empty collection.
     */
    fun findReferencedTargetUnitLoadIds(unitLoadIds: Collection<Long>, clientId: Long): Set<Long> =
        getEntityManager()
            .createQuery(
                "select distinct p.targetUnitLoadId from PickOrder p " +
                    "where p.targetUnitLoadId in :ids and p.clientId = :clientId",
                Long::class.java,
            )
            .setParameter("ids", unitLoadIds)
            .setParameter("clientId", clientId)
            .resultList
            .toSet()
}
