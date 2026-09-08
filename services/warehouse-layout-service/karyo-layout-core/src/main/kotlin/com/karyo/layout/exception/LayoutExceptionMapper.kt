package com.karyo.layout.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class LayoutExceptionMapper : ExceptionMapper<LayoutException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: LayoutException): Response {
        val (status, type) = when (exception) {
            is LayoutException.NotFound -> 404 to "layout-not-found"
            is LayoutException.DuplicateName -> 409 to "duplicate-name"
            is LayoutException.InvalidLockTransition -> 422 to "invalid-lock-transition"
            is LayoutException.LocationInUse -> 409 to "location-in-use"
            is LayoutException.InvalidReference -> 422 to "invalid-reference"
            is LayoutException.WeightLimitExceeded -> 422 to "weight-limit-exceeded"
            is LayoutException.ProductValidationFailed -> 422 to "product-validation-failed"
            is LayoutException.InvalidUsage -> 400 to "invalid-usage"
            is LayoutException.HasDependents -> 409 to "has-dependents"
            is LayoutException.InvalidReferenceList -> 400 to "invalid-reference-list"
            is LayoutException.InvalidSortTokens -> 400 to "invalid-sort-tokens"
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
