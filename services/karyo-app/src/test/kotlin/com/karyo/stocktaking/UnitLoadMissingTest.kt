package com.karyo.stocktaking

import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.CountInput
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.exception.StocktakingException
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test for [StocktakingService.unitLoadMissing] (St4 — unit-load identity on count
 * lines + the unit-load-missing op).
 *
 * clientId 2801-2803 reserved for this suite.
 */
@QuarkusTest
class UnitLoadMissingTest {

    @Inject
    lateinit var service: StocktakingService

    @Inject
    lateinit var tenantContext: TenantContext

    // ── Seeding helpers (mirrored from ReviewTest/SubmitCountTest) ─────────────

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
    private fun createStock(ulId: Long, amount: Double, prefix: String = "ULM"): Long {
        val suffix = System.nanoTime().toString().takeLast(8)
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":2801001,"itemDataNumber":"$prefix-$suffix",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun stockStateOf(stockUnitId: Long): Int =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath().getInt("state")

    private fun stockAmountOf(stockUnitId: Long): BigDecimal =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath()
            .getString("amount").let { BigDecimal(it) }

    private fun unitLoadStateOf(unitLoadId: Long): Int =
        given().`when`().get("/api/v1/unit-loads/$unitLoadId")
            .then().statusCode(200).extract().jsonPath().getInt("state")

    private fun locationAllocationOf(locationId: Long): Double =
        given().`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200).extract().jsonPath().getDouble("allocation")

    data class TwoUlCtx(
        val locationId: Long,
        val ulAId: Long,
        val ulBId: Long,
        val suA1: Long,
        val suA2: Long,
        val suB1: Long,
    )

    /** Two unit loads on one location: UL A holds 2 stock units, UL B holds 1 (the 2/1 split). */
    private fun seedTwoUnitLoads(clientId: Long): TwoUlCtx {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULM-$ns")
        val areaId = createArea("AREA-ULM-$ns")
        val locName = "LOC-ULM-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulAId = createUnitLoad("UL-ULM-A-$ns", locationId, locName)
        val ulBId = createUnitLoad("UL-ULM-B-$ns", locationId, locName)
        val suA1 = createStock(ulAId, 4.0, "ULA1")
        val suA2 = createStock(ulAId, 6.0, "ULA2")
        val suB1 = createStock(ulBId, 9.0, "ULB1")
        tenantContext.clientId = clientId
        return TwoUlCtx(locationId, ulAId, ulBId, suA1, suA2, suB1)
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2801"), Claim(key = "tenant_code", value = "ULM-TEST")])
    fun `missing-op zeroes only the targeted unit load, order stays GENERATED, then full lifecycle`() {
        val ctx = seedTwoUnitLoads(2801)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2801)
        val sessionView = service.getSession(started.id, 2801)
        val order = sessionView.orders.first()
        assertThat(order.lines).hasSize(3)

        service.unitLoadMissing(order.id, ctx.ulAId, 2801)

        // order state UNCHANGED -- operator keeps counting the rest
        val afterMissing = service.orderView(order.id, 2801)
        assertThat(afterMissing.state).isEqualTo(CountOrderState.GENERATED.code)

        val linesByStock = afterMissing.lines.associateBy { it.stockUnitId }
        assertThat(linesByStock.getValue(ctx.suA1).state).isEqualTo(CountLineState.COUNTED.code)
        assertThat(linesByStock.getValue(ctx.suA1).countedAmount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(linesByStock.getValue(ctx.suA2).state).isEqualTo(CountLineState.COUNTED.code)
        assertThat(linesByStock.getValue(ctx.suA2).countedAmount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(linesByStock.getValue(ctx.suB1).state).isEqualTo(CountLineState.PLANNED.code)
        assertThat(linesByStock.getValue(ctx.suB1).countedAmount).isNull()

        // blind entry view reflects the group's UL identity + lock
        val entry = service.entryView(order.id, 2801)
        val entryByStock = entry.lines.associate { it.lineId to it }
        val aLine1Id = linesByStock.getValue(ctx.suA1).id
        val bLineId = linesByStock.getValue(ctx.suB1).id
        assertThat(entryByStock.getValue(aLine1Id).unitLoadLabel).isNotNull()

        // submit only UL B's line (exact match) -- A's lines are already COUNTED@0
        val submitted = service.submitCount(
            orderId = order.id,
            inputs = listOf(CountInput(lineId = bLineId, countedAmount = BigDecimal("9"))),
            clientId = 2801,
        )

        // order COUNTED -- a discrepancy is present (A's zeros vs planned 4/6)
        assertThat(submitted.state).isEqualTo(CountOrderState.COUNTED.code)
        val subLinesByStock = submitted.lines.associateBy { it.stockUnitId }
        assertThat(subLinesByStock.getValue(ctx.suA1).state).isEqualTo(CountLineState.COUNTED.code)
        assertThat(subLinesByStock.getValue(ctx.suA2).state).isEqualTo(CountLineState.COUNTED.code)
        // B matched exactly -- FINISHED
        assertThat(subLinesByStock.getValue(ctx.suB1).state).isEqualTo(CountLineState.FINISHED.code)

        // a missing-op is refused once the order has left GENERATED
        assertThatThrownBy { service.unitLoadMissing(order.id, ctx.ulBId, 2801) }
            .isInstanceOf(StocktakingException.InvalidState::class.java)

        // accept the discrepancy
        val accepted = service.accept(order.id, 2801)
        assertThat(accepted.state).isEqualTo(CountOrderState.FINISHED.code)
        assertThat(accepted.lines).allMatch { it.state == CountLineState.FINISHED.code }

        // UL A's stock deleted (soft -- DELETABLE=1000) by applyCount(0)
        assertThat(stockStateOf(ctx.suA1)).isEqualTo(1000)
        assertThat(stockStateOf(ctx.suA2)).isEqualTo(1000)
        // UL B's stock left as-is -- matched exactly, no adjustment needed
        assertThat(stockAmountOf(ctx.suB1)).isEqualByComparingTo("9")

        // emptied-UL branch: UL A itself is soft-deleted (DELETABLE) once its last stock unit
        // goes DELETABLE -- see DefaultStockCountingPort.applyCount KDoc for why this is a
        // soft-delete (state flip), not a physical row removal.
        assertThat(unitLoadStateOf(ctx.ulAId)).isEqualTo(1000)
        // UL B still has live stock -- untouched
        assertThat(unitLoadStateOf(ctx.ulBId)).isNotEqualTo(1000)

        // defect row 3 (2026-08-02 burndown): the terminal flip also releases UL A's share of
        // the shared location's allocation via UnitLoadTrashedEvent. Both UL A and UL B were
        // created directly at this location (never transferred), so allocation was never bumped
        // above its floor of 0 -- this pins that the release call clamps cleanly rather than
        // driving it negative, now that UL A has gone DELETABLE.
        assertThat(locationAllocationOf(ctx.locationId)).isEqualTo(0.0)
    }

    @Test
    @TestSecurity(user = "operator2", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2802"), Claim(key = "tenant_code", value = "ULM-TEST2")])
    fun `regression -- without the missing-op, submitCount still requires an input for every line`() {
        val ctx = seedTwoUnitLoads(2802)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2802)
        val sessionView = service.getSession(started.id, 2802)
        val order = sessionView.orders.first()
        val oneLine = order.lines.first { it.stockUnitId == ctx.suA1 }

        // only one of the three lines gets an input -- the other two (including UL B's) are
        // still PLANNED and must still be rejected as "missing count"
        assertThatThrownBy {
            service.submitCount(
                orderId = order.id,
                inputs = listOf(CountInput(lineId = oneLine.id, countedAmount = oneLine.plannedAmount)),
                clientId = 2802,
            )
        }.isInstanceOf(StocktakingException.InvalidCount::class.java)
    }

    @Test
    @TestSecurity(user = "operator3", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2803"), Claim(key = "tenant_code", value = "ULM-TEST3")])
    fun `REST leg -- missing-op 404s for a unit load not on the order`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULR-$ns")
        val areaId = createArea("AREA-ULR-$ns")
        val locName = "LOC-ULR-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-ULR-$ns", locationId, locName)
        createStock(ulId, 5.0, "ULR")

        val sessionResp = given().contentType(ContentType.JSON)
            .body("""{"locationIds":[$locationId]}""")
            .`when`().post("/api/v1/count-sessions")
            .then().statusCode(201)
            .extract().jsonPath()
        val sessionId = sessionResp.getLong("id")
        val orderId = given().`when`().get("/api/v1/count-sessions/$sessionId")
            .then().statusCode(200)
            .extract().jsonPath().getLong("orders[0].id")

        given().contentType(ContentType.JSON)
            .body("""{"unitLoadId":999999999}""")
            .`when`().post("/api/v1/count-orders/$orderId/unit-loads/missing")
            .then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "operator4", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2804"), Claim(key = "tenant_code", value = "ULM-TEST4")])
    fun `REST leg -- submitting a new input for a line already zeroed by the missing-op 422s`() {
        val ctx = seedTwoUnitLoads(2804)
        val started = service.startCount(StartCountRequest(locationIds = listOf(ctx.locationId)), 2804)
        val sessionView = service.getSession(started.id, 2804)
        val order = sessionView.orders.first()

        // the missing-op zeroes UL A's two lines (COUNTED, no longer PLANNED)
        service.unitLoadMissing(order.id, ctx.ulAId, 2804)
        val afterMissing = service.orderView(order.id, 2804)
        val aLine = afterMissing.lines.first { it.stockUnitId == ctx.suA1 }
        val bLine = afterMissing.lines.first { it.stockUnitId == ctx.suB1 }

        // this is the exact PWA-vs-web race Important 3 describes: the floor app submits an
        // amount for EVERY entry line, including one the web-triggered missing-op already
        // zeroed. B's (still-PLANNED, legitimate) input is included too, so a 422 here can only
        // be attributed to the stale A input -- not to some other line missing an input.
        given().contentType(ContentType.JSON)
            .body(
                """{"lines":[{"lineId":${aLine.id},"countedAmount":4},""" +
                    """{"lineId":${bLine.id},"countedAmount":9}]}"""
            )
            .`when`().post("/api/v1/count-orders/${order.id}/count")
            .then().statusCode(422)
    }
}
