package com.karyo.orders.repository

import com.karyo.orders.domain.model.DeliveryOrder
import com.karyo.orders.vo.OrderState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheQuery
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Parameters
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class DeliveryOrderRepository : PanacheRepository<DeliveryOrder> {

    fun findByIdAndClient(id: Long, clientId: Long): DeliveryOrder? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()

    /**
     * Distinct client ids with at least one CREATED, un-waved order -- backs
     * [com.karyo.orders.spi.OrderReleasePort.clientIdsWithWaveEligibleOrders] (Task 9, wave bulk
     * fulfillment sprint). Deliberately unscoped, same native-query shape as
     * `FixAssignmentRepository.distinctClientIds` / `StockUnitRepository
     * .clientIdsWithDeletableStock`.
     */
    @Suppress("UNCHECKED_CAST")
    fun clientIdsWithWaveEligibleOrders(): List<Long> =
        getEntityManager()
            .createNativeQuery(
                "SELECT DISTINCT client_id FROM karyo.delivery_orders WHERE state = :state AND wave_id IS NULL",
            )
            .setParameter("state", OrderState.CREATED.code)
            .resultList
            .map { (it as Number).toLong() }

    fun findByOrderNumber(orderNumber: String, clientId: Long): DeliveryOrder? =
        find("orderNumber = ?1 and clientId = ?2", orderNumber, clientId).firstResult()

    /**
     * Tenant-scoped search with optional state filter and free-text query over
     * orderNumber/customerName (case-insensitive contains).
     */
    fun search(clientId: Long, state: Int?, q: String?, sort: Sort): PanacheQuery<DeliveryOrder> {
        val query = StringBuilder("clientId = :clientId")
        val params = Parameters.with("clientId", clientId)

        if (state != null) {
            query.append(" and state = :state")
            params.and("state", state)
        }
        if (!q.isNullOrBlank()) {
            query.append(" and (lower(orderNumber) like :q or lower(customerName) like :q)")
            params.and("q", "%${q.lowercase()}%")
        }
        return find(query.toString(), sort, params)
    }
}
