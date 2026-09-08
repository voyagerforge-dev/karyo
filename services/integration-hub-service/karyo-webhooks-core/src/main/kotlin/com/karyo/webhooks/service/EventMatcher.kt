package com.karyo.webhooks.service

/** Pure event-type matching: '*' = all, trailing '*' = prefix, else exact. Any entry matches. */
object EventMatcher {
    fun matches(patterns: List<String>, eventType: String): Boolean =
        patterns.any { p ->
            when {
                p == "*" -> true
                p.endsWith("*") -> eventType.startsWith(p.dropLast(1))
                else -> p == eventType
            }
        }
}
