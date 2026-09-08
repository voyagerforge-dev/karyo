package com.karyo.replenishment

import com.karyo.replenishment.dto.GeneratedTask
import com.karyo.replenishment.dto.ReplenishmentNeed
import com.karyo.replenishment.dto.ReplenishmentScanResult
import com.karyo.replenishment.service.ReplenishmentService
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import java.math.BigDecimal

@QuarkusTest
class ReplenishmentResourceTest {

    @InjectMock
    lateinit var replenishmentService: ReplenishmentService

    @Test
    @TestSecurity(user = "mgr", roles = ["task-write", "task-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `scan returns a result envelope`() {
        doReturn(ReplenishmentScanResult(generated = emptyList<GeneratedTask>(), shortfalls = emptyList()))
            .`when`(replenishmentService).scan(1L)

        given().`when`().post("/api/v1/replenishment/scan")
            .then().statusCode(200)
            .body("generated", notNullValue())
            .body("shortfalls", notNullValue())
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["task-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `needs returns a list`() {
        doReturn(emptyList<ReplenishmentNeed>())
            .`when`(replenishmentService).needs(1L)

        given().`when`().get("/api/v1/replenishment/needs")
            .then().statusCode(200)
    }

    @Test
    fun `scan rejects unauthenticated`() {
        given().`when`().post("/api/v1/replenishment/scan").then().statusCode(401)
    }
}
