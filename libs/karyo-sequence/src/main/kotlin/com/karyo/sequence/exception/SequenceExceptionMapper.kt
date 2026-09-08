package com.karyo.sequence.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * Maps every [SequenceException] to 422, mirroring the per-subtype `when` shape used by
 * `InventoryExceptionMapper` (`com.karyo.inventory.exception.InventoryExceptionMapper`) for
 * `InventoryException.ValidationFailed` and friends — a `@Provider` scoped to this lib's own
 * exception hierarchy, discovered by karyo-app's JAX-RS runtime the same way.
 */
@Provider
class SequenceExceptionMapper : ExceptionMapper<SequenceException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: SequenceException): Response {
        val type = when (exception) {
            is SequenceException.Exhausted -> "sequence-exhausted"
            is SequenceException.TooLong -> "sequence-too-long"
        }
        val problem = ProblemDetail(
            type = "https://karyo.com/errors/$type",
            title = type.replace("-", " ").replaceFirstChar { it.uppercase() },
            status = STATUS,
            detail = exception.message ?: "Unknown error",
            instance = uriInfo.requestUri.path,
        )
        return Response.status(STATUS).entity(problem).build()
    }

    companion object {
        private const val STATUS = 422
    }
}
