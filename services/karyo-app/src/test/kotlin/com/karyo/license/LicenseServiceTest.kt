package com.karyo.license

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A stand-in commercial engine. It is an `@Alternative` so it stays inert unless a profile
 * selects it: enabled by default it would make every other `@QuarkusTest` boot look like a
 * commercial image, which is exactly what hid the free build's `community` contract before.
 */
@Alternative
@ApplicationScoped
class TestModuleInstallation : LicensedModuleInstallation {
    override val entitlement: String = "test-installed"
}

@QuarkusTest
@TestProfile(LicenseServiceTest.ConfiguredEntitlements::class)
class LicenseServiceTest {
    class ConfiguredEntitlements : QuarkusTestProfile {
        override fun getConfigOverrides() =
            mapOf("karyo.license.entitlements" to "test-installed,not-installed")

        override fun getEnabledAlternatives(): Set<Class<*>> =
            setOf(TestModuleInstallation::class.java)
    }

    @Inject
    lateinit var licenseService: LicenseService

    @Inject
    lateinit var installedModules: Instance<LicensedModuleInstallation>

    @Test
    fun `entitled module returns true`() {
        assertTrue(licenseService.isEntitled("test-installed"))
        assertTrue(licenseService.isEntitled("not-installed"))
    }

    @Test
    fun `non-entitled module returns false`() {
        assertFalse(licenseService.isEntitled("slotting"))
    }

    @Test
    fun `anonymous caller cannot tell this entitled deployment from an unlicensed one`() {
        val body = given()
            .`when`().get("/api/v1/license")
            .then().statusCode(200)
            .extract().body().asString()
        assertEquals(expectedAnonymousBody(installedModules), body)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["VIEWER"])
    fun `authenticated caller reads only configured modules installed in this image`() {
        given()
            .`when`().get("/api/v1/license")
            .then().statusCode(200)
            .body("entitlements", hasItem("test-installed"))
            .body("entitlements.size()", equalTo(1))
    }
}
