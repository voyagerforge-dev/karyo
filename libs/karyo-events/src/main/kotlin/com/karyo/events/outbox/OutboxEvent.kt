package com.karyo.events.outbox

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "outbox_events")
class OutboxEvent : BaseEntity() {
    @Column(name = "aggregate_type", nullable = false, length = 100)
    lateinit var aggregateType: String

    @Column(name = "aggregate_id", nullable = false)
    var aggregateId: Long = 0

    @Column(name = "event_type", nullable = false, length = 100)
    lateinit var eventType: String

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    lateinit var payload: String

    @Column(name = "tenant_id", nullable = false)
    var tenantId: Long = 0

    @Column(nullable = false)
    var published: Boolean = false
}
