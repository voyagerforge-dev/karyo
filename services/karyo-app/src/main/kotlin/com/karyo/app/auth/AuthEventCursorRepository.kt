package com.karyo.app.auth

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class AuthEventCursorRepository : PanacheRepository<AuthEventCursor> {

    private fun row(): AuthEventCursor =
        find("id = 1").firstResult() ?: AuthEventCursor().also { persist(it) }

    /** Transactional so the standalone read at the top of a poll run gets a fresh session. */
    @Transactional
    fun current(): Long = row().lastEventTime

    /** Monotonic advance; call inside the poll run's write transaction. */
    fun advance(toMs: Long) {
        val c = row()
        if (toMs > c.lastEventTime) c.lastEventTime = toMs
    }

    /** Test helper: resets the cursor so the next runOnce() re-scans from the beginning. */
    @Transactional
    fun reset() {
        row().lastEventTime = 0
    }
}
