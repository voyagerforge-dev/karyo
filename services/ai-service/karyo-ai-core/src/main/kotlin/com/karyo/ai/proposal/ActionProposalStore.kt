package com.karyo.ai.proposal

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class ActionProposalStore(
    @ConfigProperty(name = "karyo.ai.proposal-ttl-seconds", defaultValue = "600") private val ttlSeconds: Long,
) {
    private val byId = ConcurrentHashMap<String, ActionProposal>()

    private fun live(p: ActionProposal): Boolean =
        p.createdAt.isAfter(Instant.now().minusSeconds(ttlSeconds))

    fun put(p: ActionProposal) {
        evict()
        byId[p.id] = p
    }

    /** Non-removing lookup; respects the same TTL liveness rule as [pop]. */
    fun get(id: String): ActionProposal? = byId[id]?.takeIf(::live)

    fun pop(id: String): ActionProposal? = byId.remove(id)?.takeIf(::live)

    fun latestFor(sessionId: String): ActionProposal? =
        byId.values.filter { it.sessionId == sessionId && live(it) }.maxByOrNull { it.createdAt }

    private fun evict() = byId.values.removeIf { !live(it) }
}
