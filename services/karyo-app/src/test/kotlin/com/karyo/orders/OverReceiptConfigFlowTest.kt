package com.karyo.orders

import com.karyo.auth.spi.RuntimePropertyLookup
import com.karyo.orders.config.ReceivingConfig
import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnLine
import com.karyo.orders.dto.ReceiveLineRequest
import com.karyo.orders.exception.OrderException
import com.karyo.orders.service.OverReceiptGuard
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.math.BigDecimal

private const val FLOW_ROLES_USER = "manager"
private const val DOCK_LOCATION_ID = 900L
private const val DOCK_LOCATION_NAME = "DOCK-01"

/**
 * Instance-level over-receipt hard-stop toggle (`karyo.receiving.allow-over-receipt`,
 * inbound row 10). Two things are pinned here in ONE class (no second `@QuarkusTest`
 * app restart needed, unlike [com.karyo.work.TravelPathDispatchConfigTest]'s split):
 *
 *  1. An end-to-end REST case under [HardStopActive] (`allow-over-receipt=false`):
 *     a per-request `allowOverReceipt=true` must still 409 -- the instance knob is the
 *     STRICTER gate and cannot be overridden per request.
 *  2. A plain-object case exercising [OverReceiptGuard] directly with the DEFAULT
 *     config value (`allowOverReceipt=true`, matching the `karyo.receiving.allow-over-receipt`
 *     default) -- no Quarkus context needed, mirrors [OverReceiptGuard] being a small,
 *     hand-constructible bean (same style as [com.karyo.work.TravelPathDispatchTest]'s
 *     plain-strategy tests). This pins the composition DIRECTION: with the default
 *     (permissive) instance config, a per-request override still succeeds.
 */
@QuarkusTest
@TestProfile(OverReceiptConfigFlowTest.HardStopActive::class)
class OverReceiptConfigFlowTest {

    class HardStopActive : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.receiving.allow-over-receipt" to "false")
    }

    // ── REST seeding helpers (ReceivingFlowTest precedent) ────────────────

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
            .body("""{"number":"$number","name":"Over-Receipt Config Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createAsn(productId: Long, expectedAmount: Double): JsonPath =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"carrierName":"DHL","lines":[{"itemDataId":$productId,"expectedAmount":$expectedAmount}]}"""
            )
            .`when`().post("/api/v1/asns")
            .then().statusCode(201)
            .extract().jsonPath()

    private fun releaseAsn(asnId: Long) {
        given()
            .`when`().post("/api/v1/asns/$asnId/release")
            .then().statusCode(200)
    }

    private fun createReceipt(asnId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"carrierName":"DHL","asnId":$asnId}""")
            .`when`().post("/api/v1/goods-receipts")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun receiveLineBody(asnLineId: Long, amount: Double, allowOverReceipt: Boolean): String =
        """{"asnLineId":$asnLineId,"amount":$amount,"locationId":$DOCK_LOCATION_ID,""" +
            """"locationName":"$DOCK_LOCATION_NAME","allowOverReceipt":$allowOverReceipt}"""

    // ── Test 1: end-to-end hard stop ───────────────────────────────────────

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `instance knob false hard-stops over-receipt even with per-request allowOverReceipt true`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("ORC-${suffix.toString().takeLast(10)}")
        val productId = createProduct("ORC-SKU-$suffix", itemUnitId)
        val asnJson = createAsn(productId, expectedAmount = 10.0)
        val asnId = asnJson.getLong("id")
        val lineId = asnJson.getLong("lines[0].id")
        releaseAsn(asnId)
        val receiptId = createReceipt(asnId)

        // 8 of 10 is fine even under the hard-stop -- it is not yet an over-receipt.
        given()
            .contentType(ContentType.JSON)
            .body(receiveLineBody(lineId, amount = 8.0, allowOverReceipt = false))
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(201)

        // +5 would exceed expected 10. Per-request allowOverReceipt=true would normally
        // override this (see ReceivingFlowTest), but karyo.receiving.allow-over-receipt=false
        // (this class's HardStopActive profile) is the STRICTER gate and wins.
        given()
            .contentType(ContentType.JSON)
            .body(receiveLineBody(lineId, amount = 5.0, allowOverReceipt = true))
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(409)
            .body("type", `is`("https://karyo.com/errors/over-receipt"))
    }

    // ── Test 2: composition direction, plain object (default config value) ─

    private fun asnLine(id: Long, expected: BigDecimal, received: BigDecimal): AsnLine {
        val line = AsnLine()
        line.id = id
        line.expectedAmount = expected
        line.receivedAmount = received
        line.asn = Asn().apply { clientId = 1L }
        return line
    }

    /**
     * SC16 stub: a lookup with no stored rows and no config -- every call resolves to the
     * caller default, i.e. exactly the pre-SC16 behavior the plain-object test pins.
     */
    private val emptyStoreLookup = object : RuntimePropertyLookup {
        override fun getString(key: String, clientId: Long, default: String?): String? = default
        override fun getBoolean(key: String, clientId: Long, default: Boolean): Boolean = default
        override fun getInt(key: String, clientId: Long, default: Int): Int = default
    }

    private fun receiveRequest(amount: BigDecimal, allowOverReceipt: Boolean): ReceiveLineRequest =
        ReceiveLineRequest(
            asnLineId = 1L,
            amount = amount,
            locationId = DOCK_LOCATION_ID,
            locationName = DOCK_LOCATION_NAME,
            allowOverReceipt = allowOverReceipt,
        )

    @Test
    fun `default instance config true - per-request override still succeeds, composition is AND not OR`() {
        val defaultConfigGuard = OverReceiptGuard(ReceivingConfig(allowOverReceipt = true), emptyStoreLookup)
        val line = asnLine(id = 1L, expected = BigDecimal("10"), received = BigDecimal("8"))

        // Over-receipt (+5 past expected 10) with allowOverReceipt=true, default instance
        // config (true) -- both sides of the AND are true, so it must succeed.
        defaultConfigGuard.check(line, receiveRequest(BigDecimal("5"), allowOverReceipt = true))

        // Same over-receipt WITHOUT the per-request override still 409s under the default
        // instance config -- the instance knob alone never grants what the request refuses.
        assertThatThrownBy {
            defaultConfigGuard.check(line, receiveRequest(BigDecimal("5"), allowOverReceipt = false))
        }.isInstanceOf(OrderException.OverReceipt::class.java)

        // A within-expectation amount never trips the guard regardless of either flag.
        defaultConfigGuard.check(line, receiveRequest(BigDecimal("2"), allowOverReceipt = false))
    }
}
