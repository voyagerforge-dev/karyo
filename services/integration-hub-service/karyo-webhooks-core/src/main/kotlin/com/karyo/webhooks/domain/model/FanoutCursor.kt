package com.karyo.webhooks.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "webhook_fanout_cursor")
class FanoutCursor {
    @Id
    var id: Int = 1

    @Column(name = "last_outbox_id", nullable = false)
    var lastOutboxId: Long = 0
}
