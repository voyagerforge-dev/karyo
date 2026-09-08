package com.karyo.license

/**
 * The exact body `GET /api/v1/license` must serve an unauthenticated caller: one `edition`
 * field and nothing else.
 *
 * The edition is derived from what the build actually installs rather than hardcoded, because it
 * differs by build. The free Apache-2.0 build ships no [LicensedModuleInstallation] at all and
 * answers `community`; a build carrying commercial engines answers `commercial`. Hardcoding
 * either one would make this suite assert the wrong contract in the other build.
 *
 * Hold the installed set constant and vary only `karyo.license.entitlements`, and this same
 * string must come back, so an anonymous caller cannot tell a licensed deployment from an
 * unlicensed one. [LicenseServiceTest] (entitled) and [InstalledUnlicensedLicenseTest]
 * (unlicensed) are that pair; [LicenseDisclosureTest] covers the zero-installation build that no
 * CDI profile here can reach, because the commercial cores on this classpath are always present.
 */
fun expectedAnonymousBody(installed: Iterable<LicensedModuleInstallation>): String {
    val edition = LicenseEdition.of(LicenseEdition.installedEntitlements(installed))
    return "{\"edition\":\"$edition\"}"
}
