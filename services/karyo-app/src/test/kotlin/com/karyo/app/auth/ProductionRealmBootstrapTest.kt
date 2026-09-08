package com.karyo.app.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured.given
import io.restassured.filter.cookie.CookieFilter
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * Imports the production realm into a fresh Keycloak and exercises the operator bootstrap path.
 *
 * This is deliberately a runtime test rather than a realm-JSON assertion. The defect it guards
 * was observable after import: production issued tokens for five predefined human users with
 * reusable, non-temporary demo passwords. The public-snapshot rewrite removed those users and
 * replaced wildcard origins, so checking only the generated public file masked the source realm
 * mounted by `infrastructure/docker/docker-compose.prod.yml`.
 *
 * The test proves all parts of the supported first-deploy path against Keycloak itself: externally
 * supplied bootstrap-admin credentials reach the master realm, the imported application realm has
 * no human users, demo credentials cannot obtain a token, redirect origins resolve exactly, the
 * bootstrap admin can provision the first Karyo administrator, and the least-privilege service
 * identity still manages users after the temporary bootstrap account is removed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// The bootstrap contract is one narrative against a single Keycloak container: import, first
// admin provisioning, browser callbacks, service-account administration, bootstrap retirement.
// The helpers below are the OIDC browser flow that narrative needs and are meaningless apart
// from it; splitting them out would trade one honest class for two coupled ones. Baselined
// precedent for the same trade-off in this module: LargeClass on ReceivingFlowTest.
@Suppress("LargeClass")
class ProductionRealmBootstrapTest {

    private val mapper = ObjectMapper()
    private lateinit var keycloak: GenericContainer<*>
    private lateinit var baseUrl: String
    private lateinit var expectedPublicOrigin: String

    private data class BrowserSession(
        val cookies: CookieFilter = CookieFilter(),
        val cookieValues: MutableMap<String, String> = linkedMapOf(),
    )

    private data class BrowserAuthentication(
        val accessToken: String,
        val idToken: String,
        val browser: BrowserSession,
    )

    private data class LoginAttempt(
        val response: Response,
        val browser: BrowserSession,
    )

    private class BrowserCallbacks(origin: String) {
        val desktop = "$origin/"
        val desktopSilentSso = "$origin/silent-check-sso.html"
        val floor = "$origin/m/"
        val floorSilentSso = "$origin/m/silent-check-sso.html"
        val allowlist = listOf(desktop, desktopSilentSso, floor, floorSilentSso)
    }

    @BeforeAll
    fun startKeycloak() {
        val realmFile = realmFile()
        expectedPublicOrigin = importedPublicOrigin(realmFile)
        keycloak = GenericContainer("quay.io/keycloak/keycloak:26.0")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", BOOTSTRAP_ADMIN_USERNAME)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", BOOTSTRAP_ADMIN_PASSWORD)
            .withEnv("OIDC_SECRET", OIDC_SECRET)
            .withEnv("KEYCLOAK_ADMIN_CLIENT_SECRET", ADMIN_CLIENT_SECRET)
            .withEnv("KARYO_PUBLIC_ORIGIN", TEST_PUBLIC_ORIGIN)
            .withEnv("JAVA_OPTS_APPEND", "-Dkeycloak.import.replace-placeholders=true")
            .withCopyFileToContainer(
                MountableFile.forHostPath(realmFile),
                "/opt/keycloak/data/import/karyo-realm.json",
            )
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(KEYCLOAK_PORT)
            .waitingFor(
                Wait.forHttp("/realms/$REALM").forPort(KEYCLOAK_PORT)
                    .withStartupTimeout(Duration.ofMinutes(3)),
            )
        keycloak.start()
        baseUrl = "http://${keycloak.host}:${keycloak.getMappedPort(KEYCLOAK_PORT)}"
    }

    @AfterAll
    fun stopKeycloak() {
        if (this::keycloak.isInitialized) keycloak.stop()
    }

    @Test
    @Suppress("LongMethod")
    fun `fresh production import is user-free and bootstrap admin provisions the first login`() {
        val importedUsers = adminApi()
            .queryParam("max", 100)
            .get("/admin/realms/$REALM/users")
            .then()
            .statusCode(200)
            .extract()
            .`as`(Array<JsonNode>::class.java)

        assertThat(importedUsers)
            .`as`("the production realm must not import predefined human application users")
            .isEmpty()
        val serviceClients = adminApi()
            .queryParam("max", 100)
            .get("/admin/realms/$REALM/clients")
            .then()
            .statusCode(200)
            .extract()
            .`as`(Array<JsonNode>::class.java)
            .filter { it.path("serviceAccountsEnabled").asBoolean() }
        assertThat(serviceClients.map { it.path("clientId").asText() })
            .`as`("the production realm must enable only its two service identities")
            .containsExactlyInAnyOrder(BACKEND_CLIENT_ID, ADMIN_CLIENT_ID)
        val backendClient = serviceClients.single {
            it.path("clientId").asText() == BACKEND_CLIENT_ID
        }
        assertThat(backendClient.path("directAccessGrantsEnabled").asBoolean())
            .`as`("production must disable resource-owner-password grants")
            .isFalse()
        assertThat(serviceAccountUser(BACKEND_CLIENT_ID).path("username").asText())
            .isEqualTo("service-account-$BACKEND_CLIENT_ID")
        assertServiceRoles(ADMIN_CLIENT_ID, "manage-users", "view-realm")
        assertServiceRoles(BACKEND_CLIENT_ID, "view-events", "view-users")

        val callbacks = BrowserCallbacks(expectedPublicOrigin)
        val redirectUris = callbacks.allowlist
        listOf("admin", "manager", "operator", "viewer").forEachIndexed { index, username ->
            val attempt = applicationLoginAttempt(
                callbacks.desktop,
                "fixed-credential-$index",
                username,
                username,
            )
            attempt.response.then().statusCode(200)
            assertThat(attempt.response.header("Location")).isNull()
        }

        val webClient = adminApi()
            .queryParam("clientId", WEB_CLIENT_ID)
            .get("/admin/realms/$REALM/clients")
            .then()
            .statusCode(200)
            .extract()
            .path<Map<String, Any>>("[0]")
        assertThat(webClient["redirectUris"] as List<*>)
            .containsExactlyInAnyOrderElementsOf(redirectUris)
        assertThat(webClient["webOrigins"] as List<*>)
            .containsExactlyInAnyOrder(expectedPublicOrigin)
        assertThat((webClient["redirectUris"] as List<*>) + (webClient["webOrigins"] as List<*>))
            .allSatisfy { assertThat(it.toString()).doesNotContain("*") }
        authorizationRequest("https://untrusted.example.test/").then().statusCode(400)

        val firstAdminId = createFirstApplicationAdmin()
        assignRealmRole(firstAdminId, "ADMIN")

        val desktopAuthentication = activateTemporaryPasswordAndAuthorize(
            callbacks.desktop,
            DESKTOP_AUTHORIZATION_STATE,
        )
        val claims = decodeToken(desktopAuthentication.accessToken)
        assertThat(claims.path("realm_access").path("roles").map(JsonNode::asText))
            .contains("ADMIN")
        assertThat(claims.path("principal_kind").asText()).isEqualTo("ops")
        assertThat(claims.path("client_id").asLong()).isZero()
        logoutAndVerifyNoSso(
            desktopAuthentication,
            callbacks.desktop,
            callbacks.desktopSilentSso,
            DESKTOP_LOGOUT_STATE,
        )

        val floorAuthentication = applicationAuthorizationCodeGrant(
            callbacks.floor,
            FLOOR_AUTHORIZATION_STATE,
        )
        val silentAuthentication = applicationSilentSsoGrant(
            callbacks.floorSilentSso,
            floorAuthentication.browser,
            FLOOR_SILENT_SSO_STATE,
        )
        assertThat(silentAuthentication.accessToken).isNotBlank()
        logoutAndVerifyNoSso(
            silentAuthentication,
            callbacks.floor,
            callbacks.floorSilentSso,
            FLOOR_LOGOUT_STATE,
        )

        removeBootstrapAdministrator()
        verifyServiceAccountUserAdministration(firstAdminId)
        verifyBackendAuditAccess(firstAdminId)
    }

    private fun assertServiceRoles(clientId: String, vararg expectedRoles: String) {
        val serviceUserId = serviceAccountUser(clientId).path("id").asText()
        val realmManagementClientId = clientUuid("realm-management")
        val directRoles = adminApi()
            .get(
                "/admin/realms/$REALM/users/$serviceUserId/role-mappings/clients/" +
                    realmManagementClientId,
            )
            .then()
            .statusCode(200)
            .extract()
            .`as`(Array<JsonNode>::class.java)
        assertThat(directRoles.map { it.path("name").asText() })
            .containsExactlyInAnyOrder(*expectedRoles)
    }

    private fun serviceAccountUser(clientId: String): JsonNode =
        adminApi()
            .get("/admin/realms/$REALM/clients/${clientUuid(clientId)}/service-account-user")
            .then()
            .statusCode(200)
            .extract()
            .`as`(JsonNode::class.java)

    private fun clientUuid(clientId: String): String =
        requireNotNull(
            adminApi()
                .queryParam("clientId", clientId)
                .get("/admin/realms/$REALM/clients")
                .then()
                .statusCode(200)
                .extract()
                .path<String>("[0].id"),
        ) { "Keycloak client '$clientId' was not found in realm '$REALM'" }

    private fun removeBootstrapAdministrator() {
        val bootstrapUserId = requireNotNull(
            adminApi()
                .queryParam("username", BOOTSTRAP_ADMIN_USERNAME)
                .queryParam("exact", true)
                .get("/admin/realms/master/users")
                .then()
                .statusCode(200)
                .extract()
                .path<String>("[0].id"),
        ) { "Keycloak bootstrap user was not found in the master realm" }
        adminApi()
            .delete("/admin/realms/master/users/$bootstrapUserId")
            .then()
            .statusCode(204)
        KeycloakTestGrants.passwordGrant(
            realmUrl = "$baseUrl/realms/master",
            clientId = "admin-cli",
            clientSecret = null,
            username = BOOTSTRAP_ADMIN_USERNAME,
            password = BOOTSTRAP_ADMIN_PASSWORD,
        ).then()
            .statusCode(401)
    }

    @Suppress("LongMethod")
    private fun verifyServiceAccountUserAdministration(firstAdminId: String) {
        val serviceToken = clientCredentialsToken(ADMIN_CLIENT_ID, ADMIN_CLIENT_SECRET)
        serviceAdminApi(serviceToken)
            .get("/admin/realms/$REALM/users/$firstAdminId")
            .then()
            .statusCode(200)
            .body("username", equalTo(FIRST_ADMIN_USERNAME))

        val managedUserId = requireNotNull(
            serviceAdminApi(serviceToken)
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "username" to MANAGED_USER_USERNAME,
                        "enabled" to true,
                        "attributes" to managedUserAttributes(),
                        "credentials" to listOf(
                            mapOf(
                                "type" to "password",
                                "value" to MANAGED_USER_PASSWORD,
                                "temporary" to false,
                            ),
                        ),
                    ),
                )
                .post("/admin/realms/$REALM/users")
                .then()
                .statusCode(201)
                .extract()
                .header("Location")
                ?.substringAfterLast('/'),
        ) { "karyo-admin did not return the created user location" }

        serviceAdminApi(serviceToken)
            .queryParam("q", "client_id:0")
            .get("/admin/realms/$REALM/users")
            .then()
            .statusCode(200)
            .body("username", hasItem(MANAGED_USER_USERNAME))
        updateManagedUser(serviceToken, managedUserId, enabled = false)
        updateManagedUser(serviceToken, managedUserId, enabled = true)
        serviceAdminApi(serviceToken)
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "type" to "password",
                    "value" to MANAGED_USER_REPLACEMENT_PASSWORD,
                    "temporary" to false,
                ),
            )
            .put("/admin/realms/$REALM/users/$managedUserId/reset-password")
            .then()
            .statusCode(204)
        val managedAuthentication = applicationAuthorizationCodeGrant(
            BrowserCallbacks(expectedPublicOrigin).desktop,
            "managed-user-authorization",
            MANAGED_USER_USERNAME,
            MANAGED_USER_REPLACEMENT_PASSWORD,
        )
        assertThat(managedAuthentication.accessToken).isNotBlank()

        serviceAdminApi(serviceToken)
            .get("/admin/realms/$REALM/roles")
            .then()
            .statusCode(200)
            .body("name", hasItem("VIEWER"))
        val viewerRole = serviceAdminApi(serviceToken)
            .get("/admin/realms/$REALM/roles/VIEWER")
            .then()
            .statusCode(200)
            .extract()
            .`as`(JsonNode::class.java)
        serviceAdminApi(serviceToken)
            .contentType(ContentType.JSON)
            .body(listOf(viewerRole))
            .post("/admin/realms/$REALM/users/$managedUserId/role-mappings/realm")
            .then()
            .statusCode(204)
        val assignedRoles = serviceAdminApi(serviceToken)
            .get("/admin/realms/$REALM/users/$managedUserId/role-mappings/realm/composite")
            .then()
            .statusCode(200)
            .extract()
            .`as`(Array<JsonNode>::class.java)
        assertThat(assignedRoles.map { it.path("name").asText() }).contains("VIEWER")
        serviceAdminApi(serviceToken)
            .contentType(ContentType.JSON)
            .body(listOf(viewerRole))
            .delete("/admin/realms/$REALM/users/$managedUserId/role-mappings/realm")
            .then()
            .statusCode(204)
        val rolesAfterRevoke = serviceAdminApi(serviceToken)
            .get("/admin/realms/$REALM/users/$managedUserId/role-mappings/realm/composite")
            .then()
            .statusCode(200)
            .extract()
            .`as`(Array<JsonNode>::class.java)
        assertThat(rolesAfterRevoke.map { it.path("name").asText() }).doesNotContain("VIEWER")
        serviceAdminApi(serviceToken)
            .get("/admin/realms/$REALM/events")
            .then()
            .statusCode(403)
        serviceAdminApi(serviceToken)
            .delete("/admin/realms/$REALM/users/$managedUserId")
            .then()
            .statusCode(204)
    }

    private fun managedUserAttributes(): Map<String, List<String>> = mapOf(
        "client_id" to listOf("0"),
        "principal_kind" to listOf("ops"),
        "tenant_code" to listOf("SYS"),
    )

    private fun updateManagedUser(serviceToken: String, userId: String, enabled: Boolean) {
        serviceAdminApi(serviceToken)
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "username" to MANAGED_USER_USERNAME,
                    "email" to "managed-user@example.test",
                    "enabled" to enabled,
                    "attributes" to managedUserAttributes(),
                ),
            )
            .put("/admin/realms/$REALM/users/$userId")
            .then()
            .statusCode(204)
        serviceAdminApi(serviceToken)
            .get("/admin/realms/$REALM/users/$userId")
            .then()
            .statusCode(200)
            .body("enabled", equalTo(enabled))
            .body("email", equalTo("managed-user@example.test"))
    }

    private fun verifyBackendAuditAccess(firstAdminId: String) {
        val serviceToken = clientCredentialsToken(BACKEND_CLIENT_ID, OIDC_SECRET)
        val events = serviceAdminApi(serviceToken)
            .queryParam("type", "LOGIN", "LOGOUT")
            .queryParam("max", 100)
            .get("/admin/realms/$REALM/events")
            .then()
            .statusCode(200)
            .extract()
            .`as`(Array<JsonNode>::class.java)
        assertThat(events.map { it.path("type").asText() }).contains("LOGIN", "LOGOUT")
        serviceAdminApi(serviceToken)
            .get("/admin/realms/$REALM/users/$firstAdminId")
            .then()
            .statusCode(200)
            .body("username", equalTo(FIRST_ADMIN_USERNAME))
    }

    private fun clientCredentialsToken(clientId: String, clientSecret: String): String =
        given()
            .baseUri("$baseUrl/realms/$REALM")
            .contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "client_credentials")
            .formParam("client_id", clientId)
            .formParam("client_secret", clientSecret)
            .post("/protocol/openid-connect/token")
            .then()
            .statusCode(200)
            .extract()
            .path("access_token")

    private fun serviceAdminApi(token: String): RequestSpecification =
        given()
            .baseUri(baseUrl)
            .header("Authorization", "Bearer $token")

    private fun authorizationRequest(
        redirectUri: String,
        browser: BrowserSession = BrowserSession(),
        state: String = DESKTOP_AUTHORIZATION_STATE,
        prompt: String? = null,
    ): Response {
        val response = given()
            .baseUri(baseUrl)
            .filter(browser.cookies)
            .cookies(browser.cookieValues)
            .redirects().follow(false)
            .queryParam("client_id", WEB_CLIENT_ID)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("response_type", "code")
            .queryParam("response_mode", "query")
            .queryParam("scope", "openid")
            .queryParam("state", state)
            .queryParam("code_challenge", PKCE_CHALLENGE)
            .queryParam("code_challenge_method", "S256")
            .apply { prompt?.let { queryParam("prompt", it) } }
            .get("/realms/$REALM/protocol/openid-connect/auth")
        browser.cookieValues.putAll(response.cookies)
        return response
    }

    private fun applicationAuthorizationCodeGrant(
        redirectUri: String,
        state: String,
        username: String = FIRST_ADMIN_USERNAME,
        password: String = FIRST_ADMIN_PASSWORD,
    ): BrowserAuthentication {
        val attempt = applicationLoginAttempt(redirectUri, state, username, password)
        val loginResponse = attempt.response.then()
            .statusCode(302)
            .extract()
            .response()
        val callback = requireNotNull(loginResponse.header("Location")) {
            "Keycloak login did not redirect to the application"
        }
        return exchangeAuthorizationCode(callback, redirectUri, state, attempt.browser)
    }

    private fun applicationLoginAttempt(
        redirectUri: String,
        state: String,
        username: String,
        password: String,
    ): LoginAttempt {
        val browser = BrowserSession()
        val loginPage = authorizationRequest(redirectUri, browser, state)
            .then()
            .statusCode(200)
            .extract()
            .response()
        val loginAction = requireNotNull(
            LOGIN_FORM_ACTION.find(loginPage.body.asString())?.groupValues?.get(1),
        ) { "Keycloak login page did not expose its form action" }
            .replace("&amp;", "&")
        val response = given()
            .filter(browser.cookies)
            .redirects().follow(false)
            .cookies(browser.cookieValues)
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", username)
            .formParam("password", password)
            .formParam("credentialId", "")
            .post(loginAction)
        browser.cookieValues.putAll(response.cookies)
        return LoginAttempt(response, browser)
    }

    private fun activateTemporaryPasswordAndAuthorize(
        redirectUri: String,
        state: String,
    ): BrowserAuthentication {
        val attempt = applicationLoginAttempt(
            redirectUri,
            state,
            FIRST_ADMIN_USERNAME,
            FIRST_ADMIN_TEMPORARY_PASSWORD,
        )
        val requiredActionLocation = attempt.response.then()
            .statusCode(302)
            .extract()
            .header("Location")
        val updatePasswordPage = given()
            .filter(attempt.browser.cookies)
            .cookies(attempt.browser.cookieValues)
            .redirects().follow(false)
            .get(requiredActionLocation)
            .then()
            .statusCode(200)
            .extract()
            .response()
        attempt.browser.cookieValues.putAll(updatePasswordPage.cookies)
        val updateAction = requireNotNull(
            UPDATE_PASSWORD_FORM_ACTION.find(updatePasswordPage.body.asString())
                ?.groupValues?.get(1),
        ) { "Keycloak required-action page did not expose its update-password form" }
            .replace("&amp;", "&")
        val callback = given()
            .filter(attempt.browser.cookies)
            .cookies(attempt.browser.cookieValues)
            .redirects().follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("password-new", FIRST_ADMIN_PASSWORD)
            .formParam("password-confirm", FIRST_ADMIN_PASSWORD)
            .post(updateAction)
            .then()
            .statusCode(302)
            .extract()
            .header("Location")
        return exchangeAuthorizationCode(callback, redirectUri, state, attempt.browser)
    }

    private fun applicationSilentSsoGrant(
        redirectUri: String,
        browser: BrowserSession,
        state: String,
    ): BrowserAuthentication {
        val callback = authorizationRequest(redirectUri, browser, state, prompt = "none")
            .then()
            .statusCode(302)
            .extract()
            .header("Location")
        return exchangeAuthorizationCode(callback, redirectUri, state, browser)
    }

    private fun exchangeAuthorizationCode(
        callback: String,
        redirectUri: String,
        state: String,
        browser: BrowserSession,
    ): BrowserAuthentication {
        assertThat(callback).startsWith("$redirectUri?")
        val authorizationCode = requireNotNull(queryParameter(callback, "code")) {
            "Keycloak callback omitted the authorization code"
        }
        assertThat(queryParameter(callback, "state")).isEqualTo(state)

        val tokenResponse = given()
            .baseUri(baseUrl)
            .contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "authorization_code")
            .formParam("client_id", WEB_CLIENT_ID)
            .formParam("redirect_uri", redirectUri)
            .formParam("code", authorizationCode)
            .formParam("code_verifier", PKCE_VERIFIER)
            .post("/realms/$REALM/protocol/openid-connect/token")
            .then()
            .statusCode(200)
            .extract()
            .`as`(JsonNode::class.java)
        return BrowserAuthentication(
            accessToken = requireNotNull(tokenResponse.path("access_token").textValue()),
            idToken = requireNotNull(tokenResponse.path("id_token").textValue()),
            browser = browser,
        )
    }

    private fun logoutAndVerifyNoSso(
        authentication: BrowserAuthentication,
        postLogoutRedirectUri: String,
        silentRedirectUri: String,
        state: String,
    ) {
        val logoutResponse = given()
            .baseUri(baseUrl)
            .filter(authentication.browser.cookies)
            .cookies(authentication.browser.cookieValues)
            .redirects().follow(false)
            .queryParam("client_id", WEB_CLIENT_ID)
            .queryParam("id_token_hint", authentication.idToken)
            .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
            .get("/realms/$REALM/protocol/openid-connect/logout")
        logoutResponse.then()
            .statusCode(302)
            .header("Location", equalTo(postLogoutRedirectUri))
        authentication.browser.cookieValues.putAll(logoutResponse.cookies)

        val callback = authorizationRequest(
            silentRedirectUri,
            authentication.browser,
            state,
            prompt = "none",
        ).then()
            .statusCode(302)
            .extract()
            .header("Location")
        assertThat(callback).startsWith("$silentRedirectUri?")
        assertThat(queryParameter(callback, "state")).isEqualTo(state)
        assertThat(queryParameter(callback, "error")).isEqualTo("login_required")
        assertThat(queryParameter(callback, "code")).isNull()
    }

    private fun queryParameter(uri: String, name: String): String? =
        URI(uri).rawQuery
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.first() == name }
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

    private fun createFirstApplicationAdmin(): String {
        val response = adminApi()
            .contentType("application/json")
            .body(
                mapOf(
                    "username" to FIRST_ADMIN_USERNAME,
                    "enabled" to true,
                    "email" to "first-admin@example.test",
                    "firstName" to "First",
                    "lastName" to "Administrator",
                    "attributes" to mapOf(
                        "client_id" to listOf("0"),
                        "principal_kind" to listOf("ops"),
                        "tenant_code" to listOf("SYS"),
                        "warehouse_id" to listOf("WH-001"),
                    ),
                    "credentials" to listOf(
                        mapOf(
                            "type" to "password",
                            "value" to FIRST_ADMIN_TEMPORARY_PASSWORD,
                            "temporary" to true,
                        ),
                    ),
                ),
            )
            .post("/admin/realms/$REALM/users")
            .then()
            .statusCode(201)
            .extract()
            .response()
        return requireNotNull(response.header("Location")?.substringAfterLast('/')) {
            "Keycloak created the first administrator without returning its user id"
        }
    }

    private fun assignRealmRole(userId: String, roleName: String) {
        val role = adminApi()
            .get("/admin/realms/$REALM/roles/$roleName")
            .then()
            .statusCode(200)
            .extract()
            .`as`(JsonNode::class.java)
        adminApi()
            .contentType("application/json")
            .body(listOf(role))
            .post("/admin/realms/$REALM/users/$userId/role-mappings/realm")
            .then()
            .statusCode(204)
    }

    private val bootstrapAdminToken: String by lazy {
        KeycloakTestGrants.passwordGrant(
            realmUrl = "$baseUrl/realms/master",
            clientId = "admin-cli",
            clientSecret = null,
            username = BOOTSTRAP_ADMIN_USERNAME,
            password = BOOTSTRAP_ADMIN_PASSWORD,
        ).then()
            .statusCode(200)
            .extract()
            .path("access_token")
    }

    private fun adminApi(): RequestSpecification =
        given()
            .baseUri(baseUrl)
            .header("Authorization", "Bearer $bootstrapAdminToken")

    private fun decodeToken(token: String): JsonNode =
        mapper.readTree(Base64.getUrlDecoder().decode(token.substringAfter('.').substringBefore('.')))

    /**
     * The private source realm uses a placeholder resolved by the production Keycloak import.
     * A generated public snapshot is already bound to its publication origin, so the same test
     * accepts that exact source value while still rejecting wildcards and mismatched arrays.
     */
    private fun importedPublicOrigin(path: Path): String {
        val realm = mapper.readTree(Files.readString(path))
        val webClient = realm.path("clients").first { it.path("clientId").asText() == WEB_CLIENT_ID }
        val sourceOrigins = webClient.path("webOrigins").map(JsonNode::asText)
        require(sourceOrigins.size == 1 && "*" !in sourceOrigins.single()) {
            "production karyo-web webOrigins must contain one exact origin"
        }
        val sourceOrigin = sourceOrigins.single()
        require(
            webClient.path("redirectUris").map(JsonNode::asText) == browserRedirectUris(sourceOrigin),
        ) { "production karyo-web redirectUris must match its exact public origin" }
        return if (sourceOrigin == PUBLIC_ORIGIN_PLACEHOLDER) TEST_PUBLIC_ORIGIN else sourceOrigin
    }

    /** Walks up from Gradle's test working directory to the production realm. */
    private fun realmFile(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("infrastructure/keycloak/karyo-realm-prod.json")
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        error("production Keycloak realm not found above ${Paths.get("").toAbsolutePath()}")
    }

    private fun browserRedirectUris(origin: String): List<String> =
        BrowserCallbacks(origin).allowlist

    private companion object {
        val LOGIN_FORM_ACTION = Regex("""<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"""")
        val UPDATE_PASSWORD_FORM_ACTION =
            Regex("""<form[^>]*id="kc-passwd-update-form"[^>]*action="([^"]+)"""")
        const val KEYCLOAK_PORT = 8080
        const val REALM = "karyo"
        const val BACKEND_CLIENT_ID = "karyo-backend"
        const val ADMIN_CLIENT_ID = "karyo-admin"
        const val WEB_CLIENT_ID = "karyo-web"
        const val PUBLIC_ORIGIN_PLACEHOLDER = "\${KARYO_PUBLIC_ORIGIN}"
        const val TEST_PUBLIC_ORIGIN = "https://warehouse.example.test"
        const val OIDC_SECRET = "test-only-production-client-secret-9Hj4vT"
        const val ADMIN_CLIENT_SECRET = "test-only-admin-client-secret-8Kp4zW"
        const val BOOTSTRAP_ADMIN_USERNAME = "external-bootstrap-admin"
        const val BOOTSTRAP_ADMIN_PASSWORD = "test-only-bootstrap-password-4Nv8xQ"
        const val FIRST_ADMIN_USERNAME = "first-karyo-admin"
        const val FIRST_ADMIN_TEMPORARY_PASSWORD = "test-only-temporary-password-6Qm3sV"
        const val FIRST_ADMIN_PASSWORD = "test-only-first-admin-password-7Tk2mP"
        const val MANAGED_USER_USERNAME = "service-managed-user"
        const val MANAGED_USER_PASSWORD = "test-only-managed-user-password-2Fs9qR"
        const val MANAGED_USER_REPLACEMENT_PASSWORD = "test-only-replacement-password-3Gt7pL"
        const val DESKTOP_AUTHORIZATION_STATE = "desktop-authorization"
        const val DESKTOP_LOGOUT_STATE = "desktop-after-logout"
        const val FLOOR_AUTHORIZATION_STATE = "floor-authorization"
        const val FLOOR_SILENT_SSO_STATE = "floor-silent-sso"
        const val FLOOR_LOGOUT_STATE = "floor-after-logout"
        const val PKCE_VERIFIER = "test-only-pkce-verifier-abcdefghijklmnopqrstuvwxyz-0123456789"
        val PKCE_CHALLENGE: String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(PKCE_VERIFIER.toByteArray()),
        )
    }
}
