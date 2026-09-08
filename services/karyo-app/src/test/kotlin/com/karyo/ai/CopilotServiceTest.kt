package com.karyo.ai

import com.karyo.ai.config.AiConfig
import com.karyo.ai.config.AiProvider
import com.karyo.ai.exception.CopilotDisabledException
import com.karyo.ai.service.ClaudeCopilot
import com.karyo.ai.service.CopilotService
import com.karyo.ai.service.LocalCopilot
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CopilotServiceTest {

    private val claude = mockk<ClaudeCopilot>()
    private val local = mockk<LocalCopilot>()

    private fun svc(provider: AiProvider): CopilotService {
        val cfg = mockk<AiConfig>()
        every { cfg.provider } returns provider
        return CopilotService(cfg, claude, local)
    }

    @Test
    fun `dispatches to claude when provider is anthropic`() {
        every { claude.chat("s1", "hi") } returns "claude says hi"
        assertEquals("claude says hi", svc(AiProvider.ANTHROPIC).chat("s1", "hi"))
    }

    @Test
    fun `dispatches to local when provider is ollama`() {
        every { local.chat("s1", "hi") } returns "local says hi"
        assertEquals("local says hi", svc(AiProvider.OLLAMA).chat("s1", "hi"))
    }

    @Test
    fun `throws when provider is none`() {
        assertThrows(CopilotDisabledException::class.java) { svc(AiProvider.NONE).chat("s1", "hi") }
    }
}
