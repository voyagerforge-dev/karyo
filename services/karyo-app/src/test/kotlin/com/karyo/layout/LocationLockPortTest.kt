package com.karyo.layout

import com.karyo.layout.spi.LocationLockPort
import com.karyo.layout.vo.LockType
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.hamcrest.CoreMatchers.nullValue
import java.time.Instant

@QuarkusTest
class LocationLockPortTest {

    @Inject
    lateinit var port: LocationLockPort

    // ── Seeding helpers ────────────────────────────────────────────────────

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

    private fun currentLockType(locationId: Long): Int =
        given()
            .`when`().get("/api/v1/locations/$locationId").then().statusCode(200)
            .extract().jsonPath().getInt("lockType")

    private fun currentLastCountedAt(locationId: Long): String? =
        given()
            .`when`().get("/api/v1/locations/$locationId").then().statusCode(200)
            .extract().jsonPath().getString("lastCountedAt")

    data class SeedCtx(val areaId: Long, val locationId: Long, val locationName: String)

    private fun seedAreaWithLocation(ns: Long = System.nanoTime()): SeedCtx {
        val ltId = createLocationType("LT-LOCK-$ns")
        val areaId = createArea("AREA-LOCK-$ns")
        val locName = "LOC-LOCK-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        return SeedCtx(areaId, locationId, locName)
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "5501"), Claim(key = "tenant_code", value = "LOCK-TEST")])
    fun `lockForCount sets STOCKTAKING, releaseCount clears it, expandArea returns locations`() {
        val ctx = seedAreaWithLocation()

        // lockForCount → STOCKTAKING (code 7)
        port.lockForCount(ctx.locationId, 5501)
        assertThat(currentLockType(ctx.locationId)).isEqualTo(LockType.STOCKTAKING.code)

        // releaseCount → UNLOCKED (code 0)
        port.releaseCount(ctx.locationId, 5501)
        assertThat(currentLockType(ctx.locationId)).isEqualTo(LockType.UNLOCKED.code)

        // expandAreaToLocations returns the seeded location
        assertThat(port.expandAreaToLocations(ctx.areaId, 5501)).contains(ctx.locationId)
    }

    @Test
    @TestSecurity(user = "mgr2", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "5502"), Claim(key = "tenant_code", value = "LOCK-TEST2")])
    fun `locationName returns the name for the owning client`() {
        val ctx = seedAreaWithLocation()

        val name = port.locationName(ctx.locationId, 5502)
        assertThat(name).isEqualTo(ctx.locationName)
    }

    @Test
    @TestSecurity(user = "mgr3", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "5503"), Claim(key = "tenant_code", value = "LOCK-TEST3")])
    fun `locationName returns null for a different client`() {
        val ctx = seedAreaWithLocation()

        // client 9999 did not create this location — must get null back
        val name = port.locationName(ctx.locationId, 9999L)
        assertThat(name).isNull()
    }

    // ── markCounted (L3, locations-layout sprint Task 6) ────────────────────

    @Test
    @TestSecurity(user = "mgr4", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "5504"), Claim(key = "tenant_code", value = "LOCK-TEST4")])
    fun `markCounted stamps lastCountedAt for every requested location`() {
        val ctx = seedAreaWithLocation()

        // honest gap before any count: never counted
        given().`when`().get("/api/v1/locations/${ctx.locationId}")
            .then().statusCode(200).body("lastCountedAt", nullValue())

        val stamp = Instant.now()
        port.markCounted(listOf(ctx.locationId), stamp)

        // Postgres TIMESTAMPTZ is microsecond-precision — compare via Instant.parse (tolerant
        // of the nanosecond-tail truncation) rather than exact string equality.
        val persisted = Instant.parse(currentLastCountedAt(ctx.locationId))
        assertThat(persisted).isCloseTo(stamp, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS))
    }

    @Test
    @TestSecurity(user = "mgr5", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "5505"), Claim(key = "tenant_code", value = "LOCK-TEST5")])
    fun `markCounted on an unknown location id is a no-op, not an error`() {
        // unscoped internal write — a bad/missing id must not throw (mirrors updateAllocation's
        // not-found handling), it just logs and skips.
        port.markCounted(listOf(999_999_999L), Instant.now())
    }

    // ── orderIndexFor (St6, stocktaking-block sprint Task 8) ─────────────────

    @Test
    @TestSecurity(user = "mgr6", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "5506"), Claim(key = "tenant_code", value = "LOCK-TEST6")])
    fun `orderIndexFor returns a batched id-to-orderIndex map, omitting ids it cannot resolve`() {
        val ns = System.nanoTime()
        val ltId = createLocationType("LT-OIF-$ns")
        val areaId = createArea("AREA-OIF-$ns")
        val locationId = createLocation("LOC-OIF-$ns", ltId, areaId, orderIndex = 42)
        val unknownId = 999_999_999L

        val result = port.orderIndexFor(listOf(locationId, unknownId))

        assertThat(result).containsEntry(locationId, 42)
        assertThat(result).doesNotContainKey(unknownId)
    }

    @Test
    @TestSecurity(user = "mgr7", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "5507"), Claim(key = "tenant_code", value = "LOCK-TEST7")])
    fun `orderIndexFor on an empty input returns an empty map`() {
        assertThat(port.orderIndexFor(emptyList())).isEmpty()
    }
}
