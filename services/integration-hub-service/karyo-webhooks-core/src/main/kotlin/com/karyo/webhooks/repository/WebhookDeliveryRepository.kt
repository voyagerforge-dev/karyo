package com.karyo.webhooks.repository

import com.karyo.webhooks.domain.model.DeliveryStatus
import com.karyo.webhooks.domain.model.WebhookDelivery
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Parameters
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class WebhookDeliveryRepository : PanacheRepository<WebhookDelivery> {
    /** Relay use: due deliveries across tenants, oldest first. */
    fun findDue(limit: Int): List<WebhookDelivery> =
        find(
            "status in ?1 and nextAttemptAt <= ?2 order by id asc",
            listOf(DeliveryStatus.PENDING, DeliveryStatus.FAILED),
            Instant.now(),
        ).page(Page.ofSize(limit)).list()

    fun existsForEvent(subscriptionId: Long, outboxEventId: Long): Boolean =
        count("subscriptionId = ?1 and outboxEventId = ?2", subscriptionId, outboxEventId) > 0

    fun findByClientAndFilter(
        clientId: Long,
        subscriptionId: Long?,
        status: DeliveryStatus?,
        limit: Int,
    ): List<WebhookDelivery> {
        val q = StringBuilder("tenantId = :clientId")
        val params = Parameters.with("clientId", clientId)
        if (subscriptionId != null) { q.append(" and subscriptionId = :sub"); params.and("sub", subscriptionId) }
        if (status != null) { q.append(" and status = :st"); params.and("st", status) }
        q.append(" order by id desc")
        return find(q.toString(), params).page(Page.ofSize(limit)).list()
    }

    fun findByIdAndClient(id: Long, clientId: Long): WebhookDelivery? =
        find("id = ?1 and tenantId = ?2", id, clientId).firstResult()
}
