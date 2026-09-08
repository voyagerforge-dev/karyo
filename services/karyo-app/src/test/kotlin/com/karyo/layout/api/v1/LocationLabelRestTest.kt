package com.karyo.layout.api.v1

import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.repository.AreaRepository
import com.karyo.layout.repository.LocationTypeRepository
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
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test

/**
 * D11: storage-location ZPL barcode label — Code128 of `scanCode ?: name` plus the location
 * name and a zone/area line. Rendered through the shared [com.karyo.documents.DocumentRenderer]
 * ZPL path, so `sanitizeZpl` strips `^`/`~` from any interpolated string automatically
 * (pinned below against the location NAME). Tenant scoping mirrors
 * [com.karyo.layout.service.LocationService.findById] (private `findEntityById` idiom): 404
 * on missing/foreign, never a leak.
 */
@QuarkusTest
class LocationLabelRestTest {

    @Inject lateinit var locationRepository: StorageLocationRepository
    @Inject lateinit var locationTypeRepository: LocationTypeRepository
    @Inject lateinit var areaRepository: AreaRepository

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":["STORAGE"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long, scanCode: String? = null): Long =
        given().contentType(ContentType.JSON)
            .body(
                buildString {
                    append("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId""")
                    if (scanCode != null) append(""","scanCode":"$scanCode"""")
                    append("}")
                },
            )
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /** Seeds a location with a NULL scanCode directly (the REST API always defaults scanCode
     * to the name on create, and partial-update cannot clear it back to null -- see
     * [com.karyo.layout.service.LocationService.updateLocation] -- so this is the only way
     * to exercise the `scanCode ?: name` fallback). */
    @Transactional
    fun seedLocationWithNullScanCode(name: String, locationTypeId: Long, areaId: Long, owner: Long = 1L): Long {
        val entity = StorageLocation().apply {
            this.name = name
            this.scanCode = null
            this.locationType = locationTypeRepository.findById(locationTypeId)!!
            this.area = areaRepository.findById(areaId)!!
            this.clientId = owner
        }
        locationRepository.persist(entity)
        return entity.id!!
    }

    @Test
    @TestSecurity(user = "operator", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `label renders as ZPL with a Code128 barcode of the scanCode`() {
        val s = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LblType-$s")
        val areaId = createArea("LblArea-$s")
        val locId = createLocation("LBL-A-$s", ltId, areaId, scanCode = "SCAN-$s")

        val zpl = given().`when`().get("/api/v1/locations/$locId/label.zpl")
            .then().statusCode(200).contentType(startsWith("text/plain"))
            .extract().asString()

        assertThat(zpl).contains("^XA")
        assertThat(zpl).contains("^XZ")
        assertThat(zpl).contains("^BCN,100,Y,N,N^FDSCAN-$s^FS")
        assertThat(zpl).contains("LBL-A-$s")
        assertThat(zpl).contains("LblArea-$s")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `label falls back to the location name as the barcode payload when scanCode is null`() {
        val s = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LblType2-$s")
        val areaId = createArea("LblArea2-$s")
        val locId = seedLocationWithNullScanCode("LBL-NOSCAN-$s", ltId, areaId)

        val zpl = given().`when`().get("/api/v1/locations/$locId/label.zpl")
            .then().statusCode(200)
            .extract().asString()

        assertThat(zpl).contains("^BCN,100,Y,N,N^FDLBL-NOSCAN-$s^FS")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `label strips injected ZPL control characters from the location name`() {
        val s = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LblType3-$s")
        val areaId = createArea("LblArea3-$s")
        // no explicit scanCode -> createLocation defaults scanCode to name, so the injected
        // string flows through both the display name AND the barcode payload.
        val locId = createLocation("LBL^XZINJ-$s", ltId, areaId)

        val zpl = given().`when`().get("/api/v1/locations/$locId/label.zpl")
            .then().statusCode(200)
            .extract().asString()

        assertThat(zpl).doesNotContain("LBL^XZINJ-$s")
        assertThat(zpl).contains("LBLXZINJ-$s")
        assertThat(zpl.trim()).startsWith("^XA")
        assertThat(zpl.trim()).endsWith("^XZ")
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `label for a non-existent location returns 404`() {
        given().`when`().get("/api/v1/locations/99999999/label.zpl").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `label for a foreign-tenant location returns 404`() {
        val s = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LblType4-$s")
        val areaId = createArea("LblArea4-$s")
        val foreignId = seedLocationWithNullScanCode("LBL-FOREIGN-$s", ltId, areaId, owner = 999L)

        given().`when`().get("/api/v1/locations/$foreignId/label.zpl").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `label is forbidden without layout-read`() {
        given().`when`().get("/api/v1/locations/1/label.zpl").then().statusCode(403)
    }
}
