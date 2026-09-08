package com.karyo.auth.pact

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.karyo.app.auth.KeycloakTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Pact provider verification for auth-service.
 *
 * Verifies that the auth-service REST API satisfies all consumer contracts
 * published to the Pact Broker. Auth-service is a thin proxy over Keycloak
 * Admin API, so @State methods are no-ops -- Dev Services Keycloak starts
 * with the karyo realm containing test users (admin, operator).
 */
@QuarkusTest
@Provider("auth-service")
@PactBroker(url = "\${pact.broker.url:http://localhost:9292}")
// ignoreIoErrors stays a literal "true" -- a config-driven "${pact.ignore.io.errors:...}" form was
// verified NOT to resolve on pact-jvm 4.6.17's JUnit5 extension (getValueResolver() defaults to null,
// so the expression rethrows the original IO exception instead of applying its default). Full
// rationale in ProductPactProviderTest. CI's honesty for "broker unreachable" rests on the guaranteed
// pact-broker service container + `curl -sSf` on publish, not on this flag.
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
// GET /api/v1/users is @RolesAllowed("user-admin") -- a fine-grained role, not literal "ADMIN".
// TestSecurity roles are literal (no Keycloak composite-role expansion outside a real realm), so
// "ADMIN" alone 403s here even though the "admin" Keycloak user carries it via composite mapping.
@TestSecurity(user = "admin", roles = ["user-admin"])
// GET /api/v1/users is a thin proxy over the Keycloak Admin API, so unlike every other provider
// test this one needs a REAL Keycloak, not just a mocked principal. Nothing runs on the default
// admin-client URL (localhost:8180) during a test run -- Keycloak Dev Services does not start
// either, because an explicit quarkus.oidc.auth-server-url disables it -- so without this the
// interaction fails with "Connection refused: localhost/127.0.0.1:8180". KeycloakTestResource boots
// the real karyo realm and publishes both the OIDC URL and the admin-client server URL; it is
// restricted to annotated classes so only the tests that need it pay the container-boot cost.
@QuarkusTestResource(KeycloakTestResource::class, restrictToAnnotatedClass = true)
class AuthPactProviderTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    var port: Int = 0

    @BeforeEach
    fun setup(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", port)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPact(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("users exist")
    fun usersExist() {
        // No-op: Dev Services Keycloak starts with karyo realm containing test users (admin, operator).
        // Auth-service proxies to Keycloak Admin API -- no database seeding needed.
    }
}
