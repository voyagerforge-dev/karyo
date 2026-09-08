package com.karyo.inventory.service

import com.karyo.inventory.api.dto.CreateUnitLoadTypeRequest
import com.karyo.inventory.api.dto.UpdateUnitLoadTypeRequest
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import io.quarkus.cache.CacheInvalidateAll
import io.quarkus.cache.CacheResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class UnitLoadTypeService(
    private val repository: UnitLoadTypeRepository,
    private val unitLoadRepository: UnitLoadRepository,
    private val weightCalculator: UnitLoadWeightCalculator,
) {
    @CacheResult(cacheName = "unit-load-types")
    fun findAll(): List<UnitLoadType> = repository.listAll()

    fun findById(id: Long): UnitLoadType =
        repository.findById(id) ?: throw InventoryException.NotFound("UnitLoadType", id)

    @Transactional
    @CacheInvalidateAll(cacheName = "unit-load-types")
    fun delete(id: Long) {
        repository.findById(id)
            ?: throw InventoryException.NotFound("UnitLoadType", id)
        val ulCount = unitLoadRepository.countByUnitLoadTypeId(id)
        if (ulCount > 0) {
            throw InventoryException.HasDependents("UnitLoadType", id, "unit loads")
        }
        repository.deleteById(id)
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "unit-load-types")
    fun create(request: CreateUnitLoadTypeRequest): UnitLoadType {
        if (repository.findByName(request.name) != null) {
            throw InventoryException.DuplicateName("UnitLoadType", request.name)
        }
        val ult = UnitLoadType().apply {
            this.name = request.name
            this.usages = request.usages
            this.aggregateStocks = request.aggregateStocks
            this.height = request.height
            this.width = request.width
            this.depth = request.depth
            this.liftingCapacity = request.liftingCapacity
            this.weight = request.weight
            this.manageEmpties = request.manageEmpties
        }
        repository.persist(ult)
        return ult
    }

    /**
     * Full-representation update. Carries [CacheInvalidateAll] for the same reason create and
     * delete do: findAll is cached for 60 minutes, so a mutation that skipped this
     * would serve a stale type for an hour. That was defect B22; do not remove this annotation.
     *
     * Row :1411 (defect-burndown-5): a tare change on a type is invisible to every unit load
     * already carrying it until something recomputes them -- [UnitLoadWeightCalculator] only
     * ever ran off a stock mutation, never off a type mutation, so an existing pallet's
     * [UnitLoad.weightCalculated] silently went stale the moment its type's tare was edited.
     * Closed by an unconditional batch recompute after applying the new fields: one
     * [UnitLoadRepository.findByUnitLoadTypeId] read plus [UnitLoadWeightCalculator.
     * recalculateAll]'s own bound (one plural stock query, one unioned product-measures query),
     * so this stays 2-3 queries and one flush regardless of how many unit loads the type has --
     * unconditional (not gated on "did weight actually change") because that bound does not
     * grow with volume and a gate would need a null-safe `BigDecimal` comparison for a saving
     * that only matters on the other, non-weight-editing fields of this same full-representation
     * PUT. An operator [UnitLoad.weightMeasure] override survives this recompute unchanged --
     * see [UnitLoadWeightCalculator]'s effective-weight rule.
     */
    @Transactional
    @CacheInvalidateAll(cacheName = "unit-load-types")
    fun update(id: Long, request: UpdateUnitLoadTypeRequest): UnitLoadType {
        val ult = findById(id)
        val existing = repository.findByName(request.name)
        if (existing != null && existing.id != id) {
            throw InventoryException.DuplicateName("UnitLoadType", request.name)
        }
        ult.apply {
            this.name = request.name
            this.usages = request.usages
            this.aggregateStocks = request.aggregateStocks
            this.height = request.height
            this.width = request.width
            this.depth = request.depth
            this.liftingCapacity = request.liftingCapacity
            this.weight = request.weight
            this.manageEmpties = request.manageEmpties
        }
        weightCalculator.recalculateAll(unitLoadRepository.findByUnitLoadTypeId(id))
        return ult
    }
}
