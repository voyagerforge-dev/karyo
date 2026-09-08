package com.karyo.ai.service

import com.karyo.ai.config.AiConfig
import com.karyo.ai.config.AiProvider
import com.karyo.ai.exception.CopilotDisabledException
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class CopilotService(
    private val aiConfig: AiConfig,
    private val claude: ClaudeCopilot,
    private val local: LocalCopilot,
) {
    fun chat(sessionId: String, message: String): String = when (aiConfig.provider) {
        AiProvider.ANTHROPIC -> claude.chat(sessionId, message)
        AiProvider.OLLAMA -> local.chat(sessionId, message)
        AiProvider.NONE -> throw CopilotDisabledException()
    }
}
