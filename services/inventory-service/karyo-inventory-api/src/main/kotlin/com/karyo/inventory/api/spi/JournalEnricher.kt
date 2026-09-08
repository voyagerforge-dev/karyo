package com.karyo.inventory.api.spi

/**
 * SPI for enriching journal entries with custom data.
 * Deploy as @Alternative @Priority in a client extension JAR.
 */
interface JournalEnricher {
    /** Called before a journal entry is persisted. Add custom fields via the context map. */
    fun enrich(context: Map<String, Any>): Map<String, Any> = context
}
