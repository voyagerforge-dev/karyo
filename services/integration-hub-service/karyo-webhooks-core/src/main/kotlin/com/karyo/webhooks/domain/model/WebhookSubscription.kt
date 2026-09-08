package com.karyo.webhooks.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "webhook_subscription")
class WebhookSubscription : TenantEntity() {
    @Column(nullable = false, length = 120)
    lateinit var name: String

    @Column(name = "target_url", nullable = false, length = 2048)
    lateinit var targetUrl: String

    @Column(nullable = false, length = 128)
    lateinit var secret: String

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_types", nullable = false, columnDefinition = "JSONB")
    var eventTypes: List<String> = emptyList()

    @Column(nullable = false)
    var active: Boolean = true
}
