package com.karyo.inventory.messaging

import com.karyo.inventory.domain.model.InactiveProduct
import com.karyo.inventory.repository.InactiveProductRepository
import com.karyo.product.event.ItemDataStateChangedEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Observes ItemDataStateChangedEvent fired in-process by the product module.
 * Maintains a local projection of inactive products so that StockSelectionService
 * can exclude them without cross-module lookups (replaces the former Kafka consumer).
 * Runs synchronously and joins the caller's transaction (REQUIRED).
 */
@ApplicationScoped
class ProductStateChangedObserver(
    private val inactiveProductRepository: InactiveProductRepository,
) {
    private val log = Logger.getLogger(ProductStateChangedObserver::class.java)

    companion object {
        const val INACTIVE_STATE = 700
        const val ACTIVE_STATE = 100
    }

    @Transactional
    fun onProductStateChanged(@Observes event: ItemDataStateChangedEvent) {
        when (event.newState) {
            INACTIVE_STATE -> markInactive(event)
            ACTIVE_STATE -> markActive(event)
            else -> log.warn("Unknown product state ${event.newState} for itemDataId=${event.itemDataId}, ignoring")
        }
    }

    private fun markInactive(event: ItemDataStateChangedEvent) {
        val existing = inactiveProductRepository.findByItemAndClient(event.itemDataId, event.clientId)
        if (existing == null) {
            val record = InactiveProduct().apply {
                itemDataId = event.itemDataId
                clientId = event.clientId
                deactivated = Instant.now()
            }
            inactiveProductRepository.persist(record)
            log.info("Product ${event.itemDataId} (${event.number}) marked inactive for client ${event.clientId}")
        } else {
            log.debug("Product ${event.itemDataId} already marked inactive for client ${event.clientId}, skipping")
        }
    }

    private fun markActive(event: ItemDataStateChangedEvent) {
        val deleted = inactiveProductRepository.delete(
            "itemDataId = ?1 and clientId = ?2", event.itemDataId, event.clientId
        )
        if (deleted > 0) {
            log.info("Product ${event.itemDataId} (${event.number}) re-activated for client ${event.clientId}")
        }
        // No-op if row doesn't exist -- idempotent
    }
}
