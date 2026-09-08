package com.karyo.license

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Drives [LicenseResource] directly so the installed set can be varied, including the empty set
 * of the free Apache-2.0 build. No `@QuarkusTest` here can reach that state: the commercial cores
 * are on this classpath, so every CDI boot resolves at least one [LicensedModuleInstallation].
 *
 * What this covers: the response body [LicenseResource.get] builds for each caller, serialised.
 * What it does not: HTTP routing, authentication, and the wire bytes RESTEasy actually writes,
 * which [LicenseResourceTest], [LicenseServiceTest] and [InstalledUnlicensedLicenseTest] assert
 * against a running server.
 */
class LicenseDisclosureTest {
    private val mapper = ObjectMapper()

    private fun installations(vararg entitlements: String): Instance<LicensedModuleInstallation> {
        val modules: List<LicensedModuleInstallation> = entitlements.map { key ->
            object : LicensedModuleInstallation {
                override val entitlement: String = key
            }
        }
        val instance = mockk<Instance<LicensedModuleInstallation>>()
        every { instance.iterator() } answers { modules.toMutableList().iterator() }
        return instance
    }

    private fun caller(anonymous: Boolean): SecurityIdentity {
        val identity = mockk<SecurityIdentity>()
        every { identity.isAnonymous } returns anonymous
        return identity
    }

    private fun body(
        installed: Instance<LicensedModuleInstallation>,
        licence: String,
        anonymous: Boolean,
    ): String {
        val resource = LicenseResource(
            LicenseService(licenseKey = "none", publicKeyOverride = "unused", legacyRaw = Optional.of(licence)),
            installed,
            caller(anonymous),
        )
        return mapper.writeValueAsString(resource.get().entity)
    }

    @Test
    fun `a build with no installed engine reports the free community edition`() {
        assertEquals(
            """{"edition":"community"}""",
            body(installations(), licence = "none", anonymous = true),
        )
    }

    @Test
    fun `a free build discloses nothing more when a licence is configured`() {
        assertEquals(
            body(installations(), licence = "none", anonymous = true),
            body(installations(), licence = "monitors,wave", anonymous = true),
        )
    }

    @Test
    fun `an anonymous caller cannot tell a licensed build from an unlicensed one`() {
        val licensed = body(installations("monitors"), licence = "monitors", anonymous = true)
        val unlicensed = body(installations("monitors"), licence = "none", anonymous = true)
        assertEquals("""{"edition":"commercial"}""", licensed)
        assertEquals(licensed, unlicensed)
    }

    @Test
    fun `a free build grants no entitlement to an authenticated caller`() {
        assertEquals(
            """{"edition":"community","entitlements":[]}""",
            body(installations(), licence = "monitors,wave", anonymous = false),
        )
    }

    @Test
    fun `an authenticated caller reads the engines this build both installs and is licensed for`() {
        assertEquals(
            """{"edition":"commercial","entitlements":["monitors"]}""",
            body(installations("monitors"), licence = "monitors,wave", anonymous = false),
        )
    }
}
