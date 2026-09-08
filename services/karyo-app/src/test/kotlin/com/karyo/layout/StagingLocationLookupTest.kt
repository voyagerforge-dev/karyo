package com.karyo.layout

import com.karyo.layout.spi.StagingLocationLookup
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@QuarkusTest
class StagingLocationLookupTest {

    @Inject
    lateinit var lookup: StagingLocationLookup

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String, vararg usages: String): Long {
        val usagesJson = usages.joinToString(",") { "\"$it\"" }
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":[$usagesJson]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long) {
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findPackStaging returns a location whose area carries PACK_STAGING`() {
        val ltId = createLocationType("PackStgType-${System.nanoTime()}")
        val areaId = createArea("PACK-STG-${System.nanoTime()}", "PACK_STAGING")
        val locName = "PACK-STAGING-${System.nanoTime()}"
        createLocation(locName, ltId, areaId)

        val staging = lookup.findPackStaging(1L)
        assertThat(staging).isNotNull
        assertThat(staging!!.name).isNotBlank
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findPackStaging is tenant-scoped - another client sees no staging location`() {
        // Seed a PACK_STAGING area + location owned by client 1.
        val ltId = createLocationType("PackStgType-${System.nanoTime()}")
        val areaId = createArea("PACK-STG-ISO-${System.nanoTime()}", "PACK_STAGING")
        createLocation("PACK-STAGING-ISO-${System.nanoTime()}", ltId, areaId)

        // Client 2 owns no location in any PACK_STAGING area, so the lookup must return null
        // (areas are tenant-shared; isolation is enforced at the StorageLocation.clientId level).
        assertThat(lookup.findPackStaging(2L)).isNull()
    }

    @Test
    @TestSecurity(user = "mgr3", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "3"), Claim(key = "tenant_code", value = "ACME3")])
    fun `findPackStaging matches an area carrying PACK_STAGING among multiple usages`() {
        // The usages column stores a comma-joined string ("STORAGE,PACK_STAGING"); the lookup's
        // split(",")-then-contains must recover PACK_STAGING as an exact token. Run under a dedicated
        // client (3) so the only PACK_STAGING location this tenant owns is the multi-usage one —
        // a broken join/split (e.g. a stray space) would yield null here.
        val ltId = createLocationType("PackStgType3-${System.nanoTime()}")
        val areaId = createArea("PACK-STG-MULTI-${System.nanoTime()}", "STORAGE", "PACK_STAGING")
        createLocation("PACK-STAGING-MULTI-${System.nanoTime()}", ltId, areaId)

        val staging = lookup.findPackStaging(3L)
        assertThat(staging).isNotNull
        assertThat(staging!!.name).isNotBlank
    }
}
