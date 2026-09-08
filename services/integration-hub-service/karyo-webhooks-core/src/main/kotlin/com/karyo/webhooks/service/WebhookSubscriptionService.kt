package com.karyo.webhooks.service

import com.karyo.webhooks.domain.model.DeliveryStatus
import com.karyo.webhooks.domain.model.WebhookDelivery
import com.karyo.webhooks.domain.model.WebhookSubscription
import com.karyo.webhooks.repository.WebhookDeliveryRepository
import com.karyo.webhooks.repository.WebhookSubscriptionRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import java.security.SecureRandom
import java.time.Instant

@ApplicationScoped
class WebhookSubscriptionService(
    private val subs: WebhookSubscriptionRepository,
    private val deliveries: WebhookDeliveryRepository,
    private val urlValidator: WebhookUrlValidator,
) {
    private val rng = SecureRandom()

    private fun newSecret(): String {
        val bytes = ByteArray(32).also { rng.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun validateFields(name: String?, eventTypes: List<String>?) {
        if (name != null && name.isBlank()) throw BadRequestException("name must not be blank")
        if (eventTypes != null && eventTypes.isEmpty()) throw BadRequestException("eventTypes must not be empty")
    }

    @Transactional
    fun create(
        clientId: Long,
        name: String,
        url: String,
        eventTypes: List<String>,
        active: Boolean,
    ): WebhookSubscription {
        validateFields(name, eventTypes)
        urlValidator.validate(url)
        val s = WebhookSubscription().apply {
            this.clientId = clientId
            this.name = name
            this.targetUrl = url
            this.secret = newSecret()
            this.eventTypes = eventTypes
            this.active = active
        }
        subs.persist(s)
        return s
    }

    fun list(clientId: Long): List<WebhookSubscription> = subs.findByClient(clientId)

    fun get(clientId: Long, id: Long): WebhookSubscription =
        subs.findByIdAndClient(id, clientId) ?: throw NotFoundException("subscription $id")

    @Transactional
    fun update(
        clientId: Long,
        id: Long,
        name: String?,
        url: String?,
        eventTypes: List<String>?,
        active: Boolean?,
    ): WebhookSubscription {
        validateFields(name, eventTypes)
        if (url != null) urlValidator.validate(url)
        val s = subs.findByIdAndClient(id, clientId) ?: throw NotFoundException("subscription $id")
        name?.let { s.name = it }
        url?.let { s.targetUrl = it }
        eventTypes?.let { s.eventTypes = it }
        active?.let { s.active = it }
        return s
    }

    @Transactional
    fun delete(clientId: Long, id: Long) {
        val s = subs.findByIdAndClient(id, clientId) ?: throw NotFoundException("subscription $id")
        subs.delete(s)
    }

    /** Enqueue a synthetic ping delivery; the delivery scheduler will attempt it immediately. */
    @Transactional
    fun ping(clientId: Long, id: Long) {
        val s = subs.findByIdAndClient(id, clientId) ?: throw NotFoundException("subscription $id")
        deliveries.persist(WebhookDelivery().apply {
            subscriptionId = s.id!!
            tenantId = clientId
            outboxEventId = null
            eventType = "webhook.ping"
            status = DeliveryStatus.PENDING
            nextAttemptAt = Instant.now()
        })
    }
}
