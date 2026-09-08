package com.karyo.docstore.event

/** Event payload published when a new template override version is uploaded and made active. */
data class DocumentTemplateUploadedEvent(
    val templateId: Long,
    val clientId: Long,
    val templatePath: String,
    val templateVersion: Int,
)
