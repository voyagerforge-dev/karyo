package com.karyo.inventory.api.v1

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * Task 1 review fix (Finding 1): the 502 "printer unreachable" mapping was previously only
 * exercised at the [com.karyo.inventory.service.LabelPrintService] unit-test level
 * ([com.karyo.inventory.service.LabelPrintServiceTest]), never through the real JAX-RS
 * exception-mapper resolution pipeline. `karyo.print.url` is forced to a port nothing is
 * listening on (`localhost:1`) via a [QuarkusTestProfile] -- profiles fork a separate app
 * instance, hence a dedicated small test class rather than folding into [UlLabelRestTest].
 */
@QuarkusTest
@TestProfile(UlLabelPrintUnreachableIT.UnreachablePrinter::class)
class UlLabelPrintUnreachableIT {

    class UnreachablePrinter : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.print.url" to "localhost:1")
    }

    private fun createUnitLoad(label: String, locationName: String = "A-01-01"): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"$locationName"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(user = "op", roles = ["inventory-write", "inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `print with an unreachable printer yields 502 problem through the real REST pipeline`() {
        val s = System.nanoTime()
        val ulId = createUnitLoad("UL-LBL-PRINT-UNREACHABLE-$s")

        given()
            .contentType(ContentType.JSON)
            .post("/api/v1/unit-loads/{id}/label/print", ulId)
            .then()
            .statusCode(502)
            .body("title", equalTo("Printer unreachable"))
    }
}
