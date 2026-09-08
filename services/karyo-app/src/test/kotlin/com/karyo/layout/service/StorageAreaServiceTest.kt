package com.karyo.layout.service

import com.karyo.layout.exception.LayoutException
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.groups.Tuple.tuple
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test

/**
 * Real round-trip coverage (no mocks) for:
 *  - the ordered strategy-areas rewrite semantics (`StorageStrategyService.setAreas`/`getAreas`)
 *  - `StorageAreaService.clustersForAreas` (produced interface for Tasks 3/5)
 *  - `StorageAreaService.delete`'s dependent-guard when an area is still assigned to a strategy
 *
 * Seeding uses the real REST endpoints (mirrors `LocationFinderStrategyOwnershipTest`); the
 * behavior under test is invoked directly against the injected services.
 */
@QuarkusTest
class StorageAreaServiceTest {

    @Inject
    lateinit var storageStrategyService: StorageStrategyService

    @Inject
    lateinit var storageAreaService: StorageAreaService

    private val clientId = 1L

    private fun ns() = System.nanoTime()

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/storage-areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createCluster(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-clusters").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStrategy(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/storage-strategies").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"SSA Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    // ── 1. PUT ordered list assigns orderIndex 1..N in input order ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `setAreas assigns orderIndex 1 through N in input order`() {
        val s = ns()
        val strategyId = createStrategy("SSA-STRAT-$s")
        val a1 = createArea("SSA-A1-$s")
        val a2 = createArea("SSA-A2-$s")
        val a3 = createArea("SSA-A3-$s")

        val result = storageStrategyService.setAreas(strategyId, listOf(a1, a2, a3), clientId)

        assertThat(result).extracting("id", "orderIndex")
            .containsExactly(tuple(a1, 1), tuple(a2, 2), tuple(a3, 3))

        // getAreas reflects the same order
        val fetched = storageStrategyService.getAreas(strategyId, clientId)
        assertThat(fetched.map { it.id }).containsExactly(a1, a2, a3)
    }

    // ── 2. Re-PUT with a reordered list rewrites indexes ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `re-PUT with reordered list rewrites indexes`() {
        val s = ns()
        val strategyId = createStrategy("SSA-REORD-STRAT-$s")
        val a1 = createArea("SSA-REORD-A1-$s")
        val a2 = createArea("SSA-REORD-A2-$s")

        storageStrategyService.setAreas(strategyId, listOf(a1, a2), clientId)
        val reordered = storageStrategyService.setAreas(strategyId, listOf(a2, a1), clientId)

        assertThat(reordered.map { it.id }).containsExactly(a2, a1)
        assertThat(reordered.first { it.id == a2 }.orderIndex).isEqualTo(1)
        assertThat(reordered.first { it.id == a1 }.orderIndex).isEqualTo(2)
    }

    // ── 3. PUT with unknown area id -> error, no partial write ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `setAreas with unknown area id throws and leaves the previous list untouched`() {
        val s = ns()
        val strategyId = createStrategy("SSA-BAD-STRAT-$s")
        val a1 = createArea("SSA-BAD-A1-$s")
        val unknownAreaId = ns()

        storageStrategyService.setAreas(strategyId, listOf(a1), clientId)

        assertThatThrownBy { storageStrategyService.setAreas(strategyId, listOf(a1, unknownAreaId), clientId) }
            .isInstanceOf(LayoutException.InvalidReferenceList::class.java)

        // No partial write: the original single-area list is still exactly as it was
        val stillThere = storageStrategyService.getAreas(strategyId, clientId)
        assertThat(stillThere.map { it.id }).containsExactly(a1)
    }

    // ── 4. clustersForAreas returns a batched map; areas with no clusters are absent ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `clustersForAreas returns a batched map keyed by area id`() {
        val s = ns()
        val cl1 = createCluster("SSA-CLU1-$s")
        val cl2 = createCluster("SSA-CLU2-$s")
        val areaWithClusters = given().contentType(ContentType.JSON)
            .body("""{"name":"SSA-WITHCLU-$s","clusterIds":[$cl1,$cl2]}""")
            .`when`().post("/api/v1/storage-areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")
        val areaWithoutClusters = createArea("SSA-NOCLU-$s")

        val result = storageAreaService.clustersForAreas(listOf(areaWithClusters, areaWithoutClusters))

        assertThat(result[areaWithClusters]).containsExactlyInAnyOrder(cl1, cl2)
        assertThat(result).doesNotContainKey(areaWithoutClusters)
    }

    // ── 5. delete blocked by HasDependents when the area is assigned to a strategy ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delete is blocked when the area is still assigned to a strategy`() {
        val s = ns()
        val strategyId = createStrategy("SSA-DEP-STRAT-$s")
        val areaId = createArea("SSA-DEP-A-$s")
        storageStrategyService.setAreas(strategyId, listOf(areaId), clientId)

        assertThatThrownBy { storageAreaService.delete(areaId) }
            .isInstanceOf(LayoutException.HasDependents::class.java)
    }

    // ── 6. HTTP-level round trip for PUT/GET .../areas (fix round 1, Minor #1) ──────────
    //
    // Tests 1-3 above call setAreas/getAreas directly via @Inject, never through JAX-RS.
    // This proves the bare `List<Long>` request body actually deserializes correctly over
    // real HTTP and that the ordered response shape is correct on the wire, not just at the
    // service layer.

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `PUT and GET storage-strategies areas round trip over real HTTP`() {
        val s = ns()
        val strategyId = createStrategy("SSA-HTTP-STRAT-$s")
        val a1 = createArea("SSA-HTTP-A1-$s")
        val a2 = createArea("SSA-HTTP-A2-$s")

        given().contentType(ContentType.JSON)
            .body("[$a1,$a2]")
            .`when`().put("/api/v1/storage-strategies/$strategyId/areas")
            .then().statusCode(200)
            .body("[0].id", `is`(a1.toInt()))
            .body("[0].orderIndex", `is`(1))
            .body("[1].id", `is`(a2.toInt()))
            .body("[1].orderIndex", `is`(2))

        given()
            .`when`().get("/api/v1/storage-strategies/$strategyId/areas")
            .then().statusCode(200)
            .body("[0].id", `is`(a1.toInt()))
            .body("[1].id", `is`(a2.toInt()))
    }

    // ── 7. delete blocked by HasDependents when the area is referenced by an ItemDataArea
    //      (fix round 1, Minor #2 — the item-usage branch was previously untested) ────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delete is blocked when the area is referenced by an item data area`() {
        val s = ns()
        // item-unit name is @Size(max=20) -- a full nanoTime suffix overflows it (see
        // FixAssignmentLookupTest's identical bounded-suffix note); keep this one short.
        val shortSuffix = s.toString().takeLast(8)
        val areaId = createArea("SSA-ITEMDEP-A-$s")
        val itemUnitId = createItemUnit("IU-SSA-$shortSuffix")
        val itemDataId = createProduct("SSA-ITEMDEP-SKU-$s", itemUnitId)

        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"storageAreaId":$areaId}""")
            .`when`().post("/api/v1/item-data-areas")
            .then().statusCode(201)

        assertThatThrownBy { storageAreaService.delete(areaId) }
            .isInstanceOf(LayoutException.HasDependents::class.java)
    }
}
