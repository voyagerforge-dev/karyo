package com.karyo.app.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
// The upgrade contract is one ordered narrative against a single legacy Keycloak container:
// refusals, the drain ceiling, the migration itself, and the post-migration realm. Every helper
// below is the legacy-realm setup and Admin API plumbing that narrative needs and is meaningless
// apart from it; splitting them out would trade one honest class for two coupled ones. Same
// trade-off, same suppression, as ProductionRealmBootstrapTest.
@Suppress("LargeClass")
class ProductionRealmUpgradeTest {

    private val mapper = ObjectMapper()
    private lateinit var keycloak: GenericContainer<*>
    private lateinit var baseUrl: String
    private lateinit var projectRoot: Path

    @BeforeAll
    fun startLegacyKeycloak() {
        projectRoot = projectRoot()
        val realmFile = legacyRealmFile()
        keycloak = GenericContainer("quay.io/keycloak/keycloak:26.0")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", MAINTENANCE_USERNAME)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", MAINTENANCE_PASSWORD)
            .withEnv("OIDC_SECRET", OIDC_SECRET)
            .withEnv("KEYCLOAK_ADMIN_CLIENT_SECRET", OLD_ADMIN_CLIENT_SECRET)
            .withEnv("JAVA_OPTS_APPEND", "-Dkeycloak.import.replace-placeholders=true")
            .withCopyFileToContainer(
                MountableFile.forHostPath(realmFile),
                "/opt/keycloak/data/import/karyo-realm.json",
            )
            .withCommand("start-dev", "--http-relative-path=/auth", "--import-realm")
            .withExposedPorts(KEYCLOAK_PORT)
            .waitingFor(
                Wait.forHttp("/auth/realms/$REALM").forPort(KEYCLOAK_PORT)
                    .withStartupTimeout(Duration.ofMinutes(3)),
            )
        keycloak.start()
        baseUrl = "http://${keycloak.host}:${keycloak.getMappedPort(KEYCLOAK_PORT)}/auth"
    }

    @AfterAll
    fun stopKeycloak() {
        if (this::keycloak.isInitialized) keycloak.stop()
    }

    /**
     * A demo identity whose password an operator rotated is an operational account, not a
     * seeded one. The profile fingerprint still matches exactly, so the fingerprint alone cannot
     * tell them apart -- only authenticating with the seeded default credential can. Deleting or
     * disabling that account would lock its owner out of their own production realm, so the
     * whole migration must refuse without mutating anything.
     */
    @Test
    @Order(1)
    fun `migration refuses every mutation when a demo identity no longer holds its seeded credential`() {
        DEMO_CREDENTIALS.forEach { (username, password) ->
            applicationPasswordGrant(username, password).then().statusCode(200)
        }
        resetPassword("admin", ROTATED_ADMIN_PASSWORD)
        assertCannotAuthenticate("admin", "admin")
        directGrant("admin", ROTATED_ADMIN_PASSWORD).then().statusCode(200)

        val refused = runMigration(expectedExitCode = 1)

        assertThat(refused).contains("no longer authenticates with its seeded default credential")
        assertThat(refused).contains("admin")
        // Nothing moved: the rotated administrator still works, every other demo identity is
        // untouched, and the wildcard callbacks the migration exists to replace are still there.
        directGrant("admin", ROTATED_ADMIN_PASSWORD).then().statusCode(200)
        DEMO_CREDENTIALS.filterNot { it.first == "admin" }.forEach { (username, password) ->
            directGrant(username, password).then().statusCode(200)
        }
        assertThat(webClient().path("redirectUris").map(JsonNode::asText)).containsExactly("*")
        assertThat(webClient().path("webOrigins").map(JsonNode::asText)).containsExactly("*")
        assertThat(client(BACKEND_CLIENT_ID).path("directAccessGrantsEnabled").asBoolean()).isTrue()
        clientCredentialsGrant(BACKEND_CLIENT_ID, OIDC_SECRET).then().statusCode(200)
        clientCredentialsGrant(BACKEND_CLIENT_ID, ROTATED_OIDC_SECRET).then().statusCode(401)

        resetPassword("admin", "admin")
    }

    /**
     * A security-conscious operator who disabled the seeded accounts ahead of the upgrade leaves
     * them fingerprint-identical but unauthenticatable, so the seeded-credential probe cannot
     * confirm them. That is not a rotated password, and reporting it as one would advise a
     * retirement the operator already performed. The refusal fires before any mutation, so the
     * realm -- wildcard callbacks included -- must be untouched afterwards.
     */
    @Test
    @Order(2)
    fun `migration refuses and diagnoses a demo identity the operator disabled by hand`() {
        setUserEnabled("manager", false)

        val refused = runMigration(expectedExitCode = 1)

        assertThat(refused).contains("user manager matches the legacy demo profile but is disabled")
        assertThat(refused).doesNotContain("password was rotated")
        assertThat(refused).contains("re-enable manager")
        assertThat(refused).contains("resolve the account manually")
        assertThat(userByUsername("manager").path("enabled").asBoolean()).isFalse()
        assertThat(webClient().path("redirectUris").map(JsonNode::asText)).containsExactly("*")
        assertThat(webClient().path("webOrigins").map(JsonNode::asText)).containsExactly("*")
        assertThat(client(BACKEND_CLIENT_ID).path("directAccessGrantsEnabled").asBoolean()).isTrue()

        setUserEnabled("manager", true)
        directGrant("manager", "manager").then().statusCode(200)
    }

    @Test
    @Order(3)
    fun `migration refuses a fixture identity repurposed with client authority`() {
        val role = clientRole("realm-management", "view-users")
        updateUserClientRole("manager", "realm-management", role, add = true)

        val refused = runMigration(expectedExitCode = 1)

        assertThat(refused).contains("user manager no longer matches the complete legacy demo identity")
        assertThat(refused).contains("client roles")
        directGrant("manager", "manager").then().statusCode(200)
        assertThat(webClient().path("redirectUris").map(JsonNode::asText)).containsExactly("*")
        updateUserClientRole("manager", "realm-management", role, add = false)
    }

    /**
     * The post-revocation drain is an outage: `karyo-app`, nginx and every ordinary Keycloak
     * route stay down until already-issued access tokens expire. One client carrying a day-long
     * `access.token.lifespan` turns the upgrade into a day-long outage, and an operator can only
     * decline it if they are told while they can still walk away. So the migration must resolve
     * the drain first, refuse past the ceiling, name the client that set the length, and leave
     * the realm exactly as it found it -- demo identities, wildcard callbacks and the old backend
     * secret all still in place.
     */
    @Test
    @Order(4)
    fun `migration refuses a drain past the ceiling before mutating anything`() {
        setClientAccessTokenLifespan(WEB_CLIENT_ID, "86400")

        val refused = runMigration(expectedExitCode = 1)

        assertThat(refused).contains("86400 seconds")
        assertThat(refused).contains(WEB_CLIENT_ID)
        assertThat(refused).contains("access.token.lifespan")
        assertThat(refused).contains("Nothing has been changed")
        assertThat(refused).contains("--max-drain-seconds")
        // Refusal, not announce-then-proceed: the plan is never accepted.
        assertThat(refused).doesNotContain("Planned access-token drain")

        DEMO_CREDENTIALS.forEach { (username, password) ->
            directGrant(username, password).then().statusCode(200)
        }
        assertThat(webClient().path("redirectUris").map(JsonNode::asText)).containsExactly("*")
        assertThat(webClient().path("webOrigins").map(JsonNode::asText)).containsExactly("*")
        assertThat(client(BACKEND_CLIENT_ID).path("directAccessGrantsEnabled").asBoolean()).isTrue()
        clientCredentialsGrant(BACKEND_CLIENT_ID, OIDC_SECRET).then().statusCode(200)
        clientCredentialsGrant(BACKEND_CLIENT_ID, ROTATED_OIDC_SECRET).then().statusCode(401)

        // Blank, not absent: Keycloak's client update merges attributes rather than replacing
        // them, so dropping the key leaves the old value in place. A blank value is exactly what
        // the realm default inherits through, which is the state the next test expects.
        setClientAccessTokenLifespan(WEB_CLIENT_ID, "")
    }

    @Test
    @Order(5)
    // One ordered end-to-end migration against a live Keycloak: each assertion depends on the
    // realm state the previous step left behind, so splitting it would either re-run the
    // container per fragment or assert against state no longer guaranteed to hold. Same reason
    // ProductionRealmBootstrapTest suppresses this rule on its own end-to-end flows.
    @Suppress("LongMethod")
    fun `existing production realm migration retires demo access and binds exact callbacks`() {
        val unverified = runMigration(expectedExitCode = 1, backupVerified = false)
        assertThat(unverified).contains("--verified-backup is required")
        directGrant("admin", "admin").then().statusCode(200)
        assertThat(webClient().path("redirectUris").map(JsonNode::asText)).containsExactly("*")

        val missingSecret = runMigration(expectedExitCode = 1, oidcSecret = null)
        assertThat(missingSecret).contains("OIDC_SECRET")
        directGrant("admin", "admin").then().statusCode(200)
        clientCredentialsGrant(BACKEND_CLIENT_ID, OIDC_SECRET).then().statusCode(200)
        clientCredentialsGrant(BACKEND_CLIENT_ID, ROTATED_OIDC_SECRET).then().statusCode(401)

        val unserializableSecret = runMigration(
            expectedExitCode = 1,
            oidcSecret = UNSERIALIZABLE_OIDC_SECRET,
        )
        assertThat(unserializableSecret).contains("unquoted environment literal")
        assertThat(webClient().path("redirectUris").map(JsonNode::asText)).containsExactly("*")
        directGrant("admin", "admin").then().statusCode(200)
        clientCredentialsGrant(BACKEND_CLIENT_ID, OIDC_SECRET).then().statusCode(200)
        clientCredentialsGrant(BACKEND_CLIENT_ID, UNSERIALIZABLE_OIDC_SECRET).then().statusCode(401)

        seedExcessBackendAuthority()
        assertThat(
            clientTokenAuthority(BACKEND_CLIENT_ID, OIDC_SECRET).second
                .getValue("realm-management"),
        ).contains("manage-realm")
        val issuedBeforeRetirement = directGrant("admin", "admin")
            .then()
            .statusCode(200)
            .extract()
            .path<String>("access_token")
        val migrated = runMigration()
        assertThat(migrated).contains("deleted 5 legacy demo users")
        assertThat(migrated).contains("access tokens drain for 2 seconds")
        // The outage length and whatever set it are stated up front, before the run reaches the
        // irreversible deletion it reports at the end.
        assertThat(migrated).contains(
            "Planned access-token drain: 2 seconds " +
                "(longest lifespan: realm accessTokenLifespanForImplicitFlow)",
        )
        assertThat(migrated.indexOf("Planned access-token drain"))
            .isLessThan(migrated.indexOf("deleted 5 legacy demo users"))
        DEMO_CREDENTIALS.forEach { (username, password) ->
            assertUserAbsent(username)
            assertCannotAuthenticate(username, password)
        }
        given()
            .baseUri("$baseUrl/realms/$REALM")
            .header("Authorization", "Bearer $issuedBeforeRetirement")
            .get("/protocol/openid-connect/userinfo")
            .then()
            .statusCode(401)
        val rerun = runMigration()
        assertThat(rerun).contains("deleted 0 legacy demo users")
        assertThat(rerun).contains("access tokens drain for 2 seconds")

        assertThat(client(BACKEND_CLIENT_ID).path("directAccessGrantsEnabled").asBoolean()).isFalse()
        val serviceClients = adminApi()
            .queryParam("max", 100)
            .get("/admin/realms/$REALM/clients")
            .then()
            .statusCode(200)
            .extract()
            .`as`(Array<JsonNode>::class.java)
            .filter { it.path("serviceAccountsEnabled").asBoolean() }
        assertThat(serviceClients.map { it.path("clientId").asText() })
            .containsExactlyInAnyOrder(BACKEND_CLIENT_ID, ADMIN_CLIENT_ID)
        assertThat(webClient().path("redirectUris").map(JsonNode::asText)).containsExactlyInAnyOrder(
            "$PUBLIC_ORIGIN/",
            "$PUBLIC_ORIGIN/silent-check-sso.html",
            "$PUBLIC_ORIGIN/m/",
            "$PUBLIC_ORIGIN/m/silent-check-sso.html",
        )
        assertThat(webClient().path("webOrigins").map(JsonNode::asText))
            .containsExactlyInAnyOrder(PUBLIC_ORIGIN)
        assertServiceAuthority(ADMIN_CLIENT_ID, "manage-users", "view-realm")
        assertServiceAuthority(BACKEND_CLIENT_ID, "view-events", "view-users")
        assertClientTokenAuthority(ADMIN_CLIENT_ID, ADMIN_CLIENT_SECRET, "manage-users", "view-realm")
        assertClientTokenAuthority(
            BACKEND_CLIENT_ID,
            ROTATED_OIDC_SECRET,
            "view-events",
            "view-users",
            "query-groups",
            "query-users",
        )
        clientCredentialsGrant(BACKEND_CLIENT_ID, OIDC_SECRET).then().statusCode(401)

        DEMO_CREDENTIALS.map { it.first }.forEach(::assertUserAbsent)

        val verified = runMaintenanceCommand("--verify-maintenance-admin")
        assertThat(verified).contains("verified through the loopback-only endpoint")
        val retired = runMaintenanceCommand("--retire-maintenance-admin")
        assertThat(retired).contains("retired and its credentials rejected")
        KeycloakTestGrants.passwordGrant(
            realmUrl = "$baseUrl/realms/master",
            clientId = "admin-cli",
            clientSecret = null,
            username = MAINTENANCE_USERNAME,
            password = MAINTENANCE_PASSWORD,
        ).then().statusCode(401)
    }

    private fun runMigration(
        expectedExitCode: Int = 0,
        oidcSecret: String? = ROTATED_OIDC_SECRET,
        backupVerified: Boolean = true,
    ): String {
        val command = mutableListOf(
            "python3",
            projectRoot.resolve("scripts/migrate_keycloak_realm.py").toString(),
            "--apply",
            "--isolated-maintenance",
        )
        if (backupVerified) command += "--verified-backup"
        val process = ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["KARYO_PUBLIC_ORIGIN"] = PUBLIC_ORIGIN
                environment()["KARYO_KEYCLOAK_MAINTENANCE_URL"] = baseUrl
                environment()["KARYO_KEYCLOAK_MAINTENANCE_USERNAME"] = MAINTENANCE_USERNAME
                environment()["KARYO_KEYCLOAK_MAINTENANCE_PASSWORD"] = MAINTENANCE_PASSWORD
                environment()["KEYCLOAK_ADMIN_CLIENT_SECRET"] = ADMIN_CLIENT_SECRET
                environment().remove("OIDC_SECRET")
                if (oidcSecret != null) environment()["OIDC_SECRET"] = oidcSecret
            }
            .start()
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue()
        val output = process.inputStream.bufferedReader().readText()
        assertThat(process.exitValue()).withFailMessage(output).isEqualTo(expectedExitCode)
        return output
    }

    private fun runMaintenanceCommand(mode: String): String {
        val process = ProcessBuilder(
            "python3",
            projectRoot.resolve("scripts/migrate_keycloak_realm.py").toString(),
            mode,
        )
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["KARYO_KEYCLOAK_MAINTENANCE_URL"] = baseUrl
                environment()["KARYO_KEYCLOAK_MAINTENANCE_USERNAME"] = MAINTENANCE_USERNAME
                environment()["KARYO_KEYCLOAK_MAINTENANCE_PASSWORD"] = MAINTENANCE_PASSWORD
            }
            .start()
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue()
        val output = process.inputStream.bufferedReader().readText()
        assertThat(process.exitValue()).withFailMessage(output).isZero()
        return output
    }

    private fun resetPassword(username: String, password: String) {
        val userId = adminApi()
            .queryParam("username", username)
            .queryParam("exact", true)
            .get("/admin/realms/$REALM/users")
            .then()
            .statusCode(200)
            .extract()
            .path<String>("[0].id")
        adminApi()
            .contentType(ContentType.JSON)
            .body(mapOf("type" to "password", "value" to password, "temporary" to false))
            .put("/admin/realms/$REALM/users/$userId/reset-password")
            .then()
            .statusCode(204)
    }

    private fun legacyRealmFile(): Path {
        val source = projectRoot.resolve("infrastructure/keycloak/karyo-realm-prod.json")
        val realm = mapper.readTree(Files.readString(source)) as ObjectNode
        realm.put("accessTokenLifespan", 1)
        realm.put("accessTokenLifespanForImplicitFlow", 2)
        val users = realm.withArray("users")
        legacyUsers().forEach { users.add(mapper.valueToTree<JsonNode>(it)) }

        val clients = realm.withArray("clients")
        val web = clients.first { it.path("clientId").asText() == WEB_CLIENT_ID } as ObjectNode
        web.set<ArrayNode>("redirectUris", mapper.valueToTree(listOf("*")))
        web.set<ArrayNode>("webOrigins", mapper.valueToTree(listOf("*")))
        val backend = clients.first {
            it.path("clientId").asText() == BACKEND_CLIENT_ID
        } as ObjectNode
        backend.put("directAccessGrantsEnabled", true)

        val target = projectRoot.resolve(
            "services/karyo-app/build/tmp/production-realm-upgrade/legacy-realm.json",
        )
        Files.createDirectories(target.parent)
        Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(realm))
        return target
    }

    private fun legacyUsers(): List<Map<String, Any>> = listOf(
        legacyUser("admin", "admin@karyo.local", "System", "Administrator", "admin", "0", "ops", "SYS", listOf("ADMIN")),
        legacyUser(
            "manager", "manager@acme.local", "Alice", "Manager", "manager", "1", "ops", "ACME",
            listOf("MANAGER", "integration-admin"), "WH-001",
        ),
        legacyUser("operator", "operator@acme.local", "Bob", "Operator", "operator", "1", "ops", "ACME", listOf("OPERATOR"), "WH-001"),
        legacyUser("viewer", "viewer@acme.local", "Carol", "Viewer", "viewer", "1", "ops", "ACME", listOf("VIEWER")),
        legacyUser(
            "tenant2-operator",
            "operator@globex.local",
            "Dave",
            "Operator",
            "operator",
            "2",
            "owner",
            "GLOBEX",
            listOf("OPERATOR"),
            "WH-002",
        ),
    )

    private fun legacyUser(
        username: String,
        email: String,
        firstName: String,
        lastName: String,
        password: String,
        clientId: String,
        principalKind: String,
        tenantCode: String,
        realmRoles: List<String>,
        warehouseId: String? = null,
    ): Map<String, Any> {
        val attributes = linkedMapOf(
            "client_id" to listOf(clientId),
            "principal_kind" to listOf(principalKind),
            "tenant_code" to listOf(tenantCode),
        )
        warehouseId?.let { attributes["warehouse_id"] = listOf(it) }
        return mapOf(
            "username" to username,
            "enabled" to true,
            "email" to email,
            "firstName" to firstName,
            "lastName" to lastName,
            "realmRoles" to realmRoles,
            "credentials" to listOf(
                mapOf("type" to "password", "value" to password, "temporary" to false),
            ),
            "attributes" to attributes,
        )
    }

    private fun webClient(): JsonNode = client(WEB_CLIENT_ID)

    private fun assertUserAbsent(username: String) {
        val matches = adminApi()
            .queryParam("username", username)
            .queryParam("exact", true)
            .get("/admin/realms/$REALM/users")
            .then()
            .statusCode(200)
            .extract()
            .`as`(Array<JsonNode>::class.java)
        assertThat(matches).isEmpty()
    }

    private fun client(clientId: String): JsonNode {
        val uuid = adminApi()
            .queryParam("clientId", clientId)
            .get("/admin/realms/$REALM/clients")
            .then()
            .statusCode(200)
            .extract()
            .path<String>("[0].id")
        return adminApi()
            .get("/admin/realms/$REALM/clients/$uuid")
            .then()
            .statusCode(200)
            .extract()
            .`as`(JsonNode::class.java)
    }

    private fun serviceUserId(clientId: String): String {
        val serviceClient = client(clientId)
        return adminApi()
            .get("/admin/realms/$REALM/clients/${serviceClient.path("id").asText()}/service-account-user")
            .then()
            .statusCode(200)
            .extract()
            .path("id")
    }

    private fun clientRole(clientId: String, roleName: String): JsonNode {
        val clientUuid = client(clientId).path("id").asText()
        return adminApi()
            .get("/admin/realms/$REALM/clients/$clientUuid/roles/$roleName")
            .then()
            .statusCode(200)
            .extract()
            .`as`(JsonNode::class.java)
    }

    private fun updateUserClientRole(
        username: String,
        clientId: String,
        role: JsonNode,
        add: Boolean,
    ) {
        val userId = userByUsername(username).path("id").asText()
        val clientUuid = client(clientId).path("id").asText()
        val request = adminApi()
            .contentType(ContentType.JSON)
            .body(mapper.writeValueAsString(listOf(role)))
        if (add) {
            request.post("/admin/realms/$REALM/users/$userId/role-mappings/clients/$clientUuid")
                .then()
                .statusCode(204)
        } else {
            request.delete("/admin/realms/$REALM/users/$userId/role-mappings/clients/$clientUuid")
                .then()
                .statusCode(204)
        }
    }

    private fun seedExcessBackendAuthority() {
        val userId = serviceUserId(BACKEND_CLIENT_ID)
        val adminRole = adminApi()
            .get("/admin/realms/$REALM/roles/ADMIN")
            .then()
            .statusCode(200)
            .extract()
            .`as`(JsonNode::class.java)
        adminApi()
            .contentType(ContentType.JSON)
            .body(mapper.writeValueAsString(listOf(adminRole)))
            .post("/admin/realms/$REALM/users/$userId/role-mappings/realm")
            .then()
            .statusCode(204)

        val account = client("account")
        val accountRole = clientRole("account", "manage-account")
        adminApi()
            .contentType(ContentType.JSON)
            .body(mapper.writeValueAsString(listOf(accountRole)))
            .post(
                "/admin/realms/$REALM/users/$userId/role-mappings/clients/" +
                    account.path("id").asText(),
            )
            .then()
            .statusCode(204)

        val groupResponse = adminApi()
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "migration-overprivileged"))
            .post("/admin/realms/$REALM/groups")
            .then()
            .statusCode(201)
            .extract()
            .response()
        val groupId = groupResponse.header("Location").substringAfterLast("/")
        adminApi()
            .contentType(ContentType.JSON)
            .body(mapper.writeValueAsString(listOf(adminRole)))
            .post("/admin/realms/$REALM/groups/$groupId/role-mappings/realm")
            .then()
            .statusCode(204)
        adminApi()
            .put("/admin/realms/$REALM/users/$userId/groups/$groupId")
            .then()
            .statusCode(204)

        val backendId = client(BACKEND_CLIENT_ID).path("id").asText()
        adminApi()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "name" to "migration-hardcoded-manage-realm",
                    "protocol" to "openid-connect",
                    "protocolMapper" to "oidc-hardcoded-role-mapper",
                    "config" to mapOf("role" to "realm-management.manage-realm"),
                ),
            )
            .post("/admin/realms/$REALM/clients/$backendId/protocol-mappers/models")
            .then()
            .statusCode(201)
    }

    private fun assertServiceAuthority(clientId: String, vararg expectedRoles: String) {
        val userId = serviceUserId(clientId)
        val managementId = client("realm-management").path("id").asText()
        val accountId = client("account").path("id").asText()
        val managementRoles = adminApi()
            .get("/admin/realms/$REALM/users/$userId/role-mappings/clients/$managementId")
            .then()
            .statusCode(200)
            .extract()
            .`as`(Array<JsonNode>::class.java)
        assertThat(managementRoles.map { it.path("name").asText() })
            .containsExactlyInAnyOrder(*expectedRoles)
        for (path in listOf(
            "/admin/realms/$REALM/users/$userId/role-mappings/realm",
            "/admin/realms/$REALM/users/$userId/role-mappings/realm/composite",
            "/admin/realms/$REALM/users/$userId/role-mappings/clients/$accountId",
            "/admin/realms/$REALM/users/$userId/role-mappings/clients/$accountId/composite",
            "/admin/realms/$REALM/users/$userId/groups",
        )) {
            val values = adminApi()
                .get(path)
                .then()
                .statusCode(200)
                .extract()
                .`as`(Array<JsonNode>::class.java)
            assertThat(values).isEmpty()
        }
        val serviceClientId = client(clientId).path("id").asText()
        val mappers = adminApi()
            .get("/admin/realms/$REALM/clients/$serviceClientId/protocol-mappers/models")
            .then()
            .statusCode(200)
            .extract()
            .`as`(Array<JsonNode>::class.java)
        assertThat(mappers.map { it.path("protocolMapper").asText() })
            .doesNotContain("oidc-hardcoded-role-mapper")
    }

    private fun clientTokenAuthority(
        clientId: String,
        secret: String,
    ): Pair<List<String>, Map<String, List<String>>> {
        val token = clientCredentialsGrant(clientId, secret)
            .then()
            .statusCode(200)
            .extract()
            .path<String>("access_token")
        val encodedClaims = token.split('.')[1]
        val claims = mapper.readTree(Base64.getUrlDecoder().decode(encodedClaims))
        val realmRoles = claims.path("realm_access").path("roles").map(JsonNode::asText)
        val resources = claims.path("resource_access").fields().asSequence()
            .mapNotNull { (name, access) ->
                val roles = access.path("roles").map(JsonNode::asText)
                if (roles.isEmpty()) null else name to roles
            }
            .toMap()
        return realmRoles to resources
    }

    private fun assertClientTokenAuthority(
        clientId: String,
        secret: String,
        vararg expectedRoles: String,
    ) {
        val (realmRoles, resources) = clientTokenAuthority(clientId, secret)
        assertThat(realmRoles).isEmpty()
        assertThat(resources.keys).containsExactly("realm-management")
        assertThat(resources.getValue("realm-management"))
            .containsExactlyInAnyOrder(*expectedRoles)
    }

    /**
     * Password grant through the realm's own `admin-cli` public client -- the same client the
     * migration probes with, and the one that keeps working after `karyo-backend`'s direct
     * access grants are disabled.
     */
    private fun directGrant(username: String, password: String) =
        KeycloakTestGrants.passwordGrant(
            realmUrl = "$baseUrl/realms/$REALM",
            clientId = "admin-cli",
            clientSecret = null,
            username = username,
            password = password,
        )

    private fun assertCannotAuthenticate(username: String, password: String) {
        assertThat(directGrant(username, password).statusCode)
            .withFailMessage("%s still authenticates after retirement", username)
            .isNotEqualTo(200)
    }

    private fun setUserEnabled(username: String, enabled: Boolean) {
        val user = userByUsername(username) as ObjectNode
        user.put("enabled", enabled)
        adminApi()
            .contentType(ContentType.JSON)
            .body(mapper.writeValueAsString(user))
            .put("/admin/realms/$REALM/users/${user.path("id").asText()}")
            .then()
            .statusCode(204)
    }

    /** Sets one client's `access.token.lifespan` attribute; a blank value inherits the realm's. */
    private fun setClientAccessTokenLifespan(clientId: String, seconds: String) {
        val client = client(clientId) as ObjectNode
        val attributes = client.get("attributes") as? ObjectNode ?: client.putObject("attributes")
        attributes.put("access.token.lifespan", seconds)
        adminApi()
            .contentType(ContentType.JSON)
            .body(mapper.writeValueAsString(client))
            .put("/admin/realms/$REALM/clients/${client.path("id").asText()}")
            .then()
            .statusCode(204)
    }

    private fun userByUsername(username: String): JsonNode = adminApi()
        .queryParam("username", username)
        .queryParam("exact", true)
        .get("/admin/realms/$REALM/users")
        .then()
        .statusCode(200)
        .extract()
        .`as`(Array<JsonNode>::class.java)
        .single()

    private fun applicationPasswordGrant(username: String, password: String) =
        KeycloakTestGrants.passwordGrant(
            realmUrl = "$baseUrl/realms/$REALM",
            clientId = BACKEND_CLIENT_ID,
            clientSecret = OIDC_SECRET,
            username = username,
            password = password,
        )

    private fun clientCredentialsGrant(clientId: String, secret: String) =
        given()
            .baseUri("$baseUrl/realms/$REALM")
            .contentType(ContentType.URLENC)
            .formParam("grant_type", "client_credentials")
            .formParam("client_id", clientId)
            .formParam("client_secret", secret)
            .post("/protocol/openid-connect/token")

    /**
     * Minted per call rather than cached: the master realm's access tokens are short-lived and
     * this class now spans several subprocess migrations, so a cached bearer would start
     * returning 401 partway through a run.
     */
    private fun maintenanceToken(): String =
        KeycloakTestGrants.passwordGrant(
            realmUrl = "$baseUrl/realms/master",
            clientId = "admin-cli",
            clientSecret = null,
            username = MAINTENANCE_USERNAME,
            password = MAINTENANCE_PASSWORD,
        ).then()
            .statusCode(200)
            .extract()
            .path("access_token")

    private fun adminApi(): RequestSpecification = given()
        .baseUri(baseUrl)
        .header("Authorization", "Bearer ${maintenanceToken()}")

    private fun projectRoot(): Path {
        var directory: Path? = Paths.get("").toAbsolutePath()
        while (directory != null) {
            if (Files.exists(directory.resolve("infrastructure/keycloak/karyo-realm-prod.json"))) {
                return directory
            }
            directory = directory.parent
        }
        error("project root not found")
    }

    private companion object {
        const val KEYCLOAK_PORT = 8080
        const val REALM = "karyo"
        const val WEB_CLIENT_ID = "karyo-web"
        const val ADMIN_CLIENT_ID = "karyo-admin"
        const val BACKEND_CLIENT_ID = "karyo-backend"
        const val PUBLIC_ORIGIN = "https://warehouse.example.test"
        const val OIDC_SECRET = "test-only-production-client-secret-9Hj4vT"
        const val ROTATED_OIDC_SECRET = "test-only-rotated-backend-secret-3Qm8wL"
        const val UNSERIALIZABLE_OIDC_SECRET = "test-only-rotated\$backend-secret-3Qm8wL"
        const val ADMIN_CLIENT_SECRET = "test-only-admin-client-secret-8Kp4zW"
        const val OLD_ADMIN_CLIENT_SECRET = "test-only-old-admin-client-secret-5Jn7sV"
        const val MAINTENANCE_USERNAME = "temporary-maintenance-admin"
        const val MAINTENANCE_PASSWORD = "test-only-maintenance-password-4Nv8xQ"
        const val ROTATED_ADMIN_PASSWORD = "rotated-production-admin-password-7Lp2xQ"
        val DEMO_CREDENTIALS = listOf(
            "admin" to "admin",
            "manager" to "manager",
            "operator" to "operator",
            "viewer" to "viewer",
            "tenant2-operator" to "operator",
        )
    }
}
