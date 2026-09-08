package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.ShipmentState
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test

/**
 * Row :1470 (A8): `autoOpenPending` is derived at READ TIME on the pick-order DETAIL response
 * only -- see [com.karyo.fulfillment.api.v1.dto.PickOrderResponse.autoOpenPending]'s KDoc for
 * the exact rule and the list-path cost tradeoff.
 *
 * These tests exercise the derivation directly by persisting a PICKED [PickOrder] (and, where
 * needed, a [Shipment]) against a REAL DeliveryOrder/OrderStrategy pair -- bypassing the
 * release/confirm-pick flow and its AFTER_SUCCESS [com.karyo.fulfillment.service.PackingService
 * .onAutoPackEvent] observer entirely (mirrors [PickOrderPersistenceTest]'s direct-repository
 * pattern). Going through the real event would make the "true" scenario non-deterministic:
 * [com.karyo.fulfillment.service.PackingService.openPacking] has no dependency on a seeded
 * packing-staging area, so a real createShippingOrder auto-open normally SUCCEEDS and creates a
 * Shipment immediately, which would make the very next read already report `false`. Persisting
 * state directly isolates the derivation's four conditions instead of racing that event.
 */
@QuarkusTest
class PickOrderAutoOpenPendingTest {

    @Inject lateinit var pickOrderRepository: PickOrderRepository
    @Inject lateinit var shipmentRepository: ShipmentRepository

    private fun createStrategy(createShippingOrder: Boolean): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"AOP-S-${System.nanoTime()}","createShippingOrder":$createShippingOrder}""")
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON).body("""{"number":"$number","name":"P","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates a real, persisted DeliveryOrder bound to [strategyId] (null = default strategy). */
    private fun createOrder(strategyId: Long?): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("IU-$s")
        val pid = createProduct("AOP-$s", iu)
        val strategyField = strategyId?.let { ""","orderStrategyId":$it""" } ?: ""
        val body = """{"customerName":"C","lines":[{"itemDataId":$pid,"amount":1.0}]$strategyField}"""
        return given().contentType(ContentType.JSON).body(body)
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
    }

    @Transactional
    fun persistPickedPickOrder(deliveryOrderId: Long): Long {
        val po = PickOrder().apply {
            clientId = 1L
            pickOrderNumber = "PO-AOP-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "DO-$deliveryOrderId"
            state = PickState.PICKED.code
        }
        pickOrderRepository.persist(po)
        return po.id!!
    }

    @Transactional
    fun persistShipment(deliveryOrderId: Long, state: Int) {
        val shipment = Shipment().apply {
            clientId = 1L
            shipmentNumber = "SHP-AOP-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "DO-$deliveryOrderId"
            this.state = state
        }
        shipmentRepository.persist(shipment)
    }

    @Test
    @TestSecurity(user = "aop", roles = ["product-read", "product-write", "order-read", "order-write", "fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `PICKED order under a createShippingOrder strategy with no shipment yields autoOpenPending true`() {
        val strategyId = createStrategy(createShippingOrder = true)
        val orderId = createOrder(strategyId)
        val pickOrderId = persistPickedPickOrder(orderId)

        given().`when`().get("/api/v1/pick-orders/$pickOrderId")
            .then().statusCode(200).body("autoOpenPending", `is`(true))
    }

    @Test
    @TestSecurity(user = "aop", roles = ["product-read", "product-write", "order-read", "order-write", "fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a live non-canceled shipment makes autoOpenPending false even though the strategy is on`() {
        val strategyId = createStrategy(createShippingOrder = true)
        val orderId = createOrder(strategyId)
        val pickOrderId = persistPickedPickOrder(orderId)
        persistShipment(orderId, ShipmentState.PACKING.code)

        given().`when`().get("/api/v1/pick-orders/$pickOrderId")
            .then().statusCode(200).body("autoOpenPending", `is`(false))
    }

    @Test
    @TestSecurity(user = "aop", roles = ["product-read", "product-write", "order-read", "order-write", "fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a CANCELED shipment does not block autoOpenPending -- it does not count as live`() {
        val strategyId = createStrategy(createShippingOrder = true)
        val orderId = createOrder(strategyId)
        val pickOrderId = persistPickedPickOrder(orderId)
        persistShipment(orderId, ShipmentState.CANCELED.code)

        given().`when`().get("/api/v1/pick-orders/$pickOrderId")
            .then().statusCode(200).body("autoOpenPending", `is`(true))
    }

    @Test
    @TestSecurity(user = "aop", roles = ["product-read", "product-write", "order-read", "order-write", "fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `strategy off keeps autoOpenPending false even though the order is PICKED with no shipment`() {
        val strategyId = createStrategy(createShippingOrder = false)
        val orderId = createOrder(strategyId)
        val pickOrderId = persistPickedPickOrder(orderId)

        given().`when`().get("/api/v1/pick-orders/$pickOrderId")
            .then().statusCode(200).body("autoOpenPending", `is`(false))
    }

    @Test
    @TestSecurity(user = "aop", roles = ["product-read", "product-write", "order-read", "order-write", "fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a non-PICKED order under a createShippingOrder strategy is never pending`() {
        val strategyId = createStrategy(createShippingOrder = true)
        val orderId = createOrder(strategyId)
        val po = PickOrder().apply {
            clientId = 1L
            pickOrderNumber = "PO-AOP-${System.nanoTime()}"
            this.deliveryOrderId = orderId
            deliveryOrderNumber = "DO-$orderId"
            state = PickState.STARTED.code
        }
        persistArbitrary(po)

        given().`when`().get("/api/v1/pick-orders/${po.id}")
            .then().statusCode(200).body("autoOpenPending", `is`(false))
    }

    @Transactional
    fun persistArbitrary(po: PickOrder) {
        pickOrderRepository.persist(po)
    }

    // Row :1470 (A8): the LIST endpoint deliberately hardcodes autoOpenPending=false for every
    // row (documented on PickOrderResponse.autoOpenPending and PickOrderResource.list) -- pin
    // that even a genuinely-pending order reports false there, so a future accidental attempt to
    // "fix" list() into computing it per-row (N+1 strategy + shipment lookups) fails loudly.
    @Test
    @TestSecurity(user = "aop", roles = ["product-read", "product-write", "order-read", "order-write", "fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `list endpoint always reports autoOpenPending false even for a genuinely pending order`() {
        val strategyId = createStrategy(createShippingOrder = true)
        val orderId = createOrder(strategyId)
        val pickOrderId = persistPickedPickOrder(orderId)

        // Sanity: the SAME order is `true` via the detail endpoint.
        given().`when`().get("/api/v1/pick-orders/$pickOrderId")
            .then().statusCode(200).body("autoOpenPending", `is`(true))

        val list = given().`when`().get("/api/v1/pick-orders").then().statusCode(200).extract().jsonPath()
        val row = list.getList<Map<String, Any>>("").first { (it["id"] as Number).toLong() == pickOrderId }
        org.assertj.core.api.Assertions.assertThat(row["autoOpenPending"]).isEqualTo(false)
    }
}
