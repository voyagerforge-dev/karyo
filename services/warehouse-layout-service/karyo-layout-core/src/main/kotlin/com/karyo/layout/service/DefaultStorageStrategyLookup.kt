package com.karyo.layout.service

import com.karyo.layout.exception.LayoutException
import com.karyo.layout.spi.StorageStrategyLookup
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default implementation of [StorageStrategyLookup]: delegates to the cache-keyed
 * [StorageStrategyService.findById] (same "storage-strategies" Caffeine cache the finder's
 * own resolution warms) and turns its tenant-scoped [LayoutException.NotFound] — thrown
 * identically for a missing id AND a foreign-tenant one, see that method's KDoc — into a
 * plain `false`. Callers never see the distinction; both mean "this id is not usable by
 * this client".
 */
@ApplicationScoped
class DefaultStorageStrategyLookup(
    private val storageStrategyService: StorageStrategyService,
) : StorageStrategyLookup {

    override fun exists(id: Long, clientId: Long): Boolean =
        try {
            storageStrategyService.findById(id, clientId)
            true
        } catch (@Suppress("SwallowedException") e: LayoutException.NotFound) {
            false
        }
}
