package com.karyo.stocktaking.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class StocktakingExceptionMapper : ExceptionMapper<StocktakingException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: StocktakingException): Response {
        val (status, type) = when (exception) {
            is StocktakingException.NotFound -> 404 to "count-not-found"
            is StocktakingException.LocationLocked -> 409 to "count-location-locked"
            is StocktakingException.ReservedStock -> 409 to "count-reserved-stock"
            is StocktakingException.InvalidState -> 409 to "count-invalid-state"
            is StocktakingException.InvalidCount -> 422 to "count-invalid-count"
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
