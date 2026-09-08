package com.karyo.app.auth

import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.repository.InventoryJournalRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * SC19 end-to-end against the real Dev Services Keycloak: performs a genuine password-grant
 * login (and a failed one) so Keycloak records real admin events, then drives the poller
 * directly via [KeycloakEventPoller.runOnce] and asserts the journal rows.
 *
 * Written as one flow test on purpose: the cursor is shared mutable state, so a fixed
 * login -> poll -> re-poll -> bad-login -> poll order keeps assertions deterministic
 * regardless of JUnit method ordering.
 */
@QuarkusTest
@QuarkusTestResource(KeycloakTestResource::class, restrictToAnnotatedClass = true)
class KeycloakEventPollerTest {

    @Inject
    lateinit var poller: KeycloakEventPoller

    @Inject
    lateinit var client: KeycloakAdminClient

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    lateinit var authServerUrl: String

    @ConfigProperty(name = "quarkus.oidc.client-id")
    lateinit var oidcClientId: String

    @ConfigProperty(name = "quarkus.oidc.credentials.secret")
    lateinit var oidcSecret: String

    /** Direct-access (password) grant against the realm's karyo-backend confidential client. */
    private fun passwordGrant(username: String, password: String): Int =
        KeycloakTestGrants.passwordGrant(
            realmUrl = authServerUrl,
            clientId = oidcClientId,
            clientSecret = oidcSecret,
            username = username,
            password = password,
        ).statusCode()

    /** Reads in a fresh transaction so each assertion sees the poller's committed writes. */
    private fun rows(recordType: JournalRecordType, operator: String): List<InventoryJournal> =
        QuarkusTransaction.requiringNew().call {
            journalRepository
                .find("recordType = ?1 and operatorName = ?2", recordType.code, operator)
                .list()
        }

    @Test
    fun `real Keycloak login and failed login land as deduplicated journal rows`() {
        // -- a real LOGIN event ------------------------------------------------------------
        assertThat(passwordGrant("manager", "manager"))
            .`as`("password grant for manager/manager must succeed")
            .isEqualTo(200)

        poller.runOnce()

        val kcLogin = client.fetchEvents(0).events
            .filter { it.type == "LOGIN" && it.username == "manager" }
            .maxByOrNull { it.time }
        assertThat(kcLogin).`as`("Keycloak must have recorded a LOGIN admin event").isNotNull

        val loginRows = rows(JournalRecordType.LOGIN, "manager")
        assertThat(loginRows).isNotEmpty
        val row = loginRows.singleOrNull { it.created == Instant.ofEpochMilli(kcLogin!!.time) }
        assertThat(row)
            .`as`("journal row must be backdated to the exact Keycloak event time")
            .isNotNull
        assertThat(row!!.recordType).isEqualTo(JournalRecordType.LOGIN.code)
        assertThat(row.activityCode).isEqualTo("LOGIN")
        assertThat(row.correlationId).`as`("correlationId = Keycloak session id").isNotNull()
        assertThat(row.correlationId).isEqualTo(kcLogin!!.sessionId)
        assertThat(row.clientId)
            .`as`("clientId = the actor's own client_id user attribute (manager -> ACME/1)")
            .isEqualTo(1L)
        assertThat(row.ipAddress).isEqualTo(kcLogin.ipAddress)

        // -- idempotency: a second pass re-reads the overlap but appends nothing -----------
        val loginCount = rows(JournalRecordType.LOGIN, "manager").size
        poller.runOnce()
        assertThat(rows(JournalRecordType.LOGIN, "manager"))
            .`as`("second runOnce must not duplicate the LOGIN row")
            .hasSize(loginCount)

        // -- a real LOGIN_ERROR event ------------------------------------------------------
        assertThat(passwordGrant("manager", "definitely-wrong-password")).isEqualTo(401)

        poller.runOnce()

        val failedRows = rows(JournalRecordType.LOGIN_FAILED, "manager")
        assertThat(failedRows).`as`("failed login must land as a LOGIN_FAILED row").isNotEmpty
        assertThat(failedRows.map { it.activityCode }).containsOnly("LOGIN_ERROR")

        val failedCount = failedRows.size
        poller.runOnce()
        assertThat(rows(JournalRecordType.LOGIN_FAILED, "manager"))
            .`as`("second runOnce must not duplicate the LOGIN_FAILED row")
            .hasSize(failedCount)
    }
}
