package com.karyo.stocktaking

import com.karyo.inventory.api.vo.LockType
import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.CountInput
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.exception.StocktakingException
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.service.StocktakingService
import com.karyo.stocktaking.vo.CountLineState
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test for [StocktakingService.cancelOrder] (St7 — location-level drop, no
 * regenerate).
 *
 * Setup mirrors [ReviewTest] / [SubmitCountTest] / [StartCountTest]: seed layout + stock via
 * REST, prime [TenantContext.clientId], drive the service directly.
 *
 * clientId 2601-2604 reserved for this suite.
 */
@QuarkusTest
class CancelOrderTest {

    @Inject
    lateinit var service: StocktakingService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var orderRepository: CountOrderRepository

    // ── Seeding helpers (mirrored from ReviewTest/SubmitCountTest/StartCountTest) ─────────────

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

    /** Creates an ON_STOCK stock unit. Item-unit name suffix ≤8 chars (max=20 field). */
    private fun createStock(ulId: Long, amount: Double, prefix: String = "CXL"): Long {
        val suffix = System.nanoTime().toString().takeLast(8)
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":2601001,"itemDataNumber":"$prefix-$suffix",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    data class SeedCtx(val locationId: Long, val unitLoadId: Long, val stockUnitId: Long)

    private fun seedStockAtLocation(clientId: Long, amount: Double): SeedCtx {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-CXL-$ns")
        val areaId = createArea("AREA-CXL-$ns")
        val locName = "LOC-CXL-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-CXL-$ns", locationId, locName)
        val suId = createStock(ulId, amount)
        tenantContext.clientId = clientId
        return SeedCtx(locationId, ulId, suId)
    }

    private fun lockTypeOf(stockUnitId: Long): Int =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath().getInt("lockType")

    private fun locationLockOf(locationId: Long): Int =
        given().`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200).extract().jsonPath().getInt("lockType")

    private fun lastCountedAtOf(locationId: Long): String? =
        given().`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200).extract().jsonPath().getString("lastCountedAt")

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2601"), Claim(key = "tenant_code", value = "CXL-TEST")])
    fun `cancel a GENERATED order releases everything, stamps nothing, and generates no replacement`() {
        val ctx = seedStockAtLocation(clientId = 2601, amount = 12.0)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2601)
        val sessionView = service.getSession(started.id, 2601)
        val order = sessionView.orders.first()
        assertThat(order.state).isEqualTo(CountOrderState.GENERATED.code)

        val view = service.cancelOrder(order.id, 2601)

        // order CANCELLED
        assertThat(view.state).isEqualTo(CountOrderState.CANCELLED.code)
        // its lines CANCELLED
        assertThat(view.lines).allMatch { it.state == CountLineState.CANCELLED.code }
        // stock lock back to 0 (UNLOCKED)
        assertThat(lockTypeOf(ctx.stockUnitId)).isEqualTo(LockType.UNLOCKED.code)
        // location lock back to 0 (UNLOCKED)
        assertThat(locationLockOf(ctx.locationId)).isEqualTo(LockType.UNLOCKED.code)
        // lastCountedAt NOT stamped -- nothing was counted
        assertThat(lastCountedAtOf(ctx.locationId)).isNull()
        // no replacement order generated -- the session still has exactly the one (now
        // cancelled) order
        val session = service.getSession(sessionView.id, 2601)
        assertThat(session.orders).hasSize(1)
        assertThat(session.orders.first().id).isEqualTo(order.id)
        // a cancelled order must vanish from the claimable pool (state filter is GENERATED)
        assertThat(orderRepository.findClaimable(2601).map { it.id }).doesNotContain(order.id)
    }

    @Test
    @TestSecurity(user = "manager2", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2602"), Claim(key = "tenant_code", value = "CXL-TEST2")])
    fun `cancelling the only order in a session closes the session`() {
        val ctx = seedStockAtLocation(clientId = 2602, amount = 8.0)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2602)
        val sessionView = service.getSession(started.id, 2602)
        val order = sessionView.orders.first()

        service.cancelOrder(order.id, 2602)

        val session = service.getSession(sessionView.id, 2602)
        assertThat(session.state).isEqualTo(CountSessionState.CLOSED.code)
    }

    @Test
    @TestSecurity(user = "manager3", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2603"), Claim(key = "tenant_code", value = "CXL-TEST3")])
    fun `cancelling a FINISHED order throws InvalidState`() {
        val ctx = seedStockAtLocation(clientId = 2603, amount = 6.0)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2603)
        val sessionView = service.getSession(started.id, 2603)
        val order = sessionView.orders.first()
        val line = order.lines.first()

        // Exact match -> auto-finish
        val finished = service.submitCount(
            orderId = order.id,
            inputs = listOf(CountInput(lineId = line.id, countedAmount = line.plannedAmount)),
            clientId = 2603,
        )
        assertThat(finished.state).isEqualTo(CountOrderState.FINISHED.code)

        assertThatThrownBy { service.cancelOrder(order.id, 2603) }
            .isInstanceOf(StocktakingException.InvalidState::class.java)
    }

    @Test
    @TestSecurity(user = "manager4", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2604"), Claim(key = "tenant_code", value = "CXL-TEST4")])
    fun `REST leg -- POST count-orders id cancel returns 200 with state 800`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-CXR-$ns")
        val areaId = createArea("AREA-CXR-$ns")
        val locName = "LOC-CXR-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-CXR-$ns", locationId, locName)
        createStock(ulId, 9.0, prefix = "CXR")

        val sessionResp = given().contentType(ContentType.JSON)
            .body("""{"locationIds":[$locationId]}""")
            .`when`().post("/api/v1/count-sessions")
            .then().statusCode(201)
            .extract().jsonPath()
        val sessionId = sessionResp.getLong("id")
        val orderId = given().`when`().get("/api/v1/count-sessions/$sessionId")
            .then().statusCode(200)
            .extract().jsonPath().getLong("orders[0].id")

        given()
            .`when`().post("/api/v1/count-orders/$orderId/cancel")
            .then().statusCode(200)
            .body("state", org.hamcrest.Matchers.equalTo(CountOrderState.CANCELLED.code))
    }
}
