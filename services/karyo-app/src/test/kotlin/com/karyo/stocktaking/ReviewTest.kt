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
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test for [StocktakingService.accept] and [StocktakingService.recount].
 *
 * Setup: startCount then submitCount with a mismatch to produce a COUNTED order.
 *
 * clientId 2401/2402 reserved for this suite.
 */
@QuarkusTest
class ReviewTest {

    @Inject
    lateinit var service: StocktakingService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var em: EntityManager

    // ── Seeding helpers ─────────────────────────────────────────────────────

    data class SeedCtx(val locationId: Long, val unitLoadId: Long, val stockUnitId: Long)

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
    private fun createStock(ulId: Long, amount: Double, prefix: String = "RVW"): Long {
        val suffix = System.nanoTime().toString().takeLast(8)
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":2401001,"itemDataNumber":"$prefix-$suffix",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun seedStockAtLocation(clientId: Long, amount: Double): SeedCtx {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-RV-$ns")
        val areaId = createArea("AREA-RV-$ns")
        val locName = "LOC-RV-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-RV-$ns", locationId, locName)
        val suId = createStock(ulId, amount)
        tenantContext.clientId = clientId
        return SeedCtx(locationId, ulId, suId)
    }

    /** startCount → submitCount with a mismatch → returns COUNTED order ready for review. */
    private fun setupCountedOrder(clientId: Long, plannedAmount: Double, countedAmount: Double): Pair<Long, Long> {
        val ctx = seedStockAtLocation(clientId, plannedAmount)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), clientId)
        val sessionView = service.getSession(started.id, clientId)
        val order = sessionView.orders.first()
        val line = order.lines.first()
        service.submitCount(
            orderId = order.id,
            inputs = listOf(CountInput(lineId = line.id, countedAmount = BigDecimal.valueOf(countedAmount))),
            clientId = clientId,
        )
        return Pair(order.id, ctx.stockUnitId)
    }

    private fun stockAmountOf(stockUnitId: Long): BigDecimal =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath()
            .getString("amount").let { BigDecimal(it) }

    private fun lockTypeOf(stockUnitId: Long): Int =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath().getInt("lockType")

    private fun lastCountedAtOf(locationId: Long): String? =
        given().`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200).extract().jsonPath().getString("lastCountedAt")

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2401"), Claim(key = "tenant_code", value = "RVW-TEST")])
    fun `accept applies the discrepancy and finishes the order`() {
        // startCount(25) → submitCount(18) → order COUNTED
        val (orderId, stockUnitId) = setupCountedOrder(clientId = 2401, plannedAmount = 25.0, countedAmount = 18.0)

        val view = service.accept(orderId, 2401)

        // order FINISHED
        assertThat(view.state).isEqualTo(CountOrderState.FINISHED.code)
        // every line FINISHED
        assertThat(view.lines).allMatch { it.state == CountLineState.FINISHED.code }
        // discrepancy applied — stock amount adjusted to counted value
        assertThat(stockAmountOf(stockUnitId)).isEqualByComparingTo("18")
        // lock released
        assertThat(lockTypeOf(stockUnitId)).isEqualTo(LockType.UNLOCKED.code)
        // L3 (Task 6): the discrepancy-accept path stamps the real lastCountedAt writer
        assertThat(lastCountedAtOf(view.locationId)).isNotNull()
    }

    @Test
    @TestSecurity(user = "manager2", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2402"), Claim(key = "tenant_code", value = "RVW-TEST2")])
    fun `recount cancels the old order and generates a fresh one`() {
        // startCount(30) → submitCount(15) → order COUNTED
        val (orderId, stockUnitId) = setupCountedOrder(clientId = 2402, plannedAmount = 30.0, countedAmount = 15.0)

        // capture the location from the old order via a fresh DB read (bypass L1 cache)
        em.clear()
        val oldOrder = em.find(com.karyo.stocktaking.domain.model.CountOrder::class.java, orderId)!!
        val locationId = oldOrder.locationId

        val freshView = service.recount(orderId, 2402)

        // old order CANCELLED — clear cache before re-reading to bypass L1 cache
        em.clear()
        val cancelledOrder = em.find(com.karyo.stocktaking.domain.model.CountOrder::class.java, orderId)!!
        assertThat(cancelledOrder.state).isEqualTo(CountOrderState.CANCELLED.code)

        // new order GENERATED for the same location
        assertThat(freshView.state).isEqualTo(CountOrderState.GENERATED.code)
        assertThat(freshView.locationId).isEqualTo(locationId)
        assertThat(freshView.id).isNotEqualTo(orderId)
        // L3 (Task 6): a cancelled/recounted order never reaches finishOrder — the location
        // must NOT be stamped as counted just because it was cancelled.
        assertThat(lastCountedAtOf(locationId)).isNull()

        // no inventory mutation from recount — stock amount unchanged (still 30)
        assertThat(stockAmountOf(stockUnitId)).isEqualByComparingTo("30")
    }
}
