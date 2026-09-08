package com.karyo.docstore.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * A persisted, generated document (PDF/ZPL) — the archive row behind `?store=true` on the 10
 * live document endpoints. `clientId` (inherited from [TenantEntity]) is always the subject
 * ENTITY's owner (e.g. the shipment's client), never the acting principal — see
 * [com.karyo.documents.DocumentStore].
 *
 * `content` is a plain `ByteArray` mapped straight onto the `bytea` column: there is no
 * BYTEA-mapping precedent elsewhere in the codebase, but Hibernate 6 maps `ByteArray` to
 * `bytea` by default with no extra annotation (`@Lob` is for the older `oid`/large-object
 * path and is deliberately not used here).
 */
@Entity
@Table(name = "documents")
class StoredDocument : TenantEntity() {

    @Column(name = "entity_type", nullable = false, length = 64)
    lateinit var entityType: String

    @Column(name = "entity_id", nullable = false)
    var entityId: Long = 0

    @Column(name = "document_type", nullable = false, length = 64)
    lateinit var documentType: String

    @Column(name = "file_name", nullable = false, length = 255)
    lateinit var fileName: String

    @Column(name = "media_type", nullable = false, length = 64)
    lateinit var mediaType: String

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Int = 0

    @Column(name = "content", nullable = false)
    lateinit var content: ByteArray
}
