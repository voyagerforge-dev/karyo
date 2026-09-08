package com.karyo.inventory.api.v1

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Task 1 review fix (Finding 2): the 204 success path was previously only exercised at the
 * [com.karyo.inventory.service.LabelPrintService] unit-test level, never through the real
 * [UnitLoadResource.printLabel] REST route. `karyo.print.url` is forced to a fixed high port
 * (19113, unlikely to collide with anything else on the box) via a [QuarkusTestProfile] --
 * profiles fork a separate app instance, hence a dedicated small test class rather than folding
 * into [UlLabelRestTest]. The test binds a real [ServerSocket] on that same port before driving
 * the POST, so the request actually leaves the app process over TCP and lands on a listener this
 * test controls.
 *
 * Also folds in the reviewer's accepted-and-ignored-body check: the request carries a
 * `{"printer":"whatever"}` JSON body, which [UnitLoadResource.printLabel] has no parameter to
 * deserialize it into -- the route must behave identically to a bodyless call.
 */
@QuarkusTest
@TestProfile(UlLabelPrintSuccessIT.FixedPortPrinter::class)
class UlLabelPrintSuccessIT {

    class FixedPortPrinter : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.print.url" to "localhost:$PRINTER_PORT")
    }

    private fun createUnitLoad(label: String, locationName: String = "A-01-01"): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"$locationName"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(user = "op", roles = ["inventory-write", "inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `print succeeds end-to-end and the printer receives non-empty ZPL, printer field in body is ignored`() {
        val s = System.nanoTime()
        val label = "UL-LBL-PRINT-OK-$s"
        val ulId = createUnitLoad(label)

        val received = StringBuilder()
        ServerSocket(PRINTER_PORT).use { server ->
            val t = thread {
                server.accept().use { conn -> received.append(conn.getInputStream().readBytes().toString(Charsets.UTF_8)) }
            }

            given()
                .contentType(ContentType.JSON)
                .body("""{"printer":"whatever"}""")
                .post("/api/v1/unit-loads/{id}/label/print", ulId)
                .then()
                .statusCode(204)

            t.join(3000)
        }

        assertThat(received.toString()).contains("^XA")
        assertThat(received.toString()).contains(label)
    }

    companion object {
        private const val PRINTER_PORT = 19113
    }
}
