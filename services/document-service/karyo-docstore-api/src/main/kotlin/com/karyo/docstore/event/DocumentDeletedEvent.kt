package com.karyo.docstore.event

/** Event payload published when an archived document is deleted. Attributed to the owning client. */
data class DocumentDeletedEvent(
    val documentId: Long,
    val entityType: String,
    val entityId: Long,
    val documentType: String,
    val clientId: Long,
)
