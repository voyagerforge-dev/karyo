package com.karyo.layout.service

import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.domain.model.StorageStrategy
import com.karyo.layout.domain.model.Zone
import com.karyo.layout.repository.StorageStrategyRepository
import com.karyo.layout.spi.LocationFinder
import com.karyo.layout.spi.LocationFinderRequest
import com.karyo.layout.spi.LocationFinderResult
import com.karyo.security.TenantContext
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
 * Regression coverage for `LocationFinderService.resolveStrategy` — the ownership check
 * that stops a `LocationFinderRequest.storageStrategyId` naming a *foreign* client's
 * [StorageStrategy] from being trusted (see the fix's KDoc on `resolveStrategy`).
 *
 * **Finding from investigation (see report):** filter 6 (the `findPutawayCandidates` SQL
 * predicate `l.clientId = 0 or l.clientId = :clientId`) already excludes a foreign
 * client's dedicated location *unconditionally*, before filter 8 (`clientMixingAllowed`,
 * driven by `strategy.mixClient`) ever runs. So a foreign strategy's `mixClient = true`
 * can never surface another client's dedicated location in the current query shape — the
 * exploitable, `resolveStrategy`-dependent surface is the **zone** the strategy
 * contributes via `resolveZoneId`, not client mixing. The tests below cover both: the
 * zone test genuinely depends on the fix (proven to fail on the pre-fix code below), and
 * the client-mixing tests document — and lock in — that filter 6 makes the mixing flag a
 * no-op for this exact "foreign dedicated location" shape, so nobody re-introduces the
 * assumption that `resolveStrategy` alone gates it.
 *
 * client_id is fixed at 1 ("client A") for the JWT claim on every test; foreign
 * strategies/locations use a per-test unique "client B" id (`System.nanoTime()`-derived)
 * that no other test in the suite uses, so an unconstrained (zoneless) query can't pick
 * up unrelated locations from the shared test DB.
 */
@QuarkusTest
class LocationFinderStrategyOwnershipTest {

    @Inject
    lateinit var locationFinder: LocationFinder

    @Inject
    lateinit var strategyRepository: StorageStrategyRepository

    @Inject
    lateinit var tenantContext: TenantContext

    private val clientA = 1L

    // ── REST seeding helpers (mirrors LocationFinderTest) ──────────────────

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

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** clientA product (REST-created, so `tenantContext.clientId` stamps it) whose
     * `defaultStorageStrategyId` points at [defaultStrategyId] — which may belong to a
     * foreign client (seeded directly, see [createForeignStrategy]). */
    private fun createProductWithDefaultStrategy(number: String, itemUnitId: Long, defaultStrategyId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"number":"$number","name":"Resolver Test Product","itemUnitId":$itemUnitId,""" +
                    """"defaultStorageStrategyId":$defaultStrategyId}"""
            )
            .`when`().post("/api/v1/products")
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

    /** Own-client (clientA) strategy via the real REST endpoint — tenantContext.clientId == clientA. */
    private fun createOwnStrategy(name: String, mixClient: Boolean, zoneId: Long? = null): Long {
        val zonePart = zoneId?.let { ""","zoneId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","mixClient":$mixClient$zonePart}""")
            .`when`().post("/api/v1/storage-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /**
     * A strategy owned by a *foreign* client, created by direct entity manipulation — the
     * REST endpoint always stamps `tenantContext.clientId` (clientA), so a genuinely
     * foreign-owned row can only be seeded this way. Mirrors LocationFinderTest's
     * `setLocationClient` direct-entity pattern.
     */
    @Transactional
    fun createForeignStrategy(name: String, ownerClientId: Long, mixClient: Boolean, zoneId: Long? = null): Long {
        val em = strategyRepository.getEntityManager()
        val entity = StorageStrategy().apply {
            this.name = name
            this.mixClient = mixClient
            this.clientId = ownerClientId
            this.zone = zoneId?.let { em.find(Zone::class.java, it) }
        }
        strategyRepository.persist(entity)
        return entity.id!!
    }

    /** Directly set the goods owner on a location — simulates a client-dedicated bin. */
    @Transactional
    fun setLocationClient(id: Long, ownerClientId: Long) {
        val loc = strategyRepository.getEntityManager().find(StorageLocation::class.java, id)
        loc.clientId = ownerClientId
    }

    private fun request(
        reservationKey: Long,
        clientId: Long? = clientA,
        storageStrategyId: Long? = null,
        preferredZoneId: Long? = null,
        itemDataId: Long? = null,
    ) = LocationFinderRequest(
        unitLoadId = 9000L + reservationKey,
        unitLoadTypeId = 1L,
        weight = BigDecimal.ZERO,
        clientId = clientId,
        reservationKey = reservationKey,
        storageStrategyId = storageStrategyId,
        preferredZoneId = preferredZoneId,
        itemDataId = itemDataId,
    )

    private fun suffix() = System.nanoTime()

    // ── Zone dimension: the demonstrably fix-dependent path ────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `client A naming client B's strategy does not inherit B's zone constraint`() {
        val s = suffix()
        val clientB = s // per-test unique "other client" id, distinct from clientA (1)

        val storage = createArea("ZFRN-ST-$s", listOf("STORAGE"))
        val type = createLocationType("ZFRN-LT-$s", BigDecimal("1000"))
        val zoneB = createZone("ZFRN-ZB-$s") // clientB's strategy zone — deliberately left EMPTY
        val zoneA = createZone("ZFRN-ZA-$s") // where clientA's real candidate lives

        // clientA's own candidate, owned by clientA, unlocked/empty, in zoneA.
        val ownCandidate = createLocation("ZFRN-LOC-$s", type, storage, zoneId = zoneA)
        setLocationClient(ownCandidate, clientA)

        // A strategy owned by clientB (foreign to this request) pointing at zoneB, which
        // has NO locations at all.
        val foreignStrategy = createForeignStrategy("ZFRN-STRAT-$s", ownerClientId = clientB, mixClient = false, zoneId = zoneB)

        // Request as clientA, naming clientB's strategy.
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, clientId = clientA, storageStrategyId = foreignStrategy)
        )

        // Pre-fix: resolveStrategy trusts the foreign strategy unchecked -> resolveZoneId
        // returns zoneB -> the search is pinned to zoneB, which has no candidates at all
        // -> NoLocation, even though clientA's own zoneA candidate is sitting right there.
        // Post-fix: resolveStrategy rejects the foreign strategy -> no zone constraint ->
        // the search is unconstrained by zone and finds SOME clientA-eligible candidate.
        //
        // Deliberately NOT pinned to `ownCandidate`'s exact id (Task 3, locations-layout
        // sprint): this scenario passes NO preferredZoneId on purpose (that is the whole
        // point -- the only zone source is the foreign strategy, which must be rejected), so
        // the resulting search is genuinely unconstrained across the ENTIRE shared test DB,
        // not just this test's own fixtures. Every REST-created location in this suite is
        // clientId=1 by default (the fixed test principal), so any other test's STORAGE-area,
        // zero-allocation location is an equally legitimate winner here -- asserting a specific
        // id would make this test's pass/fail depend on which OTHER test classes happened to
        // run first. The meaningful, fix-dependent invariant is "found something" (proving
        // zoneB's forced emptiness did NOT propagate into a NoLocation), not which specific
        // location won.
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
    }

    // ── Client-mixing dimension: written per spec, documents the filter-6 finding ──────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `client A naming client B's mixClient strategy still cannot reach B's dedicated location`() {
        val s = suffix()
        val clientB = s

        val storage = createArea("MIXF-ST-$s", listOf("STORAGE"))
        val zone = createZone("MIXF-Z-$s")
        val type = createLocationType("MIXF-LT-$s", BigDecimal("1000"))

        // The ONLY location in this zone is dedicated to clientB (non-zero, per the
        // "clientId = 0 is shared" caveat).
        val bOwned = createLocation("MIXF-LOC-$s", type, storage, zoneId = zone)
        setLocationClient(bOwned, clientB)

        // clientB's own strategy, mixClient = true — the flag that, if trusted for a
        // foreign request, would (per the fix's KDoc) disable client segregation.
        val foreignStrategy = createForeignStrategy("MIXF-STRAT-$s", ownerClientId = clientB, mixClient = true, zoneId = null)

        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, clientId = clientA, storageStrategyId = foreignStrategy, preferredZoneId = zone)
        )

        // Excluded either way (filter 6's SQL predicate already forecloses this — see the
        // class KDoc). Asserted here so the invariant is locked in by a test, not just by
        // reading the query.
        assertThat(result).isInstanceOf(LocationFinderResult.NoLocation::class.java)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `client A naming its own mixClient strategy is not blocked by the ownership check`() {
        val s = suffix()

        val storage = createArea("MIXO-ST-$s", listOf("STORAGE"))
        val zone = createZone("MIXO-Z-$s")
        val type = createLocationType("MIXO-LT-$s", BigDecimal("1000"))

        val ownLoc = createLocation("MIXO-LOC-$s", type, storage, zoneId = zone)
        setLocationClient(ownLoc, clientA)

        // clientA's OWN strategy (created via the real REST endpoint, so tenantContext
        // stamps clientId = clientA) with mixClient = true.
        val ownStrategy = createOwnStrategy("MIXO-STRAT-$s", mixClient = true, zoneId = null)

        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, clientId = clientA, storageStrategyId = ownStrategy, preferredZoneId = zone)
        )

        // Same-client strategy must still be usable — resolveStrategy must not reject a
        // request naming its OWN strategy.
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(ownLoc)
    }

    // ── Closing round: the ownership fail-close must apply to a FALLBACK-resolved
    // (ItemData.defaultStorageStrategyId) id exactly as to an explicit one ────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `client A's product defaulting to client B's strategy does not inherit B's zone constraint`() {
        val s = suffix()
        val clientB = s

        val storage = createArea("DFRN-ST-$s", listOf("STORAGE"))
        val type = createLocationType("DFRN-LT-$s", BigDecimal("1000"))
        val zoneB = createZone("DFRN-ZB-$s") // clientB's strategy zone — deliberately left EMPTY
        val zoneA = createZone("DFRN-ZA-$s") // where clientA's real candidate lives

        val ownCandidate = createLocation("DFRN-LOC-$s", type, storage, zoneId = zoneA)
        setLocationClient(ownCandidate, clientA)

        // A strategy owned by clientB, pointing at zoneB (no locations at all).
        val foreignStrategy = createForeignStrategy("DFRN-STRAT-$s", ownerClientId = clientB, mixClient = false, zoneId = zoneB)

        // clientA's OWN product (REST-created under clientA's JWT) whose
        // defaultStorageStrategyId points at the FOREIGN strategy — e.g. a stale/malicious
        // value, or one left over from a client-reassignment. No storageStrategyId on the
        // request itself: the only path to the foreign strategy is the rung-2 fallback.
        val itemUnit = createItemUnit("DFRN-IU-${s.toString().takeLast(10)}")
        val product = createProductWithDefaultStrategy("DFRN-SKU-$s", itemUnit, foreignStrategy)

        // Final-stamp mutation check: this direct (non-HTTP) finder call runs with a DEFAULT
        // TenantContext (clientId=0, OWNER) — the known @QuarkusTest gotcha — under which the
        // tenant-scoped ProductLookup cannot even see clientA's product, so rung 2 resolved
        // NOTHING and this test passed with the ownership check mutated away (non-load-bearing).
        // Priming the context (same as PutawayFlowTest's F1 tests) makes the fallback actually
        // fire, so a broken ownership check now pins the search to zoneB (empty) -> NoLocation
        // -> this assertion fails. Verified by mutation both ways.
        tenantContext.clientId = clientA
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, clientId = clientA, itemDataId = product)
        )

        // A BROKEN ownership check -- one that trusted the fallback-resolved id without
        // re-checking clientId -- would pin the search to zoneB (which has zero candidates)
        // -> NoLocation. The fix rejects the foreign strategy regardless of which rung
        // produced its id -> no zone constraint -> some clientA-eligible candidate is found.
        // Deliberately NOT pinned to `ownCandidate`'s id -- same shared-test-DB reasoning as
        // the explicit-storageStrategyId zone test above (no preferredZoneId is passed, so
        // the search is genuinely unconstrained across every REST-created (clientId=1)
        // location in the suite, not just this test's own fixtures).
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
    }
}
