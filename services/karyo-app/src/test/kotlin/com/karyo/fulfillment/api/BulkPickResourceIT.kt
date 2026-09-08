package com.karyo.fulfillment.api

import com.karyo.fulfillment.FreeTierBatchPickFixture
import com.karyo.fulfillment.spi.BatchPickPort
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * Bulk Allocation Sprint B, Task 4: [com.karyo.fulfillment.api.v1.PickOrderResource]'s
 * `bulk-lines`/`bulk-confirm` routes over [com.karyo.fulfillment.service.BulkPickService], plus
 * the [com.karyo.fulfillment.messaging.PickWorkProvider] BULK work-inbox summary. Domain rows
 * are created through free REST endpoints; [FreeTierBatchPickFixture] creates batch work through
 * the public fulfillment SPI for client 9303.
 */
@QuarkusTest
class BulkPickResourceIT {

    @Inject
    lateinit var batchPickPort: BatchPickPort

    @Inject
    lateinit var tenantContext: TenantContext

    // ── Fixture helpers (copied verbatim from BulkPickServiceIT) ───────────

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
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,""" +
                    """"unitLoadId":$ulId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun seedStagingArea(usage: String) {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"BP-$usage-${System.nanoTime()}","usages":["$usage"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"BP-$usage-LT-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"BP-$usage-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    /** Seeds a product with [stockAmount] of stock on its own unit load. Returns the product/item id. */
    private fun seedProductWithStock(tag: String, stockAmount: Double): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val num = "BP-$tag-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("BP-$tag-UL-$s")
        createStock(ul, pid, num, stockAmount)
        return pid
    }

    private fun createOrder(itemDataId: Long, amount: Double, customerName: String, zipCode: String, city: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"$customerName","zipCode":"$zipCode","city":"$city",""" +
                    """"lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun generateBatch(orderIds: List<Long>, pickMode: String) =
        FreeTierBatchPickFixture(batchPickPort, tenantContext).generate(orderIds, CLIENT_ID, pickMode)

    /** One SKU, 100 on one stock unit; three orders 20/15/5 so the bulk line is 40 over three slices. */
    private fun bulkWave(tag: String): Triple<Long, Long, List<Long>> {
        seedStagingArea("PACK_STAGING"); seedStagingArea("SHIP_STAGING")
        val pid = seedProductWithStock(tag, 100.0)
        val orders = listOf(
            createOrder(pid, 20.0, "$tag Co", "10001", "NYC"),
            createOrder(pid, 15.0, "$tag Co", "20002", "DC"),
            createOrder(pid, 5.0, "$tag Co", "30003", "LA"),
        )
        val generated = generateBatch(orders, "BULK")
        return Triple(generated.waveId, generated.result.batchPickOrderIds.single(), orders)
    }

    @Test
    @TestSecurity(user = "op", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
        "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9303")])
    fun `bulk-lines and bulk-confirm over REST, non-bulk 409`() {
        val (_, batchId, _) = bulkWave("rest")
        val suId = given().`when`().get("/api/v1/pick-orders/$batchId/bulk-lines").then().statusCode(200)
            .body("size()", equalTo(1)).body("[0].plannedTotal", equalTo(40.0f))
            .extract().jsonPath().getLong("[0].sourceStockUnitId")
        given().contentType(ContentType.JSON).body("""{"sourceStockUnitId":$suId,"pickedAmount":27}""")
            .`when`().post("/api/v1/pick-orders/$batchId/bulk-confirm").then().statusCode(200)
            .body("filledSlices", equalTo(2)).body("shortSlices", equalTo(1))
            .body("slices[2].picked", equalTo(0.0f))
        given().contentType(ContentType.JSON).body("""{"sourceStockUnitId":$suId,"pickedAmount":1}""")
            .`when`().post("/api/v1/pick-orders/$batchId/bulk-confirm").then().statusCode(422)

        // A non-bulk (PICK_ONLY) batch order is refused with 409, not 200/404.
        val pid = seedProductWithStock("rest-nb", 10.0)
        val o = createOrder(pid, 3.0, "RB Co", "10001", "NYC")
        val nonBulk = generateBatch(listOf(o), "PICK_ONLY").result.batchPickOrderIds.single()
        given().`when`().get("/api/v1/pick-orders/$nonBulk/bulk-lines").then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "op", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
        "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9303")])
    fun `work inbox labels a claimable bulk pick order as Bulk`() {
        val (waveId, batchId, _) = bulkWave("inbox")
        // Batch PickOrders are minted RELEASED with no operator, so they are claimable (PickWorkProvider.listOpen).
        given().`when`().get("/api/v1/work/available?type=PICK").then().statusCode(200)
            .body("find { it.ref == 'PICK:$batchId' }.summary", containsString("Bulk pick order"))
            .body("find { it.ref == 'PICK:$batchId' }.summary", containsString("wave $waveId"))
    }

    /**
     * Defect-burndown-6 (row :2051, A5): the per-slice route `POST /picks/{id}/confirm` is refused
     * on a BULK pick order. Confirming one slice behind the fan-out's back breaks
     * [com.karyo.fulfillment.service.BulkPickService]'s open-slice arithmetic (the bulk line's
     * planned total and the operator's one CONFIRM press stop describing the same set of slices),
     * so the route 409s with `fulfillment-validation-failed` and names the bulk route instead. The
     * bulk route itself is unaffected: the full 40 still fans out across all three slices.
     */
    @Test
    @TestSecurity(user = "op", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
        "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9303")])
    fun `per-slice confirm is refused on a bulk pick order`() {
        val (_, batchId, _) = bulkWave("slice")
        val pickId = given().`when`().get("/api/v1/pick-orders/$batchId").then().statusCode(200)
            .extract().jsonPath().getLong("picks[0].id")

        given().contentType(ContentType.JSON).body("""{"pickedAmount":1}""")
            .`when`().post("/api/v1/picks/$pickId/confirm").then().statusCode(409)
            .body("type", endsWith("fulfillment-validation-failed"))

        val suId = given().`when`().get("/api/v1/pick-orders/$batchId/bulk-lines").then().statusCode(200)
            .extract().jsonPath().getLong("[0].sourceStockUnitId")
        given().contentType(ContentType.JSON).body("""{"sourceStockUnitId":$suId,"pickedAmount":40}""")
            .`when`().post("/api/v1/pick-orders/$batchId/bulk-confirm").then().statusCode(200)
            .body("filledSlices", equalTo(3)).body("shortSlices", equalTo(0))
    }

    private companion object {
        const val CLIENT_ID = 9303L
    }
}
