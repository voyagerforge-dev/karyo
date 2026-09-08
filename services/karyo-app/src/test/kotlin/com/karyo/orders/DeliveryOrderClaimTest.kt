package com.karyo.orders

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.vo.OrderState
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * Integration test (REAL beans) for Row 10's operator claim: [com.karyo.orders.service.OrderService.claim]
 * and `.releaseOperator`. Follows [com.karyo.orders.service.GoodsReceiptService.claim]'s
 * SEMANTICS -- pure metadata, claim is not idempotent, closed orders refuse -- never the pick
 * precedent's state-moving claim (asserted explicitly by the first test).
 */
@QuarkusTest
class DeliveryOrderClaimTest {

    @Inject
    lateinit var orderRepository: DeliveryOrderRepository

    @Inject
    lateinit var outboxEvents: OutboxEventRepository

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Claim Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createOrder(itemDataId: Long): Long =
        given().contentType(ContentType.JSON).body(
            """{"customerName":"Claim Test Customer","lines":[{"itemDataId":$itemDataId,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun seedOrder(): Long {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("CL-IU-${suffix.toString().takeLast(8)}")
        val pid = createProduct("CL-SKU-$suffix", itemUnitId)
        return createOrder(pid)
    }

    /** Direct-entity claim seeding -- REST always stamps the caller's own username, so a
     *  genuinely different holder can only be set by direct entity persist. */
    @Transactional
    fun claimAs(orderId: Long, operatorId: String) {
        val order = orderRepository.findById(orderId)!!
        order.operatorId = operatorId
    }

    @Transactional
    fun forceState(orderId: Long, state: OrderState) {
        val order = orderRepository.findById(orderId)!!
        order.state = state.code
    }

    @Test
    @TestSecurity(user = "op-claim", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `claiming an unclaimed order records the operator and leaves the state alone`() {
        val orderId = seedOrder()
        val before = given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200)
            .extract().jsonPath().getInt("state")

        given().`when`().post("/api/v1/delivery-orders/$orderId/claim")
            .then().statusCode(200)
            .body("operatorId", `is`("op-claim"))
            .body("state", `is`(before))

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("operatorId", `is`("op-claim"))
            .body("state", `is`(before))
    }

    @Test
    @TestSecurity(user = "op-claim", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `claiming an order someone else holds is a 409`() {
        val orderId = seedOrder()
        claimAs(orderId, "someone-else")

        given().`when`().post("/api/v1/delivery-orders/$orderId/claim")
            .then().statusCode(409)
            .body("type", `is`("https://karyo.com/errors/order-claim-conflict"))
    }

    @Test
    @TestSecurity(user = "op-claim", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reclaiming an order the same operator already holds is a 409`() {
        val orderId = seedOrder()
        given().`when`().post("/api/v1/delivery-orders/$orderId/claim").then().statusCode(200)

        given().`when`().post("/api/v1/delivery-orders/$orderId/claim")
            .then().statusCode(409)
            .body("type", `is`("https://karyo.com/errors/order-claim-conflict"))
    }

    @Test
    @TestSecurity(user = "op-claim", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `claiming a finished order is a 409`() {
        val orderId = seedOrder()
        forceState(orderId, OrderState.FINISHED)

        given().`when`().post("/api/v1/delivery-orders/$orderId/claim")
            .then().statusCode(409)
            .body("type", `is`("https://karyo.com/errors/order-claim-conflict"))
    }

    @Test
    @TestSecurity(user = "op-claim", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `the holder can release their own claim`() {
        val orderId = seedOrder()
        given().`when`().post("/api/v1/delivery-orders/$orderId/claim").then().statusCode(200)

        given().`when`().post("/api/v1/delivery-orders/$orderId/release-operator")
            .then().statusCode(200)
            .body("operatorId", `is`(nullValue()))
    }

    @Test
    @TestSecurity(user = "op-claim", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a non-holder cannot release without MANAGER`() {
        val orderId = seedOrder()
        claimAs(orderId, "someone-else")

        given().`when`().post("/api/v1/delivery-orders/$orderId/release-operator")
            .then().statusCode(409)
            .body("type", `is`("https://karyo.com/errors/order-claim-conflict"))
    }

    @Test
    @TestSecurity(
        user = "op-claim",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a MANAGER can force-release another operator's claim`() {
        val orderId = seedOrder()
        claimAs(orderId, "someone-else")

        given().`when`().post("/api/v1/delivery-orders/$orderId/release-operator")
            .then().statusCode(200)
            .body("operatorId", `is`(nullValue()))
    }

    @Test
    @TestSecurity(user = "op-claim", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `releasing an unclaimed order as a non-manager is a no-op success`() {
        val orderId = seedOrder()

        given().`when`().post("/api/v1/delivery-orders/$orderId/release-operator")
            .then().statusCode(200)
            .body("operatorId", `is`(nullValue()))

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("operatorId", `is`(nullValue()))
    }

    @Test
    @TestSecurity(user = "op-claim", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `claiming writes no outbox row`() {
        val orderId = seedOrder()
        val before = outboxEvents.list("aggregateType = ?1 and aggregateId = ?2", "DeliveryOrder", orderId).size

        given().`when`().post("/api/v1/delivery-orders/$orderId/claim").then().statusCode(200)

        val after = outboxEvents.list("aggregateType = ?1 and aggregateId = ?2", "DeliveryOrder", orderId).size
        assertThat(after).isEqualTo(before)
    }
}
