package com.karyo.security

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Exercises the platform's path-policy boundary through real HTTP, not a version-string check.
 * Production uses resource annotations; this test-only policy makes both upstream CVE vectors
 * observable on an existing public endpoint without changing production authorization.
 */
@QuarkusTest
@TestProfile(HttpPathPolicyProfile::class)
@TestSecurity(user = "path-policy-viewer", roles = ["VIEWER"])
class HttpPathPolicyTest {
    @Test
    fun `canonical path is denied while readiness remains available`() {
        given().log().method().log().uri().get("/q/health/ready")
            .then().log().status().log().body().statusCode(200)
        given().log().method().log().uri().get("/api/v1/ping")
            .then().log().status().statusCode(403)
        given().log().method().log().uri().get("/path-policy/private/marker.txt")
            .then().log().status().statusCode(403)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1;probe/ping",
        "/api/v1%3Bprobe/ping",
        "/api/v1%3bprobe/ping",
        "/path-policy%2Fprivate/marker.txt",
        "/path-policy%5Cprivate/marker.txt",
    ])
    fun `matrix parameters and encoded separators cannot bypass the path policy`(path: String) {
        given().urlEncodingEnabled(false).log().method().log().uri().get(path)
            .then().log().status().statusCode(403)
    }
}

class HttpPathPolicyProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.http.auth.permission.path-regression.paths" to "/api/v1/ping,/path-policy/private/*",
        "quarkus.http.auth.permission.path-regression.policy" to "deny",
    )
}
