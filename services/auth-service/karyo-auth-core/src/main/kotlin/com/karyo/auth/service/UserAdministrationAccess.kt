package com.karyo.auth.service

import com.karyo.auth.exception.AuthException
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import com.karyo.security.writeScope
import org.keycloak.representations.idm.UserRepresentation

internal fun userClientId(user: UserRepresentation): Long? =
    user.attributes?.get("client_id")?.firstOrNull()?.toLongOrNull()

internal fun TenantContext.canManageUser(user: UserRepresentation): Boolean {
    val targetClientId = userClientId(user) ?: return false
    if (!writeScope().permits(targetClientId)) return false
    val targetKind = PrincipalKind.fromClaim(
        user.attributes?.get(PrincipalKind.CLAIM)?.firstOrNull(),
    )
    return principalKind == PrincipalKind.OPS || targetKind == PrincipalKind.OWNER
}

internal fun TenantContext.requireManageableUser(
    user: UserRepresentation,
    userId: String,
): Long {
    val targetClientId = userClientId(user)?.takeIf { writeScope().permits(it) }
        ?: throw AuthException.TenantMismatch(userId)
    if (!canManageUser(user)) throw AuthException.NotManageable(userId)
    return targetClientId
}
