package com.karyo.app.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Singleton progress cursor for [KeycloakEventPoller] (FanoutCursor shape; seeded by auth
 * migration V1203). [lastEventTime] is the Keycloak event timestamp in epoch millis of the
 * newest event ever seen; the poller re-reads `time >= cursor` on purpose and relies on the
 * journal-side dedup identity, so this value only has to be monotonic, never exact.
 *
 * Lives in karyo-app (not a domain core) because the poller is app-level wiring between
 * Keycloak and the inventory journal; the app module's own classes are indexed by Quarkus,
 * so entity discovery needs no extra registration.
 */
@Entity
@Table(name = "auth_event_cursor")
class AuthEventCursor {
    @Id
    var id: Long = 1

    @Column(name = "last_event_time", nullable = false)
    var lastEventTime: Long = 0
}
