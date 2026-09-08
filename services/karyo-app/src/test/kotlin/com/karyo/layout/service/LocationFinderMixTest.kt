package com.karyo.layout.service

import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.layout.repository.LocationReservationRepository
import com.karyo.layout.spi.LocationFinder
import com.karyo.layout.spi.LocationFinderRequest
import com.karyo.layout.spi.LocationFinderResult
import com.karyo.orders.vo.OrderState
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.vo.TransportType
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test (REAL layout beans + DB) for the location-finder sprint's Task 5 (LF9/LF8, 2026-08-16):
 * [OccupancyMixReader] wired into [LocationFinderService] -- LF9 (real client mixing, filters
 * 14/15) and LF8's `mixItem` pass (filter 19). A sibling of [LocationFinderAreaTest] (not an
 * addition to it), same file-split rationale (keep each file under Detekt's LargeClass budget).
 * Duplicates the small REST/direct-entity seeding helpers rather than sharing them, matching
 * the sprint's established per-file convention.
 *
 * client_id is fixed at 1 for all seeding/finder calls (silo tenancy), same as the siblings.
 */
@QuarkusTest
class LocationFinderMixTest {

    @Inject
    lateinit var locationFinder: LocationFinder

    @Inject
    lateinit var reservationRepository: LocationReservationRepository

    @Inject
    lateinit var transportOrderRepository: TransportOrderRepository

    private val client = 1L

    // ── REST seeding helpers ─────────────────────────────────────────────

    private fun createArea(name: String, usages: List<String>): Long {
        val usagesJson = usages.joinToString(",") { "\"$it\"" }
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":[$usagesJson]}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createZone(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/zones")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocationType(name: String, liftingCapacity: BigDecimal?): Long {
        val cap = liftingCapacity?.let { ""","liftingCapacity":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$cap}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    @Suppress("LongParameterList")
    private fun createLocation(name: String, typeId: Long, areaId: Long, zoneId: Long? = null): Long {
        val zonePart = zoneId?.let { ""","zoneId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId$zonePart}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createStrategy(name: String, body: String = "{}"): Long {
        val extra = if (body == "{}") "" else ",${body.trim('{', '}')}"
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$extra}""")
            .`when`().post("/api/v1/storage-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Direct entity persistence of an ON_STOCK occupant, mirroring
     * [LocationFinderAreaTest.seedStock] / [StockUnitLookupOccupantsTest.seedStock] -- bypasses
     * REST/tenant-write-scope so [occupantClientId] can be a foreign tenant. */
    @Suppress("LongParameterList")
    @Transactional
    fun seedStock(locationId: Long, itemDataId: Long, occupantClientId: Long = client, amount: BigDecimal = BigDecimal.TEN): Long {
        val em = reservationRepository.getEntityManager()
        val ulType = em.find(UnitLoadType::class.java, 1L)
        val ul = UnitLoad().apply {
            labelId = "UL-MIX-${System.nanoTime()}"
            unitLoadType = ulType
            storageLocationId = locationId
            storageLocationName = "MIX-LOC"
            this.clientId = occupantClientId
        }
        em.persist(ul)
        val su = StockUnit().apply {
            this.clientId = occupantClientId
            this.itemDataId = itemDataId
            itemDataNumber = "MIX-SKU"
            this.amount = amount
            unitLoad = ul
            state = 300
        }
        em.persist(su)
        return su.id!!
    }

    /** Direct entity persistence of an in-flight [TransportOrder] targeting [locationId],
     * mirroring [com.karyo.tasks.service.TransportDemandLookupTest]'s fixture idiom. */
    @Suppress("LongParameterList")
    @Transactional
    fun seedTransport(locationId: Long, state: Int, transportClientId: Long, itemDataId: Long? = null) {
        transportOrderRepository.persist(
            TransportOrder().apply {
                this.clientId = transportClientId
                orderNumber = "MIX-TO-${System.nanoTime()}"
                transportType = TransportType.MOVE
                unitLoadId = 1
                unitLoadLabel = "UL-MIX-DEMAND"
                sourceLocationId = 10
                sourceLocationName = "SRC-1"
                destinationLocationId = locationId
                destinationLocationName = "MIX-LOC"
                this.itemDataId = itemDataId
                this.state = state
            }
        )
    }

    /**
     * Row :1614's open-PUTAWAY shape: only [suggestedLocationId] set, [destinationLocationId]
     * left null (stamped only at completion). Returns the persisted order's id so a caller can
     * drive the self-exclusion guard via [LocationFinderRequest.reservationKey].
     */
    @Transactional
    fun seedPutawayTransport(suggestedLocationId: Long, state: Int, transportClientId: Long, itemDataId: Long? = null): Long {
        val order = TransportOrder().apply {
            this.clientId = transportClientId
            orderNumber = "MIX-PTO-${System.nanoTime()}"
            transportType = TransportType.PUTAWAY
            unitLoadId = 1
            unitLoadLabel = "UL-MIX-DEMAND"
            sourceLocationId = 10
            sourceLocationName = "SRC-1"
            this.suggestedLocationId = suggestedLocationId
            suggestedLocationName = "MIX-LOC"
            this.itemDataId = itemDataId
            this.state = state
        }
        transportOrderRepository.persist(order)
        return order.id!!
    }

    @Suppress("LongParameterList")
    private fun request(
        reservationKey: Long,
        clientId: Long? = 1L,
        preferredZoneId: Long? = null,
        storageStrategyId: Long? = null,
        itemDataId: Long? = null,
    ) = LocationFinderRequest(
        unitLoadId = 9000L + reservationKey,
        unitLoadTypeId = 1L,
        weight = BigDecimal.ZERO,
        clientId = clientId,
        reservationKey = reservationKey,
        preferredZoneId = preferredZoneId,
        storageStrategyId = storageStrategyId,
        itemDataId = itemDataId,
    )

    private fun suffix() = System.nanoTime()

    // ── LF9: real occupancy client mixing (filter 14) ───────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a shared location holding another client's stock is excluded when mixClient is false`() {
        val s = suffix()
        val storage = createArea("MC1-ST-$s", listOf("STORAGE"))
        val zone = createZone("MC1-Z-$s")
        val type = createLocationType("MC1-LT-$s", BigDecimal("1000"))

        val foreignHeld = createLocation("MC1-FOREIGN-$s", type, storage, zoneId = zone)
        val own = createLocation("MC1-OWN-$s", type, storage, zoneId = zone)
        seedStock(foreignHeld, itemDataId = 1_000_000L + s, occupantClientId = 999L)

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(own)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a shared location holding only own stock stays a candidate`() {
        val s = suffix()
        val storage = createArea("MC2-ST-$s", listOf("STORAGE"))
        val zone = createZone("MC2-Z-$s")
        val type = createLocationType("MC2-LT-$s", BigDecimal("1000"))

        val loc = createLocation("MC2-OWN-$s", type, storage, zoneId = zone)
        seedStock(loc, itemDataId = 1_100_000L + s, occupantClientId = client)

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(loc)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "task-read", "task-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an open transport of another client targeting the location excludes it`() {
        val s = suffix()
        val storage = createArea("MC3-ST-$s", listOf("STORAGE"))
        val zone = createZone("MC3-Z-$s")
        val type = createLocationType("MC3-LT-$s", BigDecimal("1000"))

        val demanded = createLocation("MC3-DEMANDED-$s", type, storage, zoneId = zone)
        val own = createLocation("MC3-OWN-$s", type, storage, zoneId = zone)
        seedTransport(demanded, state = OrderState.RELEASED.code, transportClientId = 999L)

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(own)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "task-read", "task-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a FINISHED transport of another client does not exclude`() {
        val s = suffix()
        val storage = createArea("MC4-ST-$s", listOf("STORAGE"))
        val zone = createZone("MC4-Z-$s")
        val type = createLocationType("MC4-LT-$s", BigDecimal("1000"))

        val loc = createLocation("MC4-LOC-$s", type, storage, zoneId = zone)
        seedTransport(loc, state = OrderState.FINISHED.code, transportClientId = 999L)

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(loc)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `mixClient true admits cross-client occupancy - opt-in preserved`() {
        val s = suffix()
        val storage = createArea("MC5-ST-$s", listOf("STORAGE"))
        val zone = createZone("MC5-Z-$s")
        val type = createLocationType("MC5-LT-$s", BigDecimal("1000"))

        val loc = createLocation("MC5-LOC-$s", type, storage, zoneId = zone)
        seedStock(loc, itemDataId = 1_500_000L + s, occupantClientId = 999L)

        val strategyId = createStrategy("MC5-STRAT-$s", """{"mixClient":true}""")
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(loc)
    }

    // ── LF8: item mixing (filter 19) ─────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `mixItem false excludes a location holding a different item`() {
        val s = suffix()
        val storage = createArea("MI1-ST-$s", listOf("STORAGE"))
        val zone = createZone("MI1-Z-$s")
        val type = createLocationType("MI1-LT-$s", BigDecimal("1000"))

        val incomingItemDataId = 2_000_000L + s
        val otherItemDataId = 2_100_000L + s
        // Named so the pre-Task-5 default (allocation ASC, name ASC) ordering would pick the
        // wrong-item location FIRST absent any item-mixing exclusion -- a true RED fixture.
        val wrongItem = createLocation("MI1-A-WRONG-$s", type, storage, zoneId = zone)
        val correctEmpty = createLocation("MI1-B-EMPTY-$s", type, storage, zoneId = zone)
        seedStock(wrongItem, itemDataId = otherItemDataId, occupantClientId = client)

        val strategyId = createStrategy("MI1-STRAT-$s", """{"mixItem":false}""")
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId, itemDataId = incomingItemDataId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(correctEmpty)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `mixItem false admits same-item occupancy and defaults stay inert - regression pin`() {
        // (a) mixItem = false, but the occupant is the SAME item as the incoming stock --
        // not a mismatch, so it must not be excluded.
        val s1 = suffix()
        val storage1 = createArea("MI2A-ST-$s1", listOf("STORAGE"))
        val zone1 = createZone("MI2A-Z-$s1")
        val type1 = createLocationType("MI2A-LT-$s1", BigDecimal("1000"))
        val itemDataId1 = 2_200_000L + s1
        val loc1 = createLocation("MI2A-LOC-$s1", type1, storage1, zoneId = zone1)
        seedStock(loc1, itemDataId = itemDataId1, occupantClientId = client)

        val strategyId1 = createStrategy("MI2A-STRAT-$s1", """{"mixItem":false}""")
        val result1 = locationFinder.findPutawayLocation(
            request(reservationKey = s1, preferredZoneId = zone1, storageStrategyId = strategyId1, itemDataId = itemDataId1)
        )
        assertThat(result1).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result1 as LocationFinderResult.Found).locationId).isEqualTo(loc1)

        // (b) no strategy at all -- mixItem defaults true -- a DIFFERENT item occupant must
        // NOT be excluded (the item-mixing pass stays inert by default, regression pin).
        val s2 = suffix()
        val storage2 = createArea("MI2B-ST-$s2", listOf("STORAGE"))
        val zone2 = createZone("MI2B-Z-$s2")
        val type2 = createLocationType("MI2B-LT-$s2", BigDecimal("1000"))
        val itemDataId2 = 2_300_000L + s2
        val otherItemDataId2 = 2_400_000L + s2
        val loc2 = createLocation("MI2B-LOC-$s2", type2, storage2, zoneId = zone2)
        seedStock(loc2, itemDataId = otherItemDataId2, occupantClientId = client)

        val result2 = locationFinder.findPutawayLocation(
            request(reservationKey = s2, preferredZoneId = zone2, itemDataId = itemDataId2)
        )
        assertThat(result2).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result2 as LocationFinderResult.Found).locationId).isEqualTo(loc2)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "task-read", "task-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an open transport carrying a different item excludes when mixItem is false`() {
        val s = suffix()
        val storage = createArea("MI3-ST-$s", listOf("STORAGE"))
        val zone = createZone("MI3-Z-$s")
        val type = createLocationType("MI3-LT-$s", BigDecimal("1000"))

        val incomingItemDataId = 2_500_000L + s
        val otherItemDataId = 2_600_000L + s
        val demanded = createLocation("MI3-DEMANDED-$s", type, storage, zoneId = zone)
        val correctEmpty = createLocation("MI3-EMPTY-$s", type, storage, zoneId = zone)
        seedTransport(demanded, state = OrderState.RELEASED.code, transportClientId = client, itemDataId = otherItemDataId)

        val strategyId = createStrategy("MI3-STRAT-$s", """{"mixItem":false}""")
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId, itemDataId = incomingItemDataId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(correctEmpty)
    }

    // ── Row :1614: open PUTAWAY/TRANSFER demand + self-exclusion guard ───

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "task-read", "task-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an open PUTAWAY of another client with a suggested target excludes that location`() {
        val s = suffix()
        val storage = createArea("PT1-ST-$s", listOf("STORAGE"))
        val zone = createZone("PT1-Z-$s")
        val type = createLocationType("PT1-LT-$s", BigDecimal("1000"))

        val demanded = createLocation("PT1-DEMANDED-$s", type, storage, zoneId = zone)
        val own = createLocation("PT1-OWN-$s", type, storage, zoneId = zone)
        seedPutawayTransport(demanded, state = OrderState.RELEASED.code, transportClientId = 999L)

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(own)
    }

    /**
     * The handoff's landmine, made concrete: before this task the query only matched
     * [TransportOrder.destinationLocationId], which open PUTAWAY/TRANSFER orders never carry
     * (null until completion) -- so the predicate and "does this order's own row appear as
     * demand" were mutually exclusive by accident, and no self-exclusion guard was needed. This
     * task's coalesced predicate removes that accident: an open PUTAWAY's OWN suggested target
     * now surfaces as demand, so [TaskService.reResolveSuggestion] re-running the finder for
     * that SAME order (passing its own id as [LocationFinderRequest.reservationKey]) must not
     * see its own prior suggestion as someone else's in-flight demand and self-exclude the very
     * location it is trying to re-resolve. A deliberate client mismatch (foreign
     * transportClientId) is used throughout -- otherwise the demand row would never have
     * blocked anything in the first place, and the guard's effect would be untestable.
     *
     * SAME location, SAME single seeded demand row, for both halves -- only the caller's
     * reservationKey differs, isolating exactly the variable the guard branches on. CONTROL
     * runs FIRST, deliberately: [locationFinder.findPutawayLocation] persists a
     * [com.karyo.layout.domain.model.LocationReservation] on a `Found` result, and a single
     * reservation's `percent` (`ALLOCATION_PER_UL = 100`) alone saturates
     * `ALLOCATION_FULL (100)` -- so if WITNESS ran first and reserved the location, a later
     * CONTROL call against the same location would return `NoLocation` for the wrong reason
     * (capacity-full, filter 3, before mixing ever runs) rather than because the guard
     * correctly did NOT fire. Running CONTROL first (no reservation exists yet) keeps its
     * `NoLocation` attributable to the client-mixing exclusion this test targets.
     */
    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "task-read", "task-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `re-resolving an order does not self-exclude its own suggested target - witness and control`() {
        val s = suffix()
        val storage = createArea("SX1-ST-$s", listOf("STORAGE"))
        val zone = createZone("SX1-Z-$s")
        val type = createLocationType("SX1-LT-$s", BigDecimal("1000"))
        val loc = createLocation("SX1-LOC-$s", type, storage, zoneId = zone)
        val orderId = seedPutawayTransport(loc, state = OrderState.RELEASED.code, transportClientId = 999L)

        // CONTROL: reservationKey does NOT match the seeded order's id -- from that caller's
        // perspective this is a DIFFERENT order's demand, so the guard must not fire and the
        // foreign-client mismatch excludes the only candidate.
        val controlKey = suffix()
        val control = locationFinder.findPutawayLocation(request(reservationKey = controlKey, preferredZoneId = zone))
        assertThat(control).isInstanceOf(LocationFinderResult.NoLocation::class.java)

        // WITNESS: same location, same demand row -- reservationKey now equals the demand
        // row's OWN transportOrderId. The guard must skip that row entirely, so the same
        // foreign-client mismatch never gets a chance to block the location.
        val witness = locationFinder.findPutawayLocation(request(reservationKey = orderId, preferredZoneId = zone))
        assertThat(witness).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((witness as LocationFinderResult.Found).locationId).isEqualTo(loc)
    }
}
