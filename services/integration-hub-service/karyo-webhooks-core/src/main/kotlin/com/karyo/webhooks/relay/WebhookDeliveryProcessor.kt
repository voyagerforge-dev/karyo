package com.karyo.webhooks.relay

import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.webhooks.domain.model.DeliveryStatus
import com.karyo.webhooks.domain.model.WebhookDelivery
import com.karyo.webhooks.dto.WebhookEnvelope
import com.karyo.webhooks.repository.WebhookDeliveryRepository
import com.karyo.webhooks.repository.WebhookSubscriptionRepository
import com.karyo.webhooks.spi.DeliveryRetryPolicy
import com.karyo.webhooks.spi.WebhookSigner
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.UUID

/**
 * Processes a single webhook delivery in its own JTA transaction
 * ([Transactional.TxType.REQUIRES_NEW]).
 *
 * Extracted from [WebhookDeliveryScheduler] so that each delivery commits independently —
 * a slow or failing partner endpoint cannot roll back deliveries that already succeeded in
 * the same scheduler tick, and a JTA timeout on one slow call cannot corrupt the entire batch.
 *
 * Must be invoked through the CDI proxy (i.e. constructor-injected into the scheduler) so
 * the [@Transactional] interceptor applies.  Never call [deliverOne] as a private self-call.
 */
@ApplicationScoped
class WebhookDeliveryProcessor(
    private val subs: WebhookSubscriptionRepository,
    private val deliveries: WebhookDeliveryRepository,
    private val envelopes: WebhookEnvelopeBuilder,
    private val outboxReader: OutboxReader,
    private val signer: WebhookSigner,
    private val retryPolicy: DeliveryRetryPolicy,
    private val http: WebhookHttpClient,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "karyo.webhooks.max-attempts", defaultValue = "8") private val maxAttempts: Int,
) {

    /**
     * Loads the delivery identified by [deliveryId], executes the HTTP POST, and commits
     * the resulting status transition in its own transaction.
     *
     * Returns immediately (no-op) if the delivery no longer exists or its status is no
     * longer PENDING/FAILED — it was already handled by a concurrent tick or a manual
     * redeliver between the scheduler's ID-fetch and this call.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun deliverOne(deliveryId: Long) {
        val d = deliveries.findById(deliveryId) ?: return
        if (d.status != DeliveryStatus.PENDING && d.status != DeliveryStatus.FAILED) return

        val sub = subs.findById(d.subscriptionId) ?: run {
            d.status = DeliveryStatus.DEAD; d.lastError = "subscription gone"; return
        }
        try {
            val body = buildBody(d)
            val ts = Instant.now().epochSecond
            val headers = mapOf(
                "Content-Type" to "application/json",
                "X-Karyo-Event" to d.eventType,
                "X-Karyo-Delivery" to d.id.toString(),
                "X-Karyo-Timestamp" to ts.toString(),
                "X-Karyo-Signature" to signer.sign(sub.secret, ts, body),
            )
            val result = http.post(sub.targetUrl, headers, body)
            d.attempts += 1
            d.lastResponseCode = if (result.code > 0) result.code else null
            d.lastError = result.error
            if (result.code in 200..299) {
                d.status = DeliveryStatus.DELIVERED
                d.deliveredAt = Instant.now()
            } else if (d.attempts >= maxAttempts) {
                d.status = DeliveryStatus.DEAD
            } else {
                d.status = DeliveryStatus.FAILED
                d.nextAttemptAt = Instant.now().plusSeconds(retryPolicy.backoffSeconds(d.attempts))
            }
        } catch (e: Exception) {
            // Unexpected build/sign/IO error must not abort other deliveries in the same tick.
            d.lastError = (e.message ?: e.javaClass.simpleName).take(500)
            d.attempts += 1
            if (d.attempts >= maxAttempts) {
                d.status = DeliveryStatus.DEAD
            } else {
                d.status = DeliveryStatus.FAILED
                d.nextAttemptAt = Instant.now().plusSeconds(retryPolicy.backoffSeconds(d.attempts))
            }
        }
    }

    /** Build the JSON body: synthetic ping when no outbox event, or rebuild from source outbox row. */
    private fun buildBody(d: WebhookDelivery): String {
        val deliveryId = UUID.nameUUIDFromBytes("delivery-${d.id}".toByteArray()).toString()
        val envelope = if (d.outboxEventId == null) {
            WebhookEnvelope(
                eventId = deliveryId, eventType = d.eventType,
                occurredAt = Instant.now().toString(), tenantId = d.tenantId,
                aggregateType = "Webhook", aggregateId = 0,
                data = mapper.createObjectNode().put("message", "ping"),
            )
        } else {
            val event = outboxReader.findById(d.outboxEventId!!)
                ?: return mapper.writeValueAsString(mapper.createObjectNode().put("error", "source event gone"))
            envelopes.build(event, deliveryId)
        }
        return mapper.writeValueAsString(envelope)
    }
}
