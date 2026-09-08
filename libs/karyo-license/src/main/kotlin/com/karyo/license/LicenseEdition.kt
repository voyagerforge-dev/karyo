package com.karyo.license

/**
 * Which edition of Karyo an image is: a build carrying no commercial engine
 * ([LicensedModuleInstallation]) is the free community edition, any other build is commercial.
 *
 * This is a property of the IMAGE, never of its licence state — an entitlement list can only
 * ever be a subset of what the build installs, so the discriminator stays identical whether or
 * not a deployment carries a licence.
 */
object LicenseEdition {
    const val COMMUNITY = "community"
    const val COMMERCIAL = "commercial"

    fun installedEntitlements(modules: Iterable<LicensedModuleInstallation>): Set<String> =
        modules
            .map { it.entitlement.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun of(installedEntitlements: Set<String>): String =
        if (installedEntitlements.isEmpty()) COMMUNITY else COMMERCIAL
}
