package com.karyo.stocktaking

import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.CountInput
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.exception.StocktakingException
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
 * Integration test for [StocktakingService.locationEmpty] (St3 -- confirm-empty op + the
 * zero-line silent-no-op fix on [StocktakingService.submitCount]).
 *
 * Setup mirrors [ReviewTest] / [CancelOrderTest] / [UnitLoadMissingTest]: seed layout (+ stock,
 * where a line count > 0 is needed) via REST, prime [TenantContext.clientId], drive the service
 * directly.
 *
 * clientId 2901-2906 reserved for this suite.
 */
@QuarkusTest
class LocationEmptyTest {

    @Inject
    lateinit var service: StocktakingService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    // ── Seeding helpers (mirrored from ReviewTest/CancelOrderTest/UnitLoadMissingTest) ────────

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
    private fun createStock(ulId: Long, itemDataId: Long, amount: Double, prefix: String = "LE"): Pair<Long, String> {
        val suffix = System.nanoTime().toString().takeLast(8)
        val itemDataNumber = "$prefix-$suffix"
        val id = given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemDataNumber",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
        return Pair(id, itemDataNumber)
    }

    /** Empty location -- no unit load, no stock. */
    private fun seedEmptyLocation(clientId: Long): Long {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-LE-$ns")
        val areaId = createArea("AREA-LE-$ns")
        val locName = "LOC-LE-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        tenantContext.clientId = clientId
        return locationId
    }

    data class TwoLineCtx(
        val locationId: Long,
        val stockA: Long,
        val itemA: String,
        val stockB: Long,
        val itemB: String,
    )

    /** One location, two unit loads, one stock unit each -- a two-line count order. */
    private fun seedTwoLineLocation(clientId: Long): TwoLineCtx {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-LE2-$ns")
        val areaId = createArea("AREA-LE2-$ns")
        val locName = "LOC-LE2-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulA = createUnitLoad("UL-LE2-A-$ns", locationId, locName)
        val ulB = createUnitLoad("UL-LE2-B-$ns", locationId, locName)
        val (stockA, itemA) = createStock(ulA, 2901001, 5.0, "LEA")
        val (stockB, itemB) = createStock(ulB, 2901002, 7.0, "LEB")
        tenantContext.clientId = clientId
        return TwoLineCtx(locationId, stockA, itemA, stockB, itemB)
    }

    private fun locationLockOf(locationId: Long): Int =
        given().`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200).extract().jsonPath().getInt("lockType")

    private fun lastCountedAtOf(locationId: Long): String? =
        given().`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200).extract().jsonPath().getString("lastCountedAt")

    private fun stockStateOf(stockUnitId: Long): Int =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath().getInt("state")

    private fun journalCount(itemDataNumber: String, clientId: Long, recordType: JournalRecordType): Long =
        journalRepository.count(
            "productNumber = ?1 and clientId = ?2 and recordType = ?3",
            itemDataNumber, clientId, recordType.code,
        )

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2901"), Claim(key = "tenant_code", value = "LE-TEST")])
    fun `branch 1 -- zero-line order FINISHES directly, releases the location lock, stamps lastCountedAt, and closes the only session`() {
        val locationId = seedEmptyLocation(2901)
        val started = service.startCount(StartCountRequest(locationIds = listOf(locationId)), 2901)
        val sessionView = service.getSession(started.id, 2901)
        val order = sessionView.orders.first()
        assertThat(order.state).isEqualTo(CountOrderState.GENERATED.code)
        assertThat(order.lines).isEmpty()

        val view = service.locationEmpty(order.id, 2901)

        assertThat(view.state).isEqualTo(CountOrderState.FINISHED.code)
        assertThat(view.lines).isEmpty()
        assertThat(locationLockOf(locationId)).isEqualTo(0) // UNLOCKED
        assertThat(lastCountedAtOf(locationId)).isNotNull()

        val session = service.getSession(sessionView.id, 2901)
        assertThat(session.state).isEqualTo(CountSessionState.CLOSED.code)
    }

    @Test
    @TestSecurity(user = "operator2", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2902"), Claim(key = "tenant_code", value = "LE-TEST2")])
    fun `regression -- submitCount on a zero-line order now 422s instead of silently auto-finishing`() {
        val locationId = seedEmptyLocation(2902)
        val started = service.startCount(StartCountRequest(locationIds = listOf(locationId)), 2902)
        val sessionView = service.getSession(started.id, 2902)
        val order = sessionView.orders.first()

        assertThatThrownBy { service.submitCount(order.id, emptyList(), 2902) }
            .isInstanceOf(StocktakingException.InvalidCount::class.java)
            .hasMessageContaining("location-empty")

        // order untouched -- still GENERATED, no silent auto-finish
        val stillGenerated = service.orderView(order.id, 2902)
        assertThat(stillGenerated.state).isEqualTo(CountOrderState.GENERATED.code)
    }

    @Test
    @TestSecurity(user = "operator3", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2903"), Claim(key = "tenant_code", value = "LE-TEST3")])
    fun `branch 2 -- an order with lines has every PLANNED line zeroed and moves to COUNTED, accept applies the zeros`() {
        val ctx = seedTwoLineLocation(2903)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2903)
        val sessionView = service.getSession(started.id, 2903)
        val order = sessionView.orders.first()
        assertThat(order.lines).hasSize(2)

        val view = service.locationEmpty(order.id, 2903)

        assertThat(view.state).isEqualTo(CountOrderState.COUNTED.code)
        assertThat(view.lines).allMatch { it.state == CountLineState.COUNTED.code }
        assertThat(view.lines).allMatch { it.countedAmount?.compareTo(BigDecimal.ZERO) == 0 }

        val accepted = service.accept(order.id, 2903)
        assertThat(accepted.state).isEqualTo(CountOrderState.FINISHED.code)
        assertThat(accepted.lines).allMatch { it.state == CountLineState.FINISHED.code }

        // stock soft-deleted (DELETABLE=1000) by applyCount(0)
        assertThat(stockStateOf(ctx.stockA)).isEqualTo(1000)
        assertThat(stockStateOf(ctx.stockB)).isEqualTo(1000)

        // a COUNTED journal row per line
        assertThat(journalCount(ctx.itemA, 2903, JournalRecordType.COUNTED))
            .`as`("accept must write a COUNTED journal entry for line A")
            .isGreaterThanOrEqualTo(1)
        assertThat(journalCount(ctx.itemB, 2903, JournalRecordType.COUNTED))
            .`as`("accept must write a COUNTED journal entry for line B")
            .isGreaterThanOrEqualTo(1)
    }

    @Test
    @TestSecurity(user = "operator4", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2904"), Claim(key = "tenant_code", value = "LE-TEST4")])
    fun `locationEmpty on a COUNTED order throws InvalidState`() {
        val ctx = seedTwoLineLocation(2904)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2904)
        val sessionView = service.getSession(started.id, 2904)
        val order = sessionView.orders.first()
        val lines = order.lines

        // mismatch -> COUNTED, not FINISHED
        service.submitCount(
            orderId = order.id,
            inputs = lines.map { CountInput(lineId = it.id, countedAmount = it.plannedAmount.add(BigDecimal.ONE)) },
            clientId = 2904,
        )
        val counted = service.orderView(order.id, 2904)
        assertThat(counted.state).isEqualTo(CountOrderState.COUNTED.code)

        assertThatThrownBy { service.locationEmpty(order.id, 2904) }
            .isInstanceOf(StocktakingException.InvalidState::class.java)
    }

    @Test
    @TestSecurity(user = "operator5", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2905"), Claim(key = "tenant_code", value = "LE-TEST5")])
    fun `shared zero-lines helper -- unitLoadMissing zeroes one line, a stale submitCount input for it still 422s, then locationEmpty finishes the rest`() {
        val ctx = seedTwoLineLocation(2905)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2905)
        val sessionView = service.getSession(started.id, 2905)
        val order = sessionView.orders.first()
        val lineA = order.lines.first { it.stockUnitId == ctx.stockA }
        val lineB = order.lines.first { it.stockUnitId == ctx.stockB }

        // unitLoadMissing zeroes line A only -- order stays GENERATED (St4 semantics)
        val ulAId = order.lines.first { it.stockUnitId == ctx.stockA }.unitLoadId!!
        service.unitLoadMissing(order.id, ulAId, 2905)
        val afterMissing = service.orderView(order.id, 2905)
        assertThat(afterMissing.state).isEqualTo(CountOrderState.GENERATED.code)
        assertThat(afterMissing.lines.first { it.id == lineA.id }.state).isEqualTo(CountLineState.COUNTED.code)

        // interplay pin: a stale submitCount input targeting the already-zeroed line A still
        // 422s, even mixed with a legitimate input for the still-PLANNED line B (the existing
        // per-line guard in submitCount is generic over which op zeroed the line -- this pins
        // that the extraction of the shared zero-lines helper didn't regress it)
        assertThatThrownBy {
            service.submitCount(
                orderId = order.id,
                inputs = listOf(
                    CountInput(lineId = lineA.id, countedAmount = BigDecimal("4")),
                    CountInput(lineId = lineB.id, countedAmount = BigDecimal("7")),
                ),
                clientId = 2905,
            )
        }.isInstanceOf(StocktakingException.InvalidCount::class.java)

        // locationEmpty branch 2 zeroes the remaining PLANNED line (B) via the same shared
        // helper, leaves the already-zeroed line (A) untouched, and moves the order to COUNTED
        val view = service.locationEmpty(order.id, 2905)
        assertThat(view.state).isEqualTo(CountOrderState.COUNTED.code)
        val byId = view.lines.associateBy { it.id }
        assertThat(byId.getValue(lineA.id).state).isEqualTo(CountLineState.COUNTED.code)
        assertThat(byId.getValue(lineA.id).countedAmount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(byId.getValue(lineB.id).state).isEqualTo(CountLineState.COUNTED.code)
        assertThat(byId.getValue(lineB.id).countedAmount).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @TestSecurity(user = "operator6", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2906"), Claim(key = "tenant_code", value = "LE-TEST6")])
    fun `REST leg -- POST count-orders id location-empty with no body returns 200 with state 700 for a zero-line order`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-LER-$ns")
        val areaId = createArea("AREA-LER-$ns")
        val locName = "LOC-LER-$ns"
        val locationId = createLocation(locName, ltId, areaId)

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
            .`when`().post("/api/v1/count-orders/$orderId/location-empty")
            .then().statusCode(200)
            .body("state", org.hamcrest.Matchers.equalTo(CountOrderState.FINISHED.code))
    }
}
