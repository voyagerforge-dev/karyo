package com.karyo.layout.service

import com.karyo.layout.domain.model.ItemDataArea
import com.karyo.layout.dto.CreateItemDataAreaRequest
import com.karyo.layout.dto.ItemDataAreaResponse
import com.karyo.layout.dto.UpdateItemDataAreaRequest
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.ItemDataAreaRepository
import com.karyo.layout.repository.StorageAreaRepository
import com.karyo.product.spi.ProductLookup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class ItemDataAreaService(
    private val itemDataAreaRepository: ItemDataAreaRepository,
    private val storageAreaRepository: StorageAreaRepository,
    private val productLookup: ProductLookup,
) {

    fun listByClient(clientId: Long): List<ItemDataAreaResponse> =
        enrichAll(itemDataAreaRepository.findByClientId(clientId))

    fun findByArea(storageAreaId: Long, clientId: Long): List<ItemDataAreaResponse> =
        enrichAll(itemDataAreaRepository.findByArea(storageAreaId, clientId))

    fun findById(id: Long, clientId: Long): ItemDataAreaResponse {
        val entity = findEntityById(id, clientId)
        val names = productLookup.findNamesByIds(setOf(entity.itemDataId))
        return toResponse(entity, names[entity.itemDataId])
    }

    @Transactional
    fun create(request: CreateItemDataAreaRequest, clientId: Long): ItemDataAreaResponse {
        val area = resolveArea(request.storageAreaId)
        // Ownership/validation FIRST: resolveProduct is the only check on this path that
        // verifies the caller's tenant may reference itemDataId at all (ProductLookup is
        // tenant-scoped). Running the unscoped duplicate check before this would let a
        // foreign tenant probe (itemDataId, storageAreaId) pairs it has no access to and
        // learn whether they exist from the 409/not-409 response — an existence leak.
        val product = resolveProduct(request.itemDataId)
        assertNotDuplicate(request.itemDataId, request.storageAreaId)

        val entity = ItemDataArea().apply {
            itemDataId = request.itemDataId
            storageArea = area
            plannedAmount = request.plannedAmount
            plannedStocks = request.plannedStocks
            this.clientId = clientId
        }

        itemDataAreaRepository.persist(entity)
        return toResponse(entity, product.name)
    }

    private fun resolveArea(storageAreaId: Long) =
        storageAreaRepository.findById(storageAreaId)
            ?: throw LayoutException.NotFound("StorageArea", storageAreaId)

    private fun assertNotDuplicate(itemDataId: Long, storageAreaId: Long) {
        itemDataAreaRepository.findByItemAndArea(itemDataId, storageAreaId)?.let {
            throw LayoutException.DuplicateName("ItemDataArea", "item=$itemDataId/area=$storageAreaId")
        }
    }

    private fun resolveProduct(itemDataId: Long) =
        productLookup.findById(itemDataId)
            ?: throw LayoutException.InvalidReference("ItemDataArea", "ItemData", itemDataId)

    @Transactional
    fun update(id: Long, request: UpdateItemDataAreaRequest, clientId: Long): ItemDataAreaResponse {
        val entity = findEntityById(id, clientId)

        request.plannedAmount?.let { entity.plannedAmount = it }
        request.plannedStocks?.let { entity.plannedStocks = it }

        val names = productLookup.findNamesByIds(setOf(entity.itemDataId))
        return toResponse(entity, names[entity.itemDataId])
    }

    @Transactional
    fun delete(id: Long, clientId: Long) {
        val entity = findEntityById(id, clientId)
        itemDataAreaRepository.delete(entity)
    }

    private fun enrichAll(entities: List<ItemDataArea>): List<ItemDataAreaResponse> {
        val names = productLookup.findNamesByIds(entities.map { it.itemDataId }.toSet())
        return entities.map { toResponse(it, names[it.itemDataId]) }
    }

    private fun findEntityById(id: Long, clientId: Long): ItemDataArea {
        val entity = itemDataAreaRepository.findById(id)
            ?: throw LayoutException.NotFound("ItemDataArea", id)
        if (entity.clientId != clientId) {
            throw LayoutException.NotFound("ItemDataArea", id)
        }
        return entity
    }

    private fun toResponse(entity: ItemDataArea, itemDataName: String?): ItemDataAreaResponse = ItemDataAreaResponse(
        id = entity.id!!,
        itemDataId = entity.itemDataId,
        itemDataName = itemDataName,
        storageAreaId = entity.storageArea.id!!,
        storageAreaName = entity.storageArea.name,
        plannedAmount = entity.plannedAmount,
        plannedStocks = entity.plannedStocks,
        created = entity.created.toString(),
        modified = entity.modified.toString(),
    )
}
