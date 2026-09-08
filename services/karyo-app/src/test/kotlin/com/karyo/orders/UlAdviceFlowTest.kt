package com.karyo.orders

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val UL_DOCK_ID = 900L
private const val UL_DOCK_NAME = "DOCK-01"

/**
 * Integration test (REAL beans, no mocks) for UL pre-advice — a Karyo-native
 * capability (no myWMS behavioral reference; see [com.karyo.orders.domain.model.AsnUlAdvice]'s
 * KDoc for why): create/delete on an ASN, the ZPL pre-print sheet, and the silent
 * receive-time match ([com.karyo.orders.service.AsnUlAdviceService.match]).
 */
@QuarkusTest
class UlAdviceFlowTest {

    // ── REST seeding helpers (mirrors GoodsReceiptAsnLinkFlowTest) ──────────

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
            .body("""{"number":"$number","name":"UL Advice Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun seedProduct(suffix: Long): Long {
        val itemUnitId = createItemUnit("UA-${suffix.toString().takeLast(10)}")
        return createProduct("UA-SKU-$suffix", itemUnitId)
    }

    /** Creates a CREATED (not-yet-released) single-line ASN; returns (asnId, asnLineId). */
    private fun createAsn(productId: Long, expectedAmount: Double = 10.0): Pair<Long, Long> {
        val created = given()
            .contentType(ContentType.JSON)
            .body("""{"carrierName":"DHL","lines":[{"itemDataId":$productId,"expectedAmount":$expectedAmount}]}""")
            .`when`().post("/api/v1/asns")
            .then().statusCode(201)
            .extract().jsonPath()
        return created.getLong("id") to created.getLong("lines[0].id")
    }

    private fun releaseAsn(asnId: Long) =
        given().`when`().post("/api/v1/asns/$asnId/release").then().statusCode(200)

    private fun getAsn(asnId: Long): JsonPath =
        given().`when`().get("/api/v1/asns/$asnId").then().statusCode(200).extract().jsonPath()

    private fun createAdvice(asnId: Long, body: String, expectedStatus: Int = 201): JsonPath =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`().post("/api/v1/asns/$asnId/ul-advices")
            .then().statusCode(expectedStatus)
            .extract().jsonPath()

    private fun deleteAdvice(asnId: Long, adviceId: Long, expectedStatus: Int) =
        given().`when`().delete("/api/v1/asns/$asnId/ul-advices/$adviceId").then().statusCode(expectedStatus)

    private fun ulLabelsZpl(asnId: Long): String =
        given().`when`().get("/api/v1/asns/$asnId/ul-labels.zpl").then().statusCode(200).extract().asString()

    private fun createReceipt(body: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`().post("/api/v1/goods-receipts")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun receiveLine(receiptId: Long, asnLineId: Long, amount: Double, unitLoadLabel: String): JsonPath =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"asnLineId":$asnLineId,"amount":$amount,"unitLoadLabel":"$unitLoadLabel",""" +
                    """"locationId":$UL_DOCK_ID,"locationName":"$UL_DOCK_NAME"}"""
            )
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(201)
            .extract().jsonPath()

    private fun reverseLine(receiptId: Long, lineId: Long): JsonPath =
        given()
            .`when`().delete("/api/v1/goods-receipts/$receiptId/lines/$lineId")
            .then().statusCode(200)
            .extract().jsonPath()

    // ── (a) add 2 advices to a CREATED ASN -> AsnResponse carries them ──────

    @Test
    @TestSecurity(user = "op-ula-a", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `advices added to a CREATED ASN are carried on AsnResponse`() {
        val suffix = System.nanoTime()
        val productId = seedProduct(suffix)
        val (asnId, _) = createAsn(productId)

        val advice1 = createAdvice(asnId, """{"labelId":"ULA-A-$suffix-1","itemDataId":$productId}""")
        assertThat(advice1.getString("labelId")).isEqualTo("ULA-A-$suffix-1")
        assertThat(advice1.getString("stateName")).isEqualTo("CREATED")
        assertThat(advice1.getLong("itemDataId")).isEqualTo(productId)
        assertThat(advice1.getString("itemDataNumber")).isEqualTo("UA-SKU-$suffix")

        createAdvice(asnId, """{"labelId":"ULA-A-$suffix-2"}""")

        val asn = getAsn(asnId)
        assertThat(asn.getList<Any>("ulAdvices")).hasSize(2)
        assertThat(asn.getList<String>("ulAdvices.labelId"))
            .containsExactlyInAnyOrder("ULA-A-$suffix-1", "ULA-A-$suffix-2")
    }

    // ── (a2) omitted labelId is server-generated (ULA- prefix), unique per ASN ─

    @Test
    @TestSecurity(user = "op-ula-a2", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an omitted labelId is server-generated with the ULA- prefix`() {
        val suffix = System.nanoTime()
        val productId = seedProduct(suffix)
        val (asnId, _) = createAsn(productId)

        val advice = createAdvice(asnId, "{}")
        assertThat(advice.getString("labelId")).startsWith("ULA-")
    }

    // ── (b) create on a STARTED ASN -> 409 ───────────────────────────────────

    @Test
    @TestSecurity(user = "op-ula-b", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create refuses a STARTED ASN with 409`() {
        val suffix = System.nanoTime()
        val productId = seedProduct(suffix)
        val (asnId, lineId) = createAsn(productId)
        releaseAsn(asnId)
        val receiptId = createReceipt("""{"carrierName":"DHL","asnIds":[$asnId]}""")
        receiveLine(receiptId, lineId, amount = 3.0, unitLoadLabel = "UL-STARTED-$suffix")

        assertThat(getAsn(asnId).getString("stateName")).isEqualTo("STARTED")
        createAdvice(asnId, """{"labelId":"ULA-B-$suffix"}""", expectedStatus = 409)
    }

    // ── (c) GET ul-labels.zpl returns both blocks, each containing its labelId ─

    @Test
    @TestSecurity(user = "op-ula-c", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `ul-labels zpl renders one XA-XZ block per advice`() {
        val suffix = System.nanoTime()
        val productId = seedProduct(suffix)
        val (asnId, _) = createAsn(productId)
        createAdvice(asnId, """{"labelId":"ULA-C-$suffix-1"}""")
        createAdvice(asnId, """{"labelId":"ULA-C-$suffix-2"}""")

        val zpl = ulLabelsZpl(asnId)
        assertThat(zpl).contains("^XA").contains("^XZ")
        assertThat(zpl.split("^XA").size - 1).isEqualTo(2)
        assertThat(zpl).contains("ULA-C-$suffix-1").contains("ULA-C-$suffix-2")
    }

    // ── (d) receive matches an advised label; an un-advised label is unaffected ─

    @Test
    @TestSecurity(user = "op-ula-d", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `receiving an advised label matches it, an un-advised label receives fine and leaves the rest untouched`() {
        val suffix = System.nanoTime()
        val productId = seedProduct(suffix)
        val (asnId, lineId) = createAsn(productId, expectedAmount = 20.0)
        val advice1 = createAdvice(asnId, """{"labelId":"ULA-D-$suffix-1"}""")
        val advice2 = createAdvice(asnId, """{"labelId":"ULA-D-$suffix-2"}""")
        val advice1Id = advice1.getLong("id")
        val advice2Id = advice2.getLong("id")
        releaseAsn(asnId)

        val receiptId = createReceipt("""{"carrierName":"DHL","asnIds":[$asnId]}""")
        val received = receiveLine(receiptId, lineId, amount = 4.0, unitLoadLabel = "ULA-D-$suffix-1")
        val matchedLineId = received.getLong("lineId")

        // un-advised label -- receives fine, no error, no advice touched.
        receiveLine(receiptId, lineId, amount = 3.0, unitLoadLabel = "UNADVISED-$suffix")

        val asnAfter = getAsn(asnId)
        val advices = asnAfter.getList<Map<String, Any>>("ulAdvices")
        val matched = advices.first { it["id"] == advice1Id.toInt() || (it["id"] as Number).toLong() == advice1Id }
        val untouched = advices.first { (it["id"] as Number).toLong() == advice2Id }

        assertThat(matched["stateName"]).isEqualTo("FINISHED")
        assertThat((matched["matchedReceiptLineId"] as Number).toLong()).isEqualTo(matchedLineId)
        assertThat(untouched["stateName"]).isEqualTo("CREATED")
        assertThat(untouched["matchedReceiptLineId"]).isNull()
    }

    // ── (d2) a blind receipt (no ASN attached at all) never matches ─────────

    @Test
    @TestSecurity(user = "op-ula-d2", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a blind receipt never matches even when a label happens to equal an open advice's labelId`() {
        val suffix = System.nanoTime()
        val productId = seedProduct(suffix)
        val (asnId, _) = createAsn(productId)
        val advice = createAdvice(asnId, """{"labelId":"ULA-D2-$suffix"}""")
        val adviceId = advice.getLong("id")

        val blindReceiptId = createReceipt("""{"carrierName":"DHL"}""")
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$productId,"amount":2.0,"unitLoadLabel":"ULA-D2-$suffix",""" +
                    """"locationId":$UL_DOCK_ID,"locationName":"$UL_DOCK_NAME"}"""
            )
            .`when`().post("/api/v1/goods-receipts/$blindReceiptId/lines")
            .then().statusCode(201)

        val asnAfter = getAsn(asnId)
        val advices = asnAfter.getList<Map<String, Any>>("ulAdvices")
        val stillOpen = advices.first { (it["id"] as Number).toLong() == adviceId }
        assertThat(stillOpen["stateName"]).isEqualTo("CREATED")
    }

    // ── (e) delete: matched -> 409, unmatched -> 204 ─────────────────────────

    @Test
    @TestSecurity(user = "op-ula-e", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delete refuses a matched advice with 409, deletes an unmatched one with 204`() {
        val suffix = System.nanoTime()
        val productId = seedProduct(suffix)
        val (asnId, lineId) = createAsn(productId, expectedAmount = 20.0)
        val matchedAdviceId = createAdvice(asnId, """{"labelId":"ULA-E-$suffix-1"}""").getLong("id")
        val unmatchedAdviceId = createAdvice(asnId, """{"labelId":"ULA-E-$suffix-2"}""").getLong("id")
        releaseAsn(asnId)

        val receiptId = createReceipt("""{"carrierName":"DHL","asnIds":[$asnId]}""")
        receiveLine(receiptId, lineId, amount = 4.0, unitLoadLabel = "ULA-E-$suffix-1")

        deleteAdvice(asnId, matchedAdviceId, expectedStatus = 409)
        deleteAdvice(asnId, unmatchedAdviceId, expectedStatus = 204)

        val asnAfter = getAsn(asnId)
        assertThat(asnAfter.getList<Any>("ulAdvices")).hasSize(1)
    }

    // ── (f) ^/~ injected into labelId/reasonForReturn does not survive into ZPL ─

    @Test
    @TestSecurity(user = "op-ula-f", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `ZPL control chars in labelId or reasonForReturn are sanitized out of the rendered label`() {
        val suffix = System.nanoTime()
        val productId = seedProduct(suffix)
        val (asnId, _) = createAsn(productId)
        val rawLabel = "AB^12~34-$suffix"
        val strippedLabel = "AB1234-$suffix"
        val reason = "R^EASON~-$suffix"
        createAdvice(asnId, """{"labelId":"$rawLabel","reasonForReturn":"$reason"}""")

        val zpl = ulLabelsZpl(asnId)
        assertThat(zpl).doesNotContain(rawLabel)
        assertThat(zpl).contains(strippedLabel)
        // reasonForReturn is deliberately never in the render data map (advice-local
        // fields only) -- confirm its raw content (control chars or not) is absent.
        assertThat(zpl).doesNotContain(reason).doesNotContain("REASON")
    }

    // ── (g) reversing a matched line reopens its advice; the re-receive re-matches ─

    @Test
    @TestSecurity(user = "op-ula-g", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reversing a matched line reopens its advice, re-receiving the same label matches it again with the new line id`() {
        val suffix = System.nanoTime()
        val productId = seedProduct(suffix)
        val (asnId, lineId) = createAsn(productId, expectedAmount = 20.0)
        val adviceId = createAdvice(asnId, """{"labelId":"ULA-G-$suffix"}""").getLong("id")
        releaseAsn(asnId)

        val receiptId = createReceipt("""{"carrierName":"DHL","asnIds":[$asnId]}""")
        val firstReceive = receiveLine(receiptId, lineId, amount = 4.0, unitLoadLabel = "ULA-G-$suffix")
        val firstLineId = firstReceive.getLong("lineId")

        val afterMatch = adviceById(asnId, adviceId)
        assertThat(afterMatch["stateName"]).isEqualTo("FINISHED")
        assertThat((afterMatch["matchedReceiptLineId"] as Number).toLong()).isEqualTo(firstLineId)

        reverseLine(receiptId, firstLineId)

        val afterReversal = adviceById(asnId, adviceId)
        assertThat(afterReversal["stateName"]).isEqualTo("CREATED")
        assertThat(afterReversal["matchedReceiptLineId"]).isNull()

        val secondReceive = receiveLine(receiptId, lineId, amount = 3.0, unitLoadLabel = "ULA-G-$suffix")
        val secondLineId = secondReceive.getLong("lineId")
        assertThat(secondLineId).isNotEqualTo(firstLineId)

        val afterSecondMatch = adviceById(asnId, adviceId)
        assertThat(afterSecondMatch["stateName"]).isEqualTo("FINISHED")
        assertThat((afterSecondMatch["matchedReceiptLineId"] as Number).toLong()).isEqualTo(secondLineId)
    }

    private fun adviceById(asnId: Long, adviceId: Long): Map<String, Any> =
        getAsn(asnId).getList<Map<String, Any>>("ulAdvices").first { (it["id"] as Number).toLong() == adviceId }
}
