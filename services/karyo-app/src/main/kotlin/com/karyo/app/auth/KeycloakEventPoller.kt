package com.karyo.app.auth

import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.inventory.service.JournalService
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Instant

/**
 * SC19: polls the Keycloak admin Events API and appends user LOGIN / LOGOUT / LOGIN_FAILED
 * rows to the inventory journal, backdated to the Keycloak event time. Replaces the deleted
 * Keycloak event-listener SPI module (whose Kafka transport no longer exists) with a pull
 * from the app side, using the existing `karyo-backend` service account
 * (`view-events` + `view-users`).
 *
 * Mechanics:
 *  - Cursor = newest event time ever seen (epoch millis, [AuthEventCursor] singleton row).
 *    Each run re-reads events with `time >= cursor` — a deliberate overlap, because the
 *    Events API offers no exact-cursor query (`dateFrom`/`dateTo` are day-granular).
 *    [KeycloakAdminClient.fetchEvents] pages through everything since the cursor (hard-capped
 *    at 25 pages); on that cap being hit the cursor only advances to the oldest event this run
 *    actually processed, not the newest, so the next poll safely re-reads (and dedups) this
 *    batch instead of skipping past it. Events older than that, which the cap itself cut off,
 *    are still lost permanently: see [persist] for the accepted bound on that loss.
 *  - Dedup identity (making the overlap safe): a candidate event is a duplicate iff a
 *    journal row exists with the same backdated `created` == event time AND the same
 *    `correlationId` (Keycloak session id) AND the same record type; events without a
 *    session id (some LOGIN_ERROR) fall back to `(created, recordType, operatorName)` —
 *    see `InventoryJournalRepository.existsAuthEvent`.
 *  - Attribution: `clientId` = the actor's own `client_id` Keycloak user attribute
 *    (fallback 0 when the user is unknown/deleted), `operatorName` = the Keycloak
 *    username, `correlationId` = session id. The scheduler has no TenantContext, so both
 *    are passed explicitly to `JournalService.recordAuthEvent`.
 *  - Resilience: every failure (token, HTTP, parse) is logged WARN and swallowed — audit
 *    polling must never break the app. The next interval simply retries from the cursor.
 */
@ApplicationScoped
class KeycloakEventPoller(
    private val client: KeycloakAdminClient,
    private val journalService: JournalService,
    private val journalRepository: InventoryJournalRepository,
    private val cursorRepository: AuthEventCursorRepository,
    @ConfigProperty(name = "karyo.auth-audit.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {

    @Scheduled(every = "{karyo.auth-audit.poll-interval}", concurrentExecution = ConcurrentExecution.SKIP)
    fun scheduled() {
        runOnce()
    }

    /** One poll pass; returns the number of journal rows appended (0 on any failure). */
    fun runOnce(): Int {
        if (!enabled) return 0
        return try {
            val batch = client.fetchEvents(cursorRepository.current())
            if (batch.events.isEmpty()) 0 else persist(batch)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            LOG.warn("Auth-audit poll failed, will retry next interval: ${e.message}")
            0
        }
    }

    /**
     * Dedup + append + cursor advance in one transaction (events arrive pre-sorted oldest-first).
     *
     * The cursor target is derived only from events actually processed here (recognized
     * record types), not every row [KeycloakAdminClient.fetchEvents] happened to return. On
     * [KcEventBatch.truncated] (the page-cap was hit, meaning there may be older matching events this
     * call never saw), the cursor advances only to the OLDEST processed event's time, not the
     * newest: advancing to the newest would let the next poll's `time >= cursor` query skip
     * straight past whatever older events the cap cut off, permanently. Advancing to the
     * oldest instead means the next poll re-reads this whole batch, safe, `existsAuthEvent`
     * dedups it. It does NOT recover the events the cap missed: those are strictly older than
     * the oldest event processed here, so the next poll's `time >= cursor` filter skips them
     * too, permanently. That loss is bounded to bursts exceeding [KeycloakAdminClient.MAX_PAGES]
     * pages (5000 qualifying events) within one interval, and is accepted for best-effort auth
     * auditing.
     */
    @Transactional
    internal fun persist(batch: KcEventBatch): Int {
        val userCache = HashMap<String, KcUser>()
        var appended = 0
        var minProcessed: Long? = null
        var maxProcessed: Long? = null
        for (event in batch.events) {
            val recordType = RECORD_TYPES[event.type] ?: continue
            minProcessed = minProcessed?.let { minOf(it, event.time) } ?: event.time
            maxProcessed = maxProcessed?.let { maxOf(it, event.time) } ?: event.time
            if (append(event, recordType, userCache)) appended++
        }
        val cursorTarget = if (batch.truncated) minProcessed else maxProcessed
        cursorTarget?.let { cursorRepository.advance(it) }
        return appended
    }

    /** Appends one journal row for [event] unless the dedup identity says it already exists. */
    private fun append(
        event: KcEvent,
        recordType: JournalRecordType,
        userCache: MutableMap<String, KcUser>,
    ): Boolean {
        val user = event.userId?.let { id -> userCache.getOrPut(id) { client.fetchUser(id) ?: UNKNOWN_USER } }
        val username = event.username ?: user?.username ?: "unknown"
        val occurredAt = Instant.ofEpochMilli(event.time)
        if (journalRepository.existsAuthEvent(occurredAt, recordType.code, event.sessionId, username)) {
            return false
        }
        journalService.recordAuthEvent(
            clientId = user?.clientId ?: 0L,
            username = username,
            recordType = recordType,
            activityCode = event.type,
            correlationId = event.sessionId,
            ipAddress = event.ipAddress,
            occurredAt = occurredAt,
        )
        return true
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(KeycloakEventPoller::class.java)

        /** Keycloak event type → journal record type; anything else is ignored. */
        private val RECORD_TYPES = mapOf(
            "LOGIN" to JournalRecordType.LOGIN,
            "LOGOUT" to JournalRecordType.LOGOUT,
            "LOGIN_ERROR" to JournalRecordType.LOGIN_FAILED,
        )

        /** Cache sentinel for a user id the admin API no longer resolves (deleted user). */
        private val UNKNOWN_USER = KcUser(username = null, clientId = null)
    }
}
