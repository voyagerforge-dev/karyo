package com.karyo.orders

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Real-DB, real-service proof of D12's `GET /api/v1/delivery-orders/export.csv` -- unlike
 * [com.karyo.orders.api.v1.DeliveryOrderResourceTest] (which mocks `OrderService`), this hits
 * the real `OrderService.list` + Postgres round trip so the tenant-scoping pin is genuine: the
 * export endpoint delegates to the EXACT SAME `clientId`-scoped query as `listOrders`, so an
 * OWNER on one client_id can never see another client's rows in the CSV.
 *
 * [TestMethodOrder] guarantees the seed test (client 1) runs before the leak-check test
 * (client 2) -- `@TestSecurity` mocks one fixed identity for a whole test method, so a genuine
 * two-tenant proof needs two separate methods sharing the same DB, not two calls in one method.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeliveryOrderExportTest {

    private fun createItemUnit(name: String): Long =
        given().contentType("application/json")
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType("application/json")
            .body("""{"number":"$number","name":"Export Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createOrder(customerName: String, city: String): String {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("EXP-IU-${suffix.toString().takeLast(8)}")
        val pid = createProduct("EXP-SKU-$suffix", itemUnitId)
        return given().contentType("application/json").body(
            """{"customerName":"$customerName","city":"$city",""" +
                """"lines":[{"itemDataId":$pid,"amount":3.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getString("orderNumber")
    }

    @Test
    @Order(1)
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `export csv is 200 text-csv with a BOM, exact header row, and the created order`() {
        val orderNumber = createOrder("Csv Export Co", "Springfield")

        val bytes = given()
            .`when`().get("/api/v1/delivery-orders/export.csv")
            .then().statusCode(200)
            .contentType("text/csv")
            .extract().asByteArray()

        assertThat(bytes.copyOfRange(0, 3))
            .isEqualTo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val body = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        val lines = body.split("\r\n")
        assertThat(lines[0]).isEqualTo(
            "orderNumber,stateName,externalNumber,customerName,city,country,deliveryDate,lineCount,created",
        )
        assertThat(body).contains(orderNumber).contains("Csv Export Co").contains("Springfield")
    }

    @Test
    @Order(2)
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `export honors the state and q filters, same as listOrders`() {
        val orderNumber = createOrder("Filtered Export Co", "Shelbyville")

        val body = given()
            .queryParam("q", "filtered export co")
            .`when`().get("/api/v1/delivery-orders/export.csv")
            .then().statusCode(200)
            .extract().asString()

        assertThat(body).contains(orderNumber)
    }

    // ── Tenant-scoping pin (the export-leak risk) — this is the important one ──

    @Test
    @Order(3)
    @TestSecurity(
        user = "mgr1",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `seed a client-1-only order for the cross-tenant leak check`() {
        createOrder("Client One Only Co", "Client-One-City")
    }

    @Test
    @Order(4)
    @TestSecurity(
        user = "mgr2",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2"), Claim(key = "tenant_code", value = "GLOBEX")])
    fun `client 2's export never contains client 1's customer name`() {
        val body = given()
            .`when`().get("/api/v1/delivery-orders/export.csv")
            .then().statusCode(200)
            .extract().asString()

        assertThat(body).doesNotContain("Client One Only Co")
    }
}
