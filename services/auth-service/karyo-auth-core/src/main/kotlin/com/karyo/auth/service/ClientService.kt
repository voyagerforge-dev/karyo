package com.karyo.auth.service

import com.karyo.auth.domain.model.Client
import com.karyo.auth.dto.ClientConsistencyReport
import com.karyo.auth.dto.ClientResponse
import com.karyo.auth.dto.CreateClientRequest
import com.karyo.auth.dto.UpdateClientRequest
import com.karyo.auth.event.ClientCreatedEvent
import com.karyo.auth.event.ClientDeactivatedEvent
import com.karyo.auth.event.ClientReactivatedEvent
import com.karyo.auth.event.ClientUpdatedEvent
import com.karyo.auth.exception.AuthException
import com.karyo.auth.repository.ClientRepository
import com.karyo.auth.vo.ClientState
import com.karyo.events.outbox.OutboxService
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import com.karyo.security.readScope
import com.karyo.security.writeScope
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Goods-owner administration.
 *
 * Read scoping is unusual here and deliberate: for every other entity the tenant check compares
 * a `client_id` column, but a client row *is* the tenant, so the row's own `id` is what the
 * scope is asked about. An ops principal sees every client; a goods-owner principal sees only
 * itself.
 *
 * Writes are scoped the same way via [writeScope], and must be: every mutation endpoint echoes
 * the full [ClientResponse] back, so an unscoped write would also be an unscoped read.
 */
@ApplicationScoped
class ClientService(
    private val clientRepository: ClientRepository,
    private val outboxService: OutboxService,
    private val tenantContext: TenantContext,
) {

    fun list(): List<ClientResponse> {
        val scope = tenantContext.readScope()
        return clientRepository.listAllOrdered()
            .filter { scope.permits(it.id!!) }
            .map(::toResponse)
    }

    fun requireById(id: Long): ClientResponse {
        val entity = clientRepository.findById(id) ?: throw AuthException.ClientNotFound(id)
        // Out-of-scope reads are reported as absent, never as forbidden — a 403 would
        // confirm the row exists to a principal not entitled to know that.
        if (!tenantContext.readScope().permits(entity.id!!)) throw AuthException.ClientNotFound(id)
        return toResponse(entity)
    }

    /**
     * Resolves a goods owner that may receive new work — currently new users.
     *
     * Separate from [requireById] on purpose: reading a retired owner is legitimate (the clients
     * screen still lists it), attaching a new identity to one is not. Keeping the state check
     * here makes it a server-side invariant rather than a convention the create form happens to
     * follow.
     */
    fun requireActiveById(id: Long): ClientResponse {
        val client = requireById(id)
        if (client.state != ClientState.ACTIVE) {
            throw AuthException.ClientNotActive(id, client.state.name)
        }
        return client
    }

    @Transactional
    fun create(request: CreateClientRequest): ClientResponse {
        // Creating a goods owner is platform administration. A goods-owner principal has no
        // business minting new tenants, and unlike the id-bearing paths below there is no
        // existing row here for a scope check to compare against.
        if (tenantContext.principalKind == PrincipalKind.OWNER) {
            throw AuthException.ClientAdministrationForbidden()
        }
        requireUniqueNumber(request.number)
        requireUniqueName(request.name)

        val entity = Client().apply {
            name = request.name
            number = request.number
            code = request.code
            email = request.email
            phone = request.phone
            fax = request.fax
            state = ClientState.ACTIVE.code
        }
        clientRepository.persist(entity)
        outboxService.publish(
            aggregateType = "Client",
            aggregateId = entity.id!!,
            eventType = "ClientCreated",
            payload = ClientCreatedEvent(
                clientId = entity.id!!,
                name = entity.name,
                number = entity.number,
                code = entity.code,
                // A client row IS the tenant dimension (see class KDoc) — the event is about
                // itself, not whichever OPS principal happened to mint it.
                tenantId = entity.id!!,
            ),
            tenantId = entity.id!!,
        )
        return toResponse(entity)
    }

    @Transactional
    fun update(id: Long, request: UpdateClientRequest): ClientResponse {
        val entity = loadMutable(id)
        if (entity.name != request.name) requireUniqueName(request.name)
        entity.name = request.name
        entity.code = request.code
        entity.email = request.email
        entity.phone = request.phone
        entity.fax = request.fax
        entity.modified = Instant.now()
        outboxService.publish(
            aggregateType = "Client",
            aggregateId = entity.id!!,
            eventType = "ClientUpdated",
            payload = ClientUpdatedEvent(
                clientId = entity.id!!,
                name = entity.name,
                number = entity.number,
                tenantId = entity.id!!,
            ),
            tenantId = entity.id!!,
        )
        return toResponse(entity)
    }

    @Transactional
    fun deactivate(id: Long): ClientResponse = setState(id, ClientState.INACTIVE)

    @Transactional
    fun reactivate(id: Long): ClientResponse = setState(id, ClientState.ACTIVE)

    fun consistencyReport(): ClientConsistencyReport {
        // A full-schema dangling-id scan is platform administration, same as create(): a
        // goods-owner principal has no business running it, even while holding user-admin.
        if (tenantContext.principalKind == PrincipalKind.OWNER) {
            throw AuthException.ClientAdministrationForbidden()
        }
        return ClientConsistencyReport(
            danglingClientIds = clientRepository.findDanglingClientIds(),
            checkedAt = Instant.now(),
        )
    }

    fun toResponse(entity: Client): ClientResponse = ClientResponse(
        id = entity.id!!,
        name = entity.name,
        number = entity.number,
        code = entity.code,
        email = entity.email,
        phone = entity.phone,
        fax = entity.fax,
        state = ClientState.fromCode(entity.state),
        isSystemClient = entity.isSystemClient,
    )

    private fun setState(id: Long, target: ClientState): ClientResponse {
        val entity = loadMutable(id)
        entity.state = target.code
        entity.modified = Instant.now()
        val (eventType, payload) = when (target) {
            ClientState.INACTIVE -> "ClientDeactivated" to ClientDeactivatedEvent(
                clientId = entity.id!!,
                number = entity.number,
                tenantId = entity.id!!,
            )
            ClientState.ACTIVE -> "ClientReactivated" to ClientReactivatedEvent(
                clientId = entity.id!!,
                number = entity.number,
                tenantId = entity.id!!,
            )
        }
        outboxService.publish(
            aggregateType = "Client",
            aggregateId = entity.id!!,
            eventType = eventType,
            payload = payload,
            tenantId = entity.id!!,
        )
        return toResponse(entity)
    }

    /**
     * Loads a client for mutation, rejecting anything outside the principal's write scope and
     * then the protected system client.
     *
     * The scope check runs *first* and answers 404, mirroring [requireById]: a 403 would confirm
     * the row exists, and ordering it before the system-client check keeps an out-of-scope
     * principal from distinguishing "system client" from "not yours".
     */
    private fun loadMutable(id: Long): Client {
        val entity = clientRepository.findById(id)
        if (entity == null || !tenantContext.writeScope().permits(entity.id!!)) {
            throw AuthException.ClientNotFound(id)
        }
        if (entity.isSystemClient) throw AuthException.SystemClientProtected()
        return entity
    }

    private fun requireUniqueNumber(number: String) {
        clientRepository.findByNumber(number)?.let { throw AuthException.DuplicateClient("number", number) }
    }

    private fun requireUniqueName(name: String) {
        clientRepository.findByName(name)?.let { throw AuthException.DuplicateClient("name", name) }
    }
}
