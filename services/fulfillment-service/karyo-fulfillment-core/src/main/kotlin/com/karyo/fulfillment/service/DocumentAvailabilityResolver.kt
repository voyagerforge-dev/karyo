package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.DocumentAvailabilityStrategy
import com.karyo.fulfillment.spi.DocumentType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

@ApplicationScoped
class DocumentAvailabilityResolver(private val strategies: Instance<DocumentAvailabilityStrategy>) {
    fun availableFrom(type: DocumentType): Int =
        strategies.sortedBy { it.priority }.firstNotNullOfOrNull { it.availableFrom(type) }
            ?: error("No DocumentAvailabilityStrategy answered for $type (built-in missing?)")
}
