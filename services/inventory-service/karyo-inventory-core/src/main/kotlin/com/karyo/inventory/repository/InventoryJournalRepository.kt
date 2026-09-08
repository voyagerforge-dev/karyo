package com.karyo.inventory.repository

import com.karyo.inventory.domain.model.InventoryJournal
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class InventoryJournalRepository : PanacheRepository<InventoryJournal> {

    /**
     * SC19 auth-event dedup check. A candidate Keycloak event is a duplicate iff a journal
     * row already exists with the same backdated `created` (== the Keycloak event time
     * instant) AND the same `correlationId` (Keycloak session id) AND the same record type.
     * When the event carries no session id (some LOGIN_ERROR events), the identity falls
     * back to `(created, recordType, operatorName)` with `correlationId is null`.
     *
     * This is what makes the poller's deliberate cursor overlap (`time >= cursor` re-read)
     * safe: a re-read event maps to an identical triple and is skipped.
     */
    fun existsAuthEvent(
        created: Instant,
        recordType: Int,
        correlationId: String?,
        operatorName: String?,
    ): Boolean =
        if (correlationId != null) {
            count(
                "created = ?1 and recordType = ?2 and correlationId = ?3",
                created, recordType, correlationId,
            ) > 0
        } else {
            count(
                "created = ?1 and recordType = ?2 and correlationId is null and operatorName = ?3",
                created, recordType, operatorName ?: "",
            ) > 0
        }
}
