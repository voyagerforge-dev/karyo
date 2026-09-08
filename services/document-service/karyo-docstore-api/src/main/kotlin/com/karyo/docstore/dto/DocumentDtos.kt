@file:Suppress("MatchingDeclarationName")
// File name is a hard task-brief requirement (DocumentDtos.kt) — now holds both the documents
// archive DTO and (Task 3, docstore-templates sprint) the per-client template override DTOs.
package com.karyo.docstore.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * A stored document's metadata. Deliberately excludes [content] — the archive is bytes-heavy and
 * the content is served separately via `GET /{id}/content`, mirroring how the 10 live document
 * endpoints stream bytes rather than embedding them in JSON.
 */
data class StoredDocumentResponse(
    val id: Long,
    val entityType: String,
    val entityId: Long,
    val documentType: String,
    val fileName: String,
    val mediaType: String,
    val sizeBytes: Int,
    val created: Instant,
)

/**
 * `POST /api/v1/document-templates` body. `content` has no `@Size` char cap here — the 256 KB
 * limit is a *byte* count (UTF-8 encoded), checked in `DocumentTemplateService` where the actual
 * byte length is computed, not approximated via a character-count annotation.
 */
data class CreateDocumentTemplateRequest(
    val clientId: Long,
    @field:NotBlank @field:Size(max = 255) val templatePath: String,
    @field:NotBlank val content: String,
)

/**
 * A template override version's full record, INCLUDING [content] (unlike [StoredDocumentResponse]):
 * a template is Qute source text, not a bytes-heavy binary, and the upload/rollback UI needs the
 * content to show a diff/preview between versions.
 */
data class DocumentTemplateResponse(
    val id: Long,
    val clientId: Long,
    val templatePath: String,
    val templateVersion: Int,
    val active: Boolean,
    val content: String,
    val created: Instant,
    val modified: Instant,
)
