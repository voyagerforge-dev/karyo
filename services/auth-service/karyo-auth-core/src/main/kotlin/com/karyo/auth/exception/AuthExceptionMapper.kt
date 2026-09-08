package com.karyo.auth.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class AuthExceptionMapper : ExceptionMapper<AuthException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: AuthException): Response {
        val (status, type) = when (exception) {
            is AuthException.UserNotFound -> 404 to "user-not-found"
            is AuthException.UserAlreadyExists -> 409 to "user-already-exists"
            is AuthException.RoleNotFound -> 404 to "role-not-found"
            is AuthException.KeycloakAdminError -> 502 to "keycloak-admin-error"
            is AuthException.TenantMismatch -> 403 to "tenant-mismatch"
            is AuthException.NotManageable -> 403 to "not-manageable"
            is AuthException.ClientNotFound -> 404 to "client-not-found"
            is AuthException.DuplicateClient -> 409 to "duplicate-client"
            is AuthException.SystemClientProtected -> 403 to "system-client-protected"
            is AuthException.ClientAdministrationForbidden -> 403 to "client-administration-forbidden"
            is AuthException.ClientSelectionRequired -> 400 to "client-selection-required"
            is AuthException.ClientNotActive -> 409 to "client-not-active"
            is AuthException.PrincipalKindNotPermitted -> 403 to "principal-kind-not-permitted"
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
