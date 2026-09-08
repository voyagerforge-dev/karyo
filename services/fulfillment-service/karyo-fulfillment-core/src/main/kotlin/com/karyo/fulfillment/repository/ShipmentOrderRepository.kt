package com.karyo.fulfillment.repository

import com.karyo.fulfillment.domain.model.ShipmentOrder
import com.karyo.fulfillment.vo.ShipmentState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped

/** Sprint C: member-order rows of a GROUP [com.karyo.fulfillment.domain.model.Shipment]. */
@ApplicationScoped
class ShipmentOrderRepository : PanacheRepository<ShipmentOrder> {
    fun findByShipmentId(shipmentId: Long, clientId: Long): List<ShipmentOrder> =
        list("shipmentId = ?1 and clientId = ?2", Sort.by("id"), shipmentId, clientId)

    /** Batched sibling of [findByShipmentId] -- used by `ShipmentResource.list` so a page of
     *  group shipments resolves its members in ONE query, not one per row. Empty input -> empty. */
    fun findByShipmentIds(shipmentIds: Collection<Long>, clientId: Long): List<ShipmentOrder> =
        if (shipmentIds.isEmpty()) {
            emptyList()
        } else {
            list("shipmentId in ?1 and clientId = ?2", Sort.by("id"), shipmentIds, clientId)
        }

    /**
     * orderId -> shipmentId over non-canceled group shipments. Empty input -> empty.
     *
     * BOTH sides of the join are tenant-filtered (IMPORTANT 8, Sprint C final-review fix wave).
     * Filtering only `so.clientId` left the joined `Shipment` unscoped: the two rows always agree
     * today (`DefaultConsolidationPackPort` stamps one `clientId` onto the shipment and every
     * member row it mints), but a query that relies on that agreement silently leaks the day a
     * repair script, an import or a future `changeClient`-style path breaks it.
     */
    fun findShipmentIdsByOrderIds(orderIds: Collection<Long>, clientId: Long): Map<Long, Long> =
        if (orderIds.isEmpty()) {
            emptyMap()
        } else {
            getEntityManager().createQuery(
                "select so.deliveryOrderId, so.shipmentId from ShipmentOrder so, Shipment s " +
                    "where so.shipmentId = s.id and so.deliveryOrderId in :ids and so.clientId = :clientId " +
                    "and s.clientId = :clientId and s.state <> :canceled",
                Array<Any>::class.java,
            ).setParameter("ids", orderIds).setParameter("clientId", clientId)
                .setParameter("canceled", ShipmentState.CANCELED.code)
                .resultList.associate { (it[0] as Long) to (it[1] as Long) }
        }
}
