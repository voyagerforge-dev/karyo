package com.karyo.events.outbox

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Page
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class OutboxEventRepository : PanacheRepository<OutboxEvent> {

    fun findUnpublished(limit: Int): List<OutboxEvent> =
        find("published = false order by created asc")
            .page(Page.ofSize(limit))
            .list()

    /** Returns up to [limit] recent outbox events for a tenant, newest first, since [since]. */
    fun recentForTenant(tenantId: Long, since: Instant, limit: Int): List<OutboxEvent> =
        find("tenantId = ?1 and created >= ?2 order by created desc", tenantId, since)
            .page(0, limit)
            .list()
}
