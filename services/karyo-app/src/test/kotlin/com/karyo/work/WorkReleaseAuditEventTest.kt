package com.karyo.work

import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.events.outbox.OutboxEvent
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.domain.model.CountSession
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.repository.CountSessionRepository
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The audit trail for work released back to the pool: every Pick and Count release writes exactly
 * one outbox row naming BOTH operators -- who held the work and who took it back.
 *
 * Driven through `POST /api/v1/work/{ref}/release` rather than the services, because the actor
 * identity under test is derived there: `releasedBy` comes from the JWT username and the
 * manager capability from the caller's roles. Calling the service directly would let the test
 * supply the very fields it is meant to verify.
 *
 * The manager-override case is the reason this exists (a manager can release someone else's
 * claim, and that used to leave no trace at all), so each work type is covered twice: alice
 * releasing her own claim, and mgr releasing alice's.
 *
 * `managerOverride` is asserted to be FALSE on both self-releases even though COUNT's own write
 * role IS `inventory-write` -- the role `WorkInboxResource` reads to set `asManager`. A count
 * self-release therefore always arrives with `asManager = true`, and only comparing the two
 * operator ids keeps it labelled honestly.
 *
 * clientId 8511 is reserved for this suite.
 */
@QuarkusTest
class WorkReleaseAuditEventTest {

    private companion object {
        const val CLIENT_ID = 8511L
    }

    @Inject lateinit var pickRepo: PickOrderRepository
    @Inject lateinit var countRepo: CountOrderRepository
    @Inject lateinit var countSessionRepo: CountSessionRepository
    @Inject lateinit var outboxRepository: OutboxEventRepository
    @Inject lateinit var objectMapper: ObjectMapper

    @Transactional
    fun seedStartedPickOrder(number: String, holder: String): Long {
        val order = PickOrder().apply {
            clientId = CLIENT_ID
            pickOrderNumber = number
            deliveryOrderId = 4711L
            deliveryOrderNumber = "DO-$number"
            state = PickState.STARTED.code
            operatorId = holder
            prio = 50
        }
        pickRepo.persist(order)
        return order.id!!
    }

    /** `count_orders.session_id` carries a real FK, so the owning session is seeded alongside. */
    @Transactional
    fun seedClaimedCountOrder(number: String, holder: String): Long {
        val session = CountSession().apply {
            clientId = CLIENT_ID
            sessionNumber = "CS-$number"
            state = CountSessionState.OPEN.code
        }
        countSessionRepo.persist(session)

        val order = CountOrder().apply {
            clientId = CLIENT_ID
            sessionId = session.id!!
            orderNumber = number
            locationId = 909L
            locationName = "A-09-09"
            state = CountOrderState.GENERATED.code
            operatorId = holder
            startedBy = holder
        }
        countRepo.persist(order)
        return order.id!!
    }

    @Transactional
    fun releaseRows(aggregateType: String, aggregateId: Long, eventType: String): List<OutboxEvent> =
        outboxRepository.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            aggregateType, aggregateId, eventType,
        ).list()

    /** Asserts exactly one release row, and returns its decoded actor fields. */
    private fun assertSingleReleaseRow(
        aggregateType: String,
        aggregateId: Long,
        eventType: String,
        releasedFrom: String?,
        releasedBy: String,
        managerOverride: Boolean,
    ) {
        val rows = releaseRows(aggregateType, aggregateId, eventType)
        assertThat(rows).describedAs("exactly one $eventType row for $aggregateType $aggregateId").hasSize(1)
        val row = rows.single()
        assertThat(row.tenantId).isEqualTo(CLIENT_ID)
        val payload = objectMapper.readTree(row.payload)
        assertThat(payload.get("releasedFrom").let { if (it.isNull) null else it.asText() }).isEqualTo(releasedFrom)
        assertThat(payload.get("releasedBy").asText()).isEqualTo(releasedBy)
        assertThat(payload.get("managerOverride").asBoolean()).isEqualTo(managerOverride)
        assertThat(payload.get("clientId").asLong()).isEqualTo(CLIENT_ID)
        assertThat(payload.get("occurredAt").asText()).isNotBlank()
    }

    @Test
    @TestSecurity(user = "alice", roles = ["inventory-read", "fulfillment-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8511")])
    fun `a pick self-release writes one audit row naming alice as both holder and actor`() {
        val id = seedStartedPickOrder("PK-REL-SELF-1", holder = "alice")

        given().`when`().post("/api/v1/work/PICK:$id/release").then().statusCode(204)

        assertSingleReleaseRow(
            "PickOrder", id, "PickOrderReleased",
            releasedFrom = "alice", releasedBy = "alice", managerOverride = false,
        )
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-read", "inventory-write", "fulfillment-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8511")])
    fun `a pick manager override writes one audit row naming alice as holder and mgr as actor`() {
        val id = seedStartedPickOrder("PK-REL-MGR-1", holder = "alice")

        given().`when`().post("/api/v1/work/PICK:$id/release").then().statusCode(204)

        assertSingleReleaseRow(
            "PickOrder", id, "PickOrderReleased",
            releasedFrom = "alice", releasedBy = "mgr", managerOverride = true,
        )
    }

    @Test
    @TestSecurity(user = "alice", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8511")])
    fun `a count self-release writes one audit row and is NOT labelled a manager override`() {
        val id = seedClaimedCountOrder("CO-REL-SELF-1", holder = "alice")

        given().`when`().post("/api/v1/work/COUNT:$id/release").then().statusCode(204)

        assertSingleReleaseRow(
            "CountOrder", id, "CountOrderReleased",
            releasedFrom = "alice", releasedBy = "alice", managerOverride = false,
        )
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8511")])
    fun `a count manager override writes one audit row naming alice as holder and mgr as actor`() {
        val id = seedClaimedCountOrder("CO-REL-MGR-1", holder = "alice")

        given().`when`().post("/api/v1/work/COUNT:$id/release").then().statusCode(204)

        assertSingleReleaseRow(
            "CountOrder", id, "CountOrderReleased",
            releasedFrom = "alice", releasedBy = "mgr", managerOverride = true,
        )
    }
}
