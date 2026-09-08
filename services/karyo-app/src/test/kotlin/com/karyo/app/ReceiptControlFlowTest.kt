package com.karyo.app

import com.karyo.orders.repository.GoodsReceiptRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

private const val CONTROL_DOCK_ID = 900L
private const val CONTROL_DOCK_NAME = "DOCK-01"

/**
 * Integration test (REAL beans, no mocks) for the B7 goods-receipt CONTROL surface:
 * operator claim/release (pure metadata — `state` NEVER moves; the pick precedent's
 * state hop is not copied, see [com.karyo.orders.service.GoodsReceiptService.claim]),
 * the orthogonal pause (`paused_at` stamp; blocks receiveLine AND finish; resume is
 * LOSSLESS — the state comes back exactly where it was, unlike myWMS), and the
 * prio/receiptDate/dock header scalars incl. the PUT update path.
 *
 * Claim/release semantics mirror PickOrderService.claim/release: a claim on an
 * already-claimed receipt is 409 EVEN FOR THE SAME OPERATOR (not idempotent), and a
 * non-owner release needs the MANAGER role (asManager) or it is a 409 — the pick
 * release maps its operator mismatch to 409 (fulfillment-validation-failed), not 403.
 */
@QuarkusTest
class ReceiptControlFlowTest {

    @Inject
    lateinit var goodsReceiptRepository: GoodsReceiptRepository

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun createItemUnit(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Receipt Control Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createReceipt(body: String = """{"carrierName":"DHL"}"""): JsonPath =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`().post("/api/v1/goods-receipts")
            .then().statusCode(201)
            .extract().jsonPath()

    private fun receiveLine(receiptId: Long, itemDataId: Long, amount: Double, expectedStatus: Int = 201): JsonPath =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"amount":$amount,""" +
                    """"locationId":$CONTROL_DOCK_ID,"locationName":"$CONTROL_DOCK_NAME"}"""
            )
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(expectedStatus)
            .extract().jsonPath()

    /** Bodiless action POST — explicit JSON content type (the 415-before-@RolesAllowed trap). */
    private fun action(receiptId: Long, action: String) =
        given()
            .contentType(ContentType.JSON)
            .`when`().post("/api/v1/goods-receipts/$receiptId/$action")
            .then()

    private fun getReceipt(receiptId: Long): JsonPath =
        given()
            .`when`().get("/api/v1/goods-receipts/$receiptId")
            .then().statusCode(200)
            .extract().jsonPath()

    /**
     * Seeds a claim by a DIFFERENT operator than the test principal. Native SQL on
     * purpose: @TestSecurity fixes one identity per method, so "claimed by another"
     * cannot be produced through the REST surface within a single test.
     */
    @Transactional
    fun forceClaim(receiptId: Long, operator: String) {
        goodsReceiptRepository.getEntityManager()
            .createNativeQuery("UPDATE karyo.goods_receipts SET operator_id = :op WHERE id = :id")
            .setParameter("op", operator)
            .setParameter("id", receiptId)
            .executeUpdate()
    }

    private fun seedProduct(suffix: Long): Long {
        val itemUnitId = createItemUnit("RC-${suffix.toString().takeLast(10)}")
        return createProduct("RC-SKU-$suffix", itemUnitId)
    }

    // ── Claim / release ──────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "op-anna", roles = ["product-read", "product-write", "order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `claim and release are pure metadata - state never moves, self re-claim is 409`() {
        val receiptId = createReceipt().getLong("id")

        // Claim: operatorId = the caller's username; state STAYS CREATED(50).
        action(receiptId, "claim")
            .statusCode(200)
            .body("operatorId", `is`("op-anna"))
            .body("state", `is`(50))

        // Self re-claim: NOT idempotent — 409, matching PickOrderService.claim's
        // `operatorId != null` refusal (any existing claim conflicts, own included).
        action(receiptId, "claim")
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-claim-conflict"))

        // Owner release: clears the metadata, state still untouched.
        action(receiptId, "release")
            .statusCode(200)
            .body("operatorId", nullValue())
            .body("state", `is`(50))

        // Released -> claimable again.
        action(receiptId, "claim").statusCode(200).body("operatorId", `is`("op-anna"))
    }

    @Test
    @TestSecurity(user = "op-eddy", roles = ["product-read", "product-write", "order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `releasing an unclaimed receipt as a non-manager is a no-op success`() {
        val receiptId = createReceipt().getLong("id")

        action(receiptId, "release")
            .statusCode(200)
            .body("operatorId", nullValue())
            .body("state", `is`(50))

        assertThat(getReceipt(receiptId).getString("operatorId")).isNull()
    }

    @Test
    @TestSecurity(user = "op-bert", roles = ["product-read", "product-write", "order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `claim conflict and non-owner release without MANAGER are 409`() {
        val receiptId = createReceipt().getLong("id")
        forceClaim(receiptId, "someone-else")

        // Claimed by another -> claim refused.
        action(receiptId, "claim")
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-claim-conflict"))

        // Non-owner release without the MANAGER role -> 409 (the pick precedent maps
        // its operator mismatch to 409, not 403 — matched here).
        action(receiptId, "release")
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-claim-conflict"))
        assertThat(getReceipt(receiptId).getString("operatorId")).isEqualTo("someone-else")
    }

    @Test
    @TestSecurity(user = "mgr-clara", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `MANAGER releases another operator's claim (asManager override)`() {
        val receiptId = createReceipt().getLong("id")
        forceClaim(receiptId, "someone-else")

        action(receiptId, "release")
            .statusCode(200)
            .body("operatorId", nullValue())
            .body("state", `is`(50))
    }

    @Test
    @TestSecurity(user = "op-dora", roles = ["product-read", "product-write", "order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `closed receipts cannot be claimed`() {
        val receiptId = createReceipt().getLong("id")
        // Finish the (empty) receipt -> FINISHED(700); a closed receipt is not claimable.
        action(receiptId, "finish").statusCode(200).body("state", `is`(700))

        action(receiptId, "claim")
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-claim-conflict"))
    }

    @Test
    @TestSecurity(user = "op-hana", roles = ["product-read", "product-write", "order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `paused receipts refuse claims`() {
        val receiptId = createReceipt().getLong("id")

        action(receiptId, "pause").statusCode(200).body("pausedAt", notNullValue())

        action(receiptId, "claim")
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-claim-conflict"))
    }

    // ── Pause / resume ───────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "op-elsa",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "OPERATOR"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pause blocks receiveLine and finish - resume is lossless and unblocks both`() {
        val suffix = System.nanoTime()
        val product = seedProduct(suffix)
        val receiptId = createReceipt().getLong("id")
        receiveLine(receiptId, product, amount = 3.0) // -> STARTED(500)

        // Pause: orthogonal stamp; state STAYS STARTED — Karyo deliberately diverges from
        // myWMS's lossy state-jump (STARTED -> PAUSE -> resume recomputes to PROCESSABLE).
        action(receiptId, "pause")
            .statusCode(200)
            .body("pausedAt", notNullValue())
            .body("state", `is`(500))

        // While paused: receiving and finishing are both refused — else pause is decorative.
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$product,"amount":1.0,""" +
                    """"locationId":$CONTROL_DOCK_ID,"locationName":"$CONTROL_DOCK_NAME"}"""
            )
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-paused"))
        action(receiptId, "finish")
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-paused"))

        // Double pause -> 409 (a second pause means the caller's view is stale).
        action(receiptId, "pause")
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-pause-conflict"))

        // Resume: clears the stamp; the receipt is EXACTLY where it was (STARTED).
        action(receiptId, "resume")
            .statusCode(200)
            .body("pausedAt", nullValue())
            .body("state", `is`(500))

        // Unblocked: receiving works again, then finish closes the receipt.
        receiveLine(receiptId, product, amount = 1.0)
        action(receiptId, "finish").statusCode(200).body("state", `is`(700))

        // Pausing a FINISHED receipt -> 409; resuming a non-paused one -> 409.
        action(receiptId, "pause")
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-pause-conflict"))
        action(receiptId, "resume")
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-pause-conflict"))
    }

    // ── Scalars: prio / receiptDate / dock ───────────────────────────────

    @Test
    @TestSecurity(user = "op-finn", roles = ["product-read", "product-write", "order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `prio, backdated receiptDate and dock pair round-trip on create and update`() {
        // Create with all three scalars — receiptDate deliberately BACKDATED (the date
        // the goods physically arrived, operator-entered, not the record timestamp).
        val created = createReceipt(
            """{"carrierName":"DHL","prio":10,"receiptDate":"2026-07-01",""" +
                """"dockLocationId":$CONTROL_DOCK_ID,"dockLocationName":"$CONTROL_DOCK_NAME"}"""
        )
        val receiptId = created.getLong("id")
        assertThat(created.getInt("prio")).isEqualTo(10)
        assertThat(created.getString("receiptDate")).isEqualTo("2026-07-01")
        assertThat(created.getLong("dockLocationId")).isEqualTo(CONTROL_DOCK_ID)
        assertThat(created.getString("dockLocationName")).isEqualTo(CONTROL_DOCK_NAME)

        // Update all three (null = leave unchanged, the UpdateAsnRequest convention).
        given()
            .contentType(ContentType.JSON)
            .body("""{"prio":80,"receiptDate":"2026-06-15","dockLocationId":901,"dockLocationName":"DOCK-02"}""")
            .`when`().put("/api/v1/goods-receipts/$receiptId")
            .then().statusCode(200)
            .body("prio", `is`(80))
            .body("receiptDate", `is`("2026-06-15"))
            .body("dockLocationId", `is`(901))
            .body("dockLocationName", `is`("DOCK-02"))

        // Partial update leaves the others alone.
        given()
            .contentType(ContentType.JSON)
            .body("""{"prio":75}""")
            .`when`().put("/api/v1/goods-receipts/$receiptId")
            .then().statusCode(200)
            .body("prio", `is`(75))
            .body("receiptDate", `is`("2026-06-15"))
            .body("dockLocationName", `is`("DOCK-02"))

        // Defaults: prio 50 (the DeliveryOrder.prio convention), everything else null.
        val plain = createReceipt()
        assertThat(plain.getInt("prio")).isEqualTo(50)
        assertThat(plain.get<Any?>("receiptDate")).isNull()
        assertThat(plain.get<Any?>("dockLocationId")).isNull()
        assertThat(plain.get<Any?>("dockLocationName")).isNull()
        assertThat(plain.get<Any?>("operatorId")).isNull()
        assertThat(plain.get<Any?>("pausedAt")).isNull()
    }

    @Test
    @TestSecurity(user = "op-gerd", roles = ["product-read", "product-write", "order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `closed receipts are not editable`() {
        val receiptId = createReceipt().getLong("id")
        action(receiptId, "finish").statusCode(200).body("state", `is`(700))

        given()
            .contentType(ContentType.JSON)
            .body("""{"prio":10}""")
            .`when`().put("/api/v1/goods-receipts/$receiptId")
            .then().statusCode(409)
            .body("type", `is`("https://karyo.com/errors/order-not-editable"))
    }
}
