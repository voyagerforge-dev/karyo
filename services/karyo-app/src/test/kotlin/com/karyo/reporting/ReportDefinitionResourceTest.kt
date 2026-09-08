package com.karyo.reporting

import com.karyo.reporting.dto.CreateReportDefinitionRequest
import com.karyo.reporting.service.ReportDefinitionService
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class ReportDefinitionResourceTest {

    @Inject lateinit var service: ReportDefinitionService

    @Test
    @TestSecurity(user = "manager", roles = ["report-read", "report-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8181")])
    fun `create then list then delete round-trips a saved report`() {
        val id = given()
            .contentType(ContentType.JSON)
            .body("""{"name":"Weekly ops summary","reportType":"throughput"}""")
            .`when`().post("/api/v1/report-definitions")
            .then().statusCode(201)
            .body("name", equalTo("Weekly ops summary"))
            .body("id", notNullValue())
            .extract().path<Int>("id")

        given().get("/api/v1/report-definitions")
            .then().statusCode(200)
            .body("name", hasItem("Weekly ops summary"))

        given().delete("/api/v1/report-definitions/$id")
            .then().statusCode(204)

        given().get("/api/v1/report-definitions")
            .then().statusCode(200)
            .body("id", not(hasItem(id)))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["report-read", "report-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8181")])
    fun `blank name is rejected with 400`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"","reportType":"throughput"}""")
            .`when`().post("/api/v1/report-definitions")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["report-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8181")])
    fun `requires report-write to create`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"Nope","reportType":"throughput"}""")
            .`when`().post("/api/v1/report-definitions")
            .then().statusCode(403)
    }

    // Tenant isolation is exercised at the service layer directly — @TestSecurity/@OidcSecurity
    // fix the JWT identity for an entire test method, so a single REST-driven test can't switch
    // tenants mid-test. This mirrors KpiViewRepositoryTest's direct-injection style.
    @Test
    @TestTransaction
    fun `tenant isolation - another client cannot see or delete a report it does not own`() {
        val created = service.create(
            CreateReportDefinitionRequest(name = "Tenant 8282 report", reportType = "aging-stock"),
            clientId = 8282L,
        )

        val otherTenantList = service.list(8383L)
        assertTrue(otherTenantList.none { it.id == created.id })

        assertThrows(NotFoundException::class.java) {
            service.delete(created.id, 8383L)
        }
    }
}
