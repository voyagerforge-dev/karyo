package com.karyo.license

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The default profile: whatever this build installs, with no licence configured.
 */
@QuarkusTest
class LicenseResourceTest {
    @Inject
    lateinit var installedModules: Instance<LicensedModuleInstallation>

    @Test
    fun `the stand-in engine of the licence tests stays out of every other profile`() {
        assertFalse(
            "test-installed" in LicenseEdition.installedEntitlements(installedModules),
        )
    }

    @Test
    fun `anonymous caller reads the edition discriminator alone`() {
        val body = given()
            .`when`().get("/api/v1/license")
            .then().statusCode(200)
            .extract().body().asString()
        assertEquals(expectedAnonymousBody(installedModules), body)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["VIEWER"])
    fun `authenticated caller reads the entitlement detail`() {
        val edition = LicenseEdition.of(LicenseEdition.installedEntitlements(installedModules))
        given()
            .`when`().get("/api/v1/license")
            .then().statusCode(200)
            .body("edition", equalTo(edition))
            .body("entitlements.size()", equalTo(0))
    }
}
