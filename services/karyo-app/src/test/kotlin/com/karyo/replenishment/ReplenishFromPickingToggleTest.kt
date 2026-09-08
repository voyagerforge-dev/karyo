package com.karyo.replenishment

import com.karyo.auth.config.SystemPropertyService
import com.karyo.replenishment.service.ReplenishmentService
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
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

/** The SC16 catalog key backing [com.karyo.inventory.api.spi.SourceQuery.fromPicking]. */
private const val FROM_PICKING_KEY = "karyo.replenishment.from-picking"

/**
 * R15 (replenishment sprint Task 4) — end-to-end proof that [ReplenishmentService.scan] resolves
 * the real SC16-backed `karyo.replenishment.from-picking` toggle (rather than Task 3's
 * hard-coded `false`) and threads it into `SourceQuery.fromPicking`. Mutation guard: hard-coding
 * `fromPicking = false` again in [ReplenishmentService] fails case (b) below.
 *
 * The destination-face strictness split — a STORAGE target applying the strict rule to a
 * picking-area candidate even when `fromPicking` admits it, vs. a PICKING target using the loose
 * rule — doesn't need the runtime-store wiring to observe (only a hand-built `SourceQuery`), so
 * it's pinned directly against the selector in `ReplenishmentSourceSelectorTest` (Tests 11/12,
 * "destination-face strictness split") rather than duplicated here.
 *
 * Fixture idiom mirrors [ReplenishmentSchedulerIntegrationTest] / [ReplenishmentTopUpFlowTest]
 * (REST item-unit -> product -> location-type -> area -> location -> fix-assignment) plus
 * `ReplenishmentSourceSelectorTest.createPickingLocation` for the PICKING-usage source location.
 * Each test uses its own dedicated clientId (6501-6503 range) — [ReplenishmentService.scan]
 * processes EVERY fix assignment visible to the client id it's given, so a shared tenant would
 * pick up unrelated fixtures from other test classes, and a stored SC16 row must never leak
 * across a test's own client id into another's resolution ladder.
 */
@QuarkusTest
class ReplenishFromPickingToggleTest {

    @Inject
    lateinit var replenishmentService: ReplenishmentService

    @Inject
    lateinit var systemPropertyService: SystemPropertyService

    @Inject
    lateinit var tenantContext: TenantContext

    // ── REST seed helpers (mirrors ReplenishmentSchedulerIntegrationTest) ──

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"RFP Product","itemUnitId":$itemUnitId}""")
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

    /** Creates a location-type + PICKING-usage area + location; returns the location id (mirrors ReplenishmentSourceSelectorTest). */
    private fun createPickingLocation(namePrefix: String): Long {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = given().contentType(ContentType.JSON)
            .body("""{"name":"LT-$namePrefix-$ns"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        val pickingAreaId = given().contentType(ContentType.JSON)
            .body("""{"name":"AREA-$namePrefix-$ns","usages":["PICKING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        return given().contentType(ContentType.JSON)
            .body("""{"name":"LOC-$namePrefix-$ns","locationTypeId":$ltId,"areaId":$pickingAreaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun primeTenant(clientId: Long) {
        tenantContext.clientId = clientId
        tenantContext.principalKind = PrincipalKind.OWNER
    }

    /**
     * A below-min fix face on a plain (non-picking) target location, with its only available
     * source stock sitting on a PICKING-usage location — the exact shape that only the
     * `fromPicking` toggle (not any strictness rule; the source is fully unreserved and
     * non-mixed, so it would pass the strict rule too) gates.
     */
    private fun seedFixtures(clientId: Long, ns: Long): Triple<Long, Long, Long> {
        val tag = "$ns".takeLast(9)
        val itemUnitId = createItemUnit("IU-RFP-$tag")
        val itemDataId = createProduct("RFP-SKU-$ns", itemUnitId)
        val ltId = createLocationType("LT-RFP-$tag")
        val areaId = createArea("AREA-RFP-$tag")
        val faceLocationId = createLocation("LOC-RFP-FACE-$tag", ltId, areaId)
        val fixAssignmentId = createFixAssignment(faceLocationId, itemDataId, minAmount = 10)

        val pickingLocId = createPickingLocation("RFP-$clientId")
        val sourceUlId = createUnitLoad("UL-RFP-SRC-$ns", pickingLocId)
        createStock(sourceUlId, itemDataId, "RFP-SKU-$ns", BigDecimal("50"))

        return Triple(fixAssignmentId, itemDataId, sourceUlId)
    }

    // ── (a) toggle off (default, no stored row) ─────────────────────────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6501"), Claim(key = "tenant_code", value = "ACME")])
    fun `(a) default toggle off -- a picking-area source unit-load is not selected, scan reports NO_SOURCE`() {
        val clientId = 6501L
        val (fixAssignmentId, _, _) = seedFixtures(clientId, System.nanoTime())

        primeTenant(clientId)
        val result = replenishmentService.scan(clientId)

        assertThat(result.generated).noneMatch { it.fixAssignmentId == fixAssignmentId }
        assertThat(result.shortfalls)
            .anyMatch { it.fixAssignmentId == fixAssignmentId && it.reason == "NO_SOURCE" }
    }

    // ── (b) client-scoped row set to true -- eligible for THAT client ───────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6502"), Claim(key = "tenant_code", value = "ACME")])
    fun `(b) a client-scoped system-property row set to true makes the picking-area source eligible for that client`() {
        val clientId = 6502L
        val (fixAssignmentId, _, sourceUlId) = seedFixtures(clientId, System.nanoTime())
        systemPropertyService.set(clientId, FROM_PICKING_KEY, null, "true")

        primeTenant(clientId)
        val result = replenishmentService.scan(clientId)

        val generated = result.generated.firstOrNull { it.fixAssignmentId == fixAssignmentId }
        assertThat(generated).isNotNull
        assertThat(generated!!.unitLoadId).isEqualTo(sourceUlId)
        assertThat(result.shortfalls).noneMatch { it.fixAssignmentId == fixAssignmentId }
    }

    // ── (c) another client, no stored row -- ladder isolation ───────────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6503"), Claim(key = "tenant_code", value = "ACME")])
    fun `(c) a different client with no stored row of its own stays excluded regardless of another client's true row`() {
        val clientId = 6503L
        val (fixAssignmentId, _, _) = seedFixtures(clientId, System.nanoTime())
        // No row stored for THIS client -- case (b)'s client-6502 "true" row must not leak here
        // (SystemPropertyServiceTest already exhaustively covers the ladder itself; this pins
        // that ReplenishmentService's real call site honors per-client scoping end-to-end).

        primeTenant(clientId)
        val result = replenishmentService.scan(clientId)

        assertThat(result.generated).noneMatch { it.fixAssignmentId == fixAssignmentId }
        assertThat(result.shortfalls)
            .anyMatch { it.fixAssignmentId == fixAssignmentId && it.reason == "NO_SOURCE" }
    }
}
