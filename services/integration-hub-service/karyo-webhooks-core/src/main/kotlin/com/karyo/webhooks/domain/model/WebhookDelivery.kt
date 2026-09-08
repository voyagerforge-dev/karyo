package com.karyo.webhooks.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "webhook_delivery")
class WebhookDelivery : BaseEntity() {
    @Column(name = "subscription_id", nullable = false)
    var subscriptionId: Long = 0

    @Column(name = "tenant_id", nullable = false)
    var tenantId: Long = 0

    @Column(name = "outbox_event_id")
    var outboxEventId: Long? = null

    @Column(name = "event_type", nullable = false, length = 100)
    lateinit var eventType: String

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: DeliveryStatus = DeliveryStatus.PENDING

    @Column(nullable = false)
    var attempts: Int = 0

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now()

    @Column(name = "last_response_code")
    var lastResponseCode: Int? = null

    @Column(name = "last_error")
    var lastError: String? = null

    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null
}
