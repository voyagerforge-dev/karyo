package com.karyo.work

import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.orders.vo.OrderState
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.vo.TransportType
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Test

/**
 * Row 19 (defect-burndown-4, Task 12): [com.karyo.work.api.v1.WorkInboxResource.claim]/`release`
 * were gated at `inventory-read` for EVERY work type even though claiming/releasing is a write.
 * Verifies the per-type write-role gate: PICK needs `fulfillment-write`, transport types
 * (PUTAWAY/MOVE/REPLENISH/TRANSFER) need `task-write`.
 *
 * clientId 8600-8602 reserved for this suite.
 */
@QuarkusTest
class WorkInboxClaimGateTest {

    @Inject lateinit var pickOrderRepo: PickOrderRepository
    @Inject lateinit var transportRepo: TransportOrderRepository

    private fun ns() = System.nanoTime()

    @Transactional
    fun seedReleasedPick(clientId: Long): Long {
        val o = PickOrder().apply {
            this.clientId = clientId
            pickOrderNumber = "PK-GATE-${ns()}"
            deliveryOrderId = 0L
            deliveryOrderNumber = "DO-GATE-${ns()}"
            state = PickState.RELEASED.code
            prio = 50
        }
        pickOrderRepo.persist(o)
        return o.id!!
    }

    @Transactional
    fun seedReleasedMove(clientId: Long): Long {
        val o = TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "WI-GATE-${ns()}"
            transportType = TransportType.MOVE
            unitLoadId = 1; unitLoadLabel = "UL-GATE"
            sourceLocationId = 1; sourceLocationName = "A-01"
            state = OrderState.RELEASED.code
            prio = 50
            executorType = "HUMAN"
        }
        transportRepo.persist(o)
        return o.id!!
    }

    @Test
    @TestSecurity(user = "reader-only", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8600")])
    fun `claim on PICK without fulfillment-write returns 403`() {
        val pickId = seedReleasedPick(8600L)

        given().`when`().post("/api/v1/work/PICK:$pickId/claim").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "picker-writer", roles = ["inventory-read", "fulfillment-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8600")])
    fun `claim on PICK with fulfillment-write succeeds`() {
        val pickId = seedReleasedPick(8600L)

        given().`when`().post("/api/v1/work/PICK:$pickId/claim").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "reader-only-move", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8601")])
    fun `claim on MOVE (transport type) without task-write returns 403`() {
        val moveId = seedReleasedMove(8601L)

        given().`when`().post("/api/v1/work/MOVE:$moveId/claim").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "mover-writer", roles = ["inventory-read", "task-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8601")])
    fun `claim on MOVE with task-write succeeds`() {
        val moveId = seedReleasedMove(8601L)

        given().`when`().post("/api/v1/work/MOVE:$moveId/claim").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "no-task-write", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8602")])
    fun `release on a transport type without task-write returns 403`() {
        val moveId = seedReleasedMove(8602L)

        given().`when`().post("/api/v1/work/MOVE:$moveId/release").then().statusCode(403)
    }
}
