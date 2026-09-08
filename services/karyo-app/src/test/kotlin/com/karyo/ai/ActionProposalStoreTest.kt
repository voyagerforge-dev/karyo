package com.karyo.ai

import com.karyo.ai.proposal.ActionProposal
import com.karyo.ai.proposal.ActionProposalStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ActionProposalStoreTest {

    private fun proposal(id: String, session: String, age: Long = 0) =
        ActionProposal(id, session, "receiveStock", "Receive 100 of DEMO-MOUSE",
            mapOf("productNumber" to "DEMO-MOUSE", "amount" to 100), Instant.now().minusSeconds(age),
            owner = "tester")

    @Test
    fun `pop returns and removes the proposal`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        store.put(proposal("p1", "s1"))
        assertEquals("receiveStock", store.pop("p1")?.toolName)
        assertNull(store.pop("p1"))
    }

    @Test
    fun `get returns the proposal without removing it`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        store.put(proposal("p1", "s1"))
        assertEquals("p1", store.get("p1")?.id)
        // Still present after get (non-removing)
        assertEquals("p1", store.get("p1")?.id)
        assertEquals("p1", store.pop("p1")?.id)
        assertNull(store.get("p1"))
    }

    @Test
    fun `get does not return an expired proposal`() {
        val store = ActionProposalStore(ttlSeconds = 1)
        store.put(proposal("old", "s1", age = 5))
        assertNull(store.get("old"))
    }

    @Test
    fun `latestFor returns the newest live proposal for a session`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        store.put(proposal("p1", "s1"))
        store.put(proposal("p2", "s1"))
        assertEquals("p2", store.latestFor("s1")?.id)
    }

    @Test
    fun `expired proposals are not returned`() {
        val store = ActionProposalStore(ttlSeconds = 1)
        store.put(proposal("old", "s1", age = 5))
        assertNull(store.latestFor("s1"))
        assertNull(store.pop("old"))
    }
}
