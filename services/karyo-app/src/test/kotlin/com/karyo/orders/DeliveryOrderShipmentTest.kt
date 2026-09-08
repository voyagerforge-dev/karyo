package com.karyo.orders

import com.karyo.fulfillment.domain.model.Shipment
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
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Verifies the [com.karyo.fulfillment.spi.ShipmentLookup] read seam on
 * [com.karyo.orders.dto.DeliveryOrderResponse]: carrier/service/tracking are wired from the
 * shipment when one exists, and stay an honest null (frontend renders "--") when it doesn't.
 */
@QuarkusTest
class DeliveryOrderShipmentTest {

    @Inject
    lateinit var shipmentRepository: ShipmentRepository

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Shipment Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createOrder(itemDataId: Long): Long =
        given().contentType(ContentType.JSON).body(
            """{"customerName":"Shipment Test Customer","lines":[{"itemDataId":$itemDataId,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    @Transactional
    fun persistShipmentFor(deliveryOrderId: Long, clientId: Long) {
        val shp = Shipment().apply {
            this.clientId = clientId
            shipmentNumber = "SHP-ORD-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "ORD-$deliveryOrderId"
            state = ShipmentState.SHIPPED.code
            carrierName = "UPS"
            carrierService = "GROUND"
            trackingNumber = "1Z999AA10123456784"
            shippedAt = Instant.parse("2026-07-01T12:00:00Z")
        }
        shipmentRepository.persist(shp)
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `order with a shipment carries carrier, service and tracking`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("SHP-IU-${suffix.toString().takeLast(8)}")
        val pid = createProduct("SHP-SKU-$suffix", itemUnitId)
        val orderId = createOrder(pid)

        persistShipmentFor(orderId, clientId = 1L)

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("carrierName", `is`("UPS"))
            .body("carrierService", `is`("GROUND"))
            .body("trackingNumber", `is`("1Z999AA10123456784"))
            .body("shippedAt", `is`("2026-07-01T12:00:00Z"))
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `order without a shipment has null carrier, service and tracking`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("NOSHP-IU-${suffix.toString().takeLast(8)}")
        val pid = createProduct("NOSHP-SKU-$suffix", itemUnitId)
        val orderId = createOrder(pid)

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("carrierName", `is`(nullValue()))
            .body("carrierService", `is`(nullValue()))
            .body("trackingNumber", `is`(nullValue()))
            .body("shippedAt", `is`(nullValue()))
    }
}
