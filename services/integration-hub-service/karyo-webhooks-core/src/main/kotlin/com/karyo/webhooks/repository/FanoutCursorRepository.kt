package com.karyo.webhooks.repository

import com.karyo.webhooks.domain.model.FanoutCursor
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class FanoutCursorRepository : PanacheRepository<FanoutCursor> {
    private fun row(): FanoutCursor =
        find("id = 1").firstResult()
            ?: FanoutCursor().also { persist(it) }

    fun current(): Long = row().lastOutboxId

    fun advance(toId: Long) {
        val c = row()
        if (toId > c.lastOutboxId) c.lastOutboxId = toId
    }

    /** Test helper: resets cursor to 0 so the next runOnce() re-scans from the beginning. */
    fun reset() {
        val c = row()
        c.lastOutboxId = 0
    }
}
