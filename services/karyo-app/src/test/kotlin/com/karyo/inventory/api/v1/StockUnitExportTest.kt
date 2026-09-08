package com.karyo.inventory.api.v1

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Real-DB proof of D12's `GET /api/v1/stock-units/export.csv` -- unlike
 * [StockUnitResourceTest] this focuses on the export shape (BOM/header/row) plus the tenant-
 * scoping pin: the export delegates to the EXACT SAME `StockService.find*Paginated` calls as
 * `list`, so an OWNER on one client_id can never see another client's stock in the CSV.
 *
 * [TestMethodOrder] guarantees the client-1 seed runs before the client-2 leak check --
 * `@TestSecurity` mocks one fixed identity per test method, so a genuine two-tenant proof needs
 * two methods sharing the same DB.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StockUnitExportTest {

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(itemDataNumber: String, amount: Double, lotNumber: String): Long {
        val ulId = createUnitLoad("UL-EXP-${System.nanoTime()}")
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":10,"itemDataNumber":"$itemDataNumber","amount":$amount,""" +
                    """"unitLoadId":$ulId,"lotNumber":"$lotNumber"}""",
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    @Test
    @Order(1)
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `export csv is 200 text-csv with a BOM, exact header row, and the created stock unit`() {
        val suffix = System.nanoTime()
        val suId = createStock("SU-EXP-$suffix", 42.0, "LOT-EXP-$suffix")

        val bytes = given()
            .`when`().get("/api/v1/stock-units/export.csv")
            .then().statusCode(200)
            .contentType("text/csv")
            .extract().asByteArray()

        assertThat(bytes.copyOfRange(0, 3))
            .isEqualTo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val body = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        val lines = body.split("\r\n")
        assertThat(lines[0]).isEqualTo(
            "id,itemDataNumber,amount,reservedAmount,lotNumber,bestBefore,serialNumber," +
                "stateName,lockTypeName,unitLoadLabel,locationName",
        )
        assertThat(body).contains(suId.toString()).contains("SU-EXP-$suffix").contains("LOT-EXP-$suffix")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `export honors the itemDataId filter, same as list`() {
        val suffix = System.nanoTime()
        createStock("SU-EXP-FILTER-$suffix", 7.0, "LOT-FILTER-$suffix")

        val body = given()
            .queryParam("itemDataId", 10)
            .`when`().get("/api/v1/stock-units/export.csv")
            .then().statusCode(200)
            .extract().asString()

        assertThat(body).contains("LOT-FILTER-$suffix")
    }

    // ── Tenant-scoping pin (the export-leak risk) — this is the important one ──

    @Test
    @Order(3)
    @TestSecurity(user = "operator1", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `seed a client-1-only stock unit for the cross-tenant leak check`() {
        createStock("SU-EXP-CLIENT1-ONLY", 5.0, "LOT-CLIENT1-ONLY-MARKER")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "operator2", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2")])
    fun `client 2's export never contains client 1's lot marker`() {
        val body = given()
            .`when`().get("/api/v1/stock-units/export.csv")
            .then().statusCode(200)
            .extract().asString()

        assertThat(body).doesNotContain("LOT-CLIENT1-ONLY-MARKER")
    }
}
