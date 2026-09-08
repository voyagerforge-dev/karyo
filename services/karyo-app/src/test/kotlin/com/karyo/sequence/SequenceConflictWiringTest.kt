package com.karyo.sequence

import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.ShipmentState
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * IMPORTANT 3 fix (Task 4 review): proves the PRODUCTION `isUnique` wiring for the three
 * fulfillment generation sites (`PickOrderService.releaseToPicking`, `ExtinguishService.extinguish`
 * mint path, `PackingService.openPacking`) — replaces [SequenceRolloutTest]'s earlier,
 * mutation-vulnerable version of this pin, which built a private [SequenceNumberService] with the
 * `isUnique` predicate RESTATED in the test rather than exercising the real CDI-injected one.
 *
 * Mechanism: [FixedCandidateTestGenerator] (selected via this class's `@TestProfile`) always
 * returns the same literal, so pre-seeding one colliding row per test's owning repository forces
 * every one of the real service's generation attempts to collide — driving `next` to
 * `SequenceException.Exhausted` (422 `sequence-exhausted`), never a distinct number. If any of
 * the three production `isUnique` lambdas were neutered to `{ true }`, the collision would go
 * undetected and either (a) `persist()` would violate the target table's own `UNIQUE` constraint,
 * or (b) an EXT merge/packing precondition would behave differently — either way NOT the clean
 * `sequence-exhausted` 422 these tests assert, so the mutation is killed regardless of the exact
 * failure shape a neutered predicate produces.
 *
 * Three DISTINCT client ids (one per test) keep the pre-seeded colliding rows from interfering
 * with each other or with a test's own prerequisite calls: `releaseToPicking` also runs (and
 * must succeed on its first, uncontested attempt) as a setup step for the packing test, so that
 * test's owner must not already carry the pick-order-side collision the pick-order test seeds
 * for ITS owner.
 */
@QuarkusTest
@TestProfile(SequenceConflictWiringTest.FixedGenerator::class)
class SequenceConflictWiringTest {

    class FixedGenerator : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.sequence.generator" to "FIXED_TEST")
    }

    @Inject lateinit var pickOrderRepository: PickOrderRepository
    @Inject lateinit var pickRepository: PickRepository
    @Inject lateinit var shipmentRepository: ShipmentRepository

    /** Fixed-length suffix — see [SequenceRolloutTest.uniqueSuffix]'s KDoc for why an untruncated
     *  `System.nanoTime()` is unsafe against a tightly-capped field like `CreateItemUnitRequest.
     *  name` (`@Size(max = 20)`); this class's "EXT-OU-" prefix (7 chars) is exactly the shape
     *  that surfaced the bug. */
    private fun uniqueSuffix() = System.nanoTime().toString().takeLast(SUFFIX_DIGITS)

    // ── REST seeding helpers (PickConfirmServiceTest / SequenceRolloutTest precedent) ────────

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

    /** Creates+releases a delivery order backed by fresh stock; returns the order id. */
    private fun seedAndReleaseOrder(): Long {
        val s = uniqueSuffix()
        val iu = createItemUnit("OU-$s")
        val num = "SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("UL-$s")
        createStock(ul, pid, num, 10.0)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$pid,"amount":10.0}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    /** Fresh, unreserved stock unit for an extinguish selector — no owning order. */
    private fun seedLooseStock(): Long {
        val s = uniqueSuffix()
        val iu = createItemUnit("EXT-OU-$s")
        val num = "EXT-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("EXT-UL-$s")
        return createStock(ul, pid, num, 5.0)
    }

    private fun persistCollidingPickOrder(clientId: Long) {
        QuarkusTransaction.requiringNew().run {
            val po = PickOrder().apply {
                this.clientId = clientId
                pickOrderNumber = FixedCandidateTestGenerator.FIXED_CANDIDATE
                deliveryOrderId = PLACEHOLDER_DELIVERY_ORDER_ID
                deliveryOrderNumber = "PLACEHOLDER"
                state = PickState.CANCELED.code
                started = Instant.now()
            }
            pickOrderRepository.persist(po)
        }
    }

    private fun persistCollidingShipment(clientId: Long) {
        QuarkusTransaction.requiringNew().run {
            val s = Shipment().apply {
                this.clientId = clientId
                shipmentNumber = FixedCandidateTestGenerator.FIXED_CANDIDATE
                deliveryOrderId = PLACEHOLDER_DELIVERY_ORDER_ID
                deliveryOrderNumber = "PLACEHOLDER"
                state = ShipmentState.SHIPPED.code
                started = Instant.now()
            }
            shipmentRepository.persist(s)
        }
    }

    // ── PickOrderService.releaseToPicking ─────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "sc17-wiring-po",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "60171"), Claim(key = "tenant_code", value = "ACME")])
    fun `releaseToPicking's real isUnique predicate rejects a forced collision — 422 sequence-exhausted`() {
        seedPackStaging()
        persistCollidingPickOrder(clientId = 60171L)
        val orderId = seedAndReleaseOrder()

        given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then()
            .statusCode(422)
            .body("type", `is`("https://karyo.com/errors/sequence-exhausted"))
    }

    // ── ExtinguishService.extinguish (mint path) ──────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "sc17-wiring-ext",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "60172"), Claim(key = "tenant_code", value = "GLOBEX")])
    fun `extinguish's real isUnique predicate rejects a forced collision — 422 sequence-exhausted`() {
        seedPackStaging()
        persistCollidingPickOrder(clientId = 60172L)
        val stockUnitId = seedLooseStock()

        given().contentType(ContentType.JSON).body("""{"stockUnitIds":[$stockUnitId]}""")
            .`when`().post("/api/v1/pick-orders/extinguish").then()
            .statusCode(422)
            .body("type", `is`("https://karyo.com/errors/sequence-exhausted"))
    }

    // ── PackingService.openPacking ─────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "sc17-wiring-pack",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "60173"), Claim(key = "tenant_code", value = "ACME")])
    fun `openPacking's real isUnique predicate rejects a forced collision — 422 sequence-exhausted`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrder()

        // This owner's FIRST pick-order-number generation must succeed uncontested (no colliding
        // pick_orders row seeded for client 60173) so the prerequisite pick+confirm flow reaches
        // PICKED cleanly -- only the SHIPMENT-number generation below is made to collide.
        val pickOrderId = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath().getLong("[0].id")
        pickRepository.findByPickOrderId(pickOrderId).forEach { pick ->
            given().contentType(ContentType.JSON).body("""{"pickedAmount":${pick.plannedAmount}}""")
                .`when`().post("/api/v1/picks/${pick.id}/confirm").then().statusCode(200)
        }

        persistCollidingShipment(clientId = 60173L)

        given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/shipments").then()
            .statusCode(422)
            .body("type", `is`("https://karyo.com/errors/sequence-exhausted"))
    }

    companion object {
        /** No FK on pick_orders.delivery_order_id / shipments.delivery_order_id (index only) —
         *  a placeholder id is safe; these rows exist only to occupy the FIXED_CANDIDATE value. */
        private const val PLACEHOLDER_DELIVERY_ORDER_ID = -1L
        private const val SUFFIX_DIGITS = 12
    }
}
