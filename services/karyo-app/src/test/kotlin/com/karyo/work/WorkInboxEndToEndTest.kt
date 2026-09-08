package com.karyo.work

import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.orders.vo.OrderState
import com.karyo.security.TenantContext
import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.domain.model.CountSession
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.repository.CountSessionRepository
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.vo.TransportType
import com.karyo.work.service.WorkGroupService
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
 * Cross-type capstone E2E test: proves the unified work inbox works end-to-end with all three
 * providers live — [com.karyo.tasks.messaging.TransportWorkProvider] (MOVE),
 * [com.karyo.fulfillment.messaging.PickWorkProvider] (PICK),
 * and [com.karyo.stocktaking.messaging.CountWorkProvider] (COUNT).
 *
 * ClientId allocation — one tenant per scenario to prevent WorkGroup eligibility bleed:
 *   8400 — priority + mine scenarios (NO WorkGroups created; all operators see all types)
 *   8401 — eligibility-filter scenario (Pickers WorkGroup restricts "picker" to PICK only)
 *   8402 — claim-race scenario
 */
@QuarkusTest
class WorkInboxEndToEndTest {

    @Inject lateinit var transportRepo: TransportOrderRepository
    @Inject lateinit var pickOrderRepo: PickOrderRepository
    @Inject lateinit var countOrderRepo: CountOrderRepository
    @Inject lateinit var countSessionRepo: CountSessionRepository
    @Inject lateinit var workGroupService: WorkGroupService
    @Inject lateinit var tenantContext: TenantContext

    // ── Seeding helpers (write directly via repositories, inside @Transactional) ──────────────

    @Transactional
    fun seedReleasedMove(clientId: Long, orderNumber: String, prio: Int): Long {
        val o = TransportOrder().apply {
            this.clientId = clientId
            this.orderNumber = orderNumber
            transportType = TransportType.MOVE
            unitLoadId = 1; unitLoadLabel = "UL-E2E-$clientId"
            sourceLocationId = 1; sourceLocationName = "A-01"
            state = OrderState.RELEASED.code
            this.prio = prio
            executorType = "HUMAN"
        }
        transportRepo.persist(o)
        return o.id!!
    }

    @Transactional
    fun seedReleasedPickOrder(clientId: Long, pickOrderNumber: String, prio: Int): Long {
        val o = PickOrder().apply {
            this.clientId = clientId
            this.pickOrderNumber = pickOrderNumber
            deliveryOrderId = 0L
            deliveryOrderNumber = "DO-E2E-$clientId"
            state = PickState.RELEASED.code
            this.prio = prio
        }
        pickOrderRepo.persist(o)
        return o.id!!
    }

    @Transactional
    fun seedGeneratedCountOrder(clientId: Long, sessionNumber: String, orderNumber: String): Long {
        val session = CountSession().apply {
            this.clientId = clientId
            this.sessionNumber = sessionNumber
            state = CountSessionState.OPEN.code
        }
        countSessionRepo.persist(session)
        val order = CountOrder().apply {
            this.clientId = clientId
            sessionId = session.id!!
            this.orderNumber = orderNumber
            locationId = 1L
            locationName = "A-01-01"
            state = CountOrderState.GENERATED.code
        }
        countOrderRepo.persist(order)
        return order.id!!
    }

    // ── Scenario 1: Priority dispatch across types ────────────────────────────────────────────

    /**
     * Three work items in tenant 8400 (MOVE prio=90, PICK prio=50, COUNT prio=50 default).
     * An operator with no group membership is eligible for all types.
     * POST /work/next must return the MOVE because prio 90 beats 50 strictly.
     */
    @Test
    @TestSecurity(user = "op1", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8400")])
    fun `priority dispatch returns highest-prio MOVE across all three live providers`() {
        val moveId = seedReleasedMove(8400L, "E2E-MOVE-8400-1", prio = 90)
        seedReleasedPickOrder(8400L, "E2E-PICK-8400-1", prio = 50)
        seedGeneratedCountOrder(8400L, "E2E-CS-8400-1", "E2E-CO-8400-1")

        val body = given()
            .`when`().post("/api/v1/work/next")
            .then().statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("ref")).isEqualTo("MOVE:$moveId")
        assertThat(body.getString("state")).isEqualTo("CLAIMED")
        assertThat(body.getString("claimedBy")).isEqualTo("op1")
    }

    // ── Scenario 2: Mine endpoint shows what the operator claimed ─────────────────────────────

    /**
     * Self-contained mine test in tenant 8400 (no groups, separate user from scenario 1).
     * Seeds its own MOVE with prio=1000 so it wins even if scenario 1's items are in the pool.
     * POST /work/next claims it; GET /work/mine must include that exact ref.
     */
    @Test
    @TestSecurity(user = "op2", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8400")])
    fun `mine lists the work item this operator claimed`() {
        val mineId = seedReleasedMove(8400L, "E2E-MOVE-8400-2", prio = 1000)

        val claimedRef = given()
            .`when`().post("/api/v1/work/next")
            .then().statusCode(200)
            .extract().jsonPath().getString("ref")

        // prio 1000 beats everything else that may be in the pool for tenant 8400
        assertThat(claimedRef).isEqualTo("MOVE:$mineId")

        val mineRefs = given()
            .`when`().get("/api/v1/work/mine")
            .then().statusCode(200)
            .extract().jsonPath().getList<String>("ref")

        assertThat(mineRefs).contains("MOVE:$mineId")
    }

    // ── Scenario 3: Eligibility filter — WorkGroup restricts picker to PICK only ──────────────

    /**
     * Tenant 8401 has a "Pickers" WorkGroup covering only PICK.
     * Operator "picker" is a member → the eligibility resolver returns {PICK} → only the
     * PickOrder enters the dispatch pool → POST /work/next returns the PICK, never MOVE or COUNT.
     *
     * Group creation and membership are seeded via CDI (WorkGroupService) rather than via HTTP,
     * because the single @TestSecurity identity here is "picker" (inventory-read only).
     * WorkGroupService itself has no role guard; we prime tenantContext.clientId before the call.
     */
    @Test
    @TestSecurity(user = "picker", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8401")])
    fun `eligibility filter - Pickers group member only receives PICK work`() {
        seedReleasedMove(8401L, "E2E-MOVE-8401-1", prio = 90)
        val pickId = seedReleasedPickOrder(8401L, "E2E-PICK-8401-1", prio = 50)
        seedGeneratedCountOrder(8401L, "E2E-CS-8401-1", "E2E-CO-8401-1")

        // Create the WorkGroup + membership directly via CDI (test-thread context).
        // TenantFilter populates the HTTP request's TenantContext from the JWT claims above;
        // here we prime the test-thread's @RequestScoped instance for the CDI calls below.
        tenantContext.clientId = 8401L
        val group = workGroupService.create("Pickers-8401", setOf("PICK"), null)
        workGroupService.addMember(group.id!!, "picker")

        // REST call: TenantFilter sets clientId=8401, username="picker" from the JWT.
        // Resolver: groups exist → eligible types = union of picker's groups = {PICK}.
        // Pool: only the PickOrder (prio=50) enters; MOVE and COUNT are filtered out.
        val body = given()
            .`when`().post("/api/v1/work/next")
            .then().statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("ref")).isEqualTo("PICK:$pickId")
    }

    // ── Scenario 4: Claim race — second direct claim returns 409 ─────────────────────────────

    /**
     * Two sequential POST /{ref}/claim calls on the same COUNT ref:
     *   first call → 200 (claimed successfully)
     *   second call → 409 (WorkClaimConflictException → WorkClaimConflictMapper → CONFLICT)
     */
    @Test
    @TestSecurity(user = "alice", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8402")])
    fun `claim race - second direct claim on the same ref returns 409 Conflict`() {
        val countId = seedGeneratedCountOrder(8402L, "E2E-CS-8402-1", "E2E-CO-8402-1")
        val ref = "COUNT:$countId"

        given().`when`().post("/api/v1/work/$ref/claim")
            .then().statusCode(200)

        given().`when`().post("/api/v1/work/$ref/claim")
            .then().statusCode(409)
    }
}
