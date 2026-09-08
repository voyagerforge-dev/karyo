package com.karyo.ai.service

import com.karyo.ai.tools.WarehouseActionTools
import com.karyo.ai.tools.WarehouseInsightTools
import com.karyo.ai.tools.WarehouseReadTools
import dev.langchain4j.service.MemoryId
import dev.langchain4j.service.SystemMessage
import dev.langchain4j.service.UserMessage
import io.quarkiverse.langchain4j.RegisterAiService

// T4: WarehouseReadTools. T5: WarehouseInsightTools. T7: WarehouseActionTools added.
@RegisterAiService(modelName = "local", tools = [WarehouseReadTools::class, WarehouseInsightTools::class, WarehouseActionTools::class])
interface LocalCopilot {
    @SystemMessage(CopilotPrompt.SYSTEM)
    fun chat(@MemoryId sessionId: String, @UserMessage message: String): String
}
