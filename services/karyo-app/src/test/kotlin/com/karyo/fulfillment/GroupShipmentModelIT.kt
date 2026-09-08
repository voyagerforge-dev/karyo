package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShipmentOrder
import com.karyo.fulfillment.repository.ShipmentOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.vo.ShipmentState
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test

/**
 * Bulk Allocation Sprint C, Task 1: the widened [Shipment] model over REST -- a GROUP (cross-
 * order) shipment persisted directly through the repositories (no wave/pack-out flow wired yet;
 * that lands in later tasks of this sprint) round-trips its [Shipment.deliveryOrderId]-null,
 * [Shipment.consolidationGroupId], and member-order shape through `GET /api/v1/shipments/{id}`,
 * and the existing per-order `pack` route refuses it outright (Task 1's `pack` guard).
 */
@QuarkusTest
class GroupShipmentModelIT {

    @Inject
    lateinit var shipmentRepository: ShipmentRepository

    @Inject
    lateinit var shipmentOrderRepository: ShipmentOrderRepository

    /**
     * Sprint C: builds and persists a GROUP shipment. `clientId` is a PARAMETER (not a class
     * property) deliberately -- an outer-class property of this name would be shadowed by
     * [Shipment]'s own `clientId` inside the `apply` block below (the implicit-receiver-shadows-
     * outer-member Kotlin pitfall), silently self-assigning 0 instead of the intended tenant.
     */
    @Transactional
    fun persistGroupShipment(consolidationGroupId: Long, clientId: Long, memberOrderIds: List<Long>): Long {
        val shp = Shipment().apply {
            this.clientId = clientId
            shipmentNumber = "SHP-GRP-IT-${System.nanoTime()}"
            this.consolidationGroupId = consolidationGroupId
            this.waveId = 42L
            state = ShipmentState.PACKING.code
        }
        shipmentRepository.persist(shp)
        memberOrderIds.forEach { orderId ->
            val so = ShipmentOrder().apply {
                this.clientId = clientId
                this.shipmentId = shp.id!!
                this.deliveryOrderId = orderId
                this.deliveryOrderNumber = "ORD-$orderId"
            }
            shipmentOrderRepository.persist(so)
        }
        return shp.id!!
    }

    @Test
    @TestSecurity(user = "op", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
        "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9401")])
    fun `GET on a group shipment reports a null order and its members, POST pack is refused`() {
        val base = System.nanoTime()
        val orderA = base
        val orderB = base + 1
        val shipmentId = persistGroupShipment(consolidationGroupId = 77L, clientId = 9401L, memberOrderIds = listOf(orderA, orderB))

        val body = given().`when`().get("/api/v1/shipments/$shipmentId")
            .then().statusCode(200)
            .body("deliveryOrderId", nullValue())
            .body("consolidationGroupId", equalTo(77))
            .body("waveId", equalTo(42))
            .body("orders.size()", equalTo(2))
            .extract().jsonPath()
        val memberOrderIds = body.getList<Number>("orders.id").map { it.toLong() }
        assertThat(memberOrderIds).containsExactlyInAnyOrder(orderA, orderB)

        given().contentType(ContentType.JSON).body("""{"weight":1}""")
            .`when`().post("/api/v1/shipments/$shipmentId/pack")
            .then().statusCode(422)
            .body("type", containsString("invalid-pack-request"))

        // Fix (task-1 review, Important 2): the LIST route resolves group-shipment members too --
        // Task 6's shipments-list UI falls back to "{orders.length} orders" and searches
        // orders[].number, so a group shipment must not show up with an empty orders array there.
        val listOrders = given().`when`().get("/api/v1/shipments")
            .then().statusCode(200)
            .extract().jsonPath()
            .getList<Map<String, Any>>("find { it.id == $shipmentId }.orders")
        assertThat(listOrders).hasSize(2)
    }
}
