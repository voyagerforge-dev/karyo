package com.karyo.documents

/**
 * Persist a generated document under the ENTITY OWNER's tenant. Implemented by karyo-docstore;
 * callers (doc services) resolve it via Instance<DocumentStore> and skip silently if absent.
 * [ownerClientId] is the document's subject entity's client_id — NEVER the acting principal's.
 */
interface DocumentStore {
    fun store(
        ownerClientId: Long,
        entityType: String,
        entityId: Long,
        documentType: String,
        fileName: String,
        mediaType: String,
        content: ByteArray,
    ): Long
}
