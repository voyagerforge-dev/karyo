package com.karyo.layout

import com.karyo.layout.domain.model.FixAssignment
import com.karyo.layout.repository.FixAssignmentRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.spi.FixAssignmentLookup
import com.karyo.security.TenantContext
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Integration test for [FixAssignmentLookup] — real beans, no mocks.
 *
 * Seeding pattern (mirrors StagingLocationLookupTest):
 *   item-unit → product → location-type → area → location → fix-assignment
 *   → unit-load (at that location) → stock-unit
 * Then calls the SPI directly and asserts thresholds + current on-hand.
 */
@QuarkusTest
class FixAssignmentLookupTest {

    @Inject
    lateinit var lookup: FixAssignmentLookup

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var fixAssignmentRepository: FixAssignmentRepository

    @Inject
    lateinit var storageLocationRepository: StorageLocationRepository

    // ── Seeding helpers ────────────────────────────────────────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"FA Lookup Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")

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

    private fun createFixAssignment(locationId: Long, itemDataId: Long, minAmount: Int): Long =
        given().contentType(ContentType.JSON)
            .body("""{"locationId":$locationId,"itemDataId":$itemDataId,"minAmount":$minAmount,"maxAmount":50,"desiredAmount":30}""")
            .`when`().post("/api/v1/fix-assignments").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createFixAssignmentWithCeiling(locationId: Long, itemDataId: Long, maxPickAmount: Double?): Long {
        val ceilingField = if (maxPickAmount != null) ""","maxPickAmount":$maxPickAmount""" else ""
        return given().contentType(ContentType.JSON)
            .body("""{"locationId":$locationId,"itemDataId":$itemDataId$ceilingField}""")
            .`when`().post("/api/v1/fix-assignments").then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    private fun createUnitLoad(label: String, locationId: Long, locationName: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":$locationId,"storageLocationName":"$locationName"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStockUnit(unitLoadId: Long, itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"FA-SKU","amount":$amount,"unitLoadId":$unitLoadId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    // ── Context returned by seed helper ───────────────────────────────────

    data class SeedCtx(val assignmentId: Long, val locationId: Long, val itemDataId: Long)

    private fun seedFixAssignmentWithStock(minAmount: Int, onHand: Double): SeedCtx {
        // Bounded suffix: item-unit name is @Size(max=20); a full System.nanoTime() (15+ digits on a
        // long-running host) pushes "IU-FA-$ns" past 20 chars → flaky 400. Last 8 digits stay unique-enough.
        val ns = System.nanoTime().toString().takeLast(8)
        val itemUnitId = createItemUnit("IU-FA-$ns")
        val itemDataId = createProduct("FA-SKU-$ns", itemUnitId)
        val ltId = createLocationType("LT-FA-$ns")
        val areaId = createArea("AREA-FA-$ns")
        val locName = "LOC-FA-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val assignmentId = createFixAssignment(locationId, itemDataId, minAmount)
        val ulId = createUnitLoad("UL-FA-$ns", locationId, locName)
        createStockUnit(ulId, itemDataId, onHand)
        return SeedCtx(assignmentId, locationId, itemDataId)
    }

    // ── Test ──────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `listForReplenishment returns assignments with thresholds and current on-hand`() {
        val ctx = seedFixAssignmentWithStock(minAmount = 10, onHand = 4.0)

        // TenantContext is @RequestScoped; the REST seeding calls above populate it for each
        // HTTP request, but the direct SPI call below bypasses the filter.  Set it explicitly
        // so DefaultStockUnitLookup can resolve stock for this tenant (same pattern as
        // DefaultStockPickerTest / ShipStagingLookupTest).
        tenantContext.clientId = 1L

        val views = lookup.listForReplenishment(1L)
        val v = views.first { it.assignmentId == ctx.assignmentId }

        assertThat(v.minAmount).isEqualByComparingTo("10")
        assertThat(v.currentAmount).isEqualByComparingTo("4")
        assertThat(v.locationId).isEqualTo(ctx.locationId)
        assertThat(v.itemDataId).isEqualTo(ctx.itemDataId)
    }

    // ── L7: pickCeilings ─────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pickCeilings batches by location, filters by product, and omits null ceilings`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val itemUnitId = createItemUnit("IU-PC-$ns")
        val targetItemDataId = createProduct("PC-TARGET-$ns", itemUnitId)
        val otherItemDataId = createProduct("PC-OTHER-$ns", itemUnitId)
        val ltId = createLocationType("LT-PC-$ns")
        val areaId = createArea("AREA-PC-$ns")

        // L1: fix on the TARGET product, WITH a ceiling — must appear in the result.
        val loc1 = createLocation("LOC-PC1-$ns", ltId, areaId)
        createFixAssignmentWithCeiling(loc1, targetItemDataId, maxPickAmount = 12.0)

        // L2: fix on the TARGET product, NO ceiling (null) — must be omitted.
        val loc2 = createLocation("LOC-PC2-$ns", ltId, areaId)
        createFixAssignmentWithCeiling(loc2, targetItemDataId, maxPickAmount = null)

        // L3: fix on a DIFFERENT product, WITH a ceiling — must be omitted (product mismatch).
        val loc3 = createLocation("LOC-PC3-$ns", ltId, areaId)
        createFixAssignmentWithCeiling(loc3, otherItemDataId, maxPickAmount = 20.0)

        tenantContext.clientId = 1L

        val ceilings = lookup.pickCeilings(1L, targetItemDataId, setOf(loc1, loc2, loc3))

        assertThat(ceilings).hasSize(1)
        assertThat(ceilings[loc1]).isEqualByComparingTo("12.0")
        assertThat(ceilings).doesNotContainKey(loc2)
        assertThat(ceilings).doesNotContainKey(loc3)

        // Tenant scope: a different clientId sees none of client 1's fix rows.
        val wrongTenant = lookup.pickCeilings(2L, targetItemDataId, setOf(loc1, loc2, loc3))
        assertThat(wrongTenant).isEmpty()
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pickCeilings returns empty map for an empty location set`() {
        tenantContext.clientId = 1L
        assertThat(lookup.pickCeilings(1L, 999999L, emptySet())).isEmpty()
    }

    // ── R11: clientIdsWithAssignments ───────────────────────────────────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `clientIdsWithAssignments returns distinct client ids across tenants, unscoped`() {
        val ctx = seedFixAssignmentWithStock(minAmount = 5, onHand = 1.0) // seeded as client 1 via REST

        // A second tenant's fix row, persisted directly (bypassing REST, which is pinned to the
        // @OidcSecurity client_id=1 claim for this whole test method) -- reuses the same
        // location with a different itemDataId to satisfy the (location_id, item_data_id)
        // unique constraint, `clientId` set explicitly on the managed entity. Mirrors the
        // direct-entity seeding pattern used by ConfirmMergeFlowTest/TransferToCarrierTest for
        // cross-tenant fixtures that REST-under-one-JWT can't produce.
        val otherClientId = 2L
        QuarkusTransaction.requiringNew().run {
            val location = storageLocationRepository.findById(ctx.locationId)!!
            val other = FixAssignment().apply {
                this.location = location
                this.itemDataId = ctx.itemDataId + 999_000
                this.clientId = otherClientId
            }
            fixAssignmentRepository.persist(other)
        }

        val clientIds = lookup.clientIdsWithAssignments()

        // containsAll, not exact equality -- the shared test DB may already hold fix rows for
        // other clients from earlier tests in this run.
        assertThat(clientIds).contains(1L, otherClientId)
    }
}
