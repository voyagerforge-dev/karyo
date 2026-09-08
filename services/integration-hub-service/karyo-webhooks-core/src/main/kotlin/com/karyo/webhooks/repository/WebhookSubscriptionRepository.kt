package com.karyo.webhooks.repository

import com.karyo.webhooks.domain.model.WebhookSubscription
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class WebhookSubscriptionRepository : PanacheRepository<WebhookSubscription> {
    /** Relay use: ALL active subscriptions across tenants (no request filter in @Scheduled). */
    fun findActive(): List<WebhookSubscription> = list("active = true")

    fun findByClient(clientId: Long): List<WebhookSubscription> =
        list("clientId = ?1 order by created desc", clientId)

    fun findByIdAndClient(id: Long, clientId: Long): WebhookSubscription? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()
}
