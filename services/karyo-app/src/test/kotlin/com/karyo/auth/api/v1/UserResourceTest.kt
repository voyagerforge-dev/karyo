package com.karyo.auth.api.v1

import com.karyo.auth.dto.CreateUserRequest
import com.karyo.auth.dto.UserResponse
import com.karyo.auth.service.RoleService
import com.karyo.auth.service.UserManagementService
import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PageMetadata
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = org.mockito.ArgumentMatchers.any<T>() ?: null as T

@QuarkusTest
class UserResourceTest {

    @InjectMock
    lateinit var userManagementService: UserManagementService

    @InjectMock
    lateinit var roleService: RoleService

    private fun sampleUserResponse(
        id: String = "user-123",
        username: String = "testuser",
        email: String = "test@acme.com",
        enabled: Boolean = true,
    ) = UserResponse(
        id = id,
        username = username,
        email = email,
        firstName = "Test",
        lastName = "User",
        enabled = enabled,
        roles = listOf("OPERATOR"),
        tenantCode = "ACME",
        warehouseId = "WH-001",
        createdTimestamp = System.currentTimeMillis(),
    )

    private fun validCreateRequest() = CreateUserRequest(
        username = "newuser",
        email = "newuser@acme.com",
        firstName = "New",
        lastName = "User",
        password = "password123",
        clientId = 1L,
        principalKind = "owner",
    )

    // ── 1. Admin can create user ───────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `admin can create user - 201`() {
        doReturn(sampleUserResponse()).`when`(userManagementService).createUser(anyObj())

        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/users")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("username", `is`("testuser"))
            .body("enabled", `is`(true))
    }

    // ── 2. Admin can list users ────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `admin can list users - 200`() {
        val paginated = PaginatedResponse(
            content = listOf(sampleUserResponse()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(userManagementService)
            .listUsersPaginated(anyObj(), anyObj(), anyObj())

        given()
            .`when`().get("/api/v1/users")
            .then()
            .statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].username", `is`("testuser"))
            .body("page.totalElements", `is`(1))
    }

    // ── 3. Admin can get user by ID ────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `admin can get user by ID - 200`() {
        doReturn(sampleUserResponse()).`when`(userManagementService).getUser("user-123")

        given()
            .`when`().get("/api/v1/users/user-123")
            .then()
            .statusCode(200)
            .body("id", `is`("user-123"))
            .body("username", `is`("testuser"))
    }

    // ── 4. Admin can deactivate user ───────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `admin can deactivate user - 200`() {
        doReturn(sampleUserResponse(enabled = false)).`when`(userManagementService).deactivateUser("user-123")

        given()
            .`when`().put("/api/v1/users/user-123/deactivate")
            .then()
            .statusCode(200)
            .body("enabled", `is`(false))
    }

    // ── 5. Admin can assign role ───────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `admin can assign role - 200`() {
        doNothing().`when`(roleService).assignRole("user-123", "VIEWER")

        given()
            .`when`().put("/api/v1/users/user-123/roles?action=assign&role=VIEWER")
            .then()
            .statusCode(200)
    }

    // ── 6. Operator cannot create user ─────────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `operator without user-admin role gets 403`() {
        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/users")
            .then()
            .statusCode(403)
    }

    // ── 7. Unauthenticated request rejected ────────────────────────────

    @Test
    fun `unauthenticated request gets 401`() {
        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/users")
            .then()
            .statusCode(401)
    }

    // ── 8. Validation rejects invalid input ────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `validation rejects empty username - 400`() {
        given()
            .contentType(ContentType.JSON)
            .body(CreateUserRequest(
                username = "",
                email = "test@acme.com",
                firstName = "Test",
                lastName = "User",
                password = "password123",
                clientId = 1L,
                principalKind = "owner",
            ))
            .`when`().post("/api/v1/users")
            .then()
            .statusCode(400)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `validation rejects an omitted goods owner - 400`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "username" to "ownerless",
                    "email" to "ownerless@acme.com",
                    "firstName" to "Ownerless",
                    "lastName" to "User",
                    "password" to "password123",
                    "principalKind" to "owner",
                ),
            )
            .`when`().post("/api/v1/users")
            .then()
            .statusCode(400)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `validation rejects an omitted principal kind - 400`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "username" to "kindless",
                    "email" to "kindless@acme.com",
                    "firstName" to "Kindless",
                    "lastName" to "User",
                    "password" to "password123",
                    "clientId" to 1,
                ),
            )
            .`when`().post("/api/v1/users")
            .then()
            .statusCode(400)
    }
}
