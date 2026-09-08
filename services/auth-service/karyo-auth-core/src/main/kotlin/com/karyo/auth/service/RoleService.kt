package com.karyo.auth.service

import com.karyo.auth.event.RoleAssignedEvent
import com.karyo.auth.event.RoleRevokedEvent
import com.karyo.auth.exception.AuthException
import com.karyo.events.outbox.OutboxService
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import org.keycloak.admin.client.Keycloak

/**
 * Manages role assignment/revocation for Keycloak users with tenant scoping.
 */
@ApplicationScoped
class RoleService(
    private val keycloak: Keycloak,
    private val outboxService: OutboxService,
    private val tenantContext: TenantContext,
) {
    private val log = Logger.getLogger(RoleService::class.java)

    companion object {
        const val REALM = "karyo"
    }

    // @Transactional for the outbox write; the Keycloak role change is external (not rolled back).
    @Transactional
    fun assignRole(userId: String, roleName: String) {
        val userClientId = verifyTenant(userId)

        val roleRep = try {
            keycloak.realm(REALM).roles().get(roleName).toRepresentation()
        } catch (@Suppress("SwallowedException") e: jakarta.ws.rs.NotFoundException) {
            throw AuthException.RoleNotFound(roleName)
        }

        keycloak.realm(REALM).users().get(userId).roles().realmLevel().add(listOf(roleRep))

        outboxService.publish(
            aggregateType = "User",
            aggregateId = 0L,
            eventType = "RoleAssigned",
            payload = RoleAssignedEvent(
                userId = userId,
                username = getUserUsername(userId),
                roleName = roleName,
                tenantId = userClientId,
            ),
            tenantId = userClientId,
        )

        log.info("Assigned role $roleName to user $userId")
    }

    // @Transactional for the outbox write; the Keycloak role change is external (not rolled back).
    @Transactional
    fun revokeRole(userId: String, roleName: String) {
        val userClientId = verifyTenant(userId)

        val roleRep = try {
            keycloak.realm(REALM).roles().get(roleName).toRepresentation()
        } catch (@Suppress("SwallowedException") e: jakarta.ws.rs.NotFoundException) {
            throw AuthException.RoleNotFound(roleName)
        }

        keycloak.realm(REALM).users().get(userId).roles().realmLevel().remove(listOf(roleRep))

        outboxService.publish(
            aggregateType = "User",
            aggregateId = 0L,
            eventType = "RoleRevoked",
            payload = RoleRevokedEvent(
                userId = userId,
                username = getUserUsername(userId),
                roleName = roleName,
                tenantId = userClientId,
            ),
            tenantId = userClientId,
        )

        log.info("Revoked role $roleName from user $userId")
    }

    fun listUserRoles(userId: String): List<String> {
        verifyTenant(userId)
        return keycloak.realm(REALM).users().get(userId).roles().realmLevel()
            .listEffective()
            .map { it.name }
            .filter { !it.startsWith("default-roles-") && !it.startsWith("uma_") && it != "offline_access" }
    }

    private fun verifyTenant(userId: String): Long {
        val userRep = try {
            keycloak.realm(REALM).users().get(userId).toRepresentation()
        } catch (@Suppress("SwallowedException") e: jakarta.ws.rs.NotFoundException) {
            throw AuthException.UserNotFound(userId)
        }

        return tenantContext.requireManageableUser(userRep, userId)
    }

    private fun getUserUsername(userId: String): String {
        return try {
            keycloak.realm(REALM).users().get(userId).toRepresentation().username
        } catch (e: Exception) {
            log.debug("Could not fetch username for user $userId", e)
            "unknown"
        }
    }
}
