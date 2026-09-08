package com.karyo.auth.service

import com.karyo.auth.repository.ClientRepository
import com.karyo.auth.spi.ClientLookup
import com.karyo.auth.vo.ClientState
import com.karyo.security.TenantContext
import com.karyo.security.readScope
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default in-process [ClientLookup]. Scoping asks the read scope about the client row's own
 * `id`, because a client row is the tenant dimension rather than something that carries a
 * `client_id`.
 */
@ApplicationScoped
class DefaultClientLookup(
    private val clientRepository: ClientRepository,
    private val tenantContext: TenantContext,
) : ClientLookup {

    override fun findNamesByIds(ids: Set<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val scope = tenantContext.readScope()
        return clientRepository.findByIds(ids)
            .filter { scope.permits(it.id!!) }
            .associate { it.id!! to it.name }
    }

    override fun exists(id: Long): Boolean = clientRepository.findById(id) != null

    override fun isActive(id: Long): Boolean =
        clientRepository.findById(id)?.state == ClientState.ACTIVE.code
}
