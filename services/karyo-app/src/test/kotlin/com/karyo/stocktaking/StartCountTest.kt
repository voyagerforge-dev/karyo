package com.karyo.stocktaking

import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.service.StockService
import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.exception.StocktakingException
import com.karyo.stocktaking.service.StocktakingService
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test for [StocktakingService.startCount].
 *
 * Seeding pattern: REST helpers (authed via @TestSecurity + @OidcSecurity) create a unit load
 * at a dedicated storageLocationId, then a stock unit on it. TenantContext.clientId is primed
 * directly so port calls made by the service are tenant-scoped.
 *
 * clientId 2201/2202 reserved for this suite. locationId 22010 for happy-path, 22020 for reserved.
 */
@QuarkusTest
class StartCountTest {

    @Inject
    lateinit var service: StocktakingService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var stockService: StockService

    // ── Seeding helpers ────────────────────────────────────────────────────

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

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long, orderIndex: Int = 0): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId,"orderIndex":$orderIndex}""")
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

    /** Creates an ON_STOCK (state=300) stock unit. Optionally reserves [reserved] amount via a
     *  direct [StockService.reserveStock] call (the `/api/internal/stock-units/{id}/reserve`
     *  router it used to go through is deleted — cross-module calls are SPI/service calls now).
     *  Item-unit name is @Size(max=20) — suffix capped to 8 chars.
     *
     *  Caller must have already primed `tenantContext.clientId` to the owning client before
     *  calling this with `reserved > 0.0`, since [StockService.reserveStock] scopes the write
     *  through `tenantContext.writeScope()`. */
    private fun createStock(ulId: Long, amount: Double, reserved: Double = 0.0): Long {
        val suffix = System.nanoTime().toString().takeLast(8)
        val stockId = given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":2201001,"itemDataNumber":"SCS-$suffix",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        if (reserved > 0.0) {
            stockService.reserveStock(stockId, BigDecimal.valueOf(reserved), "TEST-RESERVE", tenantContext)
        }

        return stockId
    }

    /**
     * Seeds a real layout location + unit load + stock unit for startCount testing.
     * Creates the location via REST (so locationLockPort.lockForCount can find it),
     * then primes TenantContext so the service's port calls are tenant-scoped. Primed before
     * seeding the stock unit too, since [createStock] may reserve stock via a direct
     * [StockService] call that needs `tenantContext.clientId` set.
     */
    private fun seedStockAtLocation(clientId: Long, amount: Double, reserved: Double = 0.0): SeedCtx {
        tenantContext.clientId = clientId
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-SC-$ns")
        val areaId = createArea("AREA-SC-$ns")
        val locName = "LOC-SC-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-SC-$ns", locationId, locName)
        val suId = createStock(ulId, amount, reserved)
        return SeedCtx(locationId, ulId, suId)
    }

    /** Reads the lockType field of a stock unit via REST. */
    private fun lockTypeOf(stockUnitId: Long): Int =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath().getInt("lockType")

    /** Reads the lockType field of a location via REST. */
    private fun locationLockOf(locationId: Long, @Suppress("UNUSED_PARAMETER") clientId: Long): Int =
        given().`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200).extract().jsonPath().getInt("lockType")

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2201"), Claim(key = "tenant_code", value = "SC-TEST")])
    fun `startCount creates a session + one locked order per location with a planned snapshot`() {
        val ctx = seedStockAtLocation(clientId = 2201, amount = 25.0)
        val view = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2201)

        assertThat(view.orderCount).isEqualTo(1)
        val order = service.getSession(view.id, 2201).orders.first()
        assertThat(order.lines).hasSize(1)
        assertThat(order.lines.first().plannedAmount).isEqualByComparingTo("25")
        assertThat(lockTypeOf(ctx.stockUnitId)).isEqualTo(LockType.STOCKTAKING.code)        // stock locked
        assertThat(locationLockOf(ctx.locationId, 2201)).isEqualTo(7)                       // location locked
    }

    @Test
    @TestSecurity(user = "operator2", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2202"), Claim(key = "tenant_code", value = "SC-TEST2")])
    fun `startCount rejects a location whose stock is reserved`() {
        val ctx = seedStockAtLocation(clientId = 2202, amount = 25.0, reserved = 10.0)
        org.junit.jupiter.api.assertThrows<Exception> {
            service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2202)
        }
    }

    // St2: location name-pattern scope -- a start with only a pattern (no locationIds/areaId)
    // must resolve the seeded location by LIKE match and generate an order for it.
    @Test
    @TestSecurity(user = "operator3", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2203"), Claim(key = "tenant_code", value = "SC-TEST3")])
    fun `startCount with only a location name pattern generates orders for the matches`() {
        tenantContext.clientId = 2203
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-PAT-$ns")
        val areaId = createArea("AREA-PAT-$ns")
        val locName = "PAT-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-PAT-$ns", locationId, locName)
        createStock(ulId, 12.0)

        val view = service.startCount(StartCountRequest(locationNamePattern = "PAT-$ns%"), 2203)

        assertThat(view.orderCount).isEqualTo(1)
        assertThat(service.getSession(view.id, 2203).orders.first().locationId).isEqualTo(locationId)
    }

    // St6: generation-time walking order -- scope-resolved locationIds are re-sorted by
    // `orderIndex NULLS LAST, name` before a single CountOrder is generated, so the caller's raw
    // input order (here deliberately B-then-A, the wrong walking order) must NOT be what
    // determines generation order.
    @Test
    @TestSecurity(user = "operator4", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2204"), Claim(key = "tenant_code", value = "SC-TEST4")])
    fun `startCount generates orders in orderIndex walking order regardless of caller-supplied locationIds order`() {
        tenantContext.clientId = 2204
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-WALK-$ns")
        val areaId = createArea("AREA-WALK-$ns")

        // B has the HIGHER orderIndex (20) but is listed FIRST in the request; A has the LOWER
        // orderIndex (10) but is listed SECOND -- a caller-order-preserving (pre-St6) generation
        // would produce [B, A]; the walking-order sort must produce [A, B].
        val locNameB = "LOC-WALK-B-$ns"
        val locBId = createLocation(locNameB, ltId, areaId, orderIndex = 20)
        createStock(createUnitLoad("UL-WALK-B-$ns", locBId, locNameB), 5.0)

        val locNameA = "LOC-WALK-A-$ns"
        val locAId = createLocation(locNameA, ltId, areaId, orderIndex = 10)
        createStock(createUnitLoad("UL-WALK-A-$ns", locAId, locNameA), 5.0)

        val view = service.startCount(StartCountRequest(locationIds = listOf(locBId, locAId)), 2204)

        assertThat(view.orderCount).isEqualTo(2)
        assertThat(service.getSession(view.id, 2204).orders.map { it.locationId }).containsExactly(locAId, locBId)
    }

    // I4 (final-gate review): DefaultStockCountingPort.findStockAtLocation must skip soft-deleted
    // stock. StockService.deleteStock only flips state to DELETABLE -- the row survives and
    // findByUnitLoadId still returns it -- so without the filter a written-off stock unit gets
    // planned into a fresh count order, and accepting a non-zero count on it calls adjustAmount
    // (no state guard) and RESURRECTS it.
    @Test
    @TestSecurity(user = "operator5", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2205"), Claim(key = "tenant_code", value = "SC-TEST5")])
    fun `startCount plans only live stock -- a soft-deleted stock unit at the location is skipped`() {
        tenantContext.clientId = 2205
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-DEL-$ns")
        val areaId = createArea("AREA-DEL-$ns")
        val locName = "LOC-DEL-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-DEL-$ns", locationId, locName)

        val liveId = createStock(ulId, 25.0)
        val deadId = createStock(ulId, 9.0)
        stockService.deleteStock(deadId, tenantContext)

        val view = service.startCount(StartCountRequest(locationIds = listOf(locationId)), 2205)

        assertThat(view.orderCount).isEqualTo(1)
        val lines = service.getSession(view.id, 2205).orders.first().lines
        assertThat(lines).hasSize(1)
        assertThat(lines.first().stockUnitId).isEqualTo(liveId)
        assertThat(lines.map { it.stockUnitId }).doesNotContain(deadId)
    }

    // M2 (final-gate review): an all-wildcard locationNamePattern matches every location for the
    // tenant -- i.e. the full-warehouse scope arriving through the EXPLICIT strategy's back door,
    // producing a CYCLE-labelled session that freezes the whole warehouse with none of
    // END_OF_PERIOD's semantics. 422, same as asking for FULL_WAREHOUSE by name.
    @Test
    @TestSecurity(user = "operator6", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2206"), Claim(key = "tenant_code", value = "SC-TEST6")])
    fun `startCount refuses an all-wildcard location name pattern`() {
        tenantContext.clientId = 2206
        listOf("%", "%%", "_", "%_%").forEach { pattern ->
            org.junit.jupiter.api.assertThrows<StocktakingException.InvalidCount>("pattern '$pattern'") {
                service.startCount(StartCountRequest(locationNamePattern = pattern), 2206)
            }
        }
        // a pattern that merely CONTAINS wildcards stays legal -- it just resolves to no
        // locations here, which is the ordinary empty-scope refusal, not the wildcard refusal.
        org.junit.jupiter.api.assertThrows<StocktakingException.InvalidState> {
            service.startCount(
                StartCountRequest(locationNamePattern = "NOSUCH-${System.nanoTime()}%"),
                2206,
            )
        }
    }
}
