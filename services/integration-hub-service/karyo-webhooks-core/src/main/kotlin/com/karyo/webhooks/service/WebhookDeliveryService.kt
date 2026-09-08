package com.karyo.webhooks.service

import com.karyo.webhooks.domain.model.DeliveryStatus
import com.karyo.webhooks.domain.model.WebhookDelivery
import com.karyo.webhooks.repository.WebhookDeliveryRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import java.time.Instant

@ApplicationScoped
class WebhookDeliveryService(private val deliveries: WebhookDeliveryRepository) {

    fun list(clientId: Long, subscriptionId: Long?, status: String?, limit: Int): List<WebhookDelivery> {
        val parsed = status?.let {
            try { DeliveryStatus.valueOf(it) } catch (_: IllegalArgumentException) {
                throw BadRequestException("invalid status: $it")
            }
        }
        return deliveries.findByClientAndFilter(clientId, subscriptionId, parsed, limit)
    }

    @Transactional
    fun redeliver(clientId: Long, id: Long) {
        val d = deliveries.findByIdAndClient(id, clientId) ?: throw NotFoundException("delivery $id")
        d.status = DeliveryStatus.PENDING
        d.nextAttemptAt = Instant.now()
        // Grant a full fresh retry budget so a DEAD delivery gets all max-attempts tries again.
        d.attempts = 0
        d.lastError = null
        d.lastResponseCode = null
    }
}
