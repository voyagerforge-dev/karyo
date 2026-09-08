package com.karyo.replenishment

import com.karyo.common.pagination.PaginationParams
import com.karyo.replenishment.service.ReplenishmentScheduler
import com.karyo.tasks.service.TaskService
import com.karyo.tasks.vo.TransportType
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
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
 * Task 3 review CRITICAL-1 regression guard (replenishment sprint): real-bean, end-to-end proof
 * that [ReplenishmentScheduler.runOnce] produces a REPLENISH order for a real (non-zero) tenant
 * WITHOUT anything priming `TenantContext` first — the exact shape of a real `@Scheduled`
 * invocation. `ReplenishmentSchedulerTest` (plain unit test, `ReplenishmentService` mocked) cannot
 * catch this class of bug: mocking `ReplenishmentService` hides everything downstream of `scan`,
 * which is exactly where the two ambient-`TenantContext` reads lived (`FixAssignmentService.
 * enrichStockAmount` via `StockUnitLookup.findByItemDataId`, and `TaskService.createReplenishment`
 * via `UnitLoadLookup.findById`) before this task's fix.
 *
 * `@TestProfile` forces its own Quarkus app instance (see `TravelPathDispatchTest`'s precedent),
 * so `karyo.replenishment.auto-scan-enabled=true` is scoped to this one class and does not affect
 * the rest of the suite's default (`false`).
 *
 * Seeding (item → product → location-type → area → location → fix-assignment, then a SEPARATE
 * reserve unit-load + stock elsewhere) goes through REST, same as `FixAssignmentLookupTest`/
 * `ReplenishmentTopUpFlowTest` — those HTTP calls run on the test server's own thread/request
 * scope and do not leak into the TEST METHOD's own `TenantContext`. The face is seeded with NO
 * stock (`currentAmount = 0 < minAmount`), so it needs replenishment without any extra fixture.
 */
@QuarkusTest
@TestProfile(ReplenishmentSchedulerIntegrationTest.AutoScanEnabled::class)
class ReplenishmentSchedulerIntegrationTest {

    class AutoScanEnabled : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.replenishment.auto-scan-enabled" to "true")
    }

    @Inject
    lateinit var replenishmentScheduler: ReplenishmentScheduler

    @Inject
    lateinit var taskService: TaskService

    companion object {
        private const val CLIENT_ID = 9001L
    }

    // ── REST seed helpers (mirrors FixAssignmentLookupTest / ReplenishmentTopUpFlowTest) ────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"RSI Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")

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

    private fun createFixAssignment(locationId: Long, itemDataId: Long, minAmount: Int): Long =
        given().contentType(ContentType.JSON)
            .body("""{"locationId":$locationId,"itemDataId":$itemDataId,"minAmount":$minAmount}""")
            .`when`().post("/api/v1/fix-assignments").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String, locationId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"LOC-$locationId"}""",
            )
            .`when`().post("/api/v1/unit-loads").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, itemNumber: String, amount: BigDecimal): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    // ── Test ──────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9001"), Claim(key = "tenant_code", value = "ACME")])
    fun `runOnce mints a REPLENISH order for a real tenant with no TenantContext primed`() {
        val ns = System.nanoTime()
        val itemUnitId = createItemUnit("IU-RSI-${ns.toString().takeLast(8)}")
        val itemDataId = createProduct("RSI-SKU-$ns", itemUnitId)
        val ltId = createLocationType("LT-RSI-${ns.toString().takeLast(8)}")
        val areaId = createArea("AREA-RSI-${ns.toString().takeLast(8)}")
        val faceLocationId = createLocation("LOC-RSI-FACE-${ns.toString().takeLast(8)}", ltId, areaId)

        // No stock seeded at the face -- currentAmount = 0 < minAmount, needs replenishment.
        val fixAssignmentId = createFixAssignment(faceLocationId, itemDataId, minAmount = 10)

        val sourceLocationId = createLocation("LOC-RSI-SRC-${ns.toString().takeLast(8)}", ltId, areaId)
        val sourceUlId = createUnitLoad("UL-RSI-SRC-$ns", sourceLocationId)
        createStock(sourceUlId, itemDataId, "RSI-SKU-$ns", BigDecimal("50"))

        // Precondition, before the scheduler ever runs.
        assertThat(taskService.hasOpenReplenishment(fixAssignmentId, CLIENT_ID)).isFalse()

        // The point of this test: NOTHING primes TenantContext.clientId here (no
        // `tenantContext.clientId = CLIENT_ID`, unlike ReplenishmentTopUpFlowTest's
        // `primeTenant()`) -- this reproduces the exact shape of a real @Scheduled invocation.
        replenishmentScheduler.runOnce()

        assertThat(taskService.hasOpenReplenishment(fixAssignmentId, CLIENT_ID)).isTrue()

        // Task 3 (defect-burndown-4, row 7) regression guard: the minted order's denorm must not
        // be silently empty. Before the fix, ConfirmVariantService.denormalizeAtCreation used the
        // ambient single-arg StockUnitLookup.findByUnitLoadId, which finds nothing on this
        // unprimed scheduler thread -- itemDataId/itemDataNumber/amount all landed null even
        // though the source unit load carries exactly one live stock unit. This test class mints
        // exactly one REPLENISH order for CLIENT_ID (own @TestProfile app instance), so the sole
        // result is the one under test.
        val minted = taskService.list(CLIENT_ID, PaginationParams(), null, TransportType.REPLENISH, null, null)
            .content.single()
        assertThat(minted.itemDataId).isNotNull()
        assertThat(minted.itemDataNumber).isNotNull()
        assertThat(minted.amount).isNotNull()
    }
}
