package com.karyo.layout.service

import com.karyo.documents.DocumentRenderer
import com.karyo.documents.DocumentStore
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.StorageLocationRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * D11: storage-location ZPL barcode label — Code128 of `scanCode ?: name` plus the location
 * name and a zone/area line (`area` is a required `@ManyToOne`, always present; `zone` is
 * nullable and renders "—" when absent). Rendered through the shared [DocumentRenderer] ZPL
 * path, so `sanitizeZpl` strips `^`/`~` from any interpolated string automatically.
 *
 * Tenant scoping mirrors [LocationService.findById]'s private `findEntityById` idiom (404 on
 * missing/foreign, never a leak) — duplicated here rather than reused because that helper is
 * private and [LocationService.findById] itself returns a DTO, not the entity this needs for
 * the lazy `zone`/`area` names.
 */
@ApplicationScoped
class LocationDocumentService(
    private val locationRepository: StorageLocationRepository,
    private val renderer: DocumentRenderer,
    private val documentStore: Instance<DocumentStore>,
) {
    fun labelZpl(id: Long, clientId: Long, store: Boolean = false): String {
        val entity = findEntityById(id, clientId)
        val zpl = renderer.render("/templates/location-label.zpl", labelData(entity), entity.clientId)
        if (store && documentStore.isResolvable) {
            documentStore.get().store(
                ownerClientId = entity.clientId,
                entityType = "location",
                entityId = entity.id!!,
                documentType = "label",
                fileName = "label-${entity.id}.zpl",
                mediaType = "text/plain; charset=utf-8",
                content = zpl.toByteArray(Charsets.UTF_8),
            )
        }
        return zpl
    }

    private fun findEntityById(id: Long, clientId: Long): StorageLocation {
        val entity = locationRepository.findById(id)
            ?: throw LayoutException.NotFound("StorageLocation", id)
        if (entity.clientId != clientId) {
            throw LayoutException.NotFound("StorageLocation", id)
        }
        return entity
    }

    private fun labelData(entity: StorageLocation): Map<String, Any?> = mapOf(
        "payload" to (entity.scanCode ?: entity.name),
        "name" to entity.name,
        "zoneName" to (entity.zone?.name ?: "—"),
        "areaName" to entity.area.name,
    )
}
