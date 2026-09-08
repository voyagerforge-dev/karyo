package com.karyo.work

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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies that a manager (inventory-write role) can release work claimed by another operator
 * via POST /{ref}/release, while a plain operator (inventory-read only) is rejected with 400.
 *
 * clientId 8501 is reserved for this suite.
 * Orders are seeded directly in RESERVED state with operatorId = "alice" via repository injection
 * so each test carries its own @TestSecurity identity without needing a prior HTTP claim call.
 */
@QuarkusTest
class WorkManagerReleaseTest {

    @Inject lateinit var transportRepo: TransportOrderRepository

    @Transactional
    fun reloadOrder(id: Long): TransportOrder? = transportRepo.findById(id)

    @Transactional
    fun seedReservedMove(clientId: Long, orderNumber: String): Long {
        val o = TransportOrder().apply {
            this.clientId = clientId
            this.orderNumber = orderNumber
            transportType = TransportType.MOVE
            unitLoadId = 1; unitLoadLabel = "UL-WMR-$clientId"
            sourceLocationId = 1; sourceLocationName = "A-01"
            state = OrderState.RESERVED.code
            operatorId = "alice"
            prio = 50
            executorType = "HUMAN"
        }
        transportRepo.persist(o)
        return o.id!!
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-read", "inventory-write", "task-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8501")])
    fun `manager with inventory-write can release another operator's claimed work`() {
        val id = seedReservedMove(8501L, "WMR-MGR-8501-1")

        given()
            .`when`().post("/api/v1/work/MOVE:$id/release")
            .then().statusCode(204)

        // Prove the data effect: order must be unclaimed and back in the RELEASED pool
        val order = reloadOrder(id)!!
        assertThat(order.operatorId).isNull()
        assertThat(order.state).isEqualTo(OrderState.RELEASED.code)
    }

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "task-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8501")])
    fun `plain operator without inventory-write cannot release another operator's claimed work`() {
        val id = seedReservedMove(8501L, "WMR-OP-8501-2")

        given()
            .`when`().post("/api/v1/work/MOVE:$id/release")
            .then().statusCode(400)
    }
}
