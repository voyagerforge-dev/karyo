package com.karyo.app

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the in-process CDI wiring between the inventory and layout modules:
 * UnitLoadService.transferToLocation fires UnitLoadTransferredEvent, observed
 * synchronously by layout's UnitLoadTransferredObserver which adjusts the
 * source/destination location allocation (replaces the former Kafka topic + consumer).
 */
@QuarkusTest
class UnitLoadTransferCdiFlowTest {

    private fun createLocationType(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","liftingCapacity":1000}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":["STORAGE"]}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun getAllocation(locationId: Long): Double =
        given()
            .`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200)
            .extract().jsonPath().getDouble("allocation")

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `unit load transfer updates destination and source location allocation`() {
        val suffix = System.nanoTime()
        val locationTypeId = createLocationType("CDI-Rack-$suffix")
        val areaId = createArea("CDI-Area-$suffix")
        val sourceId = createLocation("CDI-SRC-$suffix", locationTypeId, areaId)
        val destinationId = createLocation("CDI-DST-$suffix", locationTypeId, areaId)

        // Unit load starts at the source location
        val ulId = given()
            .contentType(ContentType.JSON)
            .body(
                """{"labelId":"UL-CDI-$suffix","unitLoadTypeId":1,""" +
                    """"storageLocationId":$sourceId,"storageLocationName":"CDI-SRC-$suffix"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        assertThat(getAllocation(sourceId)).isEqualTo(0.0)
        assertThat(getAllocation(destinationId)).isEqualTo(0.0)

        // Transfer -> synchronous CDI event -> layout module adjusts allocations
        given()
            .contentType(ContentType.JSON)
            .body("""{"destinationLocationId":$destinationId,"destinationLocationName":"CDI-DST-$suffix"}""")
            .`when`().post("/api/v1/unit-loads/$ulId/transfer")
            .then().statusCode(200)

        // Destination gains 100 (one UL = 100% for v1); source stays at floor 0
        assertThat(getAllocation(destinationId)).isEqualTo(100.0)
        assertThat(getAllocation(sourceId)).isEqualTo(0.0)
    }
}
