package com.karyo.inventory.api.v1

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test

@QuarkusTest
class HealthResourceTest {
    @Test
    fun `health endpoint returns UP`() {
        given()
            .`when`().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", `is`("UP"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read"])
    fun `ping endpoint returns service name`() {
        given()
            .`when`().get("/api/v1/ping")
            .then()
            .statusCode(200)
            .body("service", `is`("inventory-service"))
    }
}
