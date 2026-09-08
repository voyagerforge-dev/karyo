package com.karyo.auth.api.v1

import com.karyo.auth.dto.CreateUserRequest
import com.karyo.auth.dto.ResetPasswordRequest
import com.karyo.auth.dto.UpdateUserRequest
import com.karyo.auth.dto.UserResponse
import com.karyo.auth.service.RoleService
import com.karyo.auth.service.UserManagementService
import com.karyo.common.exception.ProblemDetail
import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/users")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class UserResource(
    private val userManagementService: UserManagementService,
    private val roleService: RoleService,
) {

    @POST
    @RolesAllowed("user-admin")
    fun createUser(@Valid request: CreateUserRequest): Response {
        val user = userManagementService.createUser(request)
        return Response.status(Response.Status.CREATED).entity(user).build()
    }

    @GET
    @RolesAllowed("user-admin")
    fun listUsers(
        @BeanParam pagination: PaginationParams,
        @QueryParam("search") search: String?,
        @QueryParam("enabled") enabled: Boolean?,
    ): PaginatedResponse<UserResponse> =
        userManagementService.listUsersPaginated(pagination, search, enabled)

    @GET
    @Path("/{id}")
    @RolesAllowed("user-admin")
    fun getUser(@PathParam("id") id: String): UserResponse = userManagementService.getUser(id)

    @PUT
    @Path("/{id}")
    @RolesAllowed("user-admin")
    fun updateUser(@PathParam("id") id: String, @Valid request: UpdateUserRequest): UserResponse =
        userManagementService.updateUser(id, request)

    @PUT
    @Path("/{id}/deactivate")
    @RolesAllowed("user-admin")
    fun deactivateUser(@PathParam("id") id: String): UserResponse =
        userManagementService.deactivateUser(id)

    @PUT
    @Path("/{id}/reactivate")
    @RolesAllowed("user-admin")
    fun reactivateUser(@PathParam("id") id: String): UserResponse =
        userManagementService.reactivateUser(id)

    @PUT
    @Path("/{id}/roles")
    @RolesAllowed("user-admin")
    fun manageRoles(
        @PathParam("id") id: String,
        @QueryParam("action") action: String?,
        @QueryParam("role") role: String?,
    ): Response {
        if (action.isNullOrBlank() || role.isNullOrBlank()) {
            val problem = ProblemDetail(
                type = "https://karyo.com/errors/bad-request",
                title = "Bad request",
                status = 400,
                detail = "Both 'action' and 'role' query parameters are required",
            )
            return Response.status(400).entity(problem).build()
        }
        when (action) {
            "assign" -> roleService.assignRole(id, role)
            "revoke" -> roleService.revokeRole(id, role)
            else -> {
                val problem = ProblemDetail(
                    type = "https://karyo.com/errors/bad-request",
                    title = "Bad request",
                    status = 400,
                    detail = "Invalid action: '$action'. Use 'assign' or 'revoke'",
                )
                return Response.status(400).entity(problem).build()
            }
        }
        return Response.ok().build()
    }

    @POST
    @Path("/{id}/reset-password")
    @RolesAllowed("user-admin")
    fun resetPassword(@PathParam("id") id: String, @Valid request: ResetPasswordRequest): Response {
        userManagementService.resetPassword(id, request)
        return Response.noContent().build()
    }
}
