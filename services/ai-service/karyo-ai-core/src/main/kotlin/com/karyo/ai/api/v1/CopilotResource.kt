package com.karyo.ai.api.v1

import com.karyo.ai.api.v1.dto.ActionProposalDto
import com.karyo.ai.api.v1.dto.ChatRequest
import com.karyo.ai.api.v1.dto.ChatResponse
import com.karyo.ai.api.v1.dto.ConfirmResponse
import com.karyo.ai.exception.CopilotDisabledException
import com.karyo.ai.exception.CopilotForbiddenException
import com.karyo.ai.proposal.ActionExecutor
import com.karyo.ai.proposal.ActionProposalStore
import com.karyo.ai.service.CopilotService
import com.karyo.ai.service.CopilotSession
import com.karyo.common.exception.KaryoException
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/ai")
class CopilotResource(
    private val copilotService: CopilotService,
    private val copilotSession: CopilotSession,
    private val store: ActionProposalStore,
    private val executor: ActionExecutor,
    private val tenant: TenantContext,
) {

    @POST
    @Path("/chat")
    @RolesAllowed("inventory-read")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun chat(request: ChatRequest): Response = try {
        // Set sessionId on the request-scoped holder BEFORE calling the service so that
        // action @Tool beans (which inject CopilotSession) read the correct session.
        copilotSession.sessionId = request.sessionId
        val reply = copilotService.chat(request.sessionId, request.message)
        // Only surface a proposal that belongs to THIS user — a spoofed sessionId
        // belonging to another user must not disclose their pending proposal.
        val proposal = store.latestFor(request.sessionId)
            ?.takeIf { it.owner == tenant.username }
            ?.let { ActionProposalDto(it.id, it.summary, it.toolName) }
        Response.ok(ChatResponse(reply, proposal)).build()
    } catch (e: CopilotDisabledException) {
        Response.status(Response.Status.SERVICE_UNAVAILABLE).build()
    }

    @POST
    @Path("/confirm/{id}")
    @RolesAllowed("inventory-read")
    @Produces(MediaType.APPLICATION_JSON)
    fun confirm(@PathParam("id") id: String): Response {
        // Ownership check FIRST (non-removing). A proposal that is absent, expired, or
        // owned by another user yields 404 — never reveal existence, never execute.
        val p = store.get(id)
        if (p == null || p.owner != tenant.username) {
            return Response.status(Response.Status.NOT_FOUND).build()
        }
        // Role check BEFORE consuming the proposal — if the user lacks the write role, the
        // proposal must be preserved so the rightful owner can retry once granted the role.
        // Doing this after pop() would silently discard the proposal on every 403.
        val requiredRole = executor.requiredWriteRole(p.toolName)
        if (requiredRole != null && !tenant.roles.contains(requiredRole)) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity(ConfirmResponse("You don't have permission to execute ${p.toolName} (needs $requiredRole role)."))
                .build()
        }
        // Both ownership and role checks pass: now consume the proposal and execute.
        // The executor re-checks the write role inside execute() as a second line of defense
        // (privilege-escalation guard) and may throw domain exceptions during the mutation.
        store.pop(id)
        return try {
            Response.ok(ConfirmResponse(executor.execute(p))).build()
        } catch (e: CopilotForbiddenException) {
            // Belt-and-suspenders: executor's own role check fired — should not reach here.
            Response.status(Response.Status.FORBIDDEN)
                .entity(ConfirmResponse(e.message ?: "You don't have permission to execute this action."))
                .build()
        } catch (e: KaryoException) {
            // Domain rule violation (e.g. backward state transition, missing entity) -> 422.
            Response.status(422)
                .entity(ConfirmResponse(e.message ?: "Action could not be completed."))
                .build()
        }
    }
}
