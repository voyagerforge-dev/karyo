package com.karyo.product.service

import com.karyo.product.repository.ItemSubstitutionRepository
import com.karyo.product.spi.SubstituteItem
import com.karyo.product.spi.SubstitutionLookup
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class DefaultSubstitutionLookup(
    private val repository: ItemSubstitutionRepository,
    private val tenantContext: TenantContext,
) : SubstitutionLookup {

    override fun findSubstitutes(itemDataId: Long): List<SubstituteItem> =
        repository.findActiveByPrimary(itemDataId, tenantContext.clientId)
            .map { SubstituteItem(it.substituteItemDataId, it.priority) }
}
