package com.karyo.docstore.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.docstore.dto.StoredDocumentResponse
import com.karyo.docstore.service.DocumentStoreService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * The documents archive REST surface — list/download/delete over rows persisted by
 * [com.karyo.docstore.service.DocumentStoreService]. Reads use the same 5-role list as
 * `ClientResource.list` (ordinary users legitimately need to browse the archive); delete is
 * MANAGER+ only, mirroring the destructive-write convention elsewhere in the codebase.
 */
@Path("/api/v1/documents")
@ApplicationScoped
class DocumentResource(
    private val documentStoreService: DocumentStoreService,
) {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("VIEWER", "OPERATOR", "RECEIVER", "MANAGER", "ADMIN")
    fun list(
        @QueryParam("entityType") entityType: String?,
        @QueryParam("entityId") entityId: Long?,
        @QueryParam("documentType") documentType: String?,
        @BeanParam pagination: PaginationParams,
    ): PaginatedResponse<StoredDocumentResponse> =
        documentStoreService.list(entityType, entityId, documentType, pagination.page, pagination.size)

    @GET
    @Path("/{id}/content")
    @RolesAllowed("VIEWER", "OPERATOR", "RECEIVER", "MANAGER", "ADMIN")
    fun content(@PathParam("id") id: Long): Response {
        val document = documentStoreService.findForRead(id)
        return Response.ok(document.content)
            .type(document.mediaType)
            .header("Content-Disposition", "attachment; filename=\"${document.fileName}\"")
            .build()
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("MANAGER", "ADMIN")
    fun delete(@PathParam("id") id: Long): Response {
        documentStoreService.deleteById(id)
        return Response.noContent().build()
    }
}
