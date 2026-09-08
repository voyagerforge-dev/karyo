package com.karyo.ai

import com.karyo.ai.proposal.ActionProposal
import com.karyo.ai.proposal.ActionProposalStore
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Integration smoke for the confirm-before-execute HTTP flow (Task 8).
 *
 * Uses toolName "testAction" (unknown → else branch → safe string) so no real
 * DB services are invoked — the test focuses purely on HTTP wiring:
 *   - first POST pops the proposal and returns 200 + message
 *   - second POST finds nothing → 404
 */
@QuarkusTest
class CopilotConfirmFlowTest {

    @Inject
    lateinit var store: ActionProposalStore

    @Test
    @TestSecurity(user = "test", roles = ["inventory-read"])
    fun `confirm pops proposal and returns 200 with message`() {
        val id = "flow-confirm-${System.nanoTime()}"
        store.put(
            ActionProposal(
                id = id,
                sessionId = "s-test",
                toolName = "testAction",
                summary = "Test action",
                params = emptyMap(),
                createdAt = Instant.now(),
                owner = "test",
            ),
        )

        given().post("/api/v1/ai/confirm/$id")
            .then()
            .statusCode(200)
            .body("message", notNullValue())
    }

    @Test
    @TestSecurity(user = "test", roles = ["inventory-read"])
    fun `second confirm returns 404 because proposal is already popped`() {
        val id = "flow-confirm2-${System.nanoTime()}"
        store.put(
            ActionProposal(
                id = id,
                sessionId = "s-test",
                toolName = "testAction",
                summary = "Test action",
                params = emptyMap(),
                createdAt = Instant.now(),
                owner = "test",
            ),
        )

        // First confirm — pops it
        given().post("/api/v1/ai/confirm/$id").then().statusCode(200)
        // Second confirm — should 404 (already popped)
        given().post("/api/v1/ai/confirm/$id").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "test", roles = ["inventory-read"])
    fun `confirm of a write proposal by a read-only user is rejected with 403 and not executed`() {
        val id = "flow-403-${System.nanoTime()}"
        // receiveStock requires inventory-write; the identity only has inventory-read.
        store.put(
            ActionProposal(
                id = id,
                sessionId = "s-test",
                toolName = "receiveStock",
                summary = "Receive 100 × DEMO-MOUSE",
                params = mapOf("productId" to 1L, "itemDataNumber" to "X", "amount" to 100, "unitLoadId" to 1L),
                createdAt = Instant.now(),
                owner = "test",
            ),
        )

        // Re-authorization at confirm time rejects the write with 403 — the mutation never runs.
        given().post("/api/v1/ai/confirm/$id").then().statusCode(403)

        // Proposal must NOT have been consumed: the role check ran before pop(), so the
        // rightful owner (once granted the write role) can still confirm it.
        assert(store.get(id) != null) {
            "Proposal $id should still be in the store after a 403 (role check must run before pop)"
        }
    }

    @Test
    @TestSecurity(user = "bob", roles = ["inventory-read", "inventory-write"])
    fun `confirm of a proposal owned by another user returns 404`() {
        val id = "flow-404owner-${System.nanoTime()}"
        // Proposal belongs to alice; bob (even with write role) must not see or execute it.
        store.put(
            ActionProposal(
                id = id,
                sessionId = "s-alice",
                toolName = "testAction",
                summary = "Test action",
                params = emptyMap(),
                createdAt = Instant.now(),
                owner = "alice",
            ),
        )

        given().post("/api/v1/ai/confirm/$id").then().statusCode(404)
    }
}
