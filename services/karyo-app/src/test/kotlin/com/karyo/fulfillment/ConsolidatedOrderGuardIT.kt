package com.karyo.fulfillment

import com.karyo.fulfillment.service.BulkPickService
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
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import com.karyo.fulfillment.api.v1.dto.BulkConfirmRequest
import java.math.BigDecimal

/**
 * B12 ruling (Bulk Allocation Sprint C, Task 2): a per-order [PackingService.openPacking] on a
 * wave member whose picks live on a batch PickOrder must be refused with a 409 pointing at the
 * consolidation group, not fall through to the generic "no pick order for this order" error.
 * [FreeTierBatchPickFixture] creates the batch work without the commercial wave REST resource.
 */
@QuarkusTest
class ConsolidatedOrderGuardIT {

    @Inject
    lateinit var bulkPickService: BulkPickService

    @Inject
    lateinit var batchPickPort: BatchPickPort

    @Inject
    lateinit var tenantContext: TenantContext

    // ── Fixture helpers (copied verbatim from BulkPickServiceIT) ────────────

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
            .body("""{"name":"COG-$usage-${System.nanoTime()}","usages":["$usage"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"COG-$usage-LT-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"COG-$usage-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    /** Seeds a product with [stockAmount] of stock on its own unit load. Returns the product/item id. */
    private fun seedProductWithStock(tag: String, stockAmount: Double): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val num = "COG-$tag-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("COG-$tag-UL-$s")
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

    /** One SKU, 100 on one stock unit; three orders 20/15/5 so the bulk line is 40 over three slices. */
    private fun bulkWave(tag: String): Triple<Long, Long, List<Long>> {
        seedStagingArea("PACK_STAGING"); seedStagingArea("SHIP_STAGING")
        val pid = seedProductWithStock(tag, 100.0)
        val orders = listOf(
            createOrder(pid, 20.0, "$tag Co", "10001", "NYC"),
            createOrder(pid, 15.0, "$tag Co", "20002", "DC"),
            createOrder(pid, 5.0, "$tag Co", "30003", "LA"),
        )
        val generated = FreeTierBatchPickFixture(batchPickPort, tenantContext)
            .generate(orders, CLIENT_ID, "BULK")
        return Triple(generated.waveId, generated.result.batchPickOrderIds.single(), orders)
    }

    @Test
    @TestSecurity(user = "op", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
        "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9402")])
    fun `per-order openPacking on a batch-picked wave member is refused with a pointer to the group`() {
        val (waveId, batchId, orders) = bulkWave("b12")
        // Pick everything so the member orders are PICKED (the state that used to reach the empty-picks error).
        val line = bulkPickService.bulkLines(batchId, CLIENT_ID).single()
        tenantContext.clientId = CLIENT_ID
        bulkPickService.bulkConfirm(batchId, BulkConfirmRequest(line.sourceStockUnitId, BigDecimal(40)), CLIENT_ID)
        given().contentType(ContentType.JSON).body("""{"deliveryOrderId":${orders[0]}}""")
            .`when`().post("/api/v1/shipments").then().statusCode(409)
            .body("type", equalTo("https://karyo.com/errors/fulfillment-validation-failed"))
            .body("detail", containsString("consolidated in wave $waveId"))
    }

    private companion object {
        const val CLIENT_ID = 9402L
    }
}
