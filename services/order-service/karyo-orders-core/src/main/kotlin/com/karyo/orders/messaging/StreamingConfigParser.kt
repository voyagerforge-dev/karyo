package com.karyo.orders.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.orders.spi.StreamingStrategyConfig
import com.karyo.orders.vo.ReleaseMode

/** Shared by DefaultOrderReleasePort (wave exclusions) and DefaultStreamingReleasePort. */
internal object StreamingConfigParser {
    private const val MIN_BATCH = 1
    private const val MAX_BATCH = 1000

    fun parse(extensionProperties: String, objectMapper: ObjectMapper): StreamingStrategyConfig {
        val node = objectMapper.readTree(extensionProperties)
        val d = StreamingStrategyConfig()
        val maxWait = node.path("streamMaxWaitSeconds").asInt(d.streamMaxWaitSeconds).coerceAtLeast(0)
        val abandon = node.path("streamAbandonSeconds").asInt(d.streamAbandonSeconds).coerceAtLeast(maxWait)
        val timing = node.path("streamTimingStrategy").asText("").trim().ifEmpty { d.streamTimingStrategy }
        return StreamingStrategyConfig(
            releaseMode = ReleaseMode.parseOrNull(node.path("releaseMode").asText(null)) ?: d.releaseMode,
            streamBatchSize = node.path("streamBatchSize").asInt(d.streamBatchSize).coerceIn(MIN_BATCH, MAX_BATCH),
            streamMaxWaitSeconds = maxWait,
            streamAbandonSeconds = abandon,
            streamTimingStrategy = timing,
        )
    }
}
