package com.karyo.auth

import com.karyo.auth.dto.CreateClientRequest
import com.karyo.auth.dto.UpdateClientRequest
import com.karyo.auth.exception.AuthException
import com.karyo.auth.service.ClientService
import com.karyo.auth.vo.ClientState
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class ClientServiceTest {

    @Inject
    lateinit var clientService: ClientService

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var outboxEventRepository: OutboxEventRepository

    /**
     * TenantContext is @RequestScoped and normally populated by TenantFilter from the JWT. These
     * tests call the service directly, so it must be primed by hand — and `principalKind`
     * defaults fail-closed to OWNER, which now (correctly) blocks client administration. These
     * cases exercise the ops path; owner scoping is covered over REST in ClientResourceTest.
     */
    @BeforeEach
    fun primeOpsPrincipal() {
        tenantContext.clientId = 0
        tenantContext.principalKind = PrincipalKind.OPS
    }

    private fun uniqueSuffix() = System.nanoTime().toString().takeLast(6)

    @Test
    fun `creates a client and defaults it to ACTIVE`() {
        val s = uniqueSuffix()
        val created = clientService.create(CreateClientRequest(name = "Client $s", number = "CL$s", email = "a@b.com"))

        assertThat(created.id).isGreaterThanOrEqualTo(100L)
        assertThat(created.number).isEqualTo("CL$s")
        assertThat(created.state).isEqualTo(ClientState.ACTIVE)
        assertThat(created.isSystemClient).isFalse()
    }

    @Test
    fun `rejects a duplicate number`() {
        val s = uniqueSuffix()
        clientService.create(CreateClientRequest(name = "First $s", number = "DUP$s"))

        assertThatThrownBy { clientService.create(CreateClientRequest(name = "Second $s", number = "DUP$s")) }
            .isInstanceOf(AuthException.DuplicateClient::class.java)
            .hasMessageContaining("number")
    }

    @Test
    fun `rejects a duplicate name`() {
        val s = uniqueSuffix()
        clientService.create(CreateClientRequest(name = "Same Name $s", number = "N1$s"))

        assertThatThrownBy { clientService.create(CreateClientRequest(name = "Same Name $s", number = "N2$s")) }
            .isInstanceOf(AuthException.DuplicateClient::class.java)
            .hasMessageContaining("name")
    }

    @Test
    fun `deactivate then reactivate round-trips the state`() {
        val s = uniqueSuffix()
        val created = clientService.create(CreateClientRequest(name = "Cycle $s", number = "CY$s"))

        assertThat(clientService.deactivate(created.id).state).isEqualTo(ClientState.INACTIVE)
        assertThat(clientService.reactivate(created.id).state).isEqualTo(ClientState.ACTIVE)
    }

    @Test
    fun `refuses to update the system client`() {
        assertThatThrownBy { clientService.update(0L, UpdateClientRequest(name = "Renamed System")) }
            .isInstanceOf(AuthException.SystemClientProtected::class.java)
    }

    @Test
    fun `update renames a client and persists the other updatable fields`() {
        val s = uniqueSuffix()
        val created = clientService.create(CreateClientRequest(name = "Before $s", number = "UP$s"))

        val updated = clientService.update(
            created.id,
            UpdateClientRequest(name = "After $s", code = "CD$s", email = "u$s@example.com", phone = "555-$s", fax = "555-1$s"),
        )

        assertThat(updated.id).isEqualTo(created.id)
        assertThat(updated.name).isEqualTo("After $s")
        assertThat(updated.code).isEqualTo("CD$s")
        assertThat(updated.email).isEqualTo("u$s@example.com")
        assertThat(updated.phone).isEqualTo("555-$s")
        assertThat(updated.fax).isEqualTo("555-1$s")
    }

    @Test
    fun `update allows a client to keep its own name`() {
        val s = uniqueSuffix()
        val created = clientService.create(CreateClientRequest(name = "Steady Name $s", number = "ST$s"))

        val updated = clientService.update(
            created.id,
            UpdateClientRequest(name = "Steady Name $s", code = "NEWCODE$s"),
        )

        assertThat(updated.name).isEqualTo("Steady Name $s")
        assertThat(updated.code).isEqualTo("NEWCODE$s")
    }

    @Test
    fun `update rejects renaming onto a name held by a different client`() {
        val s = uniqueSuffix()
        val taken = clientService.create(CreateClientRequest(name = "Taken Name $s", number = "TK$s"))
        val other = clientService.create(CreateClientRequest(name = "Other Name $s", number = "OT$s"))

        assertThatThrownBy { clientService.update(other.id, UpdateClientRequest(name = taken.name)) }
            .isInstanceOf(AuthException.DuplicateClient::class.java)
            .hasMessageContaining("name")
    }

    @Test
    fun `update does not change the client number`() {
        val s = uniqueSuffix()
        val created = clientService.create(CreateClientRequest(name = "NumGuard $s", number = "NG$s"))

        val updated = clientService.update(created.id, UpdateClientRequest(name = "NumGuard Renamed $s"))

        assertThat(updated.number).isEqualTo("NG$s")
    }

    /**
     * Pinned to this client's own aggregateId, not a bare eventType count — a count-only
     * assertion can't tell "our update published" from "some other test's update published" if
     * the suite ever runs in parallel.
     */
    @Test
    fun `update writes an outbox event for that client`() {
        val s = uniqueSuffix()
        val created = clientService.create(CreateClientRequest(name = "Outbox Update $s", number = "OU$s"))

        clientService.update(created.id, UpdateClientRequest(name = "Outbox Update Renamed $s"))

        assertThat(
            outboxEventRepository.count(
                "eventType = ?1 and aggregateId = ?2",
                "ClientUpdated",
                created.id,
            ),
        ).isEqualTo(1L)
    }

    /**
     * The class's own KDoc: a client row IS the tenant dimension, so an event about it must be
     * attributed to itself, not to whichever OPS principal happened to perform the write. This
     * suite always runs as OPS/client 0 (see [primeOpsPrincipal]) — before the fix every one of
     * these events carried tenantId=0, so a client subscribed to its own ClientUpdated/Created/
     * Deactivated events would never learn its own record changed.
     */
    @Test
    fun `create attributes the outbox event to the new client itself, not the acting OPS principal`() {
        val s = uniqueSuffix()
        val created = clientService.create(CreateClientRequest(name = "Attr Create $s", number = "AC$s"))

        val event = outboxEventRepository.find(
            "eventType = ?1 and aggregateId = ?2",
            "ClientCreated", created.id,
        ).firstResult()!!

        assertThat(event.tenantId)
            .`as`("outbox tenantId must be the new client (${created.id}), not the acting OPS principal (0)")
            .isEqualTo(created.id)
    }

    @Test
    fun `update attributes the outbox event to the client being updated, not the acting OPS principal`() {
        val s = uniqueSuffix()
        val created = clientService.create(CreateClientRequest(name = "Attr Update $s", number = "AU$s"))

        clientService.update(created.id, UpdateClientRequest(name = "Attr Update Renamed $s"))

        val event = outboxEventRepository.find(
            "eventType = ?1 and aggregateId = ?2",
            "ClientUpdated", created.id,
        ).firstResult()!!

        assertThat(event.tenantId)
            .`as`("outbox tenantId must be the client being updated (${created.id}), not the acting OPS principal (0)")
            .isEqualTo(created.id)
    }

    @Test
    fun `deactivate attributes the outbox event to the client being deactivated, not the acting OPS principal`() {
        val s = uniqueSuffix()
        val created = clientService.create(CreateClientRequest(name = "Attr Deactivate $s", number = "AD$s"))

        clientService.deactivate(created.id)

        val event = outboxEventRepository.find(
            "eventType = ?1 and aggregateId = ?2",
            "ClientDeactivated", created.id,
        ).firstResult()!!

        assertThat(event.tenantId)
            .`as`("outbox tenantId must be the client being deactivated (${created.id}), not the acting OPS principal (0)")
            .isEqualTo(created.id)
    }

    /** Same pin-by-aggregateId discipline as the update outbox test above. */
    @Test
    fun `deactivate writes an outbox event for that client`() {
        val s = uniqueSuffix()
        val created = clientService.create(CreateClientRequest(name = "Outbox Deactivate $s", number = "OD$s"))

        clientService.deactivate(created.id)

        assertThat(
            outboxEventRepository.count(
                "eventType = ?1 and aggregateId = ?2",
                "ClientDeactivated",
                created.id,
            ),
        ).isEqualTo(1L)
    }

    @Test
    fun `refuses to deactivate the system client`() {
        assertThatThrownBy { clientService.deactivate(0L) }
            .isInstanceOf(AuthException.SystemClientProtected::class.java)
    }

    @Test
    fun `reports no dangling client ids for the seeded data`() {
        val report = clientService.consistencyReport()
        assertThat(report.danglingClientIds).doesNotContain(0L, 1L, 2L)
    }

    @Test
    @Transactional
    fun `reports a client id planted in operational data with no clients row`() {
        val danglingClientId = 8_675_309L
        em.createNativeQuery(
            "INSERT INTO karyo.inventory_journals (client_id, record_type) VALUES (?1, ?2)"
        ).setParameter(1, danglingClientId)
            .setParameter(2, 0)
            .executeUpdate()

        try {
            val report = clientService.consistencyReport()
            assertThat(report.danglingClientIds).contains(danglingClientId)
            assertThat(report.danglingClientIds).doesNotContain(0L, 1L, 2L)
        } finally {
            em.createNativeQuery("DELETE FROM karyo.inventory_journals WHERE client_id = ?1")
                .setParameter(1, danglingClientId)
                .executeUpdate()
        }
    }
}
