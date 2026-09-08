package com.karyo.layout.service

import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.spi.ClearingLocationInfo
import com.karyo.layout.spi.ClearingLocationLookup
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default [ClearingLocationLookup]. The partial unique index on
 * `storage_locations.is_clearing` (V310) guarantees at most one row can ever carry the
 * flag, so `firstResult()` is safe — there is no ambiguity to resolve. Deliberately
 * unscoped (no `clientId` filter): see the interface KDoc.
 */
@ApplicationScoped
class DefaultClearingLocationLookup(
    private val storageLocationRepository: StorageLocationRepository,
) : ClearingLocationLookup {
    override fun findClearing(): ClearingLocationInfo? =
        storageLocationRepository.find("isClearing", true).firstResult()?.let {
            ClearingLocationInfo(it.id!!, it.name)
        }
}
