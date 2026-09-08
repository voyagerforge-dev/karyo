package com.karyo.orders.repository

import com.karyo.orders.domain.model.OrderLineReservation
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class OrderLineReservationRepository : PanacheRepository<OrderLineReservation> {

    fun findByLineId(lineId: Long): List<OrderLineReservation> =
        list("lineId", lineId)

    fun findByLineIds(lineIds: List<Long>): List<OrderLineReservation> =
        if (lineIds.isEmpty()) emptyList() else list("lineId in ?1", lineIds)

    fun deleteByLineIds(lineIds: List<Long>): Long =
        if (lineIds.isEmpty()) 0 else delete("lineId in ?1", lineIds)

    /**
     * Tenant-scoped: the slice(s) recorded for [lineId] on [stockUnitId] -- via the same
     * `DeliveryOrderLine.deliveryOrder.clientId` join [countByStockUnit] uses. Backs
     * [com.karyo.orders.service.DefaultCrossDockOrdersPort.sliceExists]/`releaseSlice`.
     */
    fun findByLineAndStockUnit(lineId: Long, stockUnitId: Long, clientId: Long): List<OrderLineReservation> =
        getEntityManager().createQuery(
            """
            select r from OrderLineReservation r
            where r.lineId = :lineId and r.stockUnitId = :stockUnitId
              and r.lineId in (
                  select l.id from DeliveryOrderLine l where l.deliveryOrder.clientId = :clientId
              )
            """.trimIndent(),
            OrderLineReservation::class.java,
        )
            .setParameter("lineId", lineId)
            .setParameter("stockUnitId", stockUnitId)
            .setParameter("clientId", clientId)
            .resultList

    /**
     * Row 13, orders half of [com.karyo.inventory.api.spi.ReservationRefMover.countRefs].
     * `OrderLineReservation` has no `clientId` column of its own (see the entity's KDoc); it
     * reaches the tenant through its line's order, so this counts via a subquery over
     * [com.karyo.orders.domain.model.DeliveryOrderLine.deliveryOrder]'s mapped `clientId`,
     * the same path [countByStockUnit] and [repointStockUnit] use.
     */
    fun countByStockUnit(stockUnitId: Long, clientId: Long): Int =
        getEntityManager().createQuery(
            """
            select count(r) from OrderLineReservation r
            where r.stockUnitId = :stockUnitId
              and r.lineId in (
                  select l.id from DeliveryOrderLine l where l.deliveryOrder.clientId = :clientId
              )
            """.trimIndent(),
            Long::class.java,
        )
            .setParameter("stockUnitId", stockUnitId)
            .setParameter("clientId", clientId)
            .singleResult
            .toInt()

    /**
     * Row 13, orders half of [com.karyo.inventory.api.spi.ReservationRefMover.moveRefs]. Every
     * `order_line_reservations` row is a live claim by definition (see
     * [com.karyo.orders.service.OrderLineReservationRefMover]'s KDoc for why there is no state
     * filter), so every row scoped to [clientId] and pointing at [fromStockUnitId] repoints.
     * Only used for the fast path where NONE of the source's rows are stale -- see
     * [com.karyo.orders.service.OrderLineReservationRefMover.moveRefs] for the split-aware path.
     *
     * M8 (defect-burndown-5, :1573): bumps `version` alongside `stockUnitId`. A bulk JPQL update
     * bypasses Hibernate's entity-path dirty checking, so without this an un-bumped `@Version`
     * lets a concurrent entity-path writer (e.g. `OrderService.cancel`) flush successfully against
     * a row whose `stockUnitId` changed underneath it -- the optimistic lock never fires.
     */
    fun repointStockUnit(fromStockUnitId: Long, toStockUnitId: Long, clientId: Long): Int =
        getEntityManager().createQuery(
            """
            update OrderLineReservation r
            set r.stockUnitId = :toStockUnitId, r.version = r.version + 1
            where r.stockUnitId = :fromStockUnitId
              and r.lineId in (
                  select l.id from DeliveryOrderLine l where l.deliveryOrder.clientId = :clientId
              )
            """.trimIndent(),
        )
            .setParameter("toStockUnitId", toStockUnitId)
            .setParameter("fromStockUnitId", fromStockUnitId)
            .setParameter("clientId", clientId)
            .executeUpdate()

    /**
     * Row 13, orders half of [com.karyo.inventory.api.spi.ReservationRefMover.countStaleRefs]'s
     * input: every reservation row on [stockUnitId] scoped to [clientId], via the same
     * `DeliveryOrderLine.deliveryOrder.clientId` join [countByStockUnit] and [repointStockUnit]
     * use. Returns entities (not just line ids) so the caller can join each row's [lineId]
     * against terminal-pick slices without a second round trip per row.
     */
    fun findByStockUnit(stockUnitId: Long, clientId: Long): List<OrderLineReservation> =
        getEntityManager().createQuery(
            """
            select r from OrderLineReservation r
            where r.stockUnitId = :stockUnitId
              and r.lineId in (
                  select l.id from DeliveryOrderLine l where l.deliveryOrder.clientId = :clientId
              )
            """.trimIndent(),
            OrderLineReservation::class.java,
        )
            .setParameter("stockUnitId", stockUnitId)
            .setParameter("clientId", clientId)
            .resultList

    /**
     * Row 18, orders half of [com.karyo.inventory.api.spi.PurgeBlockerLookup.blockedStockUnitIds]:
     * distinct [stockUnitId]s among [stockUnitIds] still referenced by a live
     * `order_line_reservations` row for [clientId] -- every such row is a live claim by
     * definition (see [com.karyo.orders.service.OrderLineReservationRefMover]'s KDoc), so no
     * state filter. Batched over the whole candidate set, never one query per id. Callers must
     * not pass an empty collection.
     */
    fun findReferencedStockUnitIds(stockUnitIds: Collection<Long>, clientId: Long): Set<Long> =
        getEntityManager().createQuery(
            """
            select distinct r.stockUnitId from OrderLineReservation r
            where r.stockUnitId in :stockUnitIds
              and r.lineId in (
                  select l.id from DeliveryOrderLine l where l.deliveryOrder.clientId = :clientId
              )
            """.trimIndent(),
            Long::class.java,
        )
            .setParameter("stockUnitIds", stockUnitIds)
            .setParameter("clientId", clientId)
            .resultList
            .toSet()

    /**
     * Row 1 (defect-tail-2), orders half of
     * [com.karyo.inventory.api.spi.PurgeBlockerLookup.blockedStockUnitIds]'s terminal-netting fix:
     * every `order_line_reservations` row for ANY of [stockUnitIds] scoped to [clientId] -- full
     * entities (not just distinct ids), so the caller can fold each stock unit's rows by `lineId`
     * (slice) and net out terminal-consumed amounts before deciding whether to block. ONE query
     * for the WHOLE candidate batch, never one per id -- see
     * [com.karyo.orders.service.OrderPurgeBlockerLookup]'s KDoc for the query-count contract this
     * feeds into.
     */
    fun findByStockUnitIds(stockUnitIds: Collection<Long>, clientId: Long): List<OrderLineReservation> =
        if (stockUnitIds.isEmpty()) {
            emptyList()
        } else {
            getEntityManager().createQuery(
                """
                select r from OrderLineReservation r
                where r.stockUnitId in :stockUnitIds
                  and r.lineId in (
                      select l.id from DeliveryOrderLine l where l.deliveryOrder.clientId = :clientId
                  )
                """.trimIndent(),
                OrderLineReservation::class.java,
            )
                .setParameter("stockUnitIds", stockUnitIds)
                .setParameter("clientId", clientId)
                .resultList
        }
}
