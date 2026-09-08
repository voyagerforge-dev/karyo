package com.karyo.inventory.api.v1

import com.karyo.documents.DocumentRenderer
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.inventory.service.UnitLoadDocumentService
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * D8: unit load content list PDF — a per-pallet manifest of everything riding a unit load.
 * Three tiers, mirroring [com.karyo.orders.DeliveryNoteRestTest] / [com.karyo.fulfillment.PickTicketRestTest]:
 *  - REST: status codes + content-type (magic-byte PDF check + 404/403).
 *  - Service-level (real DB, real [UnitLoadDocumentService]): proves the ACTUAL filtering —
 *    DELETABLE stock excluded, a locked stock unit shown WITH its lock name — via PDFBox
 *    text extraction; the render-level tests below only prove the template displays
 *    whatever it's handed, not that the service computes it correctly.
 *  - Render-level ([DocumentRenderer] direct, hand-fed maps): template display/escaping.
 */
@QuarkusTest
class UlContentListRestTest {

    @Inject lateinit var renderer: DocumentRenderer
    @Inject lateinit var documentService: UnitLoadDocumentService
    @Inject lateinit var unitLoadRepository: UnitLoadRepository
    @Inject lateinit var unitLoadTypeRepository: UnitLoadTypeRepository
    @Inject lateinit var stockUnitRepository: StockUnitRepository
    @Inject lateinit var tenantContext: TenantContext

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double, state: Int = 300): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,""" +
                    """"unitLoadId":$ulId,"state":$state}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun lockStock(stockUnitId: Long, lockType: Int) {
        given().contentType(ContentType.JSON).body("""{"lockType":$lockType}""")
            .`when`().post("/api/v1/stock-units/$stockUnitId/lock").then().statusCode(200)
    }

    /** Seeds a bare unit load owned by [owner], bypassing REST so a foreign owner can be created. */
    @Transactional
    fun seedForeignUnitLoad(owner: Long): Long {
        val type = unitLoadTypeRepository.listAll().first()
        val ul = UnitLoad().apply {
            clientId = owner
            labelId = "UL-CL-FOREIGN-${System.nanoTime()}"
            unitLoadType = type
            storageLocationId = 500L
            storageLocationName = "FOREIGN-LOC"
        }
        unitLoadRepository.persist(ul)
        return ul.id!!
    }

    /**
     * Seeds ONE unit load owned by [ulOwner] carrying two stock units with DIFFERENT owners
     * ([ownRowOwner] / [foreignRowOwner]) -- a mixed-owner load, bypassing REST so a foreign-owner
     * stock unit can be created on it. This shape can exist on a long-lived DB from before D1
     * (or from an OPS-driven partial [UnitLoadService.changeClient]), and the content-list PDF
     * must scope by each STOCK UNIT row's own owner, not the parent load's owner.
     */
    @Transactional
    fun seedMixedOwnerUnitLoad(ulOwner: Long, ownRowOwner: Long, foreignRowOwner: Long): Long {
        val type = unitLoadTypeRepository.listAll().first()
        val s = System.nanoTime()
        val ul = UnitLoad().apply {
            clientId = ulOwner
            labelId = "UL-CL-MIXED-$s"
            unitLoadType = type
            storageLocationId = 600L
            storageLocationName = "MIXED-LOC"
        }
        unitLoadRepository.persist(ul)
        stockUnitRepository.persist(
            StockUnit().apply {
                clientId = ownRowOwner
                itemDataId = 8400L + (s % 100_000)
                itemDataNumber = "CL-OWN-$s"
                amount = BigDecimal("10")
                unitLoad = ul
                state = 300
            },
        )
        stockUnitRepository.persist(
            StockUnit().apply {
                clientId = foreignRowOwner
                itemDataId = 8500L + (s % 100_000)
                itemDataNumber = "CL-FOREIGN-$s"
                amount = BigDecimal("5")
                unitLoad = ul
                state = 300
            },
        )
        return ul.id!!
    }

    /**
     * Test-isolation fix (defect-burndown-5 gate): [seedForeignUnitLoad]/[seedMixedOwnerUnitLoad]
     * plant a unit load at a HARDCODED, non-FK `storageLocationId` (500/600) as a bare numeric
     * placeholder -- it names no real [com.karyo.layout.domain.model.StorageLocation] row.
     * That placeholder id is drawn from the SAME id space real `StorageLocation` rows get
     * auto-assigned elsewhere in the suite, so once enough locations have been created (routine
     * well before this test class alphabetically precedes later ones, in a suite this size),
     * some LATER test's genuinely-created location can land on that exact id. Any such later
     * test that reads occupancy by location id (e.g. the location finder's client-mixing pass,
     * [com.karyo.layout.service.OccupancyMixReader]) would then see this fixture's foreign-owned
     * `StockUnit` as a live occupant of ITS OWN, unrelated location -- observed live
     * (defect-burndown-5 gate): `LocationFinderMixTest`'s self-exclusion witness/control test
     * landed on real location id 600 and was wrongly blocked by this fixture's clientId=999 row
     * still sitting at the SAME numeric placeholder. Deletes the stock unit(s) and the unit
     * load directly (bypassing any service -- this is a bare fixture with no domain lifecycle)
     * so it does not outlive the test that created it.
     */
    @Transactional
    fun cleanupSeededUnitLoad(unitLoadId: Long) {
        stockUnitRepository.delete("unitLoad.id", unitLoadId)
        unitLoadRepository.deleteById(unitLoadId)
    }

    // ── REST: status codes + content-type ───────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `content list renders for a unit load with stock`() {
        val s = System.nanoTime()
        val ulId = createUnitLoad("UL-CL-$s")
        createStock(ulId, 8000L + (s % 100_000), "CL-SKU-$s", 10.0)

        given().`when`().get("/api/v1/unit-loads/$ulId/content-list.pdf")
            .then().statusCode(200).contentType("application/pdf")
            .extract().asByteArray().let {
                assertThat(String(it.copyOfRange(0, 4))).isEqualTo("%PDF")
            }
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `content list for a non-existent unit load returns 404`() {
        given().`when`().get("/api/v1/unit-loads/99999999/content-list.pdf").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `content list for a foreign-tenant unit load returns 404`() {
        val foreignId = seedForeignUnitLoad(owner = 999L)

        given().`when`().get("/api/v1/unit-loads/$foreignId/content-list.pdf").then().statusCode(404)

        cleanupSeededUnitLoad(foreignId)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `content list is forbidden without inventory-read`() {
        given().`when`().get("/api/v1/unit-loads/1/content-list.pdf").then().statusCode(403)
    }

    // ── Service-level: real DB, real filtering ──────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `DELETABLE stock is excluded from the content list`() {
        val s = System.nanoTime()
        val ulId = createUnitLoad("UL-CL-DEL-$s")
        createStock(ulId, 8100L + (s % 100_000), "CL-KEEP-$s", 10.0, state = 300)
        createStock(ulId, 8200L + (s % 100_000), "CL-GONE-$s", 5.0, state = 1000)
        tenantContext.clientId = 1L

        val pdf = documentService.contentListPdf(ulId, tenantContext)
        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }

        assertThat(text).contains("CL-KEEP-$s")
        assertThat(text).doesNotContain("CL-GONE-$s")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `a locked stock unit is shown with its lock name`() {
        val s = System.nanoTime()
        val ulId = createUnitLoad("UL-CL-LOCK-$s")
        val stockId = createStock(ulId, 8300L + (s % 100_000), "CL-LOCKED-$s", 10.0)
        lockStock(stockId, 1) // GENERAL
        tenantContext.clientId = 1L

        val pdf = documentService.contentListPdf(ulId, tenantContext)
        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }

        assertThat(text).contains("CL-LOCKED-$s")
        assertThat(text).contains("GENERAL")
    }

    @Test
    @TestSecurity(user = "owner", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `an OWNER principal sees only their own rows on a mixed-owner unit load`() {
        val ulId = seedMixedOwnerUnitLoad(ulOwner = 1L, ownRowOwner = 1L, foreignRowOwner = 999L)
        tenantContext.clientId = 1L

        val pdf = documentService.contentListPdf(ulId, tenantContext)
        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }

        assertThat(text).contains("CL-OWN-")
        assertThat(text).doesNotContain("CL-FOREIGN-")

        cleanupSeededUnitLoad(ulId)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `an OPS principal sees every row on a mixed-owner unit load`() {
        val ulId = seedMixedOwnerUnitLoad(ulOwner = 1L, ownRowOwner = 1L, foreignRowOwner = 999L)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val pdf = documentService.contentListPdf(ulId, tenantContext)
        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }

        assertThat(text).contains("CL-OWN-")
        assertThat(text).contains("CL-FOREIGN-")

        cleanupSeededUnitLoad(ulId)
    }

    // ── Render-level: DocumentRenderer direct, hand-fed maps (template display only) ──

    @Test
    fun `template renders SKU, lot, best-before, serial and lock name`() {
        val html = renderer.render(
            "/templates/ul-content-list.html",
            mapOf(
                "labelId" to "UL-1",
                "storageLocationName" to "A-01-01",
                "generatedAt" to "2026-07-25T00:00:00Z",
                "stockUnits" to listOf(
                    mapOf(
                        "itemDataNumber" to "SKU-1",
                        "amount" to "10",
                        "lotNumber" to "LOT-1",
                        "bestBefore" to "2027-01-01",
                        "serialNumber" to "SN-1",
                        "lock" to "GENERAL",
                    ),
                ),
            ),
        )
        assertThat(html).contains("SKU-1").contains("LOT-1").contains("2027-01-01").contains("SN-1").contains("GENERAL")
    }

    @Test
    fun `template renders a dash for missing lot, best-before and serial`() {
        val html = renderer.render(
            "/templates/ul-content-list.html",
            mapOf(
                "labelId" to "UL-2",
                "storageLocationName" to "A-01-01",
                "generatedAt" to "2026-07-25T00:00:00Z",
                "stockUnits" to listOf(
                    mapOf(
                        "itemDataNumber" to "SKU-2",
                        "amount" to "10",
                        "lotNumber" to "—",
                        "bestBefore" to "—",
                        "serialNumber" to "—",
                        "lock" to "—",
                    ),
                ),
            ),
        )
        assertThat(html).contains("SKU-2").contains("—")
    }

    @Test
    fun `template escapes an XSS attempt in itemDataNumber`() {
        val html = renderer.render(
            "/templates/ul-content-list.html",
            mapOf(
                "labelId" to "UL-3",
                "storageLocationName" to "A-01-01",
                "generatedAt" to "2026-07-25T00:00:00Z",
                "stockUnits" to listOf(
                    mapOf(
                        "itemDataNumber" to "<img src=x onerror=alert(1)>",
                        "amount" to "10",
                        "lotNumber" to "—",
                        "bestBefore" to "—",
                        "serialNumber" to "—",
                        "lock" to "—",
                    ),
                ),
            ),
        )
        assertThat(html).doesNotContain("<img src=x")
        assertThat(html).contains("&lt;img src=x")
    }
}
