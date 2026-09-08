package com.karyo.layout

import com.karyo.layout.spi.ItemDataAreaLookup
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

/**
 * Integration test for [ItemDataAreaLookup] (R12a, replenishment sprint Task 5) — real beans,
 * no mocks. Seeding mirrors [com.karyo.layout.service.StorageAreaServiceTest] (item-unit ->
 * product -> storage-area w/ clusterIds -> item-data-area) plus
 * [com.karyo.layout.LocationAreaUsageLookupTest]'s location-type -> area -> location pattern
 * for the cluster-membership side.
 */
@QuarkusTest
class ItemDataAreaLookupTest {

    @Inject
    lateinit var lookup: ItemDataAreaLookup

    private fun ns() = System.nanoTime().toString().takeLast(8)

    // ── Seeding helpers ────────────────────────────────────────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"IDAL Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createCluster(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-clusters").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStorageArea(name: String, clusterIds: List<Long>): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","clusterIds":${clusterIds}}""")
            .`when`().post("/api/v1/storage-areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLayoutArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, layoutAreaId: Long, clusterId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"name":"$name","locationTypeId":$locationTypeId,"areaId":$layoutAreaId,""" +
                    """"locationClusterId":$clusterId}"""
            )
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createItemDataArea(itemDataId: Long, storageAreaId: Long, plannedAmount: BigDecimal?, plannedStocks: Int?): Long {
        val amountPart = plannedAmount?.let { ""","plannedAmount":$it""" } ?: ""
        val stocksPart = plannedStocks?.let { ""","plannedStocks":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"storageAreaId":$storageAreaId$amountPart$stocksPart}""")
            .`when`().post("/api/v1/item-data-areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    private data class Seed(
        val itemDataAreaId: Long,
        val itemDataId: Long,
        val itemNumber: String,
        val storageAreaId: Long,
        val loc1: Long,
        val loc2: Long,
    )

    /** area with TWO clusters, each holding one location -- exercises the cluster-union resolution. */
    private fun seedTwoClusterArea(s: String, plannedAmount: BigDecimal?, plannedStocks: Int?): Seed {
        val itemUnitId = createItemUnit("IU-IDAL-$s")
        val itemNumber = "IDAL-SKU-$s"
        val itemDataId = createProduct(itemNumber, itemUnitId)

        val cl1 = createCluster("IDAL-CLU1-$s")
        val cl2 = createCluster("IDAL-CLU2-$s")
        val storageAreaId = createStorageArea("IDAL-SA-$s", listOf(cl1, cl2))

        val ltId = createLocationType("IDAL-LT-$s")
        val layoutAreaId = createLayoutArea("IDAL-LAREA-$s")
        val loc1 = createLocation("IDAL-LOC1-$s", ltId, layoutAreaId, cl1)
        val loc2 = createLocation("IDAL-LOC2-$s", ltId, layoutAreaId, cl2)

        val itemDataAreaId = createItemDataArea(itemDataId, storageAreaId, plannedAmount, plannedStocks)

        return Seed(itemDataAreaId, itemDataId, itemNumber, storageAreaId, loc1, loc2)
    }

    // ── (a) union of both clusters' locations ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8101"), Claim(key = "tenant_code", value = "ACME")])
    fun `an area with two clusters resolves the union of both clusters' locations`() {
        val s = ns()
        val seed = seedTwoClusterArea(s, BigDecimal("10.5"), 2)

        val views = lookup.listForReplenishment(8101L)
        val view = views.single { it.itemDataAreaId == seed.itemDataAreaId }

        assertThat(view.clusterLocationIds).containsExactlyInAnyOrder(seed.loc1, seed.loc2)
    }

    // ── (b) plannedAmount/plannedStocks surfaced ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8102"), Claim(key = "tenant_code", value = "ACME")])
    fun `plannedAmount and plannedStocks are surfaced`() {
        val s = ns()
        val seed = seedTwoClusterArea(s, BigDecimal("42.0000"), 3)

        val views = lookup.listForReplenishment(8102L)
        val view = views.single { it.itemDataAreaId == seed.itemDataAreaId }

        assertThat(view.plannedAmount).isEqualByComparingTo("42.0")
        assertThat(view.plannedStocks).isEqualTo(3)
        assertThat(view.storageAreaId).isEqualTo(seed.storageAreaId)
        assertThat(view.itemDataId).isEqualTo(seed.itemDataId)
    }

    // ── (c) itemDataNumber resolved ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8103"), Claim(key = "tenant_code", value = "ACME")])
    fun `itemDataNumber resolves to the product's real SKU number`() {
        val s = ns()
        val seed = seedTwoClusterArea(s, null, null)

        val views = lookup.listForReplenishment(8103L)
        val view = views.single { it.itemDataAreaId == seed.itemDataAreaId }

        assertThat(view.itemDataNumber).isEqualTo(seed.itemNumber)
    }

    // ── (d) tenant isolation ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8104"), Claim(key = "tenant_code", value = "ACME")])
    fun `a different clientId does not see this tenant's item data area`() {
        val s = ns()
        val seed = seedTwoClusterArea(s, BigDecimal.TEN, 1)

        val ownTenant = lookup.listForReplenishment(8104L)
        val otherTenant = lookup.listForReplenishment(8105L)

        assertThat(ownTenant.map { it.itemDataAreaId }).contains(seed.itemDataAreaId)
        assertThat(otherTenant.map { it.itemDataAreaId }).doesNotContain(seed.itemDataAreaId)
    }
}
