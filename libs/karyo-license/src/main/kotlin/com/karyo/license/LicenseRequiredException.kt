package com.karyo.license

/**
 * Thrown by a paid-module REST resource when the tenant lacks the given entitlement
 * (per [LicenseService]). Mapped to HTTP 403 by [LicenseRequiredExceptionMapper].
 * The `GET /api/v1/license` discovery endpoint is exempt.
 */
class LicenseRequiredException(val moduleKey: String) :
    RuntimeException("License required for module: $moduleKey")
