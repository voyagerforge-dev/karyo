package com.karyo.product.service

import com.karyo.product.domain.model.ItemSubstitution
import com.karyo.product.dto.CreateItemSubstitutionRequest
import com.karyo.product.dto.ItemSubstitutionResponse
import com.karyo.product.exception.ProductException
import com.karyo.product.repository.ItemSubstitutionRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class ItemSubstitutionService(
    private val repository: ItemSubstitutionRepository,
) {
    fun listByPrimary(itemDataId: Long, clientId: Long): List<ItemSubstitutionResponse> =
        repository.findByPrimary(itemDataId, clientId).map { toResponse(it) }

    @Transactional
    fun create(request: CreateItemSubstitutionRequest, clientId: Long): ItemSubstitutionResponse {
        if (request.itemDataId == request.substituteItemDataId) {
            throw ProductException.InvalidConfiguration("An item cannot substitute itself")
        }
        repository.findDuplicate(request.itemDataId, request.substituteItemDataId, clientId)?.let {
            throw ProductException.DuplicateSubstitution(request.itemDataId, request.substituteItemDataId)
        }
        val entity = ItemSubstitution().apply {
            this.clientId = clientId
            itemDataId = request.itemDataId
            substituteItemDataId = request.substituteItemDataId
            priority = request.priority
            active = request.active
        }
        repository.persist(entity)
        return toResponse(entity)
    }

    @Transactional
    fun delete(id: Long, clientId: Long) {
        val entity = repository.findById(id)
        if (entity == null || entity.clientId != clientId) {
            throw ProductException.NotFound("ItemSubstitution", "id=$id")
        }
        repository.delete(entity)
    }

    private fun toResponse(e: ItemSubstitution) = ItemSubstitutionResponse(
        id = e.id!!, itemDataId = e.itemDataId, substituteItemDataId = e.substituteItemDataId,
        priority = e.priority, active = e.active,
    )
}
