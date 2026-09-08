package com.karyo.inventory.api.v1

import com.karyo.docstore.repository.StoredDocumentRepository
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test

/**
 * D11: unit-load ZPL barcode label — Code128 of `labelId` plus human-readable labelId,
 * unit-load-type name and current location name. Rendered through the shared
 * [com.karyo.documents.DocumentRenderer] ZPL path, so `sanitizeZpl` strips `^`/`~` from
 * any interpolated string automatically (pinned below against `storageLocationName`).
 *
 * Tenant scoping mirrors [com.karyo.inventory.api.v1.UnitLoadResource.getById] /
 * [UlContentListRestTest]: [com.karyo.inventory.service.UnitLoadService.findById] does the
 * 404-on-foreign-or-missing check, so this test only proves the route wires that check in.
 */
@QuarkusTest
class UlLabelRestTest {

    @Inject lateinit var unitLoadRepository: UnitLoadRepository
    @Inject lateinit var unitLoadTypeRepository: UnitLoadTypeRepository
    @Inject lateinit var storedDocumentRepository: StoredDocumentRepository

    private fun createUnitLoad(label: String, locationName: String = "A-01-01"): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"$locationName"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Seeds a bare unit load owned by [owner], bypassing REST so a foreign owner can be created. */
    @Transactional
    fun seedForeignUnitLoad(owner: Long): Long {
        val type = unitLoadTypeRepository.listAll().first()
        val ul = UnitLoad().apply {
            clientId = owner
            labelId = "UL-LABEL-FOREIGN-${System.nanoTime()}"
            unitLoadType = type
            storageLocationId = 500L
            storageLocationName = "FOREIGN-LOC"
        }
        unitLoadRepository.persist(ul)
        return ul.id!!
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `label renders as ZPL with a Code128 barcode of the labelId`() {
        val s = System.nanoTime()
        val label = "UL-LBL-$s"
        val ulId = createUnitLoad(label)

        val zpl = given().`when`().get("/api/v1/unit-loads/$ulId/label.zpl")
            .then().statusCode(200).contentType(startsWith("text/plain"))
            .extract().asString()

        assertThat(zpl).contains("^XA")
        assertThat(zpl).contains("^XZ")
        assertThat(zpl).contains("^BCN,100,Y,N,N^FD$label^FS")
        assertThat(zpl).contains(label)
        assertThat(zpl).contains("A-01-01")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `label strips injected ZPL control characters from the location name`() {
        val s = System.nanoTime()
        val label = "UL-LBL-INJ-$s"
        val ulId = createUnitLoad(label, locationName = "A-01^XZINJECTED")

        val zpl = given().`when`().get("/api/v1/unit-loads/$ulId/label.zpl")
            .then().statusCode(200)
            .extract().asString()

        assertThat(zpl).doesNotContain("A-01^XZINJECTED")
        assertThat(zpl).contains("A-01XZINJECTED")
        // the template's own ^XA/^XZ frame must still be present exactly as the static markup
        assertThat(zpl.trim()).startsWith("^XA")
        assertThat(zpl.trim()).endsWith("^XZ")
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `label for a non-existent unit load returns 404`() {
        given().`when`().get("/api/v1/unit-loads/99999999/label.zpl").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `label for a foreign-tenant unit load returns 404`() {
        val foreignId = seedForeignUnitLoad(owner = 999L)

        given().`when`().get("/api/v1/unit-loads/$foreignId/label.zpl").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `label is forbidden without inventory-read`() {
        given().`when`().get("/api/v1/unit-loads/1/label.zpl").then().statusCode(403)
    }

    // ── Task 2: ?store=true opt-in archiving (docstore-templates sprint) ────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `label with store=true archives the ZPL under the unit load's client`() {
        val s = System.nanoTime()
        val label = "UL-LBL-STORE-$s"
        val ulId = createUnitLoad(label)

        given().`when`().get("/api/v1/unit-loads/$ulId/label.zpl?store=true")
            .then().statusCode(200).contentType(startsWith("text/plain"))

        val row = storedDocumentRepository
            .find("entityType = ?1 and entityId = ?2 and documentType = ?3", "unit-load", ulId, "label")
            .firstResult()
        assertThat(row).isNotNull
        assertThat(row!!.clientId).isEqualTo(1L)
        assertThat(row.fileName).isEqualTo("label-$ulId.zpl")
        assertThat(row.mediaType).isEqualTo("text/plain; charset=utf-8")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `label without store defaults to not archiving`() {
        val s = System.nanoTime()
        val label = "UL-LBL-NOSTORE-$s"
        val ulId = createUnitLoad(label)

        given().`when`().get("/api/v1/unit-loads/$ulId/label.zpl")
            .then().statusCode(200).contentType(startsWith("text/plain"))

        val count = storedDocumentRepository
            .count("entityType = ?1 and entityId = ?2 and documentType = ?3", "unit-load", ulId, "label")
        assertThat(count).isZero()
    }

    // ── Task 1: POST .../label/print pushes the ZPL to the configured printer ───────────────

    @Test
    @TestSecurity(user = "op", roles = ["inventory-write", "inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `print without configured printer yields 503 problem`() {
        val s = System.nanoTime()
        val ulId = createUnitLoad("UL-LBL-PRINT-$s")

        given()
            .contentType(ContentType.JSON)
            .post("/api/v1/unit-loads/{id}/label/print", ulId)
            .then()
            .statusCode(503)
            .body("title", equalTo("No printer configured"))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `print is denied for read-only role`() {
        // role check runs before the resource lookup, so an unseeded id is fine here --
        // mirrors `label is forbidden without inventory-read` above.
        given()
            .contentType(ContentType.JSON)
            .post("/api/v1/unit-loads/{id}/label/print", 1)
            .then()
            .statusCode(403)
    }
}
