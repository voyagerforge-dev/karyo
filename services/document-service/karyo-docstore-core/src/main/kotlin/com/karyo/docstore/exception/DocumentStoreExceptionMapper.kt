package com.karyo.docstore.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class DocumentStoreExceptionMapper : ExceptionMapper<DocumentStoreException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: DocumentStoreException): Response {
        val (status, type) = when (exception) {
            is DocumentStoreException.NotFound -> 404 to "document-not-found"
            is DocumentStoreException.ContentTooLarge -> 413 to "document-content-too-large"
            is DocumentStoreException.Forbidden -> 403 to "document-store-forbidden"
        }
        val problem = ProblemDetail(
            type = "https://karyo.com/errors/$type",
            title = type.replace("-", " ").replaceFirstChar { it.uppercase() },
            status = status,
            detail = exception.message ?: "Unknown error",
            instance = uriInfo.requestUri.path,
        )
        return Response.status(status).entity(problem).build()
    }
}
