package com.karyo.auth.service

import com.karyo.auth.dto.ClientResponse
import com.karyo.auth.dto.CreateUserRequest
import com.karyo.auth.dto.ResetPasswordRequest
import com.karyo.auth.exception.AuthException
import com.karyo.auth.vo.ClientState
import com.karyo.common.pagination.PaginationParams
import com.karyo.events.outbox.OutboxService
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.admin.client.resource.RoleMappingResource
import org.keycloak.admin.client.resource.RoleResource
import org.keycloak.admin.client.resource.RoleScopeResource
import org.keycloak.admin.client.resource.RolesResource
import org.keycloak.admin.client.resource.UserResource
import org.keycloak.admin.client.resource.UsersResource
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.net.URI
import jakarta.ws.rs.core.Response as JaxRsResponse

/**
 * Mockito's any()/eq() matchers return null, which fails for Kotlin non-null primitive types.
 * Use this helper to pass Mockito matchers safely in Kotlin verification calls.
 */
@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = org.mockito.ArgumentMatchers.any<T>() ?: null as T

@QuarkusTest
class UserManagementServiceTest {

    @InjectMock
    lateinit var keycloak: Keycloak

    @InjectMock
    lateinit var outboxService: OutboxService

    @InjectMock
    lateinit var tenantContext: TenantContext

    @InjectMock
    lateinit var clientService: ClientService

    @Inject
    lateinit var userManagementService: UserManagementService

    @Inject
    lateinit var roleService: RoleService

    private lateinit var realmResource: RealmResource
    private lateinit var usersResource: UsersResource
    private lateinit var rolesResource: RolesResource

    @BeforeEach
    fun setup() {
        realmResource = mock(RealmResource::class.java)
        usersResource = mock(UsersResource::class.java)
        rolesResource = mock(RolesResource::class.java)

        `when`(keycloak.realm("karyo")).thenReturn(realmResource)
        `when`(realmResource.users()).thenReturn(usersResource)
        `when`(realmResource.roles()).thenReturn(rolesResource)

        `when`(tenantContext.clientId).thenReturn(1L)
        `when`(tenantContext.tenantCode).thenReturn("ACME")
        `when`(tenantContext.username).thenReturn("admin")
        `when`(tenantContext.principalKind).thenReturn(PrincipalKind.OPS)
        `when`(clientService.requireActiveById(1L)).thenReturn(clientResponse(1L, "ACME"))
        `when`(clientService.requireActiveById(2L)).thenReturn(clientResponse(2L, "GLOBEX"))
    }

    private fun clientResponse(id: Long, number: String) = ClientResponse(
        id = id,
        name = "$number Corporation",
        number = number,
        code = number,
        email = "",
        phone = "",
        fax = "",
        state = ClientState.ACTIVE,
        isSystemClient = id == 0L,
    )

    private fun buildUserRep(
        id: String,
        username: String,
        email: String? = null,
        enabled: Boolean = true,
        clientId: String = "1",
        tenantCode: String = "ACME",
        principalKind: String = "owner",
    ): UserRepresentation {
        val user = UserRepresentation()
        user.id = id
        user.username = username
        user.email = email
        user.isEnabled = enabled
        user.attributes = mutableMapOf(
            "client_id" to listOf(clientId),
            "tenant_code" to listOf(tenantCode),
            "principal_kind" to listOf(principalKind),
        )
        return user
    }

    private fun mockUserResourceWithRoles(userResource: UserResource) {
        val roleMappingResource = mock(RoleMappingResource::class.java)
        val roleScopeResource = mock(RoleScopeResource::class.java)
        `when`(userResource.roles()).thenReturn(roleMappingResource)
        `when`(roleMappingResource.realmLevel()).thenReturn(roleScopeResource)
        `when`(roleScopeResource.listEffective()).thenReturn(emptyList())
    }

    /**
     * Verifies OutboxService.publish was invoked the expected number of times.
     * Uses invocation count instead of argument matchers to avoid Mockito/Kotlin null issues.
     */
    private fun verifyOutboxPublished(expectedTimes: Int = 1) {
        val invocations = org.mockito.Mockito.mockingDetails(outboxService).invocations
        val publishCalls = invocations.filter { it.method.name == "publish" }
        assertThat(publishCalls).hasSize(expectedTimes)
    }

    /**
     * An operations administrator provisioning a user FOR a goods owner must produce a goods-owner
     * principal. Inheriting the caller's `ops` kind would stamp the new user
     * `principal_kind=ops`, which resolves to TenantScope.Unscoped and lets a GLOBEX user read
     * and mutate every other owner's rows.
     */
    @Test
    fun `operations administrator creates owner-scoped user for selected goods owner`() {
        val request = CreateUserRequest(
            username = "newuser",
            email = "newuser@acme.com",
            firstName = "New",
            lastName = "User",
            password = "password123",
            roles = listOf("OPERATOR"),
            clientId = 2L,
            principalKind = "owner",
        )

        // Mock Keycloak create response with location header containing user ID
        val createResponse = mock(JaxRsResponse::class.java)
        `when`(createResponse.status).thenReturn(201)
        `when`(createResponse.location).thenReturn(URI.create("http://localhost:8180/admin/realms/karyo/users/new-user-id"))
        `when`(usersResource.create(anyObj())).thenReturn(createResponse)

        // Mock fetching the created user back
        val userResource = mock(UserResource::class.java)
        val createdUser = buildUserRep(
            "new-user-id",
            "newuser",
            "newuser@acme.com",
            clientId = "2",
            tenantCode = "GLOBEX",
        )
        createdUser.firstName = "New"
        createdUser.lastName = "User"
        createdUser.createdTimestamp = System.currentTimeMillis()
        `when`(usersResource.get("new-user-id")).thenReturn(userResource)
        `when`(userResource.toRepresentation()).thenReturn(createdUser)

        // Mock role assignment
        mockUserResourceWithRoles(userResource)

        val roleResource = mock(RoleResource::class.java)
        val operatorRole = RoleRepresentation()
        operatorRole.name = "OPERATOR"
        `when`(rolesResource.get("OPERATOR")).thenReturn(roleResource)
        `when`(roleResource.toRepresentation()).thenReturn(operatorRole)

        val result = userManagementService.createUser(request)

        assertThat(result.id).isEqualTo("new-user-id")
        assertThat(result.username).isEqualTo("newuser")
        assertThat(result.email).isEqualTo("newuser@acme.com")
        assertThat(result.enabled).isTrue()

        val createdUserCaptor = ArgumentCaptor.forClass(UserRepresentation::class.java)
        verify(usersResource).create(createdUserCaptor.capture())
        assertThat(createdUserCaptor.value.attributes["principal_kind"]).containsExactly("owner")
        assertThat(createdUserCaptor.value.attributes["client_id"]).containsExactly("2")
        assertThat(createdUserCaptor.value.attributes["tenant_code"]).containsExactly("GLOBEX")

        // Verify outbox event was published
        verifyOutboxPublished(1)
    }

    @Test
    fun `owner administrator creates owner-scoped users`() {
        `when`(tenantContext.principalKind).thenReturn(PrincipalKind.OWNER)
        val request = CreateUserRequest(
            username = "owneruser",
            email = "owneruser@acme.com",
            firstName = "Owner",
            lastName = "User",
            password = "password123",
            clientId = 1L,
            principalKind = "owner",
        )
        val createResponse = mock(JaxRsResponse::class.java)
        `when`(createResponse.status).thenReturn(201)
        `when`(createResponse.location).thenReturn(URI.create("http://localhost:8180/admin/realms/karyo/users/owner-user-id"))
        `when`(usersResource.create(anyObj())).thenReturn(createResponse)

        val userResource = mock(UserResource::class.java)
        val createdUser = buildUserRep("owner-user-id", "owneruser", "owneruser@acme.com")
        `when`(usersResource.get("owner-user-id")).thenReturn(userResource)
        `when`(userResource.toRepresentation()).thenReturn(createdUser)
        mockUserResourceWithRoles(userResource)

        userManagementService.createUser(request)

        val createdUserCaptor = ArgumentCaptor.forClass(UserRepresentation::class.java)
        verify(usersResource).create(createdUserCaptor.capture())
        assertThat(createdUserCaptor.value.attributes["principal_kind"]).containsExactly("owner")
    }

    @Test
    fun `owner cannot create user for another goods owner`() {
        `when`(tenantContext.principalKind).thenReturn(PrincipalKind.OWNER)
        val request = CreateUserRequest(
            username = "other-owner-user",
            email = "user@globex.com",
            firstName = "Other",
            lastName = "Owner",
            password = "password123",
            clientId = 2L,
            principalKind = "owner",
        )

        assertThatThrownBy { userManagementService.createUser(request) }
            .isInstanceOf(AuthException.ClientNotFound::class.java)
    }

    @Test
    fun `owner user listing pages through every matching tenant user`() {
        `when`(tenantContext.principalKind).thenReturn(PrincipalKind.OWNER)
        val firstPage = (1..100).map { index ->
            buildUserRep("user-$index", "operator$index")
        }
        val finalUser = buildUserRep("user-101", "operator101")
        `when`(
            usersResource.searchByAttributes(0, 100, null, false, "client_id:1"),
        ).thenReturn(firstPage)
        `when`(
            usersResource.searchByAttributes(100, 100, null, false, "client_id:1"),
        ).thenReturn(listOf(finalUser))

        val results = userManagementService.listUsers()

        assertThat(results).hasSize(101)
        assertThat(results.last().username).isEqualTo("operator101")
    }

    @Test
    fun `operations administrator searches and filters beyond the first Keycloak page`() {
        val firstPage = (1..100).map { index ->
            buildUserRep("user-$index", "operator$index", clientId = "1")
        }
        val target = buildUserRep(
            "user-101",
            "globex-specialist",
            "specialist@globex.test",
            enabled = false,
            clientId = "2",
            tenantCode = "GLOBEX",
        )
        `when`(usersResource.list(0, 100)).thenReturn(firstPage)
        `when`(usersResource.list(100, 100)).thenReturn(listOf(target))
        val pagination = PaginationParams().apply {
            page = 0
            size = 20
        }

        val result = userManagementService.listUsersPaginated(
            pagination,
            search = "specialist@globex",
            enabled = false,
        )

        assertThat(result.content.map { it.username }).containsExactly("globex-specialist")
        assertThat(result.page.totalElements).isEqualTo(1)
        assertThat(result.page.totalPages).isEqualTo(1)
    }

    @Test
    fun `operations administrator pages through users across goods owners`() {
        val firstPage = (1..99).map { index ->
            buildUserRep("user-$index", "operator$index", clientId = "1")
        } + UserRepresentation().apply { username = "service-account-karyo-admin" }
        val globexUser = buildUserRep(
            "user-100",
            "globex-operator",
            clientId = "2",
            tenantCode = "GLOBEX",
        )
        `when`(usersResource.list(0, 100)).thenReturn(firstPage)
        `when`(usersResource.list(100, 100)).thenReturn(listOf(globexUser))

        val results = userManagementService.listUsers()

        assertThat(results).hasSize(100)
        assertThat(results.last().username).isEqualTo("globex-operator")
    }

    @Test
    fun `operations administrator may grant operations authority explicitly`() {
        val request = CreateUserRequest(
            username = "opsuser",
            email = "opsuser@karyo.local",
            firstName = "Ops",
            lastName = "User",
            password = "password123",
            clientId = 1L,
            principalKind = "ops",
        )
        val createResponse = mock(JaxRsResponse::class.java)
        `when`(createResponse.status).thenReturn(201)
        `when`(createResponse.location)
            .thenReturn(URI.create("http://localhost:8180/admin/realms/karyo/users/ops-user-id"))
        `when`(usersResource.create(anyObj())).thenReturn(createResponse)

        val userResource = mock(UserResource::class.java)
        `when`(usersResource.get("ops-user-id"))
            .thenReturn(userResource)
        `when`(userResource.toRepresentation())
            .thenReturn(buildUserRep("ops-user-id", "opsuser", "opsuser@karyo.local"))
        mockUserResourceWithRoles(userResource)

        userManagementService.createUser(request)

        val createdUserCaptor = ArgumentCaptor.forClass(UserRepresentation::class.java)
        verify(usersResource).create(createdUserCaptor.capture())
        assertThat(createdUserCaptor.value.attributes["principal_kind"]).containsExactly("ops")
    }

    @Test
    fun `owner administrator cannot grant operations authority`() {
        `when`(tenantContext.principalKind).thenReturn(PrincipalKind.OWNER)
        val request = CreateUserRequest(
            username = "escalated",
            email = "escalated@acme.com",
            firstName = "Escalated",
            lastName = "User",
            password = "password123",
            clientId = 1L,
            principalKind = "ops",
        )

        assertThatThrownBy { userManagementService.createUser(request) }
            .isInstanceOf(AuthException.PrincipalKindNotPermitted::class.java)
        verify(usersResource, never()).create(anyObj())
    }

    @Test
    fun `an absent principal kind is rejected rather than inherited from the caller`() {
        val request = CreateUserRequest(
            username = "unspecified",
            email = "unspecified@acme.com",
            firstName = "Unspecified",
            lastName = "User",
            password = "password123",
            clientId = 1L,
            principalKind = null,
        )

        assertThatThrownBy { userManagementService.createUser(request) }
            .isInstanceOf(AuthException.PrincipalKindNotPermitted::class.java)
        verify(usersResource, never()).create(anyObj())
    }

    @Test
    fun `an absent goods owner is rejected rather than defaulted to the system client`() {
        val request = CreateUserRequest(
            username = "ownerless",
            email = "ownerless@acme.com",
            firstName = "Ownerless",
            lastName = "User",
            password = "password123",
            clientId = null,
            principalKind = "owner",
        )

        assertThatThrownBy { userManagementService.createUser(request) }
            .isInstanceOf(AuthException.ClientSelectionRequired::class.java)
        verify(usersResource, never()).create(anyObj())
    }

    @Test
    fun `a retired goods owner cannot receive new users`() {
        `when`(clientService.requireActiveById(2L))
            .thenThrow(AuthException.ClientNotActive(2L, "INACTIVE"))
        val request = CreateUserRequest(
            username = "retired",
            email = "retired@globex.com",
            firstName = "Retired",
            lastName = "User",
            password = "password123",
            clientId = 2L,
            principalKind = "owner",
        )

        assertThatThrownBy { userManagementService.createUser(request) }
            .isInstanceOf(AuthException.ClientNotActive::class.java)
        verify(usersResource, never()).create(anyObj())
    }

    /**
     * Keycloak pages a live result set: `first` advances by page size, so a user created between
     * two fetches shifts the window and repeats an already-seen representation. Accumulating it
     * verbatim would inflate the total and render the row twice.
     */
    @Test
    fun `paging tolerates a shifting result set without duplicating users`() {
        `when`(tenantContext.principalKind).thenReturn(PrincipalKind.OWNER)
        val firstPage = (1..100).map { index -> buildUserRep("user-$index", "operator$index") }
        val shifted = listOf(
            buildUserRep("user-100", "operator100"),
            buildUserRep("user-101", "operator101"),
        )
        `when`(usersResource.searchByAttributes(0, 100, null, false, "client_id:1"))
            .thenReturn(firstPage)
        `when`(usersResource.searchByAttributes(100, 100, null, false, "client_id:1"))
            .thenReturn(shifted)

        val results = userManagementService.listUsers()

        assertThat(results).hasSize(101)
        assertThat(results.map { it.id }).doesNotHaveDuplicates()
    }

    @Test
    fun `deactivateUser disables user in Keycloak and publishes event`() {
        val userResource = mock(UserResource::class.java)
        val existingUser = buildUserRep("user-1", "operator1", "op1@acme.com")
        `when`(usersResource.get("user-1")).thenReturn(userResource)
        `when`(userResource.toRepresentation()).thenReturn(existingUser)

        mockUserResourceWithRoles(userResource)

        val result = userManagementService.deactivateUser("user-1")

        assertThat(result.enabled).isFalse()

        // Verify the user was updated in Keycloak
        verify(userResource, times(1)).update(anyObj())

        // Verify outbox event was published
        verifyOutboxPublished(1)
    }

    @Test
    fun `getUser from different tenant throws TenantMismatch`() {
        `when`(tenantContext.principalKind).thenReturn(PrincipalKind.OWNER)
        val userResource = mock(UserResource::class.java)
        val otherTenantUser = buildUserRep("user-2", "other", clientId = "2", tenantCode = "GLOBEX")
        `when`(usersResource.get("user-2")).thenReturn(userResource)
        `when`(userResource.toRepresentation()).thenReturn(otherTenantUser)

        assertThatThrownBy { userManagementService.getUser("user-2") }
            .isInstanceOf(AuthException.TenantMismatch::class.java)
    }

    @Test
    fun `owner administrator cannot reset an operations identity password`() {
        `when`(tenantContext.principalKind).thenReturn(PrincipalKind.OWNER)
        val userResource = mock(UserResource::class.java)
        val operationsUser = buildUserRep("ops-1", "ops-admin", principalKind = "ops")
        `when`(usersResource.get("ops-1")).thenReturn(userResource)
        `when`(userResource.toRepresentation()).thenReturn(operationsUser)

        assertThatThrownBy {
            userManagementService.resetPassword(
                "ops-1",
                ResetPasswordRequest("replacement-password", temporary = true),
            )
        }.isInstanceOf(AuthException.NotManageable::class.java)
        verify(userResource, never()).resetPassword(anyObj())
    }

    @Test
    fun `owner administrator cannot change roles on an operations identity`() {
        `when`(tenantContext.principalKind).thenReturn(PrincipalKind.OWNER)
        val userResource = mock(UserResource::class.java)
        val operationsUser = buildUserRep("ops-1", "ops-admin", principalKind = "ops")
        `when`(usersResource.get("ops-1")).thenReturn(userResource)
        `when`(userResource.toRepresentation()).thenReturn(operationsUser)

        assertThatThrownBy { roleService.assignRole("ops-1", "VIEWER") }
            .isInstanceOf(AuthException.NotManageable::class.java)
        verify(userResource, never()).roles()
    }
}
