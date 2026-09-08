package com.karyo.auth.service

import com.karyo.auth.dto.ClientResponse
import com.karyo.auth.dto.CreateUserRequest
import com.karyo.auth.dto.ResetPasswordRequest
import com.karyo.auth.dto.UpdateUserRequest
import com.karyo.auth.dto.UserResponse
import com.karyo.auth.event.UserCreatedEvent
import com.karyo.auth.event.UserDeactivatedEvent
import com.karyo.auth.event.UserReactivatedEvent
import com.karyo.auth.exception.AuthException
import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.paginatedResponse
import com.karyo.events.outbox.OutboxService
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import com.karyo.security.readScope
import com.karyo.security.writeScope
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.UserRepresentation

/**
 * Thin proxy over the Keycloak Admin API for owner-aware user management.
 * Operations administrators may manage all goods owners; owner administrators remain scoped.
 * All identity storage is delegated to Keycloak; there is no user table in auth-service.
 */
@ApplicationScoped
class UserManagementService(
    private val keycloak: Keycloak,
    private val outboxService: OutboxService,
    private val tenantContext: TenantContext,
    private val clientService: ClientService,
) {
    private val log = Logger.getLogger(UserManagementService::class.java)

    companion object {
        const val REALM = "karyo"
        private const val KEYCLOAK_PAGE_SIZE = 100
    }

    // @Transactional is required for the outbox write. The Keycloak Admin API calls inside are
    // external HTTP and NOT rolled back if the DB write fails — acceptable known limitation of
    // the thin-proxy design (Keycloak-side creation may survive a failed outbox insert).
    @Transactional
    fun createUser(request: CreateUserRequest): UserResponse {
        val grantedKind = grantablePrincipalKind(request.principalKind)
        val targetClient = authorizedTargetClient(request, grantedKind)

        val userRep = userRepresentation(request, targetClient, grantedKind)
        val response = keycloak.realm(REALM).users().create(userRep)
        if (response.status != 201) {
            val body = response.readEntity(String::class.java)
            throw AuthException.KeycloakAdminError("Failed to create user: $body")
        }

        val userId = response.location.path.substringAfterLast("/")

        // Assign requested roles
        if (request.roles.isNotEmpty()) {
            val realmRoles = keycloak.realm(REALM).roles()
            val roleReps = request.roles.mapNotNull { roleName ->
                try {
                    realmRoles.get(roleName).toRepresentation()
                } catch (e: Exception) {
                    log.warn("Role $roleName not found during user creation, skipping: ${e.message}")
                    null
                }
            }
            if (roleReps.isNotEmpty()) {
                keycloak.realm(REALM).users().get(userId).roles().realmLevel().add(roleReps)
            }
        }

        // Publish event via outbox
        outboxService.publish(
            aggregateType = "User",
            aggregateId = 0L,
            eventType = "UserCreated",
            payload = UserCreatedEvent(
                userId = userId,
                username = request.username,
                email = request.email,
                tenantId = targetClient.id,
                roles = request.roles,
            ),
            tenantId = targetClient.id,
        )

        log.info("Created user ${request.username} (id=$userId) for client ${targetClient.number}")
        return getUser(userId)
    }

    /**
     * Resolves the goods owner the new user is attached to.
     *
     * The selection is never inherited and never defaulted: an omitted `clientId` used to arrive
     * as Jackson's `0L` and silently attach the user to the SYS system client. An owner principal
     * is bound to its own client by [writeScope]; only an operations principal can name another,
     * and that cross-owner act is logged.
     */
    private fun authorizedTargetClient(
        request: CreateUserRequest,
        grantedKind: PrincipalKind,
    ): ClientResponse {
        val targetClientId = request.clientId ?: throw AuthException.ClientSelectionRequired()
        if (!tenantContext.writeScope().permits(targetClientId)) {
            throw AuthException.ClientNotFound(targetClientId)
        }
        val targetClient = clientService.requireActiveById(targetClientId)
        if (targetClientId != tenantContext.clientId) {
            log.info(
                "Cross-owner user provisioning: ${tenantContext.username} " +
                    "(${tenantContext.principalKind.name.lowercase()}, client ${tenantContext.clientId}) " +
                    "creating ${request.username} as ${grantedKind.name.lowercase()} " +
                    "for client ${targetClient.number}",
            )
        }
        return targetClient
    }

    private fun userRepresentation(
        request: CreateUserRequest,
        targetClient: ClientResponse,
        grantedKind: PrincipalKind,
    ): UserRepresentation {
        val attrs = mutableMapOf(
            "client_id" to listOf(targetClient.id.toString()),
            "tenant_code" to listOf(targetClient.number),
            "principal_kind" to listOf(grantedKind.name.lowercase()),
        )
        request.warehouseId?.let { wid ->
            attrs["warehouse_id"] = listOf(wid)
        }
        val credential = CredentialRepresentation()
        credential.type = CredentialRepresentation.PASSWORD
        credential.value = request.password
        credential.isTemporary = request.forcePasswordChange
        return UserRepresentation().apply {
            username = request.username
            email = request.email
            firstName = request.firstName
            lastName = request.lastName
            isEnabled = true
            attributes = attrs
            credentials = listOf(credential)
        }
    }

    /**
     * Resolves the `principal_kind` the new user will carry.
     *
     * Parsing is strict rather than going through [PrincipalKind.fromClaim], which is fail-closed
     * for *tokens* (an unknown value means OWNER) — an unrecognized value in a request body is a
     * caller mistake and must be a 400, not a silent downgrade.
     *
     * Authority: an OPS principal may mint either kind; an OWNER principal may only mint further
     * OWNER principals. Granting OPS hands the new user [com.karyo.security.TenantScope.Unscoped]
     * reads and writes over every goods owner, which is exactly the escalation this check exists
     * to prevent — the value must never be inherited from whoever is calling.
     */
    private fun grantablePrincipalKind(requested: String?): PrincipalKind {
        val raw = requested?.trim()?.lowercase()
        val kind = when (raw) {
            "ops" -> PrincipalKind.OPS.takeIf { tenantContext.principalKind == PrincipalKind.OPS }
            "owner" -> PrincipalKind.OWNER
            else -> null
        }
        return kind ?: throw AuthException.PrincipalKindNotPermitted(raw ?: "<absent>")
    }

    fun listUsers(): List<UserResponse> = visibleUsers().map { toUserResponse(it) }

    /**
     * Paginated user listing. Keycloak Admin API does not support server-side sorting on
     * searchByAttributes, so sorting is applied client-side after fetching all visible users.
     * Owner principals see their own client; operations principals see users across clients.
     */
    fun listUsersPaginated(
        pagination: PaginationParams,
        search: String? = null,
        enabled: Boolean? = null,
    ): PaginatedResponse<UserResponse> {
        val query = search?.trim()?.takeIf(String::isNotEmpty)
        val visibleUsers = visibleUsers().filter { user ->
            (enabled == null || user.isEnabled == enabled) &&
                (query == null || listOf(
                    user.username,
                    user.email,
                    user.firstName,
                    user.lastName,
                ).any { value -> value?.contains(query, ignoreCase = true) == true })
        }
        val totalElements = visibleUsers.size.toLong()

        val sorted = applySorting(visibleUsers, pagination.sort)

        val startIndex = pagination.page * pagination.size
        val endIndex = minOf(startIndex + pagination.size, sorted.size)
        val pageContent = if (startIndex < sorted.size) sorted.subList(startIndex, endIndex) else emptyList()

        val content = pageContent.map { toUserResponse(it) }
        return paginatedResponse(content, pagination.page, pagination.size, totalElements)
    }

    private fun applySorting(users: List<UserRepresentation>, sortParams: List<String>?): List<UserRepresentation> {
        if (sortParams.isNullOrEmpty()) return users.sortedBy { it.username?.lowercase() }
        val param = sortParams.first()
        val parts = param.split(",", limit = 2)
        val field = parts[0].trim()
        val descending = parts.size > 1 && parts[1].trim().equals("desc", ignoreCase = true)

        val comparator: Comparator<UserRepresentation> = when (field) {
            "username" -> compareBy { it.username?.lowercase() }
            "email" -> compareBy { it.email?.lowercase() }
            "firstName" -> compareBy { it.firstName?.lowercase() }
            "lastName" -> compareBy { it.lastName?.lowercase() }
            "enabled" -> compareBy { it.isEnabled }
            "createdTimestamp" -> compareBy { it.createdTimestamp }
            else -> compareBy { it.username?.lowercase() }
        }
        return if (descending) users.sortedWith(comparator.reversed()) else users.sortedWith(comparator)
    }

    fun getUser(userId: String): UserResponse {
        val userRep = fetchWithinReadScope(userId)
        return toUserResponse(userRep, userId)
    }

    fun updateUser(userId: String, request: UpdateUserRequest): UserResponse {
        val userRep = fetchAndVerifyTenant(userId)
        val userClientId = requireUserClientId(userRep, userId)
        val userResource = keycloak.realm(REALM).users().get(userId)

        request.email?.let { userRep.email = it }
        request.firstName?.let { userRep.firstName = it }
        request.lastName?.let { userRep.lastName = it }
        request.warehouseId?.let {
            val currentAttrs = userRep.attributes ?: mutableMapOf()
            val mutableAttrs = currentAttrs.toMutableMap()
            mutableAttrs["warehouse_id"] = listOf(it)
            userRep.attributes = mutableAttrs
        }

        userResource.update(userRep)
        log.info("Updated user $userId for client $userClientId")
        return toUserResponse(userRep, userId)
    }

    // @Transactional for the outbox write; Keycloak update itself is external (not rolled back).
    @Transactional
    fun deactivateUser(userId: String): UserResponse {
        val userRep = fetchAndVerifyTenant(userId)
        val userClientId = requireUserClientId(userRep, userId)
        val userResource = keycloak.realm(REALM).users().get(userId)

        userRep.isEnabled = false
        userResource.update(userRep)

        outboxService.publish(
            aggregateType = "User",
            aggregateId = 0L,
            eventType = "UserDeactivated",
            payload = UserDeactivatedEvent(
                userId = userId,
                username = userRep.username,
                tenantId = userClientId,
                reason = null,
            ),
            tenantId = userClientId,
        )

        log.info("Deactivated user $userId for client $userClientId")
        return toUserResponse(userRep, userId)
    }

    // @Transactional for the outbox write; Keycloak update itself is external (not rolled back).
    @Transactional
    fun reactivateUser(userId: String): UserResponse {
        val userRep = fetchAndVerifyTenant(userId)
        val userClientId = requireUserClientId(userRep, userId)
        val userResource = keycloak.realm(REALM).users().get(userId)

        userRep.isEnabled = true
        userResource.update(userRep)

        outboxService.publish(
            aggregateType = "User",
            aggregateId = 0L,
            eventType = "UserReactivated",
            payload = UserReactivatedEvent(
                userId = userId,
                username = userRep.username,
                tenantId = userClientId,
            ),
            tenantId = userClientId,
        )

        log.info("Reactivated user $userId for client $userClientId")
        return toUserResponse(userRep, userId)
    }

    fun resetPassword(userId: String, request: ResetPasswordRequest) {
        fetchAndVerifyTenant(userId)
        val userResource = keycloak.realm(REALM).users().get(userId)

        val credential = CredentialRepresentation().apply {
            type = CredentialRepresentation.PASSWORD
            value = request.newPassword
            isTemporary = request.temporary
        }
        userResource.resetPassword(credential)
        log.info("Reset password for user $userId")
    }

    /**
     * Every Keycloak user the calling principal may manage.
     *
     * An OWNER principal is narrowed server-side to its own goods owner through the
     * `client_id` attribute query. An OPS principal has no such predicate, so this walks the
     * whole realm one [KEYCLOAK_PAGE_SIZE] page at a time and discards owner-less accounts
     * (service accounts) afterwards: one Admin API round trip per page of realm users, on every
     * listing request. Search, `enabled` filtering, sorting and pagination are then applied in
     * memory by the callers. That is acceptable under silo tenancy, where a realm holds one
     * company's staff; pushing `search`/`enabled` into the Keycloak query is the lever if a
     * realm ever grows past that.
     */
    private fun visibleUsers(): List<UserRepresentation> {
        val users = keycloak.realm(REALM).users()
        return if (tenantContext.principalKind == PrincipalKind.OPS) {
            pagedUsers { first, size -> users.list(first, size) }
                .filter { userClientId(it) != null }
        } else {
            // Scoped by the client_id attribute query alone. `canManageUser` is a WRITE
            // predicate built on writeScope(); filtering this read with it also hid every
            // ops-kind account inside the caller's own client, so an owner administrator
            // whose client holds only such accounts saw an empty page. Visibility is decided
            // here by client scoping; the authority to MUTATE any row that comes back is
            // still decided by requireManageableUser on each mutation path, unchanged.
            val query = "client_id:${tenantContext.clientId}"
            pagedUsers { first, size ->
                users.searchByAttributes(first, size, null, false, query)
            }
        }
    }

    /**
     * Exhausts a Keycloak listing endpoint, terminating on the first short page.
     *
     * De-duplication is not defensive padding: `first` advances by page size against a *live*
     * result set, so a user created or renamed between two page fetches shifts the window and
     * pushes an already-seen representation into the next page. Accumulating those verbatim
     * inflates `totalElements` and renders the same row twice. Identity is the Keycloak user id;
     * an id-less representation (never produced by the list endpoints, but cheap to tolerate) is
     * kept as-is rather than collapsed onto a shared null key.
     */
    private fun pagedUsers(
        fetchPage: (first: Int, size: Int) -> List<UserRepresentation>,
    ): List<UserRepresentation> {
        val byId = LinkedHashMap<String, UserRepresentation>()
        val withoutId = mutableListOf<UserRepresentation>()
        var first = 0
        do {
            val page = fetchPage(first, KEYCLOAK_PAGE_SIZE)
            for (user in page) {
                val id = user.id
                if (id == null) withoutId.add(user) else byId.putIfAbsent(id, user)
            }
            first += page.size
        } while (page.size == KEYCLOAK_PAGE_SIZE)
        return byId.values + withoutId
    }

    private fun fetchUser(userId: String): UserRepresentation {
        val userResource = keycloak.realm(REALM).users().get(userId)
        return try {
            userResource.toRepresentation()
        } catch (@Suppress("SwallowedException") e: jakarta.ws.rs.NotFoundException) {
            throw AuthException.UserNotFound(userId)
        }
    }

    /**
     * Mutation path. Unchanged: every caller that writes still goes through
     * [com.karyo.security.TenantContext] and [requireManageableUser], so the authority to modify
     * a user is decided exactly as before.
     */
    private fun fetchAndVerifyTenant(userId: String): UserRepresentation {
        val userRep = fetchUser(userId)
        tenantContext.requireManageableUser(userRep, userId)
        return userRep
    }

    /**
     * Read path, scoped the same way the listing is.
     *
     * `requireManageableUser` is a WRITE predicate; using it here meant a row the listing
     * returned could not be opened, because an owner administrator may see an ops-kind account
     * in its own client without being allowed to modify it. Scoping is still enforced and is not
     * merely dropped: [readScope] is `Owner(clientId)` for an owner principal, so fetching a user
     * in another client is refused exactly as before, and a representation carrying no
     * `client_id` at all (a service account) is never readable.
     */
    private fun fetchWithinReadScope(userId: String): UserRepresentation {
        val userRep = fetchUser(userId)
        val targetClientId = userClientId(userRep) ?: throw AuthException.TenantMismatch(userId)
        if (!tenantContext.readScope().permits(targetClientId)) {
            throw AuthException.TenantMismatch(userId)
        }
        return userRep
    }

    private fun requireUserClientId(user: UserRepresentation, userId: String): Long =
        userClientId(user) ?: throw AuthException.TenantMismatch(userId)

    private fun toUserResponse(user: UserRepresentation, userId: String? = null): UserResponse {
        val id = userId ?: user.id
        val roles = try {
            if (id != null) {
                keycloak.realm(REALM).users().get(id).roles().realmLevel()
                    .listEffective()
                    .map { it.name }
                    .filter { !it.startsWith("default-roles-") && !it.startsWith("uma_") && it != "offline_access" }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            log.debug("Could not fetch roles for user $id", e)
            emptyList()
        }

        return UserResponse(
            id = id ?: "",
            username = user.username,
            email = user.email,
            firstName = user.firstName,
            lastName = user.lastName,
            enabled = user.isEnabled,
            roles = roles,
            tenantCode = user.attributes?.get("tenant_code")?.firstOrNull(),
            warehouseId = user.attributes?.get("warehouse_id")?.firstOrNull(),
            createdTimestamp = user.createdTimestamp,
        )
    }
}
