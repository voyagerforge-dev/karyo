package com.karyo.orders

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test

private const val LINK_DOCK_ID = 900L
private const val LINK_DOCK_NAME = "DOCK-01"

/**
 * Integration test (REAL beans, no mocks) for the V424 GoodsReceipt<->Asn
 * many-to-many: create-time `asnIds` binding (+ the legacy scalar `asnId`
 * alias), the `POST /{id}/asns` attach / `DELETE /{id}/asns/{asnId}` detach
 * endpoints, and legacy-faithful auto-attach on receive against an
 * unattached ASN's line.
 */
@QuarkusTest
class GoodsReceiptAsnLinkFlowTest {

    // ── REST seeding helpers ─────────────────────────────────────────────

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
            .body("""{"number":"$number","name":"Link Flow Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun seedProduct(suffix: Long): Long {
        val itemUnitId = createItemUnit("LF-${suffix.toString().takeLast(10)}")
        return createProduct("LF-SKU-$suffix", itemUnitId)
    }

    /** Creates a released, single-line ASN for [productId]; returns (asnId, asnLineId). */
    private fun createReleasedAsn(productId: Long, expectedAmount: Double = 10.0): Pair<Long, Long> {
        val created = given()
            .contentType(ContentType.JSON)
            .body("""{"carrierName":"DHL","lines":[{"itemDataId":$productId,"expectedAmount":$expectedAmount}]}""")
            .`when`().post("/api/v1/asns")
            .then().statusCode(201)
            .extract().jsonPath()
        val asnId = created.getLong("id")
        val asnLineId = created.getLong("lines[0].id")
        given()
            .contentType(ContentType.JSON)
            .`when`().post("/api/v1/asns/$asnId/release")
            .then().statusCode(200)
        return asnId to asnLineId
    }

    /** Creates a CREATED (not-yet-released) single-line ASN — for the state-guard case (c). */
    private fun createUnreleasedAsn(productId: Long, expectedAmount: Double = 10.0): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"carrierName":"DHL","lines":[{"itemDataId":$productId,"expectedAmount":$expectedAmount}]}""")
            .`when`().post("/api/v1/asns")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createReceipt(body: String = """{"carrierName":"DHL"}""", expectedStatus: Int = 201): JsonPath =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`().post("/api/v1/goods-receipts")
            .then().statusCode(expectedStatus)
            .extract().jsonPath()

    private fun receiveLine(
        receiptId: Long,
        asnLineId: Long,
        amount: Double,
        expectedStatus: Int = 201,
    ): JsonPath =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"asnLineId":$asnLineId,"amount":$amount,""" +
                    """"locationId":$LINK_DOCK_ID,"locationName":"$LINK_DOCK_NAME"}"""
            )
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(expectedStatus)
            .extract().jsonPath()

    private fun attachAsn(receiptId: Long, asnId: Long, expectedStatus: Int = 200): JsonPath =
        given()
            .contentType(ContentType.JSON)
            .body("""{"asnId":$asnId}""")
            .`when`().post("/api/v1/goods-receipts/$receiptId/asns")
            .then().statusCode(expectedStatus)
            .extract().jsonPath()

    private fun detachAsn(receiptId: Long, asnId: Long, expectedStatus: Int) =
        given()
            .`when`().delete("/api/v1/goods-receipts/$receiptId/asns/$asnId")
            .then().statusCode(expectedStatus)

    private fun getReceipt(receiptId: Long): JsonPath =
        given()
            .`when`().get("/api/v1/goods-receipts/$receiptId")
            .then().statusCode(200)
            .extract().jsonPath()

    private fun finishReceipt(receiptId: Long) =
        given()
            .`when`().post("/api/v1/goods-receipts/$receiptId/finish")
            .then().statusCode(200)

    /** JsonPath returns bound-scale ASN ids as Integer, not Long -- normalize before comparing to a Long fixture id. */
    private fun JsonPath.boundAsnIds(): List<Long> = getList<Any>("asns.id").map { (it as Number).toLong() }

    // ── (a) create with asnIds=[A,B]; receive against a line of each ───────

    @Test
    @TestSecurity(user = "op-link-a", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with asnIds carries both ASNs, receiving against either decrements it`() {
        val suffix = System.nanoTime()
        val productA = seedProduct(suffix)
        val productB = seedProduct(suffix + 1)
        val (asnA, lineA) = createReleasedAsn(productA)
        val (asnB, lineB) = createReleasedAsn(productB)

        val created = createReceipt("""{"carrierName":"DHL","asnIds":[$asnA,$asnB]}""")
        val receiptId = created.getLong("id")
        assertThat(created.boundAsnIds()).containsExactlyInAnyOrder(asnA, asnB)

        receiveLine(receiptId, lineA, amount = 4.0)
        receiveLine(receiptId, lineB, amount = 6.0)

        val asnAAfter = given().`when`().get("/api/v1/asns/$asnA").then().statusCode(200).extract().jsonPath()
        val asnBAfter = given().`when`().get("/api/v1/asns/$asnB").then().statusCode(200).extract().jsonPath()
        assertThat(asnAAfter.getDouble("lines[0].receivedAmount")).isEqualTo(4.0)
        assertThat(asnBAfter.getDouble("lines[0].receivedAmount")).isEqualTo(6.0)

        val finalReceipt = getReceipt(receiptId)
        assertThat(finalReceipt.boundAsnIds()).containsExactlyInAnyOrder(asnA, asnB)
    }

    // ── (b) auto-attach on receive against an unattached ASN's line; dedupe ─

    @Test
    @TestSecurity(user = "op-link-b", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `receiving against an unattached ASN line auto-attaches it, second receive dedupes`() {
        val suffix = System.nanoTime()
        val productC = seedProduct(suffix)
        val (asnC, lineC) = createReleasedAsn(productC, expectedAmount = 20.0)

        val receiptId = createReceipt().getLong("id")
        assertThat(getReceipt(receiptId).getList<Any>("asns")).isEmpty()

        receiveLine(receiptId, lineC, amount = 3.0)
        val afterFirst = getReceipt(receiptId)
        assertThat(afterFirst.boundAsnIds()).containsExactly(asnC)

        // Second receive against the SAME (now-attached) ASN's line -- dedupe, no duplicate join row.
        receiveLine(receiptId, lineC, amount = 2.0)
        val afterSecond = getReceipt(receiptId)
        assertThat(afterSecond.boundAsnIds()).containsExactly(asnC)
    }

    // ── (c) attach endpoint guards: CREATED ASN -> 409, RETOUR -> 422 ──────

    @Test
    @TestSecurity(user = "op-link-c", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `attach refuses a non-receivable ASN with 409 and a RETOUR receipt with 422`() {
        val suffix = System.nanoTime()
        val product = seedProduct(suffix)
        val unreleasedAsn = createUnreleasedAsn(product)
        val (releasedAsn, _) = createReleasedAsn(seedProduct(suffix + 1))

        val receiptId = createReceipt().getLong("id")
        attachAsn(receiptId, unreleasedAsn, expectedStatus = 409)
            .let { assertThat(it.getString("type")).isEqualTo("https://karyo.com/errors/asn-not-receivable") }

        val retourReceiptId = createReceipt("""{"carrierName":"DHL","receiptType":1}""").getLong("id")
        attachAsn(retourReceiptId, releasedAsn, expectedStatus = 422)
            .let { assertThat(it.getString("type")).isEqualTo("https://karyo.com/errors/retour-with-asn") }
    }

    // ── (d) detach: 409 while a live line references it, 204 after reversal ─

    @Test
    @TestSecurity(user = "op-link-d", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `detach is refused while a non-reversed line references the ASN, allowed after reversal`() {
        val suffix = System.nanoTime()
        val product = seedProduct(suffix)
        val (asnId, lineId) = createReleasedAsn(product)

        val receiptId = createReceipt("""{"carrierName":"DHL","asnIds":[$asnId]}""").getLong("id")
        val received = receiveLine(receiptId, lineId, amount = 5.0)
        val grLineId = received.getLong("lineId")

        detachAsn(receiptId, asnId, expectedStatus = 409)

        given()
            .`when`().delete("/api/v1/goods-receipts/$receiptId/lines/$grLineId")
            .then().statusCode(200)
            .body("lines[0].reversed", `is`(true))

        detachAsn(receiptId, asnId, expectedStatus = 204)
        assertThat(getReceipt(receiptId).getList<Any>("asns")).isEmpty()
    }

    // ── (e) legacy scalar asnId alias still works ───────────────────────────

    @Test
    @TestSecurity(user = "op-link-e", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `legacy scalar asnId still binds via the create endpoint`() {
        val suffix = System.nanoTime()
        val product = seedProduct(suffix)
        val (asnId, _) = createReleasedAsn(product)

        val created = createReceipt("""{"carrierName":"DHL","asnId":$asnId}""")
        assertThat(created.boundAsnIds()).containsExactly(asnId)
        assertThat(created.getString("asns[0].asnNumber")).isNotBlank()
    }

    // ── (f) attach/detach refuse on a closed (FINISHED) receipt ─────────────

    @Test
    @TestSecurity(user = "op-link-f", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `attach to a FINISHED receipt is refused with 409`() {
        val suffix = System.nanoTime()
        val product = seedProduct(suffix)
        val (asnId, _) = createReleasedAsn(product)

        val receiptId = createReceipt().getLong("id")
        finishReceipt(receiptId)

        attachAsn(receiptId, asnId, expectedStatus = 409)
            .let { assertThat(it.getString("type")).isEqualTo("https://karyo.com/errors/receipt-not-receivable") }
    }

    @Test
    @TestSecurity(user = "op-link-g", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `detach from a FINISHED receipt is refused with 409`() {
        val suffix = System.nanoTime()
        val product = seedProduct(suffix)
        val (asnId, _) = createReleasedAsn(product)

        val receiptId = createReceipt("""{"carrierName":"DHL","asnIds":[$asnId]}""").getLong("id")
        finishReceipt(receiptId)

        detachAsn(receiptId, asnId, expectedStatus = 409)
    }
}
