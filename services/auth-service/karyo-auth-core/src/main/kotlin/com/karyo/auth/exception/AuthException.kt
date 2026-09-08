package com.karyo.auth.exception

import com.karyo.common.exception.KaryoException

sealed class AuthException(message: String) : KaryoException(message) {
    class UserNotFound(userId: String) : AuthException("User not found: $userId")
    class UserAlreadyExists(username: String) : AuthException("User already exists: $username")
    class RoleNotFound(roleName: String) : AuthException("Role not found: $roleName")
    class KeycloakAdminError(detail: String) : AuthException("Keycloak admin API error: $detail")
    class TenantMismatch(userId: String) : AuthException("User $userId does not belong to current tenant")
    class ClientNotFound(id: Long) : AuthException("Client $id not found")
    class DuplicateClient(field: String, value: String) : AuthException("A client with $field '$value' already exists")
    class SystemClientProtected : AuthException("The system client (id 0) cannot be modified or deactivated")

    /**
     * A goods-owner principal attempted platform-level client administration (creating a goods
     * owner). Distinct from [TenantMismatch], which is about a *user* not belonging to the
     * current tenant and carries a userId — there is no user here, and reusing it would emit a
     * misleading `detail` to the caller.
     *
     * Deliberately 403 rather than the 404 used for out-of-scope reads/writes on an existing
     * row: creation names no existing row, so there is nothing whose existence a 403 could leak.
     */
    class ClientAdministrationForbidden :
        AuthException("Creating a client is platform administration and is not available to a goods-owner principal")

    /**
     * The request omitted a goods owner. Never defaulted: an absent selection used to resolve to
     * client 0 (SYS), silently attaching the new user to the system client.
     */
    class ClientSelectionRequired :
        AuthException("A goods owner must be selected explicitly; it is never inferred from the caller")

    /**
     * The selected goods owner exists but is not [com.karyo.auth.vo.ClientState.ACTIVE]. Enforced
     * server-side so a direct POST cannot attach a user to a retired owner just because the UI
     * happens to hide it.
     */
    class ClientNotActive(id: Long, state: String) :
        AuthException("Client $id is $state and cannot receive new users")

    /**
     * The target account is inside the caller's own goods owner but is an operations identity,
     * which a goods-owner administrator may see but not modify. Distinct from [TenantMismatch]:
     * the account demonstrably *does* belong to the caller's goods owner, so the tenancy wording
     * would send an administrator to debug `client_id` attributes for what is an authority
     * ceiling. Both refusals are 403.
     */
    class NotManageable(userId: String) :
        AuthException(
            "User $userId belongs to this goods owner but is an operations account, " +
                "which a goods-owner administrator may not manage",
        )

    /**
     * The caller asked for a principal kind it is not entitled to grant. A goods-owner
     * administrator may only mint further goods-owner principals — granting `ops` would hand the
     * new user an unscoped read/write view over every goods owner.
     */
    class PrincipalKindNotPermitted(requested: String) :
        AuthException("Principal kind '$requested' cannot be granted by this principal")
}
