package com.karyo.auth.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.app.auth.KeycloakTestGrants
import com.karyo.app.auth.KeycloakTestResource
import com.karyo.auth.dto.CreateUserRequest
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.util.Base64

@QuarkusTest
@QuarkusTestResource(KeycloakTestResource::class, restrictToAnnotatedClass = true)
class UserManagementPrincipalKindIT {

    private val mapper = ObjectMapper()

    /**
     * A user provisioned FOR a goods owner must reach the token endpoint carrying
     * `principal_kind=owner`. Inheriting the operations administrator's own kind would issue a
     * token that resolves to TenantScope.Unscoped, so an ACME operator would read and mutate
     * every other goods owner's rows.
     */
    @Test
    @TestSecurity(user = "bootstrap-owner", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `goods-owner user provisioned by an operations administrator is owner-scoped`() {
        val username = "e2e-karyo-created-owner"
        val password = "test-created-user-password-9Vz4qP"
        createUser(username, password, principalKind = "owner").then().statusCode(201)

        val claims = tokenClaims(username, password)

        assertThat(claims.path("principal_kind").asText()).isEqualTo("owner")
        assertThat(claims.path("client_id").asLong()).isEqualTo(1L)
        assertThat(claims.path("tenant_code").asText()).isEqualTo("ACME")
    }

    @Test
    @TestSecurity(user = "bootstrap-owner", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `operations authority is granted only when explicitly selected`() {
        val username = "e2e-karyo-created-ops"
        val password = "test-created-user-password-4Rt7wN"
        createUser(username, password, principalKind = "ops").then().statusCode(201)

        assertThat(tokenClaims(username, password).path("principal_kind").asText()).isEqualTo("ops")
    }

    /**
     * The owner administrator's own kind is the ceiling on what it may grant: an OWNER caller
     * asking for `ops` is a privilege escalation, not a preference.
     */
    @Test
    @TestSecurity(user = "acme-owner-admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
            Claim(key = "principal_kind", value = "owner"),
        ],
    )
    fun `owner administrator cannot grant operations authority`() {
        createUser("e2e-karyo-escalated", "test-created-user-password-2Bs6yH", principalKind = "ops")
            .then()
            .statusCode(403)
    }

    /**
     * The listing must return the caller's own client's users, ops-kind accounts included.
     *
     * This is the regression that reached a pushed head: the listing was filtered with
     * `canManageUser`, a WRITE predicate, so an owner administrator whose client contains only
     * ops-kind accounts got `content: []`. Only the Pact provider job exercised this endpoint,
     * and the provider tests no-op without a broker, so the ordinary suite never noticed. This
     * test runs in the ordinary suite and fails fast on an empty page.
     */
    @Test
    @TestSecurity(user = "sys-owner-admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
        ],
    )
    fun `owner administrator sees the users of its own client including ops-kind accounts`() {
        val body = given().get("/api/v1/users?page=0&size=20").then().statusCode(200).extract().body().asString()
        val listed = mapper.readTree(body).path("content")

        assertThat(listed).isNotEmpty()
        assertThat(listed.map { it.path("username").asText() }).contains("admin")
    }

    /**
     * Separating the read from the write must not widen it across clients: scoping is still the
     * `client_id` attribute query, so an ACME administrator never sees SYS or GLOBEX accounts.
     */
    @Test
    @TestSecurity(user = "acme-owner-admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
            Claim(key = "principal_kind", value = "owner"),
        ],
    )
    fun `owner administrator listing stays scoped to its own client`() {
        val body = given().get("/api/v1/users?page=0&size=20").then().statusCode(200).extract().body().asString()
        val usernames = mapper.readTree(body).path("content").map { it.path("username").asText() }

        assertThat(usernames).isNotEmpty()
        // admin is client 0 (SYS) and tenant2-operator is client 2 (GLOBEX).
        assertThat(usernames).doesNotContain("admin", "tenant2-operator")
    }

    /**
     * The write half must stay exactly as locked as it was. Making ops-kind accounts VISIBLE to
     * an owner administrator must not make them MUTABLE: `requireManageableUser` still rejects
     * the mutation. Remove that guard and this test fails.
     */
    @Test
    @TestSecurity(user = "sys-owner-admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
        ],
    )
    fun `owner administrator still cannot mutate an ops-kind user it can now see`() {
        val body = given().get("/api/v1/users?page=0&size=20").then().statusCode(200).extract().body().asString()
        val adminUser = mapper.readTree(body).path("content").first { it.path("username").asText() == "admin" }
        val adminId = adminUser.path("id").asText()

        // Visible on the read path...
        assertThat(adminId).isNotBlank()

        // ...and still refused on every write path.
        given().put("/api/v1/users/$adminId/deactivate").then().statusCode(403)
    }

    /**
     * The listing and the single-user read must agree. Separating the listing from the write
     * predicate made ops-kind rows visible; the detail read still went through
     * `requireManageableUser`, so opening one of those rows 403'd and the detail pane hung on a
     * skeleton behind an error toast. Reading a listed row is now the same scoping decision.
     */
    @Test
    @TestSecurity(user = "sys-owner-admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
        ],
    )
    fun `a row the listing returns can be opened by the same administrator`() {
        val listed = mapper.readTree(
            given().get("/api/v1/users?page=0&size=20").then().statusCode(200).extract().body().asString(),
        ).path("content")
        val adminId = listed.first { it.path("username").asText() == "admin" }.path("id").asText()

        given().get("/api/v1/users/$adminId").then().statusCode(200)
            .body("username", org.hamcrest.Matchers.equalTo("admin"))
    }

    /**
     * Read scoping is enforced independently of the write guard: `readScope()` is
     * `Owner(clientId)` for an owner principal, so a user in another goods owner is refused on
     * the read path just as it was before the split.
     */
    @Test
    @TestSecurity(user = "acme-owner-admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
            Claim(key = "principal_kind", value = "owner"),
        ],
    )
    fun `owner administrator cannot open a user belonging to another client`() {
        val sysAdminId = foreignUserId("admin")
        val globexUserId = foreignUserId("tenant2-operator")

        given().get("/api/v1/users/$sysAdminId").then().statusCode(403)
        given().get("/api/v1/users/$globexUserId").then().statusCode(403)
    }

    /**
     * Widening the read must not widen authority on any mutation route: every write still routes
     * through `requireManageableUser` and still refuses the ops-kind row the same administrator
     * can now open.
     */
    @Test
    @TestSecurity(user = "sys-owner-admin", roles = ["user-admin", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
        ],
    )
    fun `every mutation path still refuses an ops-kind user the administrator can now read`() {
        val listed = mapper.readTree(
            given().get("/api/v1/users?page=0&size=20").then().statusCode(200).extract().body().asString(),
        ).path("content")
        val adminId = listed.first { it.path("username").asText() == "admin" }.path("id").asText()

        given().get("/api/v1/users/$adminId").then().statusCode(200)
        given().put("/api/v1/users/$adminId/deactivate").then().statusCode(403)
        given().put("/api/v1/users/$adminId/reactivate").then().statusCode(403)
        given().contentType(ContentType.JSON).body(mapOf("firstName" to "Nope"))
            .put("/api/v1/users/$adminId").then().statusCode(403)
        // Valid body on purpose: a malformed one is rejected at validation with 400 and
        // never reaches the guard, which would prove nothing about authorisation.
        given().contentType(ContentType.JSON)
            .body(mapOf("newPassword" to "irrelevant-but-valid-Passw0rd", "temporary" to false))
            .post("/api/v1/users/$adminId/reset-password").then().statusCode(403)

        // The two refusals are both 403 but must not read alike: this account demonstrably
        // belongs to the caller's goods owner, so the tenancy sentence would be a false
        // explanation of an authority ceiling.
        val kindRefusal = problem(given().put("/api/v1/users/$adminId/deactivate"), expectedStatus = 403)
        assertThat(kindRefusal.path("type").asText()).endsWith("/not-manageable")
        assertThat(kindRefusal.path("detail").asText())
            .doesNotContain("does not belong to current tenant")
            .contains("belongs to this goods owner")

        val tenantRefusal = problem(
            given().put("/api/v1/users/${foreignUserId("tenant2-operator")}/deactivate"),
            expectedStatus = 403,
        )
        assertThat(tenantRefusal.path("type").asText()).endsWith("/tenant-mismatch")
        assertThat(tenantRefusal.path("detail").asText()).contains("does not belong to current tenant")
    }

    private fun problem(response: io.restassured.response.Response, expectedStatus: Int): JsonNode =
        mapper.readTree(response.then().statusCode(expectedStatus).extract().body().asString())

    private fun foreignUserId(username: String): String {
        val baseUrl = ConfigProvider.getConfig().getValue(KeycloakTestResource.BASE_URL_PROPERTY, String::class.java)
        val adminToken = KeycloakTestGrants.passwordGrant(
            realmUrl = "$baseUrl/realms/master",
            clientId = "admin-cli",
            clientSecret = null,
            username = KeycloakTestResource.BOOTSTRAP_ADMIN_USERNAME,
            password = KeycloakTestResource.BOOTSTRAP_ADMIN_PASSWORD,
        ).then().statusCode(200).extract().path<String>("access_token")
        return given().header("Authorization", "Bearer $adminToken")
            .get("$baseUrl/admin/realms/${KeycloakTestResource.REALM}/users?username=$username&exact=true")
            .then().statusCode(200).extract().path<String>("[0].id")
    }

    private fun createUser(username: String, password: String, principalKind: String) =
        given()
            .contentType(ContentType.JSON)
            .body(
                CreateUserRequest(
                    username = username,
                    email = "$username@karyo.local",
                    firstName = "Karyo",
                    lastName = "Created",
                    password = password,
                    roles = listOf("VIEWER"),
                    clientId = 1L,
                    principalKind = principalKind,
                    forcePasswordChange = false,
                ),
            )
            .post("/api/v1/users")

    private fun tokenClaims(username: String, password: String): JsonNode {
        val baseUrl = ConfigProvider.getConfig().getValue(KeycloakTestResource.BASE_URL_PROPERTY, String::class.java)
        val token = KeycloakTestGrants.passwordGrant(
            realmUrl = "$baseUrl/realms/${KeycloakTestResource.REALM}",
            clientId = "karyo-backend",
            clientSecret = "dev-backend-secret",
            username = username,
            password = password,
        ).then()
            .statusCode(200)
            .extract()
            .path<String>("access_token")
        return mapper.readTree(Base64.getUrlDecoder().decode(token.substringAfter('.').substringBefore('.')))
    }
}
