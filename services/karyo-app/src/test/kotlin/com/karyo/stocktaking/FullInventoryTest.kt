package com.karyo.stocktaking

import com.karyo.inventory.service.StockService
import com.karyo.layout.domain.model.LocationReservation
import com.karyo.layout.dto.CreateLocationRequest
import com.karyo.layout.dto.LockLocationRequest
import com.karyo.layout.repository.LocationReservationRepository
import com.karyo.layout.service.LocationService
import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.CountInput
import com.karyo.stocktaking.dto.CreateCampaignRequest
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.exception.StocktakingException
import com.karyo.stocktaking.service.CountCampaignService
import com.karyo.stocktaking.service.CountScopeStrategyResolver
import com.karyo.stocktaking.service.ExplicitLocationScope
import com.karyo.stocktaking.service.FullWarehouseScope
import com.karyo.stocktaking.service.StocktakingService
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Integration test for the END_OF_PERIOD full-inventory start (St5).
 *
 * Seeding mirrors [StartCountTest]/[CountCampaignTest]: layout + stock via REST under the test's
 * own JWT, [TenantContext.clientId] primed, service driven directly. The one exception is the
 * foreign-tenant location, which is created through [LocationService.createLocation] with an
 * explicit `clientId` (REST would always stamp the test's own JWT client).
 *
 * clientId 2801-2807 reserved for this suite (2899 = the foreign tenant). Each test owns its own
 * client id because a full inventory enumerates EVERY location of the tenant in the shared test
 * DB -- cross-test client reuse would leak locations into the scope.
 */
@QuarkusTest
class FullInventoryTest {

    @Inject
    lateinit var service: StocktakingService

    @Inject
    lateinit var campaignService: CountCampaignService

    @Inject
    lateinit var resolver: CountScopeStrategyResolver

    @Inject
    lateinit var locationService: LocationService

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var reservationRepository: LocationReservationRepository

    // ── Seeding helpers ────────────────────────────────────────────────────────

    /**
     * Persists a live (non-expired) [LocationReservation] directly against [locationId] --
     * simulates an inbound putaway that has soft-claimed the destination but not yet placed,
     * without driving a full putaway task through [com.karyo.layout.spi.LocationFinder].
     */
    @Transactional
    fun reserveLocation(locationId: Long, transportOrderId: Long) {
        val reservation = LocationReservation().apply {
            this.locationId = locationId
            this.transportOrderId = transportOrderId
            this.percent = BigDecimal("100")
            this.expiresAt = Instant.now().plusSeconds(600)
        }
        reservationRepository.persist(reservation)
    }

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

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long, orderIndex: Int): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId,"orderIndex":$orderIndex}"""
            )
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

    /** Creates an ON_STOCK stock unit on [ulId], optionally reserving [reserved] of it. */
    private fun createStock(ulId: Long, amount: Double, reserved: Double = 0.0): Long {
        val suffix = System.nanoTime().toString().takeLast(8)
        val stockId = given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":2801001,"itemDataNumber":"FI-$suffix",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
        if (reserved > 0.0) {
            stockService.reserveStock(stockId, BigDecimal.valueOf(reserved), "TEST-RESERVE", tenantContext)
        }
        return stockId
    }

    /** Reads a location's lockType via REST. */
    private fun locationLockOf(locationId: Long): Int =
        given().`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200).extract().jsonPath().getInt("lockType")

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * The headline St5 behaviour: an END_OF_PERIOD start enumerates the WHOLE tenant warehouse
     * (empty locations included), freezes every location it generated an order for, and skips --
     * rather than 409s on -- locations it cannot count (reserved stock, or an existing non-count
     * lock), naming them in `skippedLocations`.
     */
    @Test
    @TestSecurity(user = "mgrfi1", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2801"), Claim(key = "tenant_code", value = "FI-TEST1")])
    fun `END_OF_PERIOD start counts every tenant location, zero-lines the empty one and skips reserved plus locked`() {
        tenantContext.clientId = 2801
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-FI-$ns")
        val areaId = createArea("AREA-FI-$ns")

        // orderIndex pins the enumeration order the port must honour (empty(10) before stocked(30)).
        val emptyName = "FI-EMPTY-$ns"
        val emptyId = createLocation(emptyName, ltId, areaId, orderIndex = 10)

        val reservedName = "FI-RESV-$ns"
        val reservedId = createLocation(reservedName, ltId, areaId, orderIndex = 20)
        createStock(createUnitLoad("UL-FI-R-$ns", reservedId, reservedName), amount = 30.0, reserved = 5.0)

        val stockedName = "FI-STOCK-$ns"
        val stockedId = createLocation(stockedName, ltId, areaId, orderIndex = 30)
        createStock(createUnitLoad("UL-FI-S-$ns", stockedId, stockedName), amount = 25.0)

        val lockedName = "FI-LOCKED-$ns"
        val lockedId = createLocation(lockedName, ltId, areaId, orderIndex = 40)
        locationService.lockLocation(lockedId, LockLocationRequest(com.karyo.layout.vo.LockType.GENERAL.code), 2801)

        // A foreign tenant's location must never be enumerated -- created with an explicit
        // clientId because REST would stamp this test's own JWT client.
        locationService.createLocation(
            CreateLocationRequest(name = "FI-FOREIGN-$ns", locationTypeId = ltId, areaId = areaId),
            2899,
        )

        val view = service.startCount(StartCountRequest(type = "END_OF_PERIOD"), 2801)
        val graph = service.getSession(view.id, 2801)

        assertThat(view.type).isEqualTo("END_OF_PERIOD")
        // Orders for the empty + stocked locations only, in orderIndex order.
        assertThat(graph.orders.map { it.locationId }).containsExactly(emptyId, stockedId)
        assertThat(graph.orders.first { it.locationId == emptyId }.lines).isEmpty()
        assertThat(graph.orders.first { it.locationId == stockedId }.lines).hasSize(1)

        // Skipped (not 409'd): both the reserved and the already-locked location, by name.
        assertThat(view.skippedLocations).containsExactlyInAnyOrder(reservedName, lockedName)

        // Freeze: every generated location is STOCKTAKING-locked; skipped ones are untouched.
        assertThat(locationLockOf(emptyId)).isEqualTo(com.karyo.layout.vo.LockType.STOCKTAKING.code)
        assertThat(locationLockOf(stockedId)).isEqualTo(com.karyo.layout.vo.LockType.STOCKTAKING.code)
        assertThat(locationLockOf(reservedId)).isEqualTo(com.karyo.layout.vo.LockType.UNLOCKED.code)
        assertThat(locationLockOf(lockedId)).isEqualTo(com.karyo.layout.vo.LockType.GENERAL.code)
    }

    /**
     * THE RESOLVER TRAP regression. [FullWarehouseScope] registers at priority 100, which beats
     * [ExplicitLocationScope]'s `Int.MAX_VALUE` -- so an UNNAMED `resolve()` now returns the
     * full-warehouse strategy. [StocktakingService] must therefore always resolve BY NAME; this
     * test pins both halves: the resolver's raw (dangerous) unnamed behaviour, and the service
     * staying on EXPLICIT for a plain start.
     */
    @Test
    @TestSecurity(user = "mgrfi2", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2802"), Claim(key = "tenant_code", value = "FI-TEST2")])
    fun `a plain start still uses EXPLICIT even though FullWarehouseScope outranks it unnamed`() {
        tenantContext.clientId = 2802
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-FIX-$ns")
        val areaId = createArea("AREA-FIX-$ns")
        val askedName = "FIX-ASKED-$ns"
        val askedId = createLocation(askedName, ltId, areaId, orderIndex = 10)
        createStock(createUnitLoad("UL-FIX-A-$ns", askedId, askedName), amount = 7.0)
        // A second tenant location that a full-warehouse scope WOULD have picked up.
        val otherName = "FIX-OTHER-$ns"
        createLocation(otherName, ltId, areaId, orderIndex = 20)

        val view = service.startCount(StartCountRequest(locationIds = listOf(askedId)), 2802)
        val graph = service.getSession(view.id, 2802)

        assertThat(view.type).isEqualTo("CYCLE")
        assertThat(graph.orders.map { it.locationId }).containsExactly(askedId)
        assertThat(view.skippedLocations).isEmpty()

        // The raw resolver behaviour that makes the above non-trivial.
        assertThat(resolver.resolve()).isInstanceOf(FullWarehouseScope::class.java)
        assertThat(resolver.resolve("EXPLICIT")).isInstanceOf(ExplicitLocationScope::class.java)
        assertThat(resolver.resolve("FULL_WAREHOUSE")).isInstanceOf(FullWarehouseScope::class.java)
        // An unknown name must FAIL, never silently fall back to the highest-priority strategy
        // (which is now the whole warehouse).
        assertThatThrownBy { resolver.resolve("NO-SUCH-SCOPE") }
            .isInstanceOf(StocktakingException.InvalidCount::class.java)
    }

    /** END_OF_PERIOD owns its scope: any caller-supplied scope input is a 422, never ignored. */
    @Test
    @TestSecurity(user = "mgrfi3", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2803"), Claim(key = "tenant_code", value = "FI-TEST3")])
    fun `END_OF_PERIOD with any explicit scope input is rejected 422, and an unknown type too`() {
        tenantContext.clientId = 2803

        assertThatThrownBy {
            service.startCount(StartCountRequest(type = "END_OF_PERIOD", locationIds = listOf(1L)), 2803)
        }.isInstanceOf(StocktakingException.InvalidCount::class.java)

        assertThatThrownBy {
            service.startCount(StartCountRequest(type = "END_OF_PERIOD", areaId = 1L), 2803)
        }.isInstanceOf(StocktakingException.InvalidCount::class.java)

        assertThatThrownBy {
            service.startCount(StartCountRequest(type = "END_OF_PERIOD", locationNamePattern = "A-%"), 2803)
        }.isInstanceOf(StocktakingException.InvalidCount::class.java)

        assertThatThrownBy {
            service.startCount(StartCountRequest(type = "END_OF_PERIOD", scopeStrategy = "EXPLICIT"), 2803)
        }.isInstanceOf(StocktakingException.InvalidCount::class.java)

        assertThatThrownBy {
            service.startCount(StartCountRequest(type = "WEEKLY", locationIds = listOf(1L)), 2803)
        }.isInstanceOf(StocktakingException.InvalidCount::class.java)
    }

    /**
     * Review Important 1: the type <-> scope invariant is BICONDITIONAL. Guarding only the
     * END_OF_PERIOD direction left `{"scopeStrategy":"FULL_WAREHOUSE"}` with no type as a back
     * door to a warehouse-wide STOCKTAKING freeze filed as a CYCLE session -- and therefore with
     * none of END_OF_PERIOD's semantics (no skip list, a CYCLE campaign check, no badge). A
     * CYCLE start must refuse the full-warehouse scope by name, 422.
     */
    @Test
    @TestSecurity(user = "mgrfi6", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2806"), Claim(key = "tenant_code", value = "FI-TEST6")])
    fun `a CYCLE start cannot select the FULL_WAREHOUSE scope by name`() {
        tenantContext.clientId = 2806
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-FIB-$ns")
        val areaId = createArea("AREA-FIB-$ns")
        val locName = "FIB-$ns"
        val locId = createLocation(locName, ltId, areaId, orderIndex = 10)
        createStock(createUnitLoad("UL-FIB-$ns", locId, locName), amount = 6.0)

        // No type at all -- the back door.
        assertThatThrownBy {
            service.startCount(StartCountRequest(scopeStrategy = "FULL_WAREHOUSE"), 2806)
        }.isInstanceOf(StocktakingException.InvalidCount::class.java)

        // Explicit CYCLE, same refusal.
        assertThatThrownBy {
            service.startCount(StartCountRequest(type = "CYCLE", scopeStrategy = "FULL_WAREHOUSE"), 2806)
        }.isInstanceOf(StocktakingException.InvalidCount::class.java)

        // ...and nothing was frozen on the way out.
        assertThat(locationLockOf(locId)).isEqualTo(com.karyo.layout.vo.LockType.UNLOCKED.code)

        // The sanctioned spelling still works.
        val view = service.startCount(StartCountRequest(type = "END_OF_PERIOD"), 2806)
        assertThat(view.type).isEqualTo("END_OF_PERIOD")
        assertThat(service.getSession(view.id, 2806).orders.map { it.locationId }).containsExactly(locId)

        // REST leg -- 422 over the wire, not a 500 or a silent whole-warehouse freeze.
        given().contentType(ContentType.JSON)
            .body("""{"scopeStrategy":"FULL_WAREHOUSE"}""")
            .`when`().post("/api/v1/count-sessions")
            .then().statusCode(422)
    }

    /**
     * Review Important 3: a CYCLE start whose scope contains an already-frozen location must be
     * refused (409), not generated. `LocationService.lockLocation` overwrites unconditionally, so
     * a second session on the same location would double-count it AND release the first session's
     * freeze the moment it finished -- a silent thaw mid-inventory.
     */
    @Test
    @TestSecurity(user = "mgrfi7", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2807"), Claim(key = "tenant_code", value = "FI-TEST7")])
    fun `a CYCLE start on a location frozen by an open full inventory is refused 409`() {
        tenantContext.clientId = 2807
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-FIF-$ns")
        val areaId = createArea("AREA-FIF-$ns")
        val locName = "FIF-$ns"
        val locId = createLocation(locName, ltId, areaId, orderIndex = 10)
        createStock(createUnitLoad("UL-FIF-$ns", locId, locName), amount = 9.0)

        // Full inventory freezes it.
        val full = service.startCount(StartCountRequest(type = "END_OF_PERIOD"), 2807)
        val fullGraph = service.getSession(full.id, 2807)
        assertThat(fullGraph.orders.map { it.locationId }).containsExactly(locId)
        assertThat(locationLockOf(locId)).isEqualTo(com.karyo.layout.vo.LockType.STOCKTAKING.code)

        // A cycle count of the same location is now refused, by name, before anything is written.
        assertThatThrownBy { service.startCount(StartCountRequest(locationIds = listOf(locId)), 2807) }
            .isInstanceOf(StocktakingException.LocationLocked::class.java)
            .hasMessageContaining(locName)

        // The full inventory's freeze and its order are untouched.
        assertThat(locationLockOf(locId)).isEqualTo(com.karyo.layout.vo.LockType.STOCKTAKING.code)
        assertThat(service.getSession(full.id, 2807).orders).hasSize(1)

        // Finish the full inventory's order -> lock released -> the cycle count is allowed again.
        val order = fullGraph.orders.first()
        val line = order.lines.first()
        service.submitCount(order.id, listOf(CountInput(line.id, line.plannedAmount)), 2807)
        assertThat(locationLockOf(locId)).isEqualTo(com.karyo.layout.vo.LockType.UNLOCKED.code)

        val cycle = service.startCount(StartCountRequest(locationIds = listOf(locId)), 2807)
        assertThat(cycle.type).isEqualTo("CYCLE")
        assertThat(service.getSession(cycle.id, 2807).orders.map { it.locationId }).containsExactly(locId)
    }

    /**
     * A campaign only accepts a start of its OWN type, with ONE relaxation (defect-burndown-3,
     * row 4): a CYCLE session is now accepted under an END_OF_PERIOD campaign -- the sanctioned
     * remediation route for a location an END_OF_PERIOD start SKIPPED (dedicated coverage below,
     * `skipped-location ids let a CYCLE session...`). The other direction is unchanged: an
     * END_OF_PERIOD session may still not be filed under a CYCLE campaign.
     */
    @Test
    @TestSecurity(user = "mgrfi4", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2804"), Claim(key = "tenant_code", value = "FI-TEST4")])
    fun `campaign type gate is one-way -- CYCLE under an END_OF_PERIOD campaign is allowed, the reverse still refuses`() {
        tenantContext.clientId = 2804
        val cycle = campaignService.createCampaign(CreateCampaignRequest(name = "FI cycle"), 2804)
        val annual = campaignService.createCampaign(
            CreateCampaignRequest(name = "FI annual", type = "END_OF_PERIOD"),
            2804,
        )

        // Full inventory under a CYCLE campaign -> still refused, 409.
        assertThatThrownBy {
            service.startCount(StartCountRequest(type = "END_OF_PERIOD", campaignId = cycle.id), 2804)
        }.isInstanceOf(StocktakingException.InvalidState::class.java)

        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-FIC-$ns")
        val areaId = createArea("AREA-FIC-$ns")

        // Plain cycle start under an END_OF_PERIOD campaign -> now ALLOWED (the gate this task
        // relaxed one-way); previously a 409.
        val cycleLocName = "FIC-CYCLE-$ns"
        val cycleLocId = createLocation(cycleLocName, ltId, areaId, orderIndex = 10)
        createStock(createUnitLoad("UL-FIC-CYCLE-$ns", cycleLocId, cycleLocName), amount = 3.0)
        val cycleUnderAnnual = service.startCount(
            StartCountRequest(locationIds = listOf(cycleLocId), campaignId = annual.id),
            2804,
        )
        assertThat(cycleUnderAnnual.type).isEqualTo("CYCLE")
        assertThat(cycleUnderAnnual.campaignId).isEqualTo(annual.id)

        // Matching pair -> still accepted, campaign stamped on the session. (cycleLocId is now
        // STOCKTAKING-locked by the session above, so the full inventory below skips it and only
        // generates an order for the fresh location -- unrelated to the assertion, just honest.)
        val locName = "FIC-$ns"
        val locId = createLocation(locName, ltId, areaId, orderIndex = 20)
        createStock(createUnitLoad("UL-FIC-$ns", locId, locName), amount = 4.0)

        val view = service.startCount(StartCountRequest(type = "END_OF_PERIOD", campaignId = annual.id), 2804)
        assertThat(view.campaignId).isEqualTo(annual.id)
        assertThat(view.type).isEqualTo("END_OF_PERIOD")
        assertThat(service.getSession(view.id, 2804).orders.map { it.locationId }).containsExactly(locId)
    }

    /**
     * Task 6 (defect-burndown-3, row 4): the skip report now carries machine-usable ids
     * alongside names, and the campaign-type gate is relaxed one-way so the sanctioned
     * remediation flow -- a CYCLE session naming the skipped location(s), filed under the SAME
     * END_OF_PERIOD campaign once the reservation clears -- is no longer blocked.
     * [StocktakingService.generateOrderForLocation]'s ReservedStock guard is untouched: the
     * location still cannot be counted while the reservation is live; this only opens a route to
     * file the remediating count under the original campaign afterwards.
     */
    @Test
    @TestSecurity(user = "mgrfi8", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2808"), Claim(key = "tenant_code", value = "FI-TEST8")])
    fun `skipped-location ids let a CYCLE session under the same EOP campaign remediate once the reservation clears`() {
        tenantContext.clientId = 2808
        val annual = campaignService.createCampaign(
            CreateCampaignRequest(name = "FI annual remediation", type = "END_OF_PERIOD"),
            2808,
        )
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-FIR8-$ns")
        val areaId = createArea("AREA-FIR8-$ns")

        val reservedName = "FI8-RESV-$ns"
        val reservedId = createLocation(reservedName, ltId, areaId, orderIndex = 10)
        val reservedStockId = createStock(
            createUnitLoad("UL-FI8-R-$ns", reservedId, reservedName), amount = 20.0, reserved = 5.0,
        )

        val stockedName = "FI8-STOCK-$ns"
        val stockedId = createLocation(stockedName, ltId, areaId, orderIndex = 20)
        createStock(createUnitLoad("UL-FI8-S-$ns", stockedId, stockedName), amount = 8.0)

        val eop = service.startCount(StartCountRequest(type = "END_OF_PERIOD", campaignId = annual.id), 2808)
        assertThat(eop.skippedLocations).containsExactly(reservedName)
        // The new machine-usable parallel list -- previously always empty.
        assertThat(eop.skippedLocationIds).containsExactly(reservedId)

        // The relaxation is one-way only: an END_OF_PERIOD start still refuses a CYCLE campaign
        // (already pinned in this class's own "campaign type gate is one-way..." test; repeated
        // here since this is the exact gate being touched).
        val cycleCampaign = campaignService.createCampaign(CreateCampaignRequest(name = "FI cycle remediation"), 2808)
        assertThatThrownBy {
            service.startCount(StartCountRequest(type = "END_OF_PERIOD", campaignId = cycleCampaign.id), 2808)
        }.isInstanceOf(StocktakingException.InvalidState::class.java)

        // Release the reservation -- the sanctioned remediation precondition (in production this
        // clears naturally as the reserving pick/order resolves).
        stockService.releaseReservation(reservedStockId, BigDecimal.valueOf(5.0), "TEST-RELEASE", tenantContext)

        // The remediating CYCLE session, naming exactly the skipped location, now files under the
        // SAME END_OF_PERIOD campaign -- previously a 409 InvalidState from the campaign-type gate.
        val remediation = service.startCount(
            StartCountRequest(locationIds = listOf(reservedId), campaignId = annual.id),
            2808,
        )
        assertThat(remediation.type).isEqualTo("CYCLE")
        assertThat(remediation.campaignId).isEqualTo(annual.id)
        assertThat(service.getSession(remediation.id, 2808).orders.map { it.locationId }).containsExactly(reservedId)

        // The campaign rollup now covers both sessions -- the annual inventory can be completed.
        assertThat(campaignService.getCampaign(annual.id, 2808).sessions).isEqualTo(2L)
    }

    /** REST leg: the type selector travels over the wire and an unknown type maps to 422. */
    @Test
    @TestSecurity(user = "mgrfi5", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2805"), Claim(key = "tenant_code", value = "FI-TEST5")])
    fun `POST count-sessions accepts type END_OF_PERIOD and 422s an unknown type`() {
        tenantContext.clientId = 2805
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-FIR-$ns")
        val areaId = createArea("AREA-FIR-$ns")
        val locName = "FIR-$ns"
        createLocation(locName, ltId, areaId, orderIndex = 10)

        given().contentType(ContentType.JSON)
            .body("""{"type":"END_OF_PERIOD"}""")
            .`when`().post("/api/v1/count-sessions")
            .then().statusCode(201)
            .body("type", org.hamcrest.Matchers.equalTo("END_OF_PERIOD"))

        given().contentType(ContentType.JSON)
            .body("""{"type":"MONTHLY"}""")
            .`when`().post("/api/v1/count-sessions")
            .then().statusCode(422)
    }

    /**
     * Row 16 (defect-burndown-4, Task 11): a location an inbound putaway has already
     * soft-claimed via a live [LocationReservation] must not be COUNTED by an END_OF_PERIOD
     * start -- the same skip-and-report treatment the headline test above pins for an existing
     * lock. Work already in flight toward the location invalidates a blind count.
     */
    @Test
    @TestSecurity(user = "mgrfi9", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2809"), Claim(key = "tenant_code", value = "FI-TEST9")])
    fun `END_OF_PERIOD start skips a location with a live inbound reservation`() {
        tenantContext.clientId = 2809
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-FIRV-$ns")
        val areaId = createArea("AREA-FIRV-$ns")

        // Empty and otherwise perfectly countable -- the ONLY thing standing between it and a
        // generated order is the live reservation below.
        val reservedName = "FIRV-RESV-$ns"
        val reservedId = createLocation(reservedName, ltId, areaId, orderIndex = 10)
        reserveLocation(reservedId, transportOrderId = 999_000_000L + reservedId)

        val stockedName = "FIRV-STOCK-$ns"
        val stockedId = createLocation(stockedName, ltId, areaId, orderIndex = 20)
        createStock(createUnitLoad("UL-FIRV-S-$ns", stockedId, stockedName), amount = 12.0)

        val view = service.startCount(StartCountRequest(type = "END_OF_PERIOD"), 2809)
        val graph = service.getSession(view.id, 2809)

        // Only the stocked location got an order -- the reserved one was skipped, not counted.
        assertThat(graph.orders.map { it.locationId }).containsExactly(stockedId)
        assertThat(view.skippedLocations).containsExactly(reservedName)
        assertThat(view.skippedLocationIds).containsExactly(reservedId)

        // Skipped, not locked -- the reservation is a soft claim, not a STOCKTAKING freeze.
        assertThat(locationLockOf(reservedId)).isEqualTo(com.karyo.layout.vo.LockType.UNLOCKED.code)
    }
}
