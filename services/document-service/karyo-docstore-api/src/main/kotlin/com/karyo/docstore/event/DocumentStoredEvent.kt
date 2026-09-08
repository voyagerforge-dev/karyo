package com.karyo.docstore.event

/** Event payload published when a document is archived. Attributed to the entity owner's client. */
data class DocumentStoredEvent(
    val documentId: Long,
    val entityType: String,
    val entityId: Long,
    val documentType: String,
    val clientId: Long,
)
