package com.karyo.app.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.security.PrincipalKind
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import org.keycloak.admin.client.Keycloak
import java.util.Base64

/**
 * Guards the `principal_kind` claim in `infrastructure/keycloak/karyo-realm.json` - the realm
 * Compose Dev Services and `docker-compose.dev.yml` mount - on **both** clients that issue
 * tokens to a human: `karyo-backend` (the confidential client the API authenticates with) and
 * `karyo-web` (the browser client `frontend/web/src/lib/keycloak.ts` logs in against). The two
 * carry separate copies of the protocol mapper, so covering one leaves the other free to drift.
 *
 * Why this needs a real Keycloak rather than a JSON assertion: every other test synthesises the
 * claim with `@OidcSecurity(claims = [Claim(key = "principal_kind", ...)])`, so nothing in the
 * suite ever reads the realm file. The dev realm shipped without the mapper while the OPS/OWNER
 * split was live in production, and no test noticed. Asserting the realm JSON's *text* would only
 * re-encode the config; asserting the *issued token* proves the protocol mapper, the user
 * attribute and the user-profile declaration all line up - three separate places the claim can go
 * missing, only one of which a text assertion would catch.
 *
 * The assertion deliberately ends at [PrincipalKind.fromClaim] rather than at claim presence.
 * `fromClaim` is fail-closed - an absent or unrecognised claim resolves to [PrincipalKind.OWNER]
 * - so a missing mapper never surfaces as an error, it silently re-scopes every read and write
 * through `readScope()`/`writeScope()`. Resolving through the same function the production
 * `TenantFilter` uses is what makes this a read-scoping check rather than a string match.
 */
@QuarkusTest
@QuarkusTestResource(KeycloakTestResource::class, restrictToAnnotatedClass = true)
class RealmPrincipalKindClaimTest {

    @Inject
    lateinit var keycloakAdminClient: Keycloak

    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    lateinit var authServerUrl: String

    @ConfigProperty(name = "quarkus.oidc.client-id")
    lateinit var oidcClientId: String

    @ConfigProperty(name = "quarkus.oidc.credentials.secret")
    lateinit var oidcSecret: String

    /**
     * Read programmatically rather than injected with `@ConfigProperty`, and that is load-bearing.
     * [KeycloakTestResource.BASE_URL_PROPERTY] exists only while this class's test resource is
     * running, but a `@ConfigProperty` **field** on a `@QuarkusTest` class is a CDI injection
     * point, and Quarkus validates every injection point in the index on *every* application boot
     * in the test JVM - `restrictToAnnotatedClass` scopes the resource lifecycle, not the bean
     * index. Injecting it here therefore failed unrelated boots with
     * `SRCFG00014: The config property karyo.test.keycloak.base-url is required`, taking the whole
     * suite down with it. A sentinel default would silence that, but it would keep the injection
     * point - and turn a genuinely missing value into a connection error against a fake host
     * instead of a clear failure here. The three `quarkus.oidc.*` fields above are safe because
     * `application.yaml` gives all three unconditional defaults.
     *
     * `by lazy` defers the lookup to first use inside a test method, where the resource is active.
     */
    private val keycloakBaseUrl: String by lazy {
        ConfigProvider.getConfig().getValue(KeycloakTestResource.BASE_URL_PROPERTY, String::class.java)
    }

    private val mapper = ObjectMapper()

    /**
     * The realm's five human users, their passwords, and the scope each must resolve to.
     * `admin` matters most: it holds `client_id` 0, so demoting it to OWNER narrows it to
     * `TenantScope.Owner(0)` - it would see only client-0 rows instead of the whole warehouse.
     */
    private val realmUsers = listOf(
        RealmUser("admin", "admin", PrincipalKind.OPS),
        RealmUser("manager", "manager", PrincipalKind.OPS),
        RealmUser("operator", "operator", PrincipalKind.OPS),
        RealmUser("viewer", "viewer", PrincipalKind.OPS),
        RealmUser("tenant2-operator", "operator", PrincipalKind.OWNER),
    )

    private data class RealmUser(val username: String, val password: String, val kind: PrincipalKind)

    private val expectedScopes get() = realmUsers.associate { it.username to it.kind }

    /**
     * Asserted as one map rather than per user so a regression reports every affected principal
     * at once - a dropped mapper flips all four ops users together, and seeing that shape is what
     * distinguishes "the realm lost the mapper" from "one user's attribute was mistyped".
     */
    @Test
    fun `dev realm issues a principal_kind claim resolving to the production scope for every user`() {
        val actual = realmUsers.associate {
            it.username to principalKindOf(accessTokenClaims(it.username, it.password))
        }

        assertThat(actual)
            .`as`(
                "every dev-realm token must carry a '%s' claim resolving to the same scope " +
                    "production grants - PrincipalKind.fromClaim fails closed to OWNER, so a " +
                    "dropped mapper shows up here as OPS users silently demoted to OWNER",
                PrincipalKind.CLAIM,
            )
            .isEqualTo(expectedScopes)
    }

    /**
     * Same guarantee for `karyo-web`, the client every browser session actually authenticates
     * against. It is a public client with `directAccessGrantsEnabled = false`, so no password
     * grant can reach it and flipping that flag would itself change the realm. Keycloak's
     * admin API instead runs its real token-mapper pipeline for a given client and user and
     * hands back the resulting access token, so this asserts what `karyo-web` would issue -
     * not what the realm file says it declares.
     */
    @Test
    fun `dev realm issues the same principal_kind scope on the karyo-web browser client`() {
        val actual = realmUsers.associate {
            it.username to principalKindOf(webClientTokenClaims(it.username))
        }

        assertThat(actual)
            .`as`(
                "karyo-web carries its own copy of the '%s' mapper - losing it would demote " +
                    "every browser session to OWNER while the karyo-backend copy kept this " +
                    "suite green",
                PrincipalKind.CLAIM,
            )
            .isEqualTo(expectedScopes)
    }

    @Test
    fun `application admin client authenticates with the dev realm service identity`() {
        val matchingUsers = keycloakAdminClient.realm(REALM).users()
            .searchByUsername(ADMIN_SERVICE_ACCOUNT_USERNAME, true)

        assertThat(matchingUsers.map { it.username })
            .containsExactly(ADMIN_SERVICE_ACCOUNT_USERNAME)
    }

    private fun principalKindOf(claims: JsonNode) =
        PrincipalKind.fromClaim(claims.get(PrincipalKind.CLAIM)?.asText())

    /** Direct-access (password) grant against the realm's karyo-backend confidential client. */
    private fun accessTokenClaims(username: String, password: String): JsonNode =
        KeycloakTestGrants.passwordGrant(
            realmUrl = authServerUrl,
            clientId = oidcClientId,
            clientSecret = oidcSecret,
            username = username,
            password = password,
        ).then()
            .statusCode(200)
            .extract()
            .path<String>("access_token")
            .let { decodePayload(it) }

    /** JWTs are signed, not encrypted - the payload is plain base64url between the dots. */
    private fun decodePayload(jwt: String): JsonNode =
        mapper.readTree(Base64.getUrlDecoder().decode(jwt.substringAfter('.').substringBefore('.')))

    /** The access token Keycloak would mint for [username] on the `karyo-web` client. */
    private fun webClientTokenClaims(username: String): JsonNode {
        val body = adminApi()
            .queryParam("userId", userId(username))
            .queryParam("scope", "openid")
            .get("/admin/realms/$REALM/clients/$webClientUuid/evaluate-scopes/generate-example-access-token")
            .then()
            .statusCode(200)
            .extract()
            .asString()
        return mapper.readTree(body)
    }

    private fun userId(username: String): String =
        requireNotNull(
            adminApi()
                .queryParam("username", username)
                .queryParam("exact", "true")
                .get("/admin/realms/$REALM/users")
                .then()
                .statusCode(200)
                .extract()
                .path<String>("[0].id"),
        ) { "realm user '$username' not found - the realm's user list changed" }

    private val webClientUuid: String by lazy {
        requireNotNull(
            adminApi()
                .queryParam("clientId", WEB_CLIENT_ID)
                .get("/admin/realms/$REALM/clients")
                .then()
                .statusCode(200)
                .extract()
                .path<String>("[0].id"),
        ) { "client '$WEB_CLIENT_ID' not found in realm '$REALM'" }
    }

    /**
     * The realm's own `karyo-backend` service account only holds `view-events`/`view-users`,
     * so reading clients and evaluating scopes needs the container's bootstrap admin in the
     * master realm - no grant added to the dev realm.
     */
    private val adminToken: String by lazy {
        KeycloakTestGrants.passwordGrant(
            realmUrl = "$keycloakBaseUrl/realms/master",
            clientId = "admin-cli",
            clientSecret = null,
            username = KeycloakTestResource.BOOTSTRAP_ADMIN_USERNAME,
            password = KeycloakTestResource.BOOTSTRAP_ADMIN_PASSWORD,
        ).then()
            .statusCode(200)
            .extract()
            .path("access_token")
    }

    private fun adminApi(): RequestSpecification =
        given()
            .baseUri(keycloakBaseUrl.trimEnd('/'))
            .header("Authorization", "Bearer $adminToken")

    private companion object {
        const val REALM = KeycloakTestResource.REALM
        const val WEB_CLIENT_ID = "karyo-web"
        const val ADMIN_SERVICE_ACCOUNT_USERNAME = "service-account-karyo-admin"
    }
}
