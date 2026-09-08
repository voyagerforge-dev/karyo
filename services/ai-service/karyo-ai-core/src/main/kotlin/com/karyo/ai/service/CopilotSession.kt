package com.karyo.ai.service

import jakarta.enterprise.context.RequestScoped

/**
 * Request-scoped session holder. CopilotResource sets [sessionId] from the incoming
 * ChatRequest BEFORE calling the copilot service, so @Tool beans can read it without
 * needing access to the @MemoryId parameter directly.
 */
@RequestScoped
class CopilotSession {
    var sessionId: String = "default"
}
