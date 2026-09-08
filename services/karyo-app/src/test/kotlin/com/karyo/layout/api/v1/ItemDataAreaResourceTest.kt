package com.karyo.layout.api.v1

import com.karyo.layout.domain.model.ItemDataArea
import com.karyo.layout.domain.model.StorageArea
import com.karyo.layout.repository.ItemDataAreaRepository
import com.karyo.product.domain.model.ItemData
import com.karyo.product.domain.model.ItemUnit
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test

/**
 * Real round-trip coverage for `/api/v1/item-data-areas` (L1, locations-layout sprint Task
 * 1) — no mocks, mirrors `FixAssignmentLookupTest`'s seeding-via-REST style. `ItemDataArea`
 * is tenant-scoped (like `FixAssignment`): post-fetch check, out-of-scope -> 404 not 403.
 */
@QuarkusTest
class ItemDataAreaResourceTest {

    @Inject
    lateinit var itemDataAreaRepository: ItemDataAreaRepository

    private fun ns() = System.nanoTime().toString().takeLast(8)

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"IDA Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/storage-areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private data class SeedCtx(val itemDataId: Long, val areaId: Long)

    private fun seedProductAndArea(suffix: String): SeedCtx {
        val itemUnitId = createItemUnit("IU-IDA-$suffix")
        val itemDataId = createProduct("IDA-SKU-$suffix", itemUnitId)
        val areaId = createArea("IDA-AREA-$suffix")
        return SeedCtx(itemDataId, areaId)
    }

    // ── 1. CRUD round trip ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create, read, update and delete an item data area round trips`() {
        val ctx = seedProductAndArea(ns())

        val id = given().contentType(ContentType.JSON)
            .body("""{"itemDataId":${ctx.itemDataId},"storageAreaId":${ctx.areaId},"plannedAmount":10.5,"plannedStocks":2}""")
            .`when`().post("/api/v1/item-data-areas")
            .then().statusCode(201)
            .body("itemDataId", `is`(ctx.itemDataId.toInt()))
            .body("storageAreaId", `is`(ctx.areaId.toInt()))
            .body("itemDataName", `is`("IDA Test Product"))
            .extract().jsonPath().getLong("id")

        given()
            .`when`().get("/api/v1/item-data-areas/$id")
            .then().statusCode(200)
            .body("plannedStocks", `is`(2))

        given().contentType(ContentType.JSON)
            .body("""{"plannedAmount":20.0,"plannedStocks":5}""")
            .`when`().put("/api/v1/item-data-areas/$id")
            .then().statusCode(200)
            .body("plannedStocks", `is`(5))

        given()
            .`when`().delete("/api/v1/item-data-areas/$id")
            .then().statusCode(204)

        given()
            .`when`().get("/api/v1/item-data-areas/$id")
            .then().statusCode(404)
    }

    // ── 2. Unknown item -> 422 (InvalidReference, matches FixAssignmentService.validateProduct's
    //      identical scenario — NOT the InvalidReferenceList/400 used for genuine list validation) ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with unknown item returns 422`() {
        val s = ns()
        val areaId = createArea("IDA-BADITEM-AREA-$s")
        val unknownItemDataId = System.nanoTime()

        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$unknownItemDataId,"storageAreaId":$areaId}""")
            .`when`().post("/api/v1/item-data-areas")
            .then().statusCode(422)
    }

    // ── 3. Unknown area -> 404 ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with unknown area returns 404`() {
        val s = ns()
        val itemUnitId = createItemUnit("IU-BADAREA-$s")
        val itemDataId = createProduct("IDA-BADAREA-SKU-$s", itemUnitId)
        val unknownAreaId = System.nanoTime()

        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"storageAreaId":$unknownAreaId}""")
            .`when`().post("/api/v1/item-data-areas")
            .then().statusCode(404)
    }

    // ── 4. Two-tenant isolation: client 2 cannot see/modify client 1's row (404 not 403) ──

    @Transactional
    fun persistForeignRow(areaEntityId: Long, ownerClientId: Long, itemDataId: Long): Long {
        val em = itemDataAreaRepository.getEntityManager()
        val area = em.find(StorageArea::class.java, areaEntityId)
        val entity = ItemDataArea().apply {
            this.itemDataId = itemDataId
            this.storageArea = area
            this.clientId = ownerClientId
        }
        itemDataAreaRepository.persist(entity)
        return entity.id!!
    }

    @Test
    @TestSecurity(user = "ida-req", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "88802"), Claim(key = "tenant_code", value = "IDA-REQ")])
    fun `client 2 principal cannot see or modify client 1's item data area row`() {
        val s = ns()
        val areaId = createArea("IDA-XTEN-AREA-$s")
        val foreignId = persistForeignRow(areaId, ownerClientId = 88801L, itemDataId = System.nanoTime())

        given()
            .`when`().get("/api/v1/item-data-areas/$foreignId")
            .then().statusCode(404)

        given().contentType(ContentType.JSON)
            .body("""{"plannedStocks":1}""")
            .`when`().put("/api/v1/item-data-areas/$foreignId")
            .then().statusCode(404)
    }

    // ── 5. Cross-tenant existence-leak probe (fix round 1, Major #1) ──────────────────
    //
    // client 1 (88803) already has a row for (itemDataId, storageAreaId). client 2 (88804,
    // this test's principal) POSTs the SAME pair. Before the fix, the unscoped duplicate
    // check ran before the tenant-ownership check (ProductLookup), so this probe returned
    // 409 -- leaking that the pair exists to a tenant with no access to it. After the fix,
    // resolveProduct (tenant-scoped) runs FIRST: client 2's ProductLookup can't see client
    // 1's product, so the response must be the ownership/validation error (422
    // InvalidReference), never 409.

    @Transactional
    fun persistForeignProduct(itemUnitId: Long, number: String, ownerClientId: Long): Long {
        val em = itemDataAreaRepository.getEntityManager()
        val itemUnit = em.find(ItemUnit::class.java, itemUnitId)
        val entity = ItemData().apply {
            this.number = number
            this.name = "Foreign Probe Product"
            this.itemUnit = itemUnit
            this.clientId = ownerClientId
        }
        em.persist(entity)
        return entity.id!!
    }

    @Test
    @TestSecurity(user = "ida-probe", roles = ["layout-read", "layout-write", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "88804"), Claim(key = "tenant_code", value = "IDA-PROBE")])
    fun `probing an existing pair owned by another tenant does not leak existence via 409`() {
        val s = ns()
        val areaId = createArea("IDA-PROBE-AREA-$s")
        val itemUnitId = createItemUnit("IU-PROBE-$s")
        val foreignItemDataId = persistForeignProduct(itemUnitId, "IDA-PROBE-SKU-$s", ownerClientId = 88803L)
        persistForeignRow(areaId, ownerClientId = 88803L, itemDataId = foreignItemDataId)

        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$foreignItemDataId,"storageAreaId":$areaId}""")
            .`when`().post("/api/v1/item-data-areas")
            .then()
            .statusCode(422)
    }
}
