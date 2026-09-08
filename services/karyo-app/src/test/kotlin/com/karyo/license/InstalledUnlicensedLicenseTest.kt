package com.karyo.license

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The unlicensed half of the disclosure pair: the same installed set [LicenseServiceTest] boots,
 * with the explicitly empty entitlement value shipped in the production env template. It must
 * boot unlicensed, not fail String configuration injection. Both serve the same anonymous body.
 */
@QuarkusTest
@TestProfile(InstalledUnlicensedLicenseTest.InstalledWithoutEntitlements::class)
class InstalledUnlicensedLicenseTest {
    class InstalledWithoutEntitlements : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.license.entitlements" to "")

        override fun getEnabledAlternatives(): Set<Class<*>> =
            setOf(TestModuleInstallation::class.java)
    }

    @Inject
    lateinit var installedModules: Instance<LicensedModuleInstallation>

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
    fun `authenticated caller reads no entitlement without a licence`() {
        given()
            .`when`().get("/api/v1/license")
            .then().statusCode(200)
            .body("entitlements.size()", equalTo(0))
    }
}
