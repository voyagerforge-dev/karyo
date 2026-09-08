package com.karyo.product.service

import com.karyo.product.repository.PackagingUnitRepository
import com.karyo.product.spi.PackagingUnitInfo
import com.karyo.product.spi.PackagingUnitLookup
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default in-process implementation of [PackagingUnitLookup]. One Panache lookup
 * projecting id/name/itemData.id -- itemData.id is read off the lazy proxy's identifier
 * without initializing it (the same pattern StockService already relies on for
 * unitLoad.id!!), so this never triggers a second SELECT.
 */
@ApplicationScoped
class DefaultPackagingUnitLookup(
    private val packagingUnitRepository: PackagingUnitRepository,
) : PackagingUnitLookup {

    override fun findById(id: Long): PackagingUnitInfo? {
        val pu = packagingUnitRepository.findById(id) ?: return null
        return PackagingUnitInfo(id = pu.id!!, name = pu.name, itemDataId = pu.itemData.id!!)
    }
}
