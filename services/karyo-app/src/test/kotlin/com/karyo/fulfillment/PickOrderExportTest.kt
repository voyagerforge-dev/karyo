package com.karyo.fulfillment

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Real-DB proof of D12's `GET /api/v1/pick-orders/export.csv` -- unlike
 * [PickOrderRestTest] (which drives the full release-to-picking/confirm lifecycle) this focuses
 * on the export shape (BOM/header/row) plus the tenant-scoping pin: `exportCsv` delegates to the
 * EXACT SAME `PickOrderService.listPickOrders()` as `list` (itself scoped via
 * `PickOrderRepository.findByClient`), so an OWNER on one client_id can never see another
 * client's pick orders in the CSV.
 *
 * [TestMethodOrder] guarantees the client-1 seed runs before the client-2 leak check --
 * `@TestSecurity` mocks one fixed identity per test method, so a genuine two-tenant proof needs
 * two methods sharing the same DB.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PickOrderExportTest {

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON).body("""{"number":"$number","name":"P","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun seedPackStaging() {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-EXP-${System.nanoTime()}","usages":["PACK_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PStgExp-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-EXP-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    /**
     * Releases a fresh order to picking and returns the resulting pick order number.
     * [orderNumberMarker], if given, becomes the delivery order's explicit `orderNumber` (and
     * therefore flows into `deliveryOrderNumber`/`pickOrderNumber`, both of which ARE export
     * columns) -- used by the leak-check test to plant a marker the CSV columns can actually
     * carry (unlike the item/product numbers, which never surface in this export's columns).
     */
    private fun releaseAPickOrder(orderNumberMarker: String? = null): String {
        val s = System.nanoTime()
        // ItemUnit.name is @Size(max = 20) -- truncate the nanoTime suffix to fit.
        val iu = createItemUnit("EXPPO-IU-${s.toString().takeLast(8)}")
        val num = "EXPPO-ORD-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("EXPPO-UL-$s")
        createStock(ul, pid, num, 100.0)
        val orderNumberField = orderNumberMarker?.let { ""","orderNumber":"$it"""" } ?: ""
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$pid,"amount":10.0}]$orderNumberField}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders")
            .then().statusCode(201).extract().jsonPath().getString("[0].pickOrderNumber")
    }

    @Test
    @Order(1)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `export csv is 200 text-csv with a BOM, exact header row, and the released pick order`() {
        seedPackStaging()
        val pickOrderNumber = releaseAPickOrder()

        val bytes = given()
            .`when`().get("/api/v1/pick-orders/export.csv")
            .then().statusCode(200)
            .contentType("text/csv")
            .extract().asByteArray()

        assertThat(bytes.copyOfRange(0, 3))
            .isEqualTo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val body = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        val lines = body.split("\r\n")
        assertThat(lines[0]).isEqualTo(
            "pickOrderNumber,deliveryOrderNumber,state,prio,operatorId,picksCount,created",
        )
        assertThat(body).contains(pickOrderNumber)
    }

    // ── Tenant-scoping pin (the export-leak risk) — this is the important one ──

    @Test
    @Order(2)
    @TestSecurity(user = "ff1", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `seed a client-1-only pick order for the cross-tenant leak check`() {
        seedPackStaging()
        releaseAPickOrder(orderNumberMarker = "DO-CLIENT1-ONLY-MARKER")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "ff2", roles = ["fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2"), Claim(key = "tenant_code", value = "GLOBEX")])
    fun `client 2's export never contains client 1's delivery order marker`() {
        val body = given()
            .`when`().get("/api/v1/pick-orders/export.csv")
            .then().statusCode(200)
            .extract().asString()

        // Client 1's seeded delivery order carries an explicit orderNumber marker that flows
        // straight into deliveryOrderNumber/pickOrderNumber -- both real export columns.
        assertThat(body).doesNotContain("CLIENT1-ONLY-MARKER")
    }

    // ── Row 20 (V605) review pin: an EXTINGUISH order's null deliveryOrderNumber renders "—" ──

    @Test
    @Order(4)
    @TestSecurity(user = "ff3", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87201"), Claim(key = "tenant_code", value = "ACME")])
    fun `an EXTINGUISH order's CSV row renders a dash for deliveryOrderNumber, never a fabricated value`() {
        seedPackStaging()
        val s = System.nanoTime()
        val iu = createItemUnit("EXP-EXT-${s.toString().takeLast(8)}")
        val num = "EXPPO-EXT-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("EXPPO-EXT-UL-$s")
        val suId = createStock(ul, pid, num, 10.0)
        val poNumber = given().contentType(ContentType.JSON)
            .body("""{"stockUnitIds":[$suId]}""")
            .`when`().post("/api/v1/pick-orders/extinguish")
            .then().statusCode(201).extract().jsonPath().getString("pickOrderNumber")

        val body = given()
            .`when`().get("/api/v1/pick-orders/export.csv")
            .then().statusCode(200)
            .extract().asString()

        val row = body.lines().single { it.startsWith(poNumber) }
        assertThat(row.split(",")[1]).isEqualTo("—")
    }
}
