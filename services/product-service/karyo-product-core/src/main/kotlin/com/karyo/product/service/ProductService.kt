package com.karyo.product.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.common.pagination.paginatedResponse
import com.karyo.events.outbox.OutboxService
import com.karyo.product.domain.model.ItemData
import com.karyo.product.domain.model.ItemDataNumber
import com.karyo.product.domain.model.PackagingUnit
import com.karyo.product.dto.*
import com.karyo.product.event.ItemDataCreatedEvent
import com.karyo.product.event.ItemDataDeletedEvent
import com.karyo.product.event.ItemDataStateChangedEvent
import com.karyo.product.event.ItemDataUpdatedEvent
import com.karyo.product.exception.ProductException
import com.karyo.product.repository.ItemDataNumberRepository
import com.karyo.product.repository.ItemDataRepository
import com.karyo.product.repository.ItemUnitRepository
import com.karyo.product.repository.PackagingUnitRepository
import com.karyo.product.vo.ItemDataState
import com.karyo.product.vo.SerialNoRecordType
import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheKey
import io.quarkus.cache.CacheResult
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.transaction.Transactional

@ApplicationScoped
class ProductService(
    private val itemDataRepository: ItemDataRepository,
    private val itemUnitRepository: ItemUnitRepository,
    private val itemDataNumberRepository: ItemDataNumberRepository,
    private val packagingUnitRepository: PackagingUnitRepository,
    private val outboxService: OutboxService,
    private val stateChangedEvent: Event<ItemDataStateChangedEvent>,
) {

    @CacheResult(cacheName = "products-by-id")
    fun findById(@CacheKey id: Long, @CacheKey clientId: Long): ProductResponse {
        val entity = findEntityById(id, clientId)
        return toProductResponse(entity)
    }

    @CacheResult(cacheName = "products-by-number")
    fun findByNumber(@CacheKey number: String, @CacheKey clientId: Long): ProductResponse {
        val entity = itemDataRepository.findByNumber(number, clientId)
            ?: throw ProductException.NotFound("Product", "number=$number")
        return toProductResponse(entity)
    }

    fun listProducts(clientId: Long): List<ProductResponse> {
        return itemDataRepository.findByClientId(clientId).map { toProductResponse(it) }
    }

    fun listProductsPaginated(clientId: Long, pagination: PaginationParams): PaginatedResponse<ProductResponse> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.ascending("number"))
        val query = itemDataRepository.find("clientId", sort, clientId).page(Page.of(pagination.page, pagination.size))
        val content = query.list().map { toProductResponse(it) }
        val totalElements = query.count()
        return paginatedResponse(content, pagination.page, pagination.size, totalElements)
    }

    @Suppress("ThrowsCount")
    @Transactional
    fun createProduct(request: CreateProductRequest, clientId: Long): ProductResponse {
        // 1. Validate SKU uniqueness within tenant
        itemDataRepository.findByNumber(request.number, clientId)?.let {
            throw ProductException.DuplicateSku(request.number)
        }

        // 2. Validate itemUnitId
        val itemUnit = itemUnitRepository.findById(request.itemUnitId)
            ?: throw ProductException.InvalidItemUnit(request.itemUnitId)

        // 3. Validate shelf-life config
        validateShelfLifeConfig(request.shelflife, request.bestBeforeMandatory)

        // 4. Validate serialNoRecordType
        val serialNoRecordType = try {
            SerialNoRecordType.valueOf(request.serialNoRecordType)
        } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
            throw ProductException.InvalidConfiguration(
                "Invalid serialNoRecordType: ${request.serialNoRecordType}. Valid values: ${SerialNoRecordType.entries.joinToString()}"
            )
        }

        // 5. Create entity
        val entity = ItemData().apply {
            this.number = request.number
            this.name = request.name
            this.description = request.description
            this.state = ItemDataState.ACTIVE.code
            this.itemUnit = itemUnit
            this.scale = request.scale
            this.weight = request.weight
            this.height = request.height
            this.width = request.width
            this.depth = request.depth
            this.lotMandatory = request.lotMandatory
            this.bestBeforeMandatory = request.bestBeforeMandatory
            this.shelflife = request.shelflife
            this.serialNoRecordType = serialNoRecordType
            this.defaultUnitLoadTypeId = request.defaultUnitLoadTypeId
            this.defaultStorageStrategyId = request.defaultStorageStrategyId
            this.zoneId = request.zoneId
            this.tradeGroup = request.tradeGroup
            this.clientId = clientId
        }

        // 6. Persist
        itemDataRepository.persist(entity)

        // 7. Publish event
        outboxService.publish(
            "ItemData",
            entity.id!!,
            "ItemDataCreated",
            ItemDataCreatedEvent(
                itemDataId = entity.id!!,
                number = entity.number,
                name = entity.name,
                clientId = clientId,
            ),
            clientId,
        )

        // 8. Return response
        return toProductResponse(entity)
    }

    @Suppress("CyclomaticComplexMethod")
    @Transactional
    fun updateProduct(id: Long, request: UpdateProductRequest, clientId: Long): ProductResponse {
        // 1. Find existing
        val entity = findEntityById(id, clientId)
        val changedFields = mutableListOf<String>()

        // 2. Handle state change
        request.state?.let { newState ->
            if (newState != entity.state) applyStateChange(entity, newState, clientId, changedFields)
        }

        // 3. Update other non-null fields
        request.name?.let { entity.name = it; changedFields.add("name") }
        request.description.apply(
            clear = { entity.description = null; changedFields.add("description") },
            set = { entity.description = it; changedFields.add("description") },
        )
        request.weight?.let { entity.weight = it; changedFields.add("weight") }
        request.height?.let { entity.height = it; changedFields.add("height") }
        request.width?.let { entity.width = it; changedFields.add("width") }
        request.depth?.let { entity.depth = it; changedFields.add("depth") }
        request.lotMandatory?.let { entity.lotMandatory = it; changedFields.add("lotMandatory") }
        request.bestBeforeMandatory?.let { entity.bestBeforeMandatory = it; changedFields.add("bestBeforeMandatory") }
        request.shelflife?.let { entity.shelflife = it; changedFields.add("shelflife") }
        request.serialNoRecordType?.let {
            val recordType = try {
                SerialNoRecordType.valueOf(it)
            } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
                throw ProductException.InvalidConfiguration(
                    "Invalid serialNoRecordType: $it. Valid values: ${SerialNoRecordType.entries.joinToString()}"
                )
            }
            entity.serialNoRecordType = recordType
            changedFields.add("serialNoRecordType")
        }
        request.defaultUnitLoadTypeId?.let { entity.defaultUnitLoadTypeId = it; changedFields.add("defaultUnitLoadTypeId") }
        request.defaultStorageStrategyId?.let { entity.defaultStorageStrategyId = it; changedFields.add("defaultStorageStrategyId") }
        request.defaultPackagingUnitId.apply(
            clear = { entity.defaultPackagingUnitId = null; changedFields.add("defaultPackagingUnitId") },
            set = { puId ->
                // Invariant: the default must be one of THIS product's packaging units.
                packagingUnitRepository.findByIdAndItemData(puId, entity.id!!)
                    ?: throw ProductException.InvalidPackagingUnit(puId)
                entity.defaultPackagingUnitId = puId
                changedFields.add("defaultPackagingUnitId")
            },
        )
        request.zoneId?.let { entity.zoneId = it; changedFields.add("zoneId") }
        request.tradeGroup?.let { entity.tradeGroup = it; changedFields.add("tradeGroup") }
        request.imageUrl?.let { entity.imageUrl = it; changedFields.add("imageUrl") }

        // 4. Validate shelf-life config after update
        validateShelfLifeConfig(entity.shelflife, entity.bestBeforeMandatory)

        // 5. Publish update event (only if non-state fields changed)
        if (changedFields.any { it != "state" }) {
            outboxService.publish(
                "ItemData",
                entity.id!!,
                "ItemDataUpdated",
                ItemDataUpdatedEvent(
                    itemDataId = entity.id!!,
                    number = entity.number,
                    changedFields = changedFields.filter { it != "state" },
                ),
                clientId,
            )
        }

        // 6. Invalidate caches
        invalidateById(id, clientId)
        invalidateByNumber(entity.number, clientId)

        // 7. Return response
        return toProductResponse(entity)
    }

    /** Validates + applies a product state transition, recording it and publishing the change event. */
    private fun applyStateChange(
        entity: ItemData,
        newState: Int,
        clientId: Long,
        changedFields: MutableList<String>,
    ) {
        // Validate state code is 100 or 700
        try {
            ItemDataState.fromCode(newState)
        } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
            throw ProductException.InvalidStateTransition(entity.state, newState)
        }

        val oldState = entity.state
        entity.state = newState
        changedFields.add("state")

        // Publish state change event (outbox = dormant external log, CDI = in-process consumers)
        val stateChange = ItemDataStateChangedEvent(
            itemDataId = entity.id!!,
            number = entity.number,
            oldState = oldState,
            newState = newState,
            clientId = clientId,
        )
        outboxService.publish("ItemData", entity.id!!, "ItemDataStateChanged", stateChange, clientId)
        stateChangedEvent.fire(stateChange)
    }

    /**
     * Deletes a product and its owned children (barcodes, packaging units) in one transaction.
     * Owned children cascade via JPA (CascadeType.ALL + orphanRemoval). Cross-module references
     * (e.g. stock units in the inventory module) are ID-only per ADR-004 — no FK enforcement here.
     */
    @Transactional
    fun deleteProduct(id: Long, clientId: Long) {
        val entity = findEntityById(id, clientId)
        val number = entity.number
        val name = entity.name

        itemDataRepository.delete(entity)

        outboxService.publish(
            "ItemData",
            id,
            "ItemDataDeleted",
            ItemDataDeletedEvent(
                itemDataId = id,
                number = number,
                name = name,
                clientId = clientId,
            ),
            clientId,
        )

        invalidateById(id, clientId)
        invalidateByNumber(number, clientId)
    }

    @Transactional
    fun addBarcode(productId: Long, request: CreateItemDataNumberRequest, clientId: Long): ItemDataNumberResponse {
        // 1. Find product
        val product = findEntityById(productId, clientId)

        // 1a. GS1 check-digit validation (SC18) -- only when numberType maps to a recognized
        // GS1 symbology; unrecognized/absent numberType stays free-form (untouched parity).
        // Extracted to keep addBarcode's own throw count at detekt's ThrowsCount limit.
        validateGs1BarcodeOrThrow(request)

        // 2. Check barcode uniqueness within tenant (item_data_numbers)
        val existingNumbers = itemDataNumberRepository.findByNumber(request.number)
        val sameTenanMatch = existingNumbers.firstOrNull { it.itemData.clientId == clientId }
        if (sameTenanMatch != null) {
            throw ProductException.DuplicateBarcode(request.number)
        }

        // 3. Check if barcode matches any item_data.number in same tenant (SKU collision)
        itemDataRepository.findByNumber(request.number, clientId)?.let {
            throw ProductException.DuplicateBarcode(request.number)
        }

        // 4. Create ItemDataNumber
        val numberEntity = ItemDataNumber().apply {
            this.number = request.number
            this.numberType = request.numberType
            this.packagingUnitId = request.packagingUnitId
            this.manufacturerName = request.manufacturerName
            this.itemData = product
            this.index = product.numbers.size
        }

        product.numbers.add(numberEntity)

        // 5. Invalidate barcode cache
        invalidateById(productId, clientId)
        invalidateByNumber(product.number, clientId)

        return toItemDataNumberResponse(numberEntity)
    }

    @Transactional
    fun removeBarcode(productId: Long, numberId: Long, clientId: Long) {
        val product = findEntityById(productId, clientId)
        val removed = product.numbers.removeIf { it.id == numberId }
        if (!removed) {
            throw ProductException.NotFound("Barcode", "id=$numberId")
        }
        invalidateById(productId, clientId)
        invalidateByNumber(product.number, clientId)
    }

    @Transactional
    fun addPackagingUnit(productId: Long, request: CreatePackagingUnitRequest, clientId: Long): PackagingUnitResponse {
        val product = findEntityById(productId, clientId)

        // Validate optional itemUnitId if provided
        val itemUnit = request.itemUnitId?.let {
            itemUnitRepository.findById(it) ?: throw ProductException.InvalidItemUnit(it)
        }

        val puEntity = PackagingUnit().apply {
            this.name = request.name
            this.amount = request.amount
            this.itemUnit = itemUnit
            this.height = request.height
            this.width = request.width
            this.depth = request.depth
            this.weight = request.weight
            this.packingLevel = request.packingLevel
            this.itemData = product
        }

        // Explicit persist (not just the collection cascade-add) so the IDENTITY id is
        // assigned NOW, before the response is built -- a cascade-only add defers the INSERT
        // to flush time, which would otherwise ship `id: 0` here (pre-A5 bug: nobody read the
        // id back from this response until PackagingUnitLookup made a real id load-bearing).
        packagingUnitRepository.persist(puEntity)
        product.packagingUnits.add(puEntity)

        invalidateById(productId, clientId)
        invalidateByNumber(product.number, clientId)

        return toPackagingUnitResponse(puEntity)
    }

    @Transactional
    fun removePackagingUnit(productId: Long, puId: Long, clientId: Long) {
        val product = findEntityById(productId, clientId)
        val removed = product.packagingUnits.removeIf { it.id == puId }
        if (!removed) {
            throw ProductException.NotFound("PackagingUnit", "id=$puId")
        }
        // Clear a dangling default in the same transaction (no FK to catch it otherwise).
        if (product.defaultPackagingUnitId == puId) {
            product.defaultPackagingUnitId = null
        }
        invalidateById(productId, clientId)
        invalidateByNumber(product.number, clientId)
    }

    @Suppress("UnusedParameter")
    @CacheInvalidate(cacheName = "products-by-id")
    fun invalidateById(@CacheKey id: Long, @CacheKey clientId: Long) {
        // Cache invalidation handled by annotation
    }

    @Suppress("UnusedParameter")
    @CacheInvalidate(cacheName = "products-by-number")
    fun invalidateByNumber(@CacheKey number: String, @CacheKey clientId: Long) {
        // Cache invalidation handled by annotation
    }

    companion object {
        val SORTABLE_FIELDS = setOf("id", "number", "name", "state", "created")
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun findEntityById(id: Long, clientId: Long): ItemData {
        val entity = itemDataRepository.findById(id)
            ?: throw ProductException.NotFound("Product", "id=$id")
        if (entity.clientId != clientId) {
            throw ProductException.NotFound("Product", "id=$id")
        }
        return entity
    }

    /** SC18: throws [ProductException.ValidationFailed] if [request]'s barcode fails GS1 check-digit validation. */
    private fun validateGs1BarcodeOrThrow(request: CreateItemDataNumberRequest) {
        Gs1BarcodeValidation.violation(request.numberType, request.number)?.let {
            throw ProductException.ValidationFailed(it)
        }
    }

    private fun validateShelfLifeConfig(shelflife: Int?, bestBeforeMandatory: Boolean) {
        if (shelflife != null && shelflife > 0 && !bestBeforeMandatory) {
            throw ProductException.InvalidConfiguration(
                "Shelf-life requires best-before-mandatory to be enabled. Set bestBeforeMandatory = true when shelflife > 0"
            )
        }
    }

    fun toProductResponse(entity: ItemData): ProductResponse {
        return ProductResponse(
            id = entity.id!!,
            number = entity.number,
            name = entity.name,
            description = entity.description,
            state = entity.state,
            itemUnit = ItemUnitResponse(
                id = entity.itemUnit.id!!,
                name = entity.itemUnit.name,
                unitType = entity.itemUnit.unitType.name,
            ),
            scale = entity.scale,
            weight = entity.weight,
            height = entity.height,
            width = entity.width,
            depth = entity.depth,
            volume = entity.volume,
            lotMandatory = entity.lotMandatory,
            bestBeforeMandatory = entity.bestBeforeMandatory,
            shelflife = entity.shelflife,
            serialNoRecordType = entity.serialNoRecordType.name,
            defaultUnitLoadTypeId = entity.defaultUnitLoadTypeId,
            defaultStorageStrategyId = entity.defaultStorageStrategyId,
            defaultPackagingUnitId = entity.defaultPackagingUnitId,
            zoneId = entity.zoneId,
            tradeGroup = entity.tradeGroup,
            imageUrl = entity.imageUrl,
            numbers = entity.numbers.map { toItemDataNumberResponse(it) },
            packagingUnits = entity.packagingUnits.map { toPackagingUnitResponse(it) },
            created = entity.created.toString(),
            modified = entity.modified.toString(),
        )
    }

    private fun toItemDataNumberResponse(entity: ItemDataNumber): ItemDataNumberResponse {
        return ItemDataNumberResponse(
            id = entity.id ?: 0L,
            number = entity.number,
            numberType = entity.numberType,
            packagingUnitId = entity.packagingUnitId,
            manufacturerName = entity.manufacturerName,
        )
    }

    private fun toPackagingUnitResponse(entity: PackagingUnit): PackagingUnitResponse {
        return PackagingUnitResponse(
            id = entity.id!!,
            name = entity.name,
            amount = entity.amount,
            itemUnitName = entity.itemUnit?.name,
            weight = entity.weight,
            height = entity.height,
            width = entity.width,
            depth = entity.depth,
            packingLevel = entity.packingLevel,
        )
    }
}
