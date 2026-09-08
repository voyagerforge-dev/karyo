package com.karyo.auth.service

import com.karyo.auth.dto.ClientResponse
import com.karyo.auth.dto.CreateUserRequest
import com.karyo.auth.vo.ClientState
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.admin.client.resource.RoleMappingResource
import org.keycloak.admin.client.resource.RoleScopeResource
import org.keycloak.admin.client.resource.RolesResource
import org.keycloak.admin.client.resource.UserResource
import org.keycloak.admin.client.resource.UsersResource
import org.keycloak.representations.idm.UserRepresentation
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.URI
import jakarta.ws.rs.core.Response as JaxRsResponse

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = org.mockito.ArgumentMatchers.any<T>() ?: null as T

/**
 * Regression test for the missing-@Transactional bug: UserManagementService.createUser
 * writes an outbox row via the REAL OutboxService (not mocked). Without @Transactional
 * on the service method, persisting the OutboxEvent throws TransactionRequiredException
 * and the endpoint returned 500.
 */
@QuarkusTest
class UserManagementServiceTransactionTest {

    @InjectMock
    lateinit var keycloak: Keycloak

    @InjectMock
    lateinit var tenantContext: TenantContext

    @InjectMock
    lateinit var clientService: ClientService

    @Inject
    lateinit var userManagementService: UserManagementService

    @Inject
    lateinit var outboxEventRepository: OutboxEventRepository

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
        `when`(clientService.requireActiveById(1L)).thenReturn(
            ClientResponse(
                id = 1L,
                name = "ACME Corporation",
                number = "ACME",
                code = "ACME",
                email = "",
                phone = "",
                fax = "",
                state = ClientState.ACTIVE,
                isSystemClient = false,
            ),
        )
    }

    @Test
    fun `createUser persists real outbox event without TransactionRequiredException`() {
        val request = CreateUserRequest(
            username = "txuser",
            email = "txuser@acme.com",
            firstName = "Tx",
            lastName = "User",
            password = "password123",
            clientId = 1L,
            principalKind = "owner",
        )

        // Mock Keycloak create response with location header containing user ID
        val createResponse = mock(JaxRsResponse::class.java)
        `when`(createResponse.status).thenReturn(201)
        `when`(createResponse.location)
            .thenReturn(URI.create("http://localhost:8180/admin/realms/karyo/users/tx-user-id"))
        `when`(usersResource.create(anyObj())).thenReturn(createResponse)

        // Mock fetching the created user back
        val userResource = mock(UserResource::class.java)
        val createdUser = UserRepresentation().apply {
            id = "tx-user-id"
            username = "txuser"
            email = "txuser@acme.com"
            isEnabled = true
            attributes = mutableMapOf(
                "client_id" to listOf("1"),
                "tenant_code" to listOf("ACME"),
            )
            createdTimestamp = System.currentTimeMillis()
        }
        `when`(usersResource.get("tx-user-id")).thenReturn(userResource)
        `when`(userResource.toRepresentation()).thenReturn(createdUser)

        val roleMappingResource = mock(RoleMappingResource::class.java)
        val roleScopeResource = mock(RoleScopeResource::class.java)
        `when`(userResource.roles()).thenReturn(roleMappingResource)
        `when`(roleMappingResource.realmLevel()).thenReturn(roleScopeResource)
        `when`(roleScopeResource.listEffective()).thenReturn(emptyList())

        val countBefore = outboxEventRepository.count("eventType", "UserCreated")

        val result = userManagementService.createUser(request)

        assertThat(result.username).isEqualTo("txuser")
        val countAfter = outboxEventRepository.count("eventType", "UserCreated")
        assertThat(countAfter).isEqualTo(countBefore + 1)
    }
}
