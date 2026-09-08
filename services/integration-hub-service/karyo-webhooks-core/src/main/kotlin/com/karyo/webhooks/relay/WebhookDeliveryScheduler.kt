package com.karyo.webhooks.relay

import com.karyo.webhooks.repository.WebhookDeliveryRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Scheduler that drives outbound webhook delivery.
 *
 * On each tick it reads the IDs of due deliveries (PENDING/FAILED with nextAttemptAt <= now)
 * and hands each one to [WebhookDeliveryProcessor.deliverOne], which runs in its own JTA
 * transaction.  This means:
 *
 * - A slow or failing partner endpoint cannot cause a Narayana JTA timeout that rolls back
 *   an entire batch of already-delivered events (the old single-transaction design).
 * - A cross-tenant "duplicate re-fire storm" on slow partners is eliminated.
 *
 * The ID-fetch read outside a transaction is intentional and safe: Quarkus/Panache will
 * auto-wrap the query in a short JTA transaction.  [processor] is constructor-injected so
 * calls go through the CDI proxy and the [@Transactional] interceptor fires correctly.
 */
@ApplicationScoped
class WebhookDeliveryScheduler(
    private val deliveries: WebhookDeliveryRepository,
    private val processor: WebhookDeliveryProcessor,
    @ConfigProperty(name = "karyo.webhooks.batch-size", defaultValue = "100") private val batchSize: Int,
) {
    @Scheduled(every = "{karyo.webhooks.poll-interval}", concurrentExecution = ConcurrentExecution.SKIP)
    fun scheduled() { runOnce() }

    /** Fetches due delivery IDs then dispatches each to its own transaction. Returns count processed. */
    fun runOnce(): Int {
        val ids = deliveries.findDue(batchSize).map { it.id!! }
        for (id in ids) processor.deliverOne(id)
        return ids.size
    }
}
