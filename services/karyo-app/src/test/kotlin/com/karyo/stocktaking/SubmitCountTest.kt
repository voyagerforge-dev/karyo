package com.karyo.stocktaking

import com.karyo.inventory.api.vo.LockType
import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.CountInput
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.service.StocktakingService
import com.karyo.stocktaking.vo.CountLineState
import com.karyo.stocktaking.vo.CountOrderState
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Integration test for [StocktakingService.submitCount].
 *
 * clientId 2301/2302 reserved for this suite.
 * Sets up via startCount (real DB + ports) and then calls submitCount directly.
 */
@QuarkusTest
class SubmitCountTest {

    @Inject
    lateinit var service: StocktakingService

    @Inject
    lateinit var tenantContext: TenantContext

    // ── Seeding helpers (mirrored from StartCountTest) ─────────────────────

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String, locationId: Long, locationName: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"$locationName"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates an ON_STOCK (state=300) stock unit. Item-unit name suffix ≤8 chars (max=20 field). */
    private fun createStock(ulId: Long, amount: Double): Long {
        val suffix = System.nanoTime().toString().takeLast(8)
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":2301001,"itemDataNumber":"SCT-$suffix",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    data class SeedCtx(val locationId: Long, val unitLoadId: Long, val stockUnitId: Long)

    private fun seedStockAtLocation(clientId: Long, amount: Double): SeedCtx {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-SCT-$ns")
        val areaId = createArea("AREA-SCT-$ns")
        val locName = "LOC-SCT-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-SCT-$ns", locationId, locName)
        val suId = createStock(ulId, amount)
        tenantContext.clientId = clientId
        return SeedCtx(locationId, ulId, suId)
    }

    private fun lockTypeOf(stockUnitId: Long): Int =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath().getInt("lockType")

    private fun lastCountedAtOf(locationId: Long): String? =
        given().`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200).extract().jsonPath().getString("lastCountedAt")

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2301"), Claim(key = "tenant_code", value = "SCT-TEST")])
    fun `all-match submit auto-finishes the order and releases locks`() {
        val ctx = seedStockAtLocation(clientId = 2301, amount = 10.0)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2301)
        val sessionView = service.getSession(started.id, 2301)

        val order = sessionView.orders.first()
        val line = order.lines.first()

        // stock must be STOCKTAKING-locked before submit
        assertThat(lockTypeOf(ctx.stockUnitId)).isEqualTo(LockType.STOCKTAKING.code)

        // submit exact match
        val orderView = service.submitCount(
            orderId = order.id,
            inputs = listOf(CountInput(lineId = line.id, countedAmount = line.plannedAmount)),
            clientId = 2301,
        )

        assertThat(orderView.state).isEqualTo(CountOrderState.FINISHED.code)
        assertThat(orderView.lines.first().state).isEqualTo(CountLineState.FINISHED.code)
        // lock released on finish
        assertThat(lockTypeOf(ctx.stockUnitId)).isEqualTo(LockType.UNLOCKED.code)
        // L3 (Task 6): the no-discrepancy auto-finish path also stamps the real
        // lastCountedAt writer (finishOrder is shared with the accept path).
        assertThat(lastCountedAtOf(ctx.locationId)).isNotNull()
    }

    @Test
    @TestSecurity(user = "operator2", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2302"), Claim(key = "tenant_code", value = "SCT-TEST2")])
    fun `a mismatch leaves the order COUNTED pending review`() {
        val ctx = seedStockAtLocation(clientId = 2302, amount = 10.0)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2302)
        val sessionView = service.getSession(started.id, 2302)

        val order = sessionView.orders.first()
        val line = order.lines.first()

        // submit a different amount — deliberately 5 instead of 10
        val orderView = service.submitCount(
            orderId = order.id,
            inputs = listOf(CountInput(lineId = line.id, countedAmount = java.math.BigDecimal("5"))),
            clientId = 2302,
        )

        assertThat(orderView.state).isEqualTo(CountOrderState.COUNTED.code)
        assertThat(orderView.lines.first().state).isEqualTo(CountLineState.COUNTED.code)
        assertThat(orderView.lines.first().countedAmount).isEqualByComparingTo("5")
        // L3 (Task 6): a COUNTED-pending-review order hasn't reached finishOrder yet — not stamped.
        assertThat(lastCountedAtOf(ctx.locationId)).isNull()
        // stock still locked — discrepancy needs review
        assertThat(lockTypeOf(ctx.stockUnitId)).isEqualTo(LockType.STOCKTAKING.code)
    }
}
