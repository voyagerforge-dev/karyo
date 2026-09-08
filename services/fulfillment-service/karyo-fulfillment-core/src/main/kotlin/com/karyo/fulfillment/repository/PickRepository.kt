package com.karyo.fulfillment.repository

import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.vo.PickState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Tuple

@ApplicationScoped
class PickRepository : PanacheRepository<Pick> {

    fun findByPickOrderId(pickOrderId: Long): List<Pick> =
        list("pickOrderId = ?1 order by id asc", pickOrderId)

    /**
     * Task 2 (Bulk Allocation Sprint A, sort station): PICKED picks referencing [lineIds] --
     * caller ([WavePickService.pickedByLines]) filters the result down to batch PickOrders
     * (`deliveryOrderId == null`, `waveId` set); per-order PickOrders never pass the put wall.
     * Empty input -> empty, callers must not otherwise pass an empty collection.
     */
    fun findPickedByLineIds(lineIds: Collection<Long>, clientId: Long): List<Pick> =
        if (lineIds.isEmpty()) emptyList()
        else list(
            "deliveryOrderLineId in ?1 and state = ?2 and clientId = ?3",
            lineIds, PickState.PICKED.code, clientId,
        )

    /**
     * Task 4 (wave bulk fulfillment): every Pick belonging to any of [pickOrderIds] -- the
     * batched sibling of [findByPickOrderId] used by [WaveTerminalChecker] and
     * [com.karyo.fulfillment.service.WavePickService.waveStats], which both need every pick of
     * every one of a wave's PickOrders (batch and per-order alike) in one round trip rather than
     * N queries. Callers must not pass an empty collection.
     */
    fun findByPickOrderIds(pickOrderIds: Collection<Long>): List<Pick> =
        if (pickOrderIds.isEmpty()) emptyList() else list("pickOrderId in ?1", pickOrderIds)

    /**
     * Task 4 (wave bulk fulfillment): open (non-terminal) pick count among picks whose
     * `deliveryOrderLineId` is one of [lineIds] -- [WavePickService.openPicksForOrders]'s
     * consolidation READY guard reaches a wave member order's picks THIS way (not via
     * `PickOrder.deliveryOrderId`) because a batch (cross-order) PickOrder carries no
     * `deliveryOrderId` of its own; the line id is the only thread back to the originating
     * DeliveryOrder for a batch-routed pick. Same terminal predicate as [countOpenBySourceStockUnitIds].
     * Callers must not pass an empty collection.
     */
    fun countOpenByLineIds(lineIds: Collection<Long>, clientId: Long): Int =
        if (lineIds.isEmpty()) 0 else count(
            "deliveryOrderLineId in ?1 and clientId = ?2 and state not in ?3",
            lineIds, clientId, listOf(PickState.PICKED.code, PickState.CANCELED.code),
        ).toInt()

    /**
     * Task 4 review fix (IMPORTANT-5): scalar "how many non-terminal picks does wave [waveId]
     * have" count via a subquery on [com.karyo.fulfillment.domain.model.PickOrder.waveId] --
     * NO entity hydration, unlike loading every Pick row of every wave PickOrder just to `.all {}`
     * over them. [WaveTerminalChecker.allTerminal] runs on every confirm of every wave-linked
     * pick (target: 50K pick lines/hr), so a full hydration there is a real hot-path cost, not a
     * one-off admin read like [findByPickOrderIds] (still used, unhydrated-count-free, by
     * [com.karyo.fulfillment.service.WavePickService.waveStats]'s point-in-time status query). A
     * wave with no PickOrders yet matches nothing in the subquery, so the count is `0` -- the
     * same vacuous "all terminal" result the old entity-hydrating version returned for an empty
     * order set.
     */
    fun countOpenByWaveId(waveId: Long, clientId: Long): Long =
        getEntityManager().createQuery(
            """
            select count(p) from Pick p
            where p.pickOrderId in (
                select po.id from PickOrder po where po.waveId = :waveId and po.clientId = :clientId
            )
            and p.state not in :terminal
            """.trimIndent(),
            Long::class.java,
        )
            .setParameter("waveId", waveId)
            .setParameter("clientId", clientId)
            .setParameter("terminal", listOf(PickState.PICKED.code, PickState.CANCELED.code))
            .singleResult

    fun findByIdAndClient(id: Long, clientId: Long): Pick? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()

    /**
     * Batched by-id lookup scoped to [clientId] (e.g. for
     * [com.karyo.fulfillment.service.ShipmentDocumentService]'s D8 packet content-list join,
     * `line.sourcePickId` -> this -> `Pick.targetStockUnitId` -- the TARGET, not the source:
     * see that service's KDoc for why the box's actual contents track the target stock unit).
     * An unknown/foreign id is simply absent from the result, mirroring
     * [com.karyo.inventory.repository.StockUnitRepository.findByIds] -- never an error,
     * callers render "—" for the row.
     */
    fun findByIdsAndClient(ids: Collection<Long>, clientId: Long): List<Pick> =
        if (ids.isEmpty()) emptyList() else list("id in ?1 and clientId = ?2", ids, clientId)

    /**
     * Count of picks in a non-terminal state referencing any of [stockUnitIds] via
     * `sourceStockUnitId`. Terminal = PICKED(600)/CANCELED(800); see
     * [com.karyo.fulfillment.service.DefaultOpenPickGuard] for the reasoning. Single batched query; callers must not pass an empty collection.
     */
    fun countOpenBySourceStockUnitIds(stockUnitIds: Collection<Long>): Long =
        count(
            "sourceStockUnitId in ?1 and state not in ?2",
            stockUnitIds,
            listOf(PickState.PICKED.code, PickState.CANCELED.code),
        )

    /**
     * Row 13, fulfillment half of [com.karyo.inventory.api.spi.ReservationRefMover.countRefs]:
     * tenant-scoped count of non-terminal picks referencing [stockUnitId] via
     * `sourceStockUnitId`. Filters `state < PickState.PICKED.code` rather than the
     * not-in-terminal-set shape [countOpenBySourceStockUnitIds] uses -- equivalent here because
     * [PickState.canAdvanceTo] only reaches CANCELED(800) from a pre-PICKED(600) state, so
     * CANCELED is always numerically past PICKED and the `<` comparison excludes both terminal
     * states in one predicate.
     */
    fun countOpenBySource(stockUnitId: Long, clientId: Long): Int =
        count(
            "sourceStockUnitId = ?1 and clientId = ?2 and state < ?3",
            stockUnitId,
            clientId,
            PickState.PICKED.code,
        ).toInt()

    /**
     * Row 13, fulfillment half of [com.karyo.inventory.api.spi.ReservationRefMover.moveRefs]:
     * repoints every non-terminal pick's `sourceStockUnitId` from [fromStockUnitId] to
     * [toStockUnitId]. A terminal pick (PICKED or CANCELED) is deliberately left alone -- see
     * [com.karyo.fulfillment.service.PickReservationRefMover]'s KDoc.
     *
     * M8 (defect-burndown-5, :1573): bumps `version` alongside `sourceStockUnitId`. A bulk JPQL
     * update bypasses Hibernate's entity-path dirty checking, so without this an un-bumped
     * `@Version` lets a concurrent entity-path writer (e.g. a pick confirm/cancel) flush
     * successfully against a row whose `sourceStockUnitId` changed underneath it -- the optimistic
     * lock never fires.
     */
    fun repointOpenPickSource(fromStockUnitId: Long, toStockUnitId: Long, clientId: Long): Int =
        getEntityManager().createQuery(
            """
            update Pick p
            set p.sourceStockUnitId = :toStockUnitId, p.version = p.version + 1
            where p.sourceStockUnitId = :fromStockUnitId
              and p.clientId = :clientId
              and p.state < :picked
            """.trimIndent(),
        )
            .setParameter("toStockUnitId", toStockUnitId)
            .setParameter("fromStockUnitId", fromStockUnitId)
            .setParameter("clientId", clientId)
            .setParameter("picked", PickState.PICKED.code)
            .executeUpdate()

    /**
     * Per-(line, substituted-SKU) sums of confirmed pick quantities: PICKED(600) picks
     * referencing any of [lineIds], grouped by `deliveryOrderLineId` AND `substitutedItemDataId`
     * so ordered-SKU and substitute-SKU quantities never land in one bucket (substitution
     * follow-ups share the parent's line id with a different SKU). Tuple aliases: `lineId`,
     * `substitutedItemDataId`, `picked`. Single round trip; callers must not pass an empty collection.
     */
    fun sumPickedByLineIds(lineIds: Collection<Long>, clientId: Long): List<Tuple> =
        getEntityManager().createQuery(
            """
            select p.deliveryOrderLineId as lineId,
                   p.substitutedItemDataId as substitutedItemDataId,
                   sum(p.pickedAmount) as picked
            from Pick p
            where p.deliveryOrderLineId in :lineIds
              and p.state = :state
              and p.clientId = :clientId
            group by p.deliveryOrderLineId, p.substitutedItemDataId
            """.trimIndent(),
            Tuple::class.java,
        )
            .setParameter("lineIds", lineIds)
            .setParameter("state", PickState.PICKED.code)
            .setParameter("clientId", clientId)
            .resultList

    /**
     * Per-(line, source stock unit) sums of `plannedAmount` over TERMINAL picks (PICKED(600) or
     * CANCELED(800)). A terminal pick's stock-side reservation is fully resolved (picked portion
     * consumed at confirm, outstanding portion released at confirm-shortfall or cancel), so
     * `OrderService.cancel` subtracts these sums from its recorded slices to find what is STILL
     * reserved on the order's behalf. Tuple aliases: `lineId`, `stockUnitId`, `planned`.
     * Single round trip; callers must not pass an empty collection.
     */
    fun sumTerminalPlannedByLineIds(lineIds: Collection<Long>, clientId: Long): List<Tuple> =
        getEntityManager().createQuery(
            """
            select p.deliveryOrderLineId as lineId,
                   p.sourceStockUnitId as stockUnitId,
                   sum(p.plannedAmount) as planned
            from Pick p
            where p.deliveryOrderLineId in :lineIds
              and p.state in :terminal
              and p.clientId = :clientId
            group by p.deliveryOrderLineId, p.sourceStockUnitId
            """.trimIndent(),
            Tuple::class.java,
        )
            .setParameter("lineIds", lineIds)
            .setParameter("terminal", listOf(PickState.PICKED.code, PickState.CANCELED.code))
            .setParameter("clientId", clientId)
            .resultList

    /**
     * Row 18, fulfillment half of [com.karyo.inventory.api.spi.PurgeBlockerLookup.blockedStockUnitIds]:
     * distinct [Pick.sourceStockUnitId] among [stockUnitIds] still carried by a NON-TERMINAL
     * pick (state < PICKED(600), same terminal predicate as [countOpenBySource]) for [clientId].
     * A terminal pick is a record of what happened, not a live claim -- see
     * [com.karyo.fulfillment.service.PickReservationRefMover]'s KDoc -- so it never blocks.
     * Batched over the whole candidate set. Callers must not pass an empty collection.
     */
    fun findOpenSourceStockUnitIds(stockUnitIds: Collection<Long>, clientId: Long): Set<Long> =
        getEntityManager()
            .createQuery(
                "select distinct p.sourceStockUnitId from Pick p " +
                    "where p.sourceStockUnitId in :ids and p.clientId = :clientId and p.state < :picked",
                Long::class.java,
            )
            .setParameter("ids", stockUnitIds)
            .setParameter("clientId", clientId)
            .setParameter("picked", PickState.PICKED.code)
            .resultList
            .toSet()

    /**
     * Row 18 fix round 1 (Critical 1): distinct [Pick.targetStockUnitId] among [stockUnitIds]
     * referenced by ANY pick for [clientId] -- unconditionally, no terminal-state filter. Unlike
     * [findOpenSourceStockUnitIds]'s source-side reference (which stops mattering once the pick
     * is terminal, because the source was fully consumed at confirm), the TARGET stock unit is
     * the one that physically holds the picked quantity after confirm, and
     * [com.karyo.fulfillment.service.ShipmentDocumentService] reads it through ungated
     * on-demand BOL/packing-slip/content-list routes with no time window -- a reprint months
     * after a terminal pick is a supported operation, so this reference is never merely
     * historical. Same "never historical" reasoning as [com.karyo.fulfillment.repository.
     * PickOrderRepository.findReferencedTargetUnitLoadIds]. Batched over the whole candidate
     * set. Callers must not pass an empty collection.
     */
    fun findReferencedTargetStockUnitIds(stockUnitIds: Collection<Long>, clientId: Long): Set<Long> =
        getEntityManager()
            .createQuery(
                "select distinct p.targetStockUnitId from Pick p " +
                    "where p.targetStockUnitId in :ids and p.clientId = :clientId",
                Long::class.java,
            )
            .setParameter("ids", stockUnitIds)
            .setParameter("clientId", clientId)
            .resultList
            .toSet()
}
