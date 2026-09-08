package com.karyo.ai

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
class CopilotConfigResourceTest {

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read"])
    fun `config reports disabled with provider none by default`() {
        given().get("/api/v1/ai/config")
            .then().statusCode(200)
            .body("enabled", equalTo(false))
            .body("provider", equalTo("none"))
    }
}
