package com.karyo.layout.service

import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.layout.repository.StorageLocationRepository
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

private const val ACME = 1L
private const val GLOBEX = 2L

/**
 * Integration test (REAL layout + inventory beans, REAL DB) for [LocationService.checkCapacity]:
 * the weight already resting on a location must count against its type's `liftingCapacity`.
 *
 * Regression pin for the live defect. The pre-fix code hard-coded
 * `currentWeight = BigDecimal.ZERO` behind a "v1: no weight cache table" comment, so it compared
 * the incoming load against the cap in isolation and could never refuse a load onto an
 * already-loaded rack - capacity enforcement did not enforce. Every test below that seeds stock
 * would have passed the old code's check.
 *
 * A sibling of [LocationServiceCheckCapacityTest] rather than an addition to it: that file mocks
 * [com.karyo.layout.repository.StorageLocationRepository] to drive each branch exactly, which
 * rules out the real persisted stock this file needs. Same split reasoning as
 * [LocationFinderGroupCapacityTest] vs [LocationFinderTest]; the small REST-seeding helpers are
 * duplicated rather than shared, matching the existing convention in this package.
 */
@QuarkusTest
class LocationServiceCapacityEnforcementTest {

    @Inject
    lateinit var locationService: LocationService

    @Inject
    lateinit var locationRepository: StorageLocationRepository

    // ── REST seeding helpers ─────────────────────────────────────────────

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":["STORAGE"]}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocationType(name: String, liftingCapacity: BigDecimal?): Long {
        val capField = liftingCapacity?.let { ""","liftingCapacity":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$capField}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createLocation(name: String, typeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /**
     * Direct entity persistence of a unit load resting at [locationId] carrying [weight]
     * (nullable - an unweighed load, for the degradation path). Bypasses REST because
     * `CreateUnitLoadRequest` has no `weight` field; mirrors
     * [LocationFinderGroupCapacityTest.seedWeightedStock]. `unitLoadTypeId`/`itemDataId` 1 are
     * the fixture rows every other test in this module already relies on existing.
     */
    @Transactional
    fun seedWeightedStock(
        locationId: Long,
        locationName: String,
        weight: BigDecimal?,
        clientId: Long = ACME,
        state: Int = 300,
    ) {
        val em = locationRepository.getEntityManager()
        val ul = UnitLoad().apply {
            labelId = "UL-CAP-${System.nanoTime()}"
            unitLoadType = em.find(UnitLoadType::class.java, 1L)
            storageLocationId = locationId
            storageLocationName = locationName
            this.clientId = clientId
            this.weight = weight
        }
        em.persist(ul)
        em.persist(
            StockUnit().apply {
                this.clientId = clientId
                itemDataId = 1L
                itemDataNumber = "CAP-SKU"
                amount = BigDecimal.ONE
                unitLoad = ul
                this.state = state
            },
        )
    }

    /** A location of a type capped at [liftingCapacity], returned as (id, name). */
    private fun givenCappedLocation(prefix: String, liftingCapacity: BigDecimal?): Pair<Long, String> {
        val s = System.nanoTime()
        val area = createArea("$prefix-AR-$s")
        val type = createLocationType("$prefix-LT-$s", liftingCapacity)
        val name = "$prefix-LOC-$s"
        return createLocation(name, type, area) to name
    }

    // ── the defect ───────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "inventory-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a location loaded near its limit refuses a load that would exceed it`() {
        val (locationId, name) = givenCappedLocation("CAPX", BigDecimal("1000"))
        seedWeightedStock(locationId, name, BigDecimal("900"))

        val result = locationService.checkCapacity(locationId, BigDecimal("200"))

        assertThat(result.currentWeight)
            .`as`("must read the real 900 already on the rack, not the zero the pre-fix code always produced")
            .isEqualByComparingTo(BigDecimal("900"))
        assertThat(result.allowed)
            .`as`("900 + 200 is over the 1000 cap; the pre-fix code allowed it because 200 alone fits")
            .isFalse()
        assertThat(result.reason).contains("exceeds lifting capacity")
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "inventory-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `the same loaded location still accepts a load that fits`() {
        val (locationId, name) = givenCappedLocation("CAPF", BigDecimal("1000"))
        seedWeightedStock(locationId, name, BigDecimal("900"))

        val result = locationService.checkCapacity(locationId, BigDecimal("100"))

        assertThat(result.currentWeight).isEqualByComparingTo(BigDecimal("900"))
        assertThat(result.allowed)
            .`as`("900 + 100 fills the 1000 cap exactly; the limit is inclusive")
            .isTrue()
        assertThat(result.reason).isNull()
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "inventory-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `weight accumulates across every unit load on the location`() {
        val (locationId, name) = givenCappedLocation("CAPA", BigDecimal("1000"))
        seedWeightedStock(locationId, name, BigDecimal("400"))
        seedWeightedStock(locationId, name, BigDecimal("400"))

        val result = locationService.checkCapacity(locationId, BigDecimal("300"))

        assertThat(result.currentWeight).isEqualByComparingTo(BigDecimal("800"))
        assertThat(result.allowed).isFalse()
    }

    /**
     * The unscoped-read ruling on [com.karyo.inventory.api.spi.StockUnitLookup.
     * grossWeightByLocationIds], asserted from this side of the seam: a foreign owner's pallet
     * presses on the same shelf, so it must count. Scoping the read to the caller would
     * under-count real weight and green-light a physical overload.
     */
    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "inventory-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `another goods owner's pallet on the same shelf counts against the cap`() {
        val (locationId, name) = givenCappedLocation("CAPT", BigDecimal("1000"))
        seedWeightedStock(locationId, name, BigDecimal("900"), clientId = GLOBEX)

        val result = locationService.checkCapacity(locationId, BigDecimal("200"))

        assertThat(result.currentWeight).isEqualByComparingTo(BigDecimal("900"))
        assertThat(result.allowed).isFalse()
    }

    /** Stock that has left the rack (SHIPPED(680)) must not keep occupying its cap. */
    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "inventory-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `shipped stock no longer counts against the cap`() {
        val (locationId, name) = givenCappedLocation("CAPS", BigDecimal("1000"))
        seedWeightedStock(locationId, name, BigDecimal("900"), state = 680)

        val result = locationService.checkCapacity(locationId, BigDecimal("200"))

        assertThat(result.currentWeight).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.allowed).isTrue()
    }

    /** Honest degradation: an unweighed load contributes zero rather than blocking the check. */
    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "inventory-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an unweighed unit load contributes zero rather than refusing the load`() {
        val (locationId, name) = givenCappedLocation("CAPU", BigDecimal("1000"))
        seedWeightedStock(locationId, name, weight = null)

        val result = locationService.checkCapacity(locationId, BigDecimal("200"))

        assertThat(result.currentWeight).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.allowed).isTrue()
    }

    /** An uncapped type stays unlimited, but must still report the real occupied weight. */
    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "inventory-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an uncapped location type allows anything and still reports the weight on it`() {
        val (locationId, name) = givenCappedLocation("CAPN", liftingCapacity = null)
        seedWeightedStock(locationId, name, BigDecimal("900"))

        val result = locationService.checkCapacity(locationId, BigDecimal("5000"))

        assertThat(result.allowed).isTrue()
        assertThat(result.liftingCapacity).isNull()
        assertThat(result.currentWeight).isEqualByComparingTo(BigDecimal("900"))
    }
}
