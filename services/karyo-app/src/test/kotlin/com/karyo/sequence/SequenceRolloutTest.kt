package com.karyo.sequence

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test

/**
 * SC17 rollout pins (Task 4 brief), exercising the real REST/service wiring rather than
 * re-testing [SequenceNumberService] mechanics (already covered by [SequenceNumberServiceTest]):
 *
 *  (b) an order number long enough to overflow `pick_orders.pick_order_number` (VARCHAR(80), SC17
 *      V606) once embedded in the `"PO-{orderNumber}-..."` prefix now surfaces as 422
 *      `sequence-too-long` through the real `POST /api/v1/pick-orders` endpoint — the overflow
 *      landmine this rollout closes (it used to reach the DB uncomputed and fail as a 500).
 *  (c) the migrated candidate shape is preserved end-to-end: a normal-length order still produces
 *      a `pickOrderNumber` matching `^PO-{orderNumber}-\d{13}-\d{3}$` — the full TIMESTAMP_RANDOM
 *      tail shape, not just the embedded-prefix half.
 *
 * Pin (a) (real conflict-checking on a forced collision) moved to
 * [SequenceConflictWiringTest] — this class's earlier version of that pin built a private
 * [SequenceNumberService] with the `isUnique` predicate RESTATED in the test rather than
 * exercising the production, CDI-injected wiring, so deleting the predicate from
 * `PickOrderService`/`ExtinguishService`/`PackingService` failed no test (Task 4 review,
 * IMPORTANT 3). [SequenceConflictWiringTest] fixes that by driving the real REST endpoints under
 * a `@TestProfile`-selected deterministic generator.
 */
@QuarkusTest
class SequenceRolloutTest {

    /**
     * Fixed-length (not just fixed-magnitude) suffix: `System.nanoTime()`'s decimal string length
     * varies with JVM/process uptime (14-19 digits depending on the platform's monotonic-clock
     * epoch), so an UNTRUNCATED nanoTime embedded in a tightly-capped field (e.g.
     * `CreateItemUnitRequest.name`, `@Size(max = 20)`) can silently cross that cap hours into an
     * uptime and turn a passing test flaky — caught during Task 4's review fix-round when a
     * longer-prefixed sibling helper in [SequenceConflictWiringTest] did exactly that. 12 digits
     * leaves comfortable room under every `@Size` this suite's helpers write into, including a
     * 7-char prefix against a 20-char cap, while remaining unique across a single test run.
     */
    private fun uniqueSuffix() = System.nanoTime().toString().takeLast(SUFFIX_DIGITS)

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
        val s = uniqueSuffix()
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-$s","usages":["PACK_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PStg-$s"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-LOC-$s","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    /** Creates+releases a delivery order with the given [orderNumber], backed by fresh stock. */
    private fun seedAndReleaseOrder(orderNumber: String): Long {
        val s = uniqueSuffix()
        val iu = createItemUnit("OU-$s")
        val num = "SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("UL-$s")
        createStock(ul, pid, num, 10.0)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"orderNumber":"$orderNumber","customerName":"C","lines":[{"itemDataId":$pid,"amount":10.0}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    @Test
    @TestSecurity(
        user = "sc17",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an over-length embedded order number surfaces 422 TooLong, not a DB 500`() {
        seedPackStaging()
        // pick_orders.pick_order_number is VARCHAR(80) (SC17 V606 widened it from 40 for exactly
        // this reason — see that migration's KDoc); "PO-" + a ~94-char orderNumber already
        // overflows it before the generator even appends its millis-random tail. Stays within
        // CreateDeliveryOrderRequest's own @Size(max=100) orderNumber cap (80 + "-" + 13-digit
        // millis = 94), so this is refused for the RIGHT reason (sequence overflow), not a 400
        // from the order-create validation itself.
        val longOrderNumber = "N".repeat(NEAR_CAP_ORDER_NUMBER_LENGTH) + "-${System.currentTimeMillis()}"
        val orderId = seedAndReleaseOrder(longOrderNumber)

        given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then()
            .statusCode(422)
            .body("type", `is`("https://karyo.com/errors/sequence-too-long"))
    }

    @Test
    @TestSecurity(
        user = "sc17b",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a normal-length order still produces a pickOrderNumber shaped PO-orderNumber-millis-rand`() {
        seedPackStaging()
        val orderNumber = "ORD-${uniqueSuffix()}"
        val orderId = seedAndReleaseOrder(orderNumber)

        val pickOrderNumber = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201)
            .extract().jsonPath().getString("[0].pickOrderNumber")

        // Full candidate shape, not just the embedded-prefix half: "PO-{orderNumber}-" followed
        // by TimestampRandomGenerator's own tail ("{13-digit millis}-{3-digit rand}").
        assertThat(pickOrderNumber).matches("^PO-${Regex.escape(orderNumber)}-\\d{13}-\\d{3}$")
    }

    companion object {
        /** 80 'N's + "-" + 13-digit millis = 94 chars — near CreateDeliveryOrderRequest's own 100-char cap. */
        private const val NEAR_CAP_ORDER_NUMBER_LENGTH = 80
        private const val SUFFIX_DIGITS = 12
    }
}
