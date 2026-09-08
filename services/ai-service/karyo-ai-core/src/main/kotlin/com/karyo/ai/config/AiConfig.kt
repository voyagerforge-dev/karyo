package com.karyo.ai.config

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

enum class AiProvider { ANTHROPIC, OLLAMA, NONE }

@ApplicationScoped
class AiConfig(
    @ConfigProperty(name = "karyo.ai.provider", defaultValue = "none") private val raw: String,
) {
    val provider: AiProvider = when (raw.trim().lowercase()) {
        "anthropic" -> AiProvider.ANTHROPIC
        "ollama" -> AiProvider.OLLAMA
        else -> AiProvider.NONE
    }
    val enabled: Boolean get() = provider != AiProvider.NONE
    val providerLabel: String get() = provider.name.lowercase()
}
