package com.karyo.orders.repository

import com.karyo.orders.domain.model.DeliveryOrderLine
import com.karyo.orders.vo.OrderState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * [DeliveryOrderLine] has no `clientId` of its own (see the entity's KDoc precedent on
 * [com.karyo.orders.domain.model.OrderLineReservation]) -- it reaches the tenant through its
 * owning [com.karyo.orders.domain.model.DeliveryOrder], so every query here joins
 * `deliveryOrder.clientId`. Added for [com.karyo.orders.service.DefaultCrossDockOrdersPort]:
 * no prior caller needed to query lines directly across the whole order aggregate.
 */
@ApplicationScoped
class DeliveryOrderLineRepository : PanacheRepository<DeliveryOrderLine> {

    fun findByIdAndClient(id: Long, clientId: Long): DeliveryOrderLine? =
        find("id = ?1 and deliveryOrder.clientId = ?2", id, clientId).firstResult()

    /**
     * Batch lookup for the wave-side [com.karyo.orders.spi.OrderReleasePort.releaseLineReservations]
     * seam: every line in [ids] that belongs to a [deliveryOrder] owned by [clientId]. A foreign
     * or unknown id is silently dropped (same fail-closed shape as [findByIdAndClient]) rather than
     * throwing, since the caller is netting a batch and a stray id should not abort the whole
     * release.
     */
    fun findByIdsAndClient(ids: Collection<Long>, clientId: Long): List<DeliveryOrderLine> =
        if (ids.isEmpty()) emptyList() else list("id in ?1 and deliveryOrder.clientId = ?2", ids, clientId)

    /**
     * Open (state below PICKED), unfulfilled (shortage > 0) lines for [itemDataId], tenant-scoped,
     * earliest parent-order ship-by (`deliveryDate`) first, nulls last.
     */
    fun findOpenByItem(itemDataId: Long, clientId: Long): List<DeliveryOrderLine> =
        list(
            "itemDataId = ?1 and deliveryOrder.clientId = ?2 and state < ?3 and amount > reservedAmount " +
                "order by deliveryOrder.deliveryDate asc nulls last, id asc",
            itemDataId,
            clientId,
            OrderState.PICKED.code,
        )

    /**
     * `deliveryOrderLineId -> deliveryOrderId` for every line of a wave's member orders, in ONE
     * query. Backs [com.karyo.orders.spi.OrderReleasePort.memberLineOwners]: the sort station's
     * put-wall picture only needs each picked line's owning order, and used to reach it by walking
     * `DeliveryOrderLookup.findForPicking` once per member order.
     */
    fun lineOwnersByWave(waveId: Long, clientId: Long): Map<Long, Long> {
        @Suppress("UNCHECKED_CAST")
        val rows = getEntityManager()
            .createQuery(
                "select l.id, l.deliveryOrder.id from DeliveryOrderLine l " +
                    "where l.deliveryOrder.waveId = :waveId and l.deliveryOrder.clientId = :clientId",
            )
            .setParameter("waveId", waveId)
            .setParameter("clientId", clientId)
            .resultList as List<Array<Any?>>
        return rows.associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
    }

    /** Open, unfulfilled lines of [deliveryOrderId] matching [itemDataId], tenant-scoped. */
    fun findOpenByOrderAndItem(deliveryOrderId: Long, itemDataId: Long, clientId: Long): List<DeliveryOrderLine> =
        list(
            "deliveryOrder.id = ?1 and itemDataId = ?2 and deliveryOrder.clientId = ?3 and state < ?4 " +
                "and amount > reservedAmount order by id asc",
            deliveryOrderId,
            itemDataId,
            clientId,
            OrderState.PICKED.code,
        )
}
