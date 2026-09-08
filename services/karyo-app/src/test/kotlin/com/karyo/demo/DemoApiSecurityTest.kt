package com.karyo.demo

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test

@QuarkusTest
class DemoApiDisabledTest {
    // KARYO_DEMO not set -> disabled -> endpoints 404 even for ADMIN
    @Test @TestSecurity(user = "a", roles = ["ADMIN"])
    fun `seed is 404 when demo disabled`() {
        given().`when`().post("/api/v1/demo/seed").then().statusCode(404)
    }
    @Test @TestSecurity(user = "a", roles = ["ADMIN"])
    fun `reset is 404 when demo disabled`() {
        given().`when`().post("/api/v1/demo/reset").then().statusCode(404)
    }

    @Test @TestSecurity(user = "v", roles = ["VIEWER"])
    fun `seed is 404 for non-admin when demo disabled (invisible in prod)`() {
        given().`when`().post("/api/v1/demo/seed").then().statusCode(404)
    }

    @Test @TestSecurity(user = "v", roles = ["VIEWER"])
    fun `status is 404 when demo disabled (gate-discovery probe)`() {
        given().`when`().get("/api/v1/demo/status").then().statusCode(404)
    }
}

@QuarkusTest
@TestProfile(DemoApiEnabledSecurityTest.Enabled::class)
class DemoApiEnabledSecurityTest {
    class Enabled : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.demo.enabled" to "on")
    }
    // enabled but non-admin -> 403
    @Test @TestSecurity(user = "v", roles = ["VIEWER"])
    fun `seed forbidden for non-admin when enabled`() {
        given().`when`().post("/api/v1/demo/seed").then().statusCode(403)
    }

    // MANAGER can also trigger seed/reset (Task 10, defect-burndown): ADMIN
    // acts as SYS (client 0) and can never see the ACME (client 1) data it
    // seeds -- MANAGER can, so the endpoints are ADMIN-or-MANAGER now.
    @Test @TestSecurity(user = "manager", roles = ["MANAGER"])
    fun `seed allowed for MANAGER when enabled`() {
        given().post("/api/v1/demo/seed").then().statusCode(200)
    }

    @Test @TestSecurity(user = "manager", roles = ["MANAGER"])
    fun `reset allowed for MANAGER when enabled`() {
        given().post("/api/v1/demo/reset").then().statusCode(204)
    }

    // Gate-discovery probe (LicenseResource precedent): VIEWER-reachable, unlike seed/reset.
    @Test @TestSecurity(user = "v", roles = ["VIEWER"])
    fun `status is 200 for VIEWER when demo enabled`() {
        given().`when`().get("/api/v1/demo/status").then().statusCode(200)
            .body("enabled", org.hamcrest.Matchers.equalTo(true))
    }
}
