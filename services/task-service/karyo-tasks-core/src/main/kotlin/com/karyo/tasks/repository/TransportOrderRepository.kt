package com.karyo.tasks.repository

import com.karyo.orders.vo.OrderState
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.vo.TransportType
import io.quarkus.hibernate.orm.panache.kotlin.PanacheQuery
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Parameters
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TransportOrderRepository : PanacheRepository<TransportOrder> {

    fun findByIdAndClient(id: Long, clientId: Long): TransportOrder? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()

    fun findByOrderNumber(orderNumber: String, clientId: Long): TransportOrder? =
        find("orderNumber = ?1 and clientId = ?2", orderNumber, clientId).firstResult()

    /** Idempotency guard for the receiving observer — one PUTAWAY task per receipt line. */
    fun findByGoodsReceiptLineId(goodsReceiptLineId: Long): TransportOrder? =
        find("goodsReceiptLineId", goodsReceiptLineId).firstResult()

    /**
     * OPEN transport work: RELEASED, unclaimed (operatorId is null), and NOT paused, optionally
     * narrowed to [typeNames]. A paused task (see [com.karyo.tasks.service.TaskService.pause])
     * is deliberately excluded — it is parked (an operator opted out mid-session), not floor
     * work, and only resurfaces once [com.karyo.tasks.service.TaskService.resume] clears
     * [TransportOrder.pausedAt].
     */
    fun findClaimable(clientId: Long, typeNames: Set<String>): List<TransportOrder> =
        if (typeNames.isEmpty()) emptyList()
        else list(
            "clientId = ?1 and state = ?2 and operatorId is null and transportType in ?3 and pausedAt is null",
            clientId, OrderState.RELEASED.code, typeNames.map { com.karyo.tasks.vo.TransportType.valueOf(it) },
        )

    /**
     * Work claimed by an operator: RESERVED or STARTED, same not-paused gate as [findClaimable],
     * so a task that gets paused after being claimed also drops off the "mine" list (same
     * "parked, not floor work" rationale, even though pause leaves [TransportOrder.operatorId] set).
     */
    fun findClaimedBy(clientId: Long, operatorId: String): List<TransportOrder> =
        list(
            "clientId = ?1 and operatorId = ?2 and state in (?3, ?4) and pausedAt is null",
            clientId, operatorId, OrderState.RESERVED.code, OrderState.STARTED.code,
        )

    /**
     * The open (non-finished, non-canceled) REPLENISH task for a fix assignment, if any.
     * Deliberately UNTOUCHED by the PT18 pause gate: this is an idempotency guard (one open
     * REPLENISH per fix assignment), not a floor-work pool. A paused REPLENISH still counts
     * as open here — excluding it would let a re-scan mint a duplicate REPLENISH task for the
     * same shortfall while the original is merely parked, not resolved.
     */
    fun findOpenReplenishment(fixAssignmentId: Long, clientId: Long): TransportOrder? =
        find(
            "fixAssignmentId = ?1 and clientId = ?2 and transportType = ?3 and state not in (?4, ?5)",
            fixAssignmentId, clientId, com.karyo.tasks.vo.TransportType.REPLENISH,
            com.karyo.orders.vo.OrderState.FINISHED.code, com.karyo.orders.vo.OrderState.CANCELED.code,
        ).firstResult()

    /**
     * R12b (Task 6): the open (non-finished, non-canceled) REPLENISH task for an
     * [com.karyo.layout.domain.model.ItemDataArea], if any — same predicate shape as
     * [findOpenReplenishment], keyed by [TransportOrder.itemDataAreaId] instead of
     * [TransportOrder.fixAssignmentId]. NEW BEHAVIOR (this sprint): the legacy Mode-2
     * (`refillStorageAreas`) corpus has no equivalent open-order dedupe — Karyo adds it so a
     * scan can't mint a second area-level order while one is already in flight for the same area.
     */
    fun findOpenAreaReplenishment(itemDataAreaId: Long, clientId: Long): TransportOrder? =
        find(
            "itemDataAreaId = ?1 and clientId = ?2 and transportType = ?3 and state not in (?4, ?5)",
            itemDataAreaId, clientId, com.karyo.tasks.vo.TransportType.REPLENISH,
            com.karyo.orders.vo.OrderState.FINISHED.code, com.karyo.orders.vo.OrderState.CANCELED.code,
        ).firstResult()

    /**
     * Row 3 (defect-burndown-4, Task 5): source unit-load ids claimed by any OPEN
     * (non-finished, non-canceled) REPLENISH transport order for [clientId] -- Mode 1 (fix-face)
     * and Mode 2 (area) orders share one namespace here, since both mint the same
     * [com.karyo.tasks.vo.TransportType.REPLENISH] type and both move a source unit-load that
     * must not be double-claimed. Backs [com.karyo.tasks.spi.TransportOrderPort.
     * openReplenishmentUnitLoadIds] -- seeds a scan pass's claimed-source set so a source already
     * committed to a still-open order from an EARLIER pass is never re-selected either.
     */
    fun openReplenishmentUnitLoadIds(clientId: Long): Set<Long> =
        getEntityManager()
            .createQuery(
                "select o.unitLoadId from TransportOrder o where o.clientId = ?1 " +
                    "and o.transportType = ?2 and o.state not in (?3, ?4)",
                Long::class.java,
            )
            .setParameter(1, clientId)
            .setParameter(2, com.karyo.tasks.vo.TransportType.REPLENISH)
            .setParameter(3, com.karyo.orders.vo.OrderState.FINISHED.code)
            .setParameter(4, com.karyo.orders.vo.OrderState.CANCELED.code)
            .resultList
            .toSet()

    /**
     * Tenant-scoped queue search: optional state / transport-type / operator / paused filters
     * plus free-text over orderNumber/unitLoadLabel (case-insensitive contains).
     */
    @Suppress("LongParameterList")
    fun search(
        clientId: Long,
        state: Int?,
        type: TransportType?,
        operatorId: String?,
        q: String?,
        /** Row 22: `true` -> pausedAt is not null, `false` -> pausedAt is null, `null` -> no clause. */
        paused: Boolean?,
        sort: Sort,
    ): PanacheQuery<TransportOrder> {
        val query = StringBuilder("clientId = :clientId")
        val params = Parameters.with("clientId", clientId)

        if (state != null) {
            query.append(" and state = :state")
            params.and("state", state)
        }
        if (type != null) {
            query.append(" and transportType = :type")
            params.and("type", type)
        }
        if (!operatorId.isNullOrBlank()) {
            query.append(" and operatorId = :operatorId")
            params.and("operatorId", operatorId)
        }
        if (!q.isNullOrBlank()) {
            // PT17: folds externalNumber into the free-text search, mirroring AsnRepository.search.
            query.append(" and (lower(orderNumber) like :q or lower(unitLoadLabel) like :q or lower(externalNumber) like :q)")
            params.and("q", "%${q.lowercase()}%")
        }
        if (paused != null) {
            query.append(if (paused) " and pausedAt is not null" else " and pausedAt is null")
        }
        return find(query.toString(), sort, params)
    }

    /**
     * Row 18, tasks half of [com.karyo.inventory.api.spi.PurgeBlockerLookup.blockedStockUnitIds]:
     * distinct [TransportOrder.sourceStockUnitId] among [stockUnitIds] still carried by a
     * non-terminal (not FINISHED/CANCELED) transport order for [clientId] -- every transport
     * type shares this namespace (Mode 1/Mode 2 REPLENISH, PUTAWAY, MOVE, TRANSFER alike), same
     * terminal predicate as [findOpenReplenishment]. Batched over the whole candidate set.
     * Callers must not pass an empty collection.
     */
    fun findOpenSourceStockUnitIds(stockUnitIds: Collection<Long>, clientId: Long): Set<Long> =
        getEntityManager()
            .createQuery(
                "select distinct o.sourceStockUnitId from TransportOrder o " +
                    "where o.sourceStockUnitId in :ids and o.clientId = :clientId " +
                    "and o.state not in (:finished, :canceled)",
                Long::class.java,
            )
            .setParameter("ids", stockUnitIds)
            .setParameter("clientId", clientId)
            .setParameter("finished", OrderState.FINISHED.code)
            .setParameter("canceled", OrderState.CANCELED.code)
            .resultList
            .toSet()

    /**
     * Row 18, tasks half of [com.karyo.inventory.api.spi.PurgeBlockerLookup.blockedUnitLoadIds]:
     * distinct [TransportOrder.unitLoadId] among [unitLoadIds] still moved by a non-terminal
     * transport order for [clientId]. Same terminal predicate as [findOpenSourceStockUnitIds].
     * Batched over the whole candidate set. Callers must not pass an empty collection.
     */
    fun findOpenUnitLoadIds(unitLoadIds: Collection<Long>, clientId: Long): Set<Long> =
        getEntityManager()
            .createQuery(
                "select distinct o.unitLoadId from TransportOrder o " +
                    "where o.unitLoadId in :ids and o.clientId = :clientId " +
                    "and o.state not in (:finished, :canceled)",
                Long::class.java,
            )
            .setParameter("ids", unitLoadIds)
            .setParameter("clientId", clientId)
            .setParameter("finished", OrderState.FINISHED.code)
            .setParameter("canceled", OrderState.CANCELED.code)
            .resultList
            .toSet()

    /**
     * Open (not FINISHED/CANCELED) transports targeting any of the given destinations.
     * Matches EITHER [TransportOrder.destinationLocationId] (MOVE/REPLENISH, stamped at
     * creation) OR, when that column is still null, [TransportOrder.suggestedLocationId]
     * (open PUTAWAY/TRANSFER -- destinationLocationId is only stamped at completion, see
     * `TaskService.complete` / `ConfirmVariantService.finishOrder`). Row :1614: before this,
     * an open PUTAWAY/TRANSFER's own suggested target was invisible to this query, so it could
     * never appear as demand for the location it was itself heading to. Unscoped: see
     * [com.karyo.layout.spi.TransportDemandLookup].
     */
    fun findOpenDemandByDestinationIds(destinationLocationIds: Set<Long>): List<TransportOrder> =
        if (destinationLocationIds.isEmpty()) emptyList()
        else find(
            "(destinationLocationId in ?1 or (destinationLocationId is null and suggestedLocationId in ?1)) " +
                "and state not in (?2, ?3)",
            destinationLocationIds, OrderState.FINISHED.code, OrderState.CANCELED.code,
        ).list()
}
