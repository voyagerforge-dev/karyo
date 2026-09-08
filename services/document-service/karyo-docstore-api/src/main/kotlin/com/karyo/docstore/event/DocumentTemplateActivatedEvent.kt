package com.karyo.docstore.event

/** Event payload published when a (possibly older) template version is made the active one. */
data class DocumentTemplateActivatedEvent(
    val templateId: Long,
    val clientId: Long,
    val templatePath: String,
    val templateVersion: Int,
)
