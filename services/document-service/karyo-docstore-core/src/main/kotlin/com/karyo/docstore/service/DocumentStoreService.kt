package com.karyo.docstore.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.paginatedResponse
import com.karyo.docstore.domain.model.StoredDocument
import com.karyo.docstore.dto.StoredDocumentResponse
import com.karyo.docstore.event.DocumentDeletedEvent
import com.karyo.docstore.event.DocumentStoredEvent
import com.karyo.docstore.exception.DocumentStoreException
import com.karyo.docstore.repository.StoredDocumentRepository
import com.karyo.documents.DocumentStore
import com.karyo.events.outbox.OutboxService
import com.karyo.security.TenantContext
import com.karyo.security.TenantScope
import com.karyo.security.readScope
import com.karyo.security.writeScope
import io.quarkus.panache.common.Page
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * The documents archive. Implements [DocumentStore] — the SPI the 5 existing document services
 * resolve via `Instance<DocumentStore>` (Task 2) — plus the read/delete surface behind
 * [com.karyo.docstore.api.v1.DocumentResource].
 *
 * Tenant scoping mirrors the ordinary (non-Client) TenantEntity idiom used across the codebase
 * (e.g. `UnitLoadService.findById`): a row's own `clientId` column is compared against the
 * principal's [TenantScope], never the acting principal's `clientId` directly — a stored
 * document's owner is always the entity's subject owner (see [DocumentStore] KDoc), which for
 * an OPS principal calling on another owner's behalf is a *different* client than the caller's.
 */
@ApplicationScoped
class DocumentStoreService(
    private val repository: StoredDocumentRepository,
    private val outboxService: OutboxService,
    private val tenantContext: TenantContext,
) : DocumentStore {

    @Transactional
    override fun store(
        ownerClientId: Long,
        entityType: String,
        entityId: Long,
        documentType: String,
        fileName: String,
        mediaType: String,
        content: ByteArray,
    ): Long {
        // No existing row to leak here (unlike a 404 on an id that already exists) -- an OWNER
        // principal attempting to store a document under a different owner is refused outright,
        // mirroring ClientService.create()'s ClientAdministrationForbidden reasoning.
        if (!tenantContext.writeScope().permits(ownerClientId)) {
            throw DocumentStoreException.Forbidden(ownerClientId)
        }
        if (content.size > MAX_CONTENT_BYTES) {
            throw DocumentStoreException.ContentTooLarge(content.size, MAX_CONTENT_BYTES)
        }

        val entity = StoredDocument().apply {
            clientId = ownerClientId
            this.entityType = entityType
            this.entityId = entityId
            this.documentType = documentType
            this.fileName = fileName
            this.mediaType = mediaType
            this.sizeBytes = content.size
            this.content = content
        }
        repository.persist(entity)

        outboxService.publish(
            aggregateType = "Document",
            aggregateId = entity.id!!,
            eventType = "document.stored",
            payload = DocumentStoredEvent(
                documentId = entity.id!!,
                entityType = entityType,
                entityId = entityId,
                documentType = documentType,
                clientId = ownerClientId,
            ),
            // Attributed to the row's owner, never the acting principal -- see class KDoc.
            tenantId = ownerClientId,
        )
        return entity.id!!
    }

    fun list(
        entityType: String?,
        entityId: Long?,
        documentType: String?,
        page: Int,
        size: Int,
    ): PaginatedResponse<StoredDocumentResponse> {
        val scope = tenantContext.readScope()
        val clientIdFilter = (scope as? TenantScope.Owner)?.clientId
        // Clamp locally (final-review F1) -- PaginationParams itself carries no max-size clamp
        // and is shared by every paginated endpoint, so it is deliberately NOT touched here.
        val effectiveSize = size.coerceIn(1, MAX_PAGE_SIZE)

        val query = repository.searchMetadata(clientIdFilter, entityType, entityId, documentType)
        val total = query.count()
        val content = query.page(Page.of(page, effectiveSize)).list()
        return paginatedResponse(content, page, effectiveSize, total)
    }

    /** Returns the full entity (including [StoredDocument.content]) for the download route. */
    fun findForRead(id: Long): StoredDocument {
        val entity = repository.findById(id) ?: throw DocumentStoreException.NotFound(id)
        // Out-of-scope reads answer 404, never 403 -- a 403 would confirm the row exists to a
        // principal not entitled to know that (mirrors ClientService.requireById).
        if (!tenantContext.readScope().permits(entity.clientId)) throw DocumentStoreException.NotFound(id)
        return entity
    }

    @Transactional
    fun deleteById(id: Long) {
        val entity = repository.findById(id) ?: throw DocumentStoreException.NotFound(id)
        if (!tenantContext.writeScope().permits(entity.clientId)) throw DocumentStoreException.NotFound(id)

        outboxService.publish(
            aggregateType = "Document",
            aggregateId = entity.id!!,
            eventType = "document.deleted",
            payload = DocumentDeletedEvent(
                documentId = entity.id!!,
                entityType = entity.entityType,
                entityId = entity.entityId,
                documentType = entity.documentType,
                clientId = entity.clientId,
            ),
            tenantId = entity.clientId,
        )
        repository.delete(entity)
    }

    companion object {
        /** 10 MB, per the docstore-templates sprint brief. */
        const val MAX_CONTENT_BYTES: Int = 10 * 1024 * 1024

        /**
         * Hard ceiling on the documents LIST page size (final-review F1). `PaginationParams`
         * carries no max-size clamp of its own and is shared by every paginated endpoint in the
         * app, so it is deliberately left untouched here — the clamp is applied locally in
         * [list] instead. Without it, `GET /api/v1/documents?size=100000` would ask Postgres for
         * the entire in-scope archive in a single round trip; 200 keeps one page bounded
         * regardless of what the caller requests.
         */
        const val MAX_PAGE_SIZE: Int = 200
    }
}
