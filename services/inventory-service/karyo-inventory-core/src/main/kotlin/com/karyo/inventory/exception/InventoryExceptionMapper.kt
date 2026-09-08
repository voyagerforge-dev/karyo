package com.karyo.inventory.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class InventoryExceptionMapper : ExceptionMapper<InventoryException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: InventoryException): Response {
        val (status, type) = when (exception) {
            is InventoryException.NotFound -> 404 to "not-found"
            is InventoryException.InsufficientStock -> 409 to "insufficient-stock"
            is InventoryException.StockLocked -> 409 to "stock-locked"
            is InventoryException.InvalidStateTransition -> 409 to "invalid-state-transition"
            is InventoryException.ConcurrencyConflict -> 409 to "concurrency-conflict"
            is InventoryException.ValidationFailed -> 400 to "validation-failed"
            is InventoryException.HasDependents -> 409 to "has-dependents"
            is InventoryException.DuplicateName -> 409 to "duplicate-name"
            is InventoryException.Forbidden -> 403 to "forbidden"
            is InventoryException.InvalidTarget -> 422 to "invalid-target"
            is InventoryException.Encumbered -> 409 to "encumbered"
            is InventoryException.NotConfigured -> 409 to "not-configured"
            is InventoryException.CrossOwner -> 409 to "cross-owner"
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
