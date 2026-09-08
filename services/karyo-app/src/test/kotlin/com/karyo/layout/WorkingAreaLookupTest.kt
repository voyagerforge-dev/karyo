package com.karyo.layout

import com.karyo.layout.spi.WorkingAreaLookup
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
 * Integration test for [WorkingAreaLookup] (L5, locations-layout sprint Task 8) — real
 * beans, no mocks, mirrors `FixAssignmentLookupTest`'s seeding-via-REST style.
 */
@QuarkusTest
@TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
@OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
class WorkingAreaLookupTest {

    @Inject
    lateinit var lookup: WorkingAreaLookup

    private fun ns() = System.nanoTime()

    private fun createCluster(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-clusters")
            .then().statusCode(201)
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

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long, clusterId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId,"locationClusterId":$clusterId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createWorkingArea(name: String, clusterIds: List<Long>): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","clusterIds":${clusterIds}}""")
            .`when`().post("/api/v1/working-areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    @Test
    fun `locationIdsFor unions locations across every member cluster`() {
        val s = ns()
        val clusterA = createCluster("WAL-CL-A-$s")
        val clusterB = createCluster("WAL-CL-B-$s")
        val clusterC = createCluster("WAL-CL-C-$s") // not a member of the working area
        val ltId = createLocationType("WAL-LT-$s")
        val areaId = createArea("WAL-AREA-$s")

        val locA = createLocation("WAL-LOC-A-$s", ltId, areaId, clusterA)
        val locB1 = createLocation("WAL-LOC-B1-$s", ltId, areaId, clusterB)
        val locB2 = createLocation("WAL-LOC-B2-$s", ltId, areaId, clusterB)
        val locC = createLocation("WAL-LOC-C-$s", ltId, areaId, clusterC)

        val workingAreaId = createWorkingArea("WAL-WA-$s", listOf(clusterA, clusterB))

        val ids = lookup.locationIdsFor(workingAreaId)

        assertThat(ids).containsExactlyInAnyOrder(locA, locB1, locB2)
        assertThat(ids).doesNotContain(locC)
    }

    @Test
    fun `locationIdsFor on a working area with no clusters returns empty set`() {
        val s = ns()
        val workingAreaId = createWorkingArea("WAL-EMPTY-$s", emptyList())

        assertThat(lookup.locationIdsFor(workingAreaId)).isEmpty()
    }

    @Test
    fun `locationIdsFor on an unknown working area id returns empty set`() {
        assertThat(lookup.locationIdsFor(ns())).isEmpty()
    }

    @Test
    fun `exists is true for a persisted working area and false otherwise`() {
        val s = ns()
        val workingAreaId = createWorkingArea("WAL-EXISTS-$s", emptyList())

        assertThat(lookup.exists(workingAreaId)).isTrue()
        assertThat(lookup.exists(ns())).isFalse()
    }
}
