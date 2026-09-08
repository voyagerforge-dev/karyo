package com.karyo.inventory.pact

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Pact message consumer test for JWT token claims shape.
 *
 * Consumer: inventory-service (representative of all services)
 * Provider: keycloak-token-issuer (conceptual -- represents Keycloak realm config)
 *
 * This contract documents the JWT claim structure that all services expect
 * via TenantFilter. The "message" payload represents decoded JWT claims body.
 * This catches drift between Keycloak protocol mapper configuration and
 * service expectations (e.g., the clientId ClassCastException from PoC).
 *
 * Claims verified (from TenantFilter.kt + TenantContext.kt):
 *   - sub (String): Keycloak user ID
 *   - preferred_username (String): username
 *   - email (String): user email
 *   - client_id (String/Number): tenant identifier, read by TenantFilter
 *   - tenant_code (String): tenant code
 *   - realm_access.roles (Array of String): role names (ADMIN, OPERATOR, etc.)
 *   - warehouse_id (String): optional warehouse assignment
 *   - locale (String): user locale preference
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "keycloak-token-issuer", providerType = ProviderType.ASYNCH)
class AuthTokenClaimsPactConsumerTest {

    private val objectMapper = ObjectMapper()

    @Pact(consumer = "inventory-service")
    fun jwtClaimsShapePact(builder: MessagePactBuilder): V4Pact {
        val body = PactDslJsonBody()
            .stringType("sub", "f47ac10b-58cc-4372-a567-0e02b2c3d479")
            .stringType("preferred_username", "admin")
            .stringType("email", "admin@karyo.dev")
            .stringType("client_id", "1")
            .stringType("tenant_code", "ACME")
            .stringType("warehouse_id", "WH-001")
            .stringType("locale", "en")
            .`object`("realm_access")
                .eachLike("roles", 1)
                    .stringType("ADMIN")
                    .closeArray()!!
            .closeObject()

        return builder
            .expectsToReceive("a decoded JWT token with expected claim shape")
            .withMetadata(mapOf("contentType" to "application/json"))
            .withContent(body!!)
            .toPact(V4Pact::class.java)
    }

    @Test
    @PactTestFor(pactMethod = "jwtClaimsShapePact")
    @Suppress("UNCHECKED_CAST")
    fun `JWT claims contain required fields for TenantFilter`(messages: List<V4Interaction.AsynchronousMessage>) {
        assertThat(messages).hasSize(1)

        val contents = messages[0].contents
        val payload = String(contents.contents.value!!)
        val claims = objectMapper.readValue(payload, Map::class.java) as Map<String, Any>

        // Verify all claim fields expected by TenantFilter exist with correct types
        assertThat(claims["sub"]).isInstanceOf(String::class.java)
        assertThat(claims["preferred_username"]).isInstanceOf(String::class.java)
        assertThat(claims["email"]).isInstanceOf(String::class.java)
        assertThat(claims["client_id"]).isInstanceOf(String::class.java)
        assertThat(claims["tenant_code"]).isInstanceOf(String::class.java)

        // Verify realm_access.roles structure
        val realmAccess = claims["realm_access"] as Map<String, Any>
        assertThat(realmAccess["roles"]).isInstanceOf(List::class.java)
        val roles = realmAccess["roles"] as List<String>
        assertThat(roles).isNotEmpty()
    }
}
