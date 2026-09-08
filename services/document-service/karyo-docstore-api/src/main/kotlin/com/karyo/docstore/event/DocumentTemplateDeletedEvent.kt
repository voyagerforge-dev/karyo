package com.karyo.docstore.event

/** Event payload published when a template override version is hard-deleted. */
data class DocumentTemplateDeletedEvent(
    val templateId: Long,
    val clientId: Long,
    val templatePath: String,
    val templateVersion: Int,
)
