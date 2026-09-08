package com.karyo.ai.api.v1

import com.karyo.ai.api.v1.dto.AiConfigResponse
import com.karyo.ai.config.AiConfig
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/v1/ai/config")
class CopilotConfigResource(private val aiConfig: AiConfig) {

    @GET
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    fun config(): AiConfigResponse = AiConfigResponse(aiConfig.enabled, aiConfig.providerLabel)
}
