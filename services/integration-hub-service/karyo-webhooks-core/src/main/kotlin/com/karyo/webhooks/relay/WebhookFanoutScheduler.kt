package com.karyo.webhooks.relay

import com.karyo.events.outbox.OutboxEvent
import com.karyo.webhooks.domain.model.DeliveryStatus
import com.karyo.webhooks.domain.model.WebhookDelivery
import com.karyo.webhooks.repository.FanoutCursorRepository
import com.karyo.webhooks.repository.WebhookDeliveryRepository
import com.karyo.webhooks.repository.WebhookSubscriptionRepository
import com.karyo.webhooks.service.EventMatcher
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Page
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant

@ApplicationScoped
class WebhookFanoutScheduler(
    private val subs: WebhookSubscriptionRepository,
    private val deliveries: WebhookDeliveryRepository,
    private val cursor: FanoutCursorRepository,
    private val outboxReader: OutboxReader,
    @ConfigProperty(name = "karyo.webhooks.batch-size", defaultValue = "100")
    private val batchSize: Int,
) {
    @Scheduled(every = "{karyo.webhooks.poll-interval}", concurrentExecution = ConcurrentExecution.SKIP)
    fun scheduled() { runOnce() }

    @Transactional
    fun runOnce(): Int {
        val from = cursor.current()
        val events = outboxReader.findAfter(from, batchSize)
        if (events.isEmpty()) return 0
        val active = subs.findActive()
        var created = 0
        var maxId = from
        for (e in events) {
            maxId = maxOf(maxId, e.id!!)
            for (s in active) {
                // SYS (client 0) owns no goods — defense for any clientId=0 row that exists
                // despite the registration-time refusal (see WebhookSubscriptionResource).
                // Skip even when e.tenantId is also 0 (a SYS-attributed event), so this is
                // merged into the tenant-match continue rather than a separate jump statement
                // (detekt LoopWithTooManyJumpStatements).
                if (s.clientId == 0L || s.clientId != e.tenantId) continue
                if (!EventMatcher.matches(s.eventTypes, e.eventType)) continue
                if (deliveries.existsForEvent(s.id!!, e.id!!)) continue
                deliveries.persist(WebhookDelivery().apply {
                    subscriptionId = s.id!!
                    tenantId = e.tenantId
                    outboxEventId = e.id
                    eventType = e.eventType
                    status = DeliveryStatus.PENDING
                    nextAttemptAt = Instant.now()
                })
                created++
            }
        }
        cursor.advance(maxId)
        return created
    }
}

/**
 * Reads outbox rows by id. [OutboxEvent.published] is not a delivery checkpoint;
 * fan-out progress belongs to [FanoutCursorRepository] instead.
 *
 * Short-lived transactions in Karyo mean commit-lag gaps are negligible; the idempotent
 * unique index on [webhook_delivery] makes re-scans safe.
 */
@ApplicationScoped
class OutboxReader : PanacheRepository<OutboxEvent> {
    fun findAfter(cursorId: Long, limit: Int): List<OutboxEvent> =
        find("id > ?1 order by id asc", cursorId).page(Page.ofSize(limit)).list()
}
