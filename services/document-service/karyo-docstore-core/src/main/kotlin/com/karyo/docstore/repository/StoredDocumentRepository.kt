package com.karyo.docstore.repository

import com.karyo.docstore.domain.model.StoredDocument
import com.karyo.docstore.dto.StoredDocumentResponse
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.hibernate.orm.panache.kotlin.PanacheQuery
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class StoredDocumentRepository : PanacheRepository<StoredDocument> {

    /**
     * Builds the filtered, ordered query for listing. Scoping (owner vs. unscoped) is applied
     * by the caller ([com.karyo.docstore.service.DocumentStoreService]) by prepending a
     * `clientId = ?` condition — kept out of this method so the OPS-unscoped case doesn't need
     * a fake always-true clause.
     */
    private fun search(
        clientId: Long?,
        entityType: String?,
        entityId: Long?,
        documentType: String?,
    ): PanacheQuery<StoredDocument> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        var idx = 1

        clientId?.let { conditions.add("clientId = ?${idx++}"); params.add(it) }
        entityType?.let { conditions.add("entityType = ?${idx++}"); params.add(it) }
        entityId?.let { conditions.add("entityId = ?${idx++}"); params.add(it) }
        documentType?.let { conditions.add("documentType = ?${idx++}"); params.add(it) }

        val where = if (conditions.isEmpty()) "" else conditions.joinToString(" and ")
        return find("$where order by created desc", *params.toTypedArray())
    }

    /**
     * Metadata-only listing query (final-review F1): projects onto [StoredDocumentResponse] via
     * Panache's `.project()`, which rewrites the query as a JPQL constructor expression
     * (`select new StoredDocumentResponse(...)`) matched against [StoredDocument]'s persistent
     * fields by name. `content` (the BYTEA column) isn't a field of the response DTO, so it is
     * never included in the generated SQL SELECT — this is a genuine content-free query, not an
     * app-side hydrate-then-drop of the full entity.
     */
    fun searchMetadata(
        clientId: Long?,
        entityType: String?,
        entityId: Long?,
        documentType: String?,
    ): PanacheQuery<StoredDocumentResponse> =
        search(clientId, entityType, entityId, documentType).project(StoredDocumentResponse::class.java)
}
