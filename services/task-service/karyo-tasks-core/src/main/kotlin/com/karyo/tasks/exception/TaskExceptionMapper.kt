package com.karyo.tasks.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class TaskExceptionMapper : ExceptionMapper<TaskException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: TaskException): Response {
        val (status, type) = when (exception) {
            is TaskException.NotFound -> NOT_FOUND to "not-found"
            is TaskException.InvalidReference -> NOT_FOUND to "invalid-reference"
            is TaskException.InvalidTransition -> CONFLICT to "invalid-state-transition"
            is TaskException.ValidationFailed -> BAD_REQUEST to "validation-failed"
            is TaskException.NoDestination -> CONFLICT to "no-destination"
            is TaskException.NotCancelable -> CONFLICT to "not-cancelable"
            is TaskException.TransportPaused -> CONFLICT to "transport-paused"
            is TaskException.TransportPauseConflict -> CONFLICT to "transport-pause-conflict"
            is TaskException.MixedSourceLoad -> CONFLICT to "mixed-source-load"
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

    companion object {
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val BAD_REQUEST = 400
    }
}
