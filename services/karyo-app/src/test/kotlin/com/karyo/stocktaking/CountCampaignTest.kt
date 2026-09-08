package com.karyo.stocktaking

import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.CountInput
import com.karyo.stocktaking.dto.CreateCampaignRequest
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.exception.StocktakingException
import com.karyo.stocktaking.service.CountCampaignService
import com.karyo.stocktaking.service.StocktakingService
import com.karyo.stocktaking.vo.CountCampaignState
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

/**
 * Integration test for [CountCampaign][com.karyo.stocktaking.domain.model.CountCampaign] (St1).
 *
 * Setup mirrors [StartCountTest] / [CancelOrderTest]: seed layout + stock via REST, prime
 * [TenantContext.clientId], drive the service directly (REST legs are added only where they
 * exercise resource wiring not covered by a direct service call).
 *
 * clientId 2701-2707 reserved for this suite.
 */
@QuarkusTest
class CountCampaignTest {

    @Inject
    lateinit var service: StocktakingService

    @Inject
    lateinit var campaignService: CountCampaignService

    @Inject
    lateinit var tenantContext: TenantContext

    // ── Seeding helpers (mirrored from CancelOrderTest/StartCountTest) ───────────

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

    /** Creates an ON_STOCK stock unit. Item-unit name suffix ≤8 chars (field max=20). */
    private fun createStock(ulId: Long, amount: Double, prefix: String = "CCT"): Long {
        val suffix = System.nanoTime().toString().takeLast(8)
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":2701001,"itemDataNumber":"$prefix-$suffix",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Seeds one real layout location + unit load + ON_STOCK stock unit, returning the
     *  location id (the unit startCount consumes). */
    private fun seedLocation(clientId: Long, amount: Double, prefix: String = "CCT"): Long {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-CC-$ns")
        val areaId = createArea("AREA-CC-$ns")
        val locName = "LOC-CC-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-CC-$ns", locationId, locName)
        createStock(ulId, amount, prefix)
        tenantContext.clientId = clientId
        return locationId
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "mgr1", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2701"), Claim(key = "tenant_code", value = "CC-TEST1")])
    fun `create returns 201 OPEN with a CC- number`() {
        tenantContext.clientId = 2701
        val view = campaignService.createCampaign(CreateCampaignRequest(name = "Q3 cycle sweep"), 2701)

        assertThat(view.state).isEqualTo(CountCampaignState.OPEN.code)
        assertThat(view.campaignNumber).startsWith("CC-")
        assertThat(view.name).isEqualTo("Q3 cycle sweep")
        assertThat(view.type).isEqualTo("CYCLE")
        assertThat(view.ended).isNull()

        // REST leg -- resource wiring, 201 + JSON body.
        given().contentType(ContentType.JSON)
            .body("""{"name":"REST campaign"}""")
            .`when`().post("/api/v1/count-campaigns")
            .then().statusCode(201)
            .body("state", org.hamcrest.Matchers.equalTo(CountCampaignState.OPEN.code))
            .body("campaignNumber", org.hamcrest.Matchers.startsWith("CC-"))
    }

    @Test
    @TestSecurity(user = "mgr2", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2702"), Claim(key = "tenant_code", value = "CC-TEST2")])
    fun `starting a session with campaignId stamps it onto the session view`() {
        tenantContext.clientId = 2702
        val campaign = campaignService.createCampaign(CreateCampaignRequest(name = "Tag test"), 2702)
        val locationId = seedLocation(2702, 10.0)

        val session = service.startCount(
            StartCountRequest(locationIds = listOf(locationId), campaignId = campaign.id),
            2702,
        )

        assertThat(session.campaignId).isEqualTo(campaign.id)
        // Round-trip via getSession too.
        assertThat(service.getSession(session.id, 2702).campaignId).isEqualTo(campaign.id)
    }

    @Test
    @TestSecurity(user = "mgr3", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2703"), Claim(key = "tenant_code", value = "CC-TEST3")])
    fun `close refuses 409 while a session is OPEN, succeeds once it finishes`() {
        tenantContext.clientId = 2703
        val campaign = campaignService.createCampaign(CreateCampaignRequest(name = "Close lifecycle"), 2703)
        val locationId = seedLocation(2703, 12.0)
        val session = service.startCount(
            StartCountRequest(locationIds = listOf(locationId), campaignId = campaign.id),
            2703,
        )
        assertThat(session.state).isEqualTo(CountSessionState.OPEN.code)

        // Session still OPEN (order GENERATED) -> close refused.
        assertThatThrownBy { campaignService.closeCampaign(campaign.id, 2703) }
            .isInstanceOf(StocktakingException.InvalidState::class.java)

        // Finish the order with an exact match -> auto-finishes the order, which
        // auto-closes the (only) session in it.
        val order = service.getSession(session.id, 2703).orders.first()
        val line = order.lines.first()
        val finished = service.submitCount(
            orderId = order.id,
            inputs = listOf(CountInput(lineId = line.id, countedAmount = line.plannedAmount)),
            clientId = 2703,
        )
        assertThat(finished.state).isEqualTo(CountOrderState.FINISHED.code)
        assertThat(service.getSession(session.id, 2703).state).isEqualTo(CountSessionState.CLOSED.code)

        val closed = campaignService.closeCampaign(campaign.id, 2703)
        assertThat(closed.state).isEqualTo(CountCampaignState.CLOSED.code)
        assertThat(closed.ended).isNotNull()

        // Forward-only: closing an already-CLOSED campaign 409s too.
        assertThatThrownBy { campaignService.closeCampaign(campaign.id, 2703) }
            .isInstanceOf(StocktakingException.InvalidState::class.java)
    }

    @Test
    @TestSecurity(user = "mgr4", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2704"), Claim(key = "tenant_code", value = "CC-TEST4")])
    fun `rollup counts sessions, orders-by-state and discrepancy lines across the campaign`() {
        tenantContext.clientId = 2704
        val campaign = campaignService.createCampaign(CreateCampaignRequest(name = "Rollup test"), 2704)

        // Session 1: exact match -> FINISHED, no discrepancy.
        val loc1 = seedLocation(2704, 10.0, prefix = "RU1")
        val s1 = service.startCount(StartCountRequest(locationIds = listOf(loc1), campaignId = campaign.id), 2704)
        val o1 = service.getSession(s1.id, 2704).orders.first()
        val l1 = o1.lines.first()
        service.submitCount(o1.id, listOf(CountInput(l1.id, l1.plannedAmount)), 2704)

        // Session 2: mismatch -> COUNTED with a discrepancy, then accepted -> FINISHED
        // (the discrepancy line itself is still a discrepancy after accept).
        val loc2 = seedLocation(2704, 5.0, prefix = "RU2")
        val s2 = service.startCount(StartCountRequest(locationIds = listOf(loc2), campaignId = campaign.id), 2704)
        val o2 = service.getSession(s2.id, 2704).orders.first()
        val l2 = o2.lines.first()
        val counted = service.submitCount(o2.id, listOf(CountInput(l2.id, l2.plannedAmount.add(java.math.BigDecimal.ONE))), 2704)
        assertThat(counted.state).isEqualTo(CountOrderState.COUNTED.code)
        service.accept(o2.id, 2704)

        val rollup = campaignService.getCampaign(campaign.id, 2704)
        assertThat(rollup.sessions).isEqualTo(2L)
        assertThat(rollup.ordersByState.finished).isEqualTo(2L)
        assertThat(rollup.ordersByState.generated).isEqualTo(0L)
        assertThat(rollup.ordersByState.counted).isEqualTo(0L)
        assertThat(rollup.ordersByState.cancelled).isEqualTo(0L)
        assertThat(rollup.discrepancyLines).isEqualTo(1L)
    }

    @Test
    @TestSecurity(user = "mgr5", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2705"), Claim(key = "tenant_code", value = "CC-TEST5")])
    fun `foreign-tenant get 404s, and starting against a CLOSED campaign 409s`() {
        tenantContext.clientId = 2705
        val campaign = campaignService.createCampaign(CreateCampaignRequest(name = "Foreign + closed"), 2705)

        // Foreign tenant cannot see it.
        assertThatThrownBy { campaignService.getCampaign(campaign.id, 999_999) }
            .isInstanceOf(StocktakingException.NotFound::class.java)

        // Close it (no sessions -> vacuously closeable), then a plain start against it 409s.
        val closed = campaignService.closeCampaign(campaign.id, 2705)
        assertThat(closed.state).isEqualTo(CountCampaignState.CLOSED.code)

        val locationId = seedLocation(2705, 3.0, prefix = "CLS")
        assertThatThrownBy {
            service.startCount(StartCountRequest(locationIds = listOf(locationId), campaignId = campaign.id), 2705)
        }.isInstanceOf(StocktakingException.InvalidState::class.java)
    }

    @Test
    @TestSecurity(user = "mgr6", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2701"), Claim(key = "tenant_code", value = "CC-TEST1")])
    fun `starting against an absent campaignId 404s`() {
        tenantContext.clientId = 2701
        val locationId = seedLocation(2701, 3.0, prefix = "ABS")
        assertThatThrownBy {
            service.startCount(StartCountRequest(locationIds = listOf(locationId), campaignId = 987_654_321L), 2701)
        }.isInstanceOf(StocktakingException.NotFound::class.java)
    }

    /**
     * Regression (review finding, Important #1): [countDiscrepancies][com.karyo.stocktaking.repository.CountLineRepository.countDiscrepancies]
     * must exclude CANCELLED lines. [service.recount] cancels the mismatched order's lines
     * WITHOUT clearing `countedAmount` (the stale mismatch is kept for audit) -- a naive
     * `state >= COUNTED` filter would count that cancelled, superseded discrepancy forever,
     * even after the fresh recount order closes clean. Pins BOTH legs: 1 before the recount
     * (the metric's positive leg), 0 after (the fix's whole point).
     */
    @Test
    @TestSecurity(user = "mgr7", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2706"), Claim(key = "tenant_code", value = "CC-TEST6")])
    fun `discrepancyLines drops to 0 after a mismatch is recounted clean`() {
        tenantContext.clientId = 2706
        val campaign = campaignService.createCampaign(CreateCampaignRequest(name = "Recount rollup"), 2706)
        val locationId = seedLocation(2706, 10.0, prefix = "RCT")
        val session = service.startCount(
            StartCountRequest(locationIds = listOf(locationId), campaignId = campaign.id),
            2706,
        )
        val order = service.getSession(session.id, 2706).orders.first()
        val line = order.lines.first()

        // Mismatch -> order COUNTED, line COUNTED (discrepant, countedAmount != plannedAmount).
        val counted = service.submitCount(
            order.id,
            listOf(CountInput(line.id, line.plannedAmount.add(java.math.BigDecimal.ONE))),
            2706,
        )
        assertThat(counted.state).isEqualTo(CountOrderState.COUNTED.code)
        assertThat(campaignService.getCampaign(campaign.id, 2706).discrepancyLines).isEqualTo(1L)

        // Recount: cancels the mismatched order+line (countedAmount NOT cleared) and generates
        // a fresh GENERATED order for the same location.
        val fresh = service.recount(order.id, 2706)
        assertThat(fresh.state).isEqualTo(CountOrderState.GENERATED.code)
        val freshLine = fresh.lines.first()

        // Clean recount -> exact match -> auto-finish.
        val finished = service.submitCount(fresh.id, listOf(CountInput(freshLine.id, freshLine.plannedAmount)), 2706)
        assertThat(finished.state).isEqualTo(CountOrderState.FINISHED.code)

        assertThat(campaignService.getCampaign(campaign.id, 2706).discrepancyLines).isEqualTo(0L)
    }

    /**
     * Review finding (Important #2): [CreateCampaignRequest.name] carries no bean validation,
     * so a blank or over-length name would either 500 (Hibernate NOT NULL/length violation
     * surfacing unmapped) or silently persist an empty name. `@field:NotBlank @field:Size` on
     * the DTO turns both into a clean 400 before the campaign is ever built.
     */
    @Test
    @TestSecurity(user = "mgr8", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2707"), Claim(key = "tenant_code", value = "CC-TEST7")])
    fun `create 400s on a blank or over-length name`() {
        given().contentType(ContentType.JSON)
            .body("""{"name":""}""")
            .`when`().post("/api/v1/count-campaigns")
            .then().statusCode(400)

        val tooLong = "x".repeat(101)
        given().contentType(ContentType.JSON)
            .body("""{"name":"$tooLong"}""")
            .`when`().post("/api/v1/count-campaigns")
            .then().statusCode(400)
    }
}
