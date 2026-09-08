package com.karyo.product.service

import com.karyo.common.patch.Patchable
import com.karyo.events.outbox.OutboxService
import com.karyo.product.domain.model.ItemData
import com.karyo.product.domain.model.ItemDataNumber
import com.karyo.product.domain.model.ItemUnit
import com.karyo.product.domain.model.PackagingUnit
import com.karyo.product.dto.CreateItemDataNumberRequest
import com.karyo.product.dto.CreatePackagingUnitRequest
import com.karyo.product.dto.CreateProductRequest
import com.karyo.product.dto.UpdateProductRequest
import com.karyo.product.exception.ProductException
import com.karyo.product.repository.ItemDataNumberRepository
import com.karyo.product.repository.ItemDataRepository
import com.karyo.product.repository.ItemUnitRepository
import com.karyo.product.repository.PackagingUnitRepository
import com.karyo.product.vo.ItemDataState
import com.karyo.product.vo.ItemUnitType
import com.karyo.security.TenantContext
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Instant

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = ArgumentMatchers.any<T>() ?: null as T

@QuarkusTest
class ProductServiceTest {

    @InjectMock
    lateinit var itemDataRepository: ItemDataRepository

    @InjectMock
    lateinit var itemUnitRepository: ItemUnitRepository

    @InjectMock
    lateinit var itemDataNumberRepository: ItemDataNumberRepository

    @InjectMock
    lateinit var packagingUnitRepository: PackagingUnitRepository

    @InjectMock
    lateinit var outboxService: OutboxService

    @InjectMock
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var productService: ProductService

    private fun buildItemUnit(id: Long = 1L, name: String = "PCS", unitType: ItemUnitType = ItemUnitType.PIECE): ItemUnit {
        val unit = ItemUnit()
        unit.id = id
        unit.name = name
        unit.unitType = unitType
        unit.created = Instant.now()
        unit.modified = Instant.now()
        return unit
    }

    private fun buildItemData(
        id: Long = 1L,
        number: String = "SKU-001",
        name: String = "Test Product",
        clientId: Long = 1L,
        state: Int = ItemDataState.ACTIVE.code,
        itemUnit: ItemUnit = buildItemUnit(),
    ): ItemData {
        val data = ItemData()
        data.id = id
        data.number = number
        data.name = name
        data.clientId = clientId
        data.state = state
        data.itemUnit = itemUnit
        data.scale = 0
        data.lotMandatory = false
        data.bestBeforeMandatory = false
        data.created = Instant.now()
        data.modified = Instant.now()
        return data
    }

    /**
     * Sets up mock persist to assign an ID to the entity (simulates database behavior).
     * Also sets up outboxService.publish to accept any arguments silently.
     */
    private fun mockPersistWithIdAssignment(assignedId: Long = 100L) {
        doAnswer { invocation ->
            val entity = invocation.getArgument<ItemData>(0)
            if (entity.id == null) {
                entity.id = assignedId
            }
            null
        }.`when`(itemDataRepository).persist(anyObj<ItemData>())
    }

    /**
     * Sets up mock persist to assign an ID to the packaging unit entity (simulates IDENTITY
     * persist behavior, same idiom as mockPersistWithIdAssignment above).
     */
    private fun mockPackagingUnitPersistWithIdAssignment(assignedId: Long = 200L) {
        doAnswer { invocation ->
            val entity = invocation.getArgument<PackagingUnit>(0)
            if (entity.id == null) {
                entity.id = assignedId
            }
            null
        }.`when`(packagingUnitRepository).persist(anyObj<PackagingUnit>())
    }

    @BeforeEach
    fun setup() {
        `when`(tenantContext.clientId).thenReturn(1L)
        `when`(tenantContext.tenantCode).thenReturn("ACME")
        `when`(tenantContext.username).thenReturn("manager")
    }

    /**
     * Verifies OutboxService.publish was invoked the expected number of times.
     * Uses invocation inspection to avoid Mockito matcher null issues with Kotlin primitives.
     */
    private fun verifyOutboxPublished(expectedTimes: Int = 1) {
        val invocations = org.mockito.Mockito.mockingDetails(outboxService).invocations
        val publishCalls = invocations.filter { it.method.name == "publish" }
        assertThat(publishCalls).hasSize(expectedTimes)
    }

    // ── 1. Create product with valid data ────────────────────────────────

    @Test
    fun `createProduct with valid data returns ProductResponse with ACTIVE state`() {
        val unit = buildItemUnit()
        `when`(itemDataRepository.findByNumber("SKU-001", 1L)).thenReturn(null)
        `when`(itemUnitRepository.findById(1L)).thenReturn(unit)
        mockPersistWithIdAssignment()

        val request = CreateProductRequest(
            number = "SKU-001",
            name = "Test Product",
            itemUnitId = 1L,
        )

        val result = productService.createProduct(request, 1L)

        assertThat(result.number).isEqualTo("SKU-001")
        assertThat(result.name).isEqualTo("Test Product")
        assertThat(result.state).isEqualTo(ItemDataState.ACTIVE.code)
        assertThat(result.itemUnit.name).isEqualTo("PCS")
        verifyOutboxPublished(1)
    }

    // ── 2. Create product with duplicate SKU ─────────────────────────────

    @Test
    fun `createProduct with duplicate SKU throws DuplicateSku`() {
        val existing = buildItemData()
        `when`(itemDataRepository.findByNumber("SKU-001", 1L)).thenReturn(existing)

        val request = CreateProductRequest(
            number = "SKU-001",
            name = "Duplicate",
            itemUnitId = 1L,
        )

        assertThatThrownBy { productService.createProduct(request, 1L) }
            .isInstanceOf(ProductException.DuplicateSku::class.java)
    }

    // ── 3. Create product with invalid itemUnitId ────────────────────────

    @Test
    fun `createProduct with invalid itemUnitId throws InvalidItemUnit`() {
        `when`(itemDataRepository.findByNumber("SKU-002", 1L)).thenReturn(null)
        `when`(itemUnitRepository.findById(999L)).thenReturn(null)

        val request = CreateProductRequest(
            number = "SKU-002",
            name = "Product",
            itemUnitId = 999L,
        )

        assertThatThrownBy { productService.createProduct(request, 1L) }
            .isInstanceOf(ProductException.InvalidItemUnit::class.java)
    }

    // ── 4. Create product with shelflife > 0 and bestBeforeMandatory = false ──

    @Test
    fun `createProduct with shelflife and bestBeforeMandatory false throws InvalidConfiguration`() {
        val unit = buildItemUnit()
        `when`(itemDataRepository.findByNumber("SKU-003", 1L)).thenReturn(null)
        `when`(itemUnitRepository.findById(1L)).thenReturn(unit)

        val request = CreateProductRequest(
            number = "SKU-003",
            name = "Perishable",
            itemUnitId = 1L,
            shelflife = 30,
            bestBeforeMandatory = false,
        )

        assertThatThrownBy { productService.createProduct(request, 1L) }
            .isInstanceOf(ProductException.InvalidConfiguration::class.java)
    }

    // ── 5. Create product with shelflife > 0 and bestBeforeMandatory = true ──

    @Test
    fun `createProduct with shelflife and bestBeforeMandatory true succeeds`() {
        val unit = buildItemUnit()
        `when`(itemDataRepository.findByNumber("SKU-004", 1L)).thenReturn(null)
        `when`(itemUnitRepository.findById(1L)).thenReturn(unit)
        mockPersistWithIdAssignment()

        val request = CreateProductRequest(
            number = "SKU-004",
            name = "Perishable OK",
            itemUnitId = 1L,
            shelflife = 30,
            bestBeforeMandatory = true,
        )

        val result = productService.createProduct(request, 1L)

        assertThat(result.shelflife).isEqualTo(30)
        assertThat(result.bestBeforeMandatory).isTrue()
    }

    // ── 6. Update product with state change ACTIVE -> INACTIVE ───────────

    @Test
    fun `updateProduct with state change ACTIVE to INACTIVE publishes state event`() {
        val existing = buildItemData(state = ItemDataState.ACTIVE.code)
        `when`(itemDataRepository.findById(1L)).thenReturn(existing)

        val request = UpdateProductRequest(state = ItemDataState.INACTIVE.code)

        val result = productService.updateProduct(1L, request, 1L)

        assertThat(result.state).isEqualTo(ItemDataState.INACTIVE.code)
        verifyOutboxPublished(1)
    }

    // ── 7. Update product with state change INACTIVE -> ACTIVE (bidirectional) ──

    @Test
    fun `updateProduct with state change INACTIVE to ACTIVE succeeds bidirectional`() {
        val existing = buildItemData(state = ItemDataState.INACTIVE.code)
        `when`(itemDataRepository.findById(1L)).thenReturn(existing)

        val request = UpdateProductRequest(state = ItemDataState.ACTIVE.code)

        val result = productService.updateProduct(1L, request, 1L)

        assertThat(result.state).isEqualTo(ItemDataState.ACTIVE.code)
    }

    // ── 8. Update product with invalid state code ────────────────────────

    @Test
    fun `updateProduct with invalid state code throws InvalidStateTransition`() {
        val existing = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(existing)

        val request = UpdateProductRequest(state = 999)

        assertThatThrownBy { productService.updateProduct(1L, request, 1L) }
            .isInstanceOf(ProductException.InvalidStateTransition::class.java)
    }

    // ── 9. Add barcode with duplicate barcode ────────────────────────────

    @Test
    fun `addBarcode with duplicate barcode in same tenant throws DuplicateBarcode`() {
        val existingProduct = buildItemData()
        val otherProduct = buildItemData(id = 2L, number = "SKU-OTHER")
        val existingNumber = ItemDataNumber().apply {
            id = 10L
            number = "EAN-123"
            itemData = otherProduct
            created = Instant.now()
            modified = Instant.now()
        }
        `when`(itemDataRepository.findById(1L)).thenReturn(existingProduct)
        `when`(itemDataNumberRepository.findByNumber("EAN-123")).thenReturn(listOf(existingNumber))

        val request = CreateItemDataNumberRequest(number = "EAN-123")

        assertThatThrownBy { productService.addBarcode(1L, request, 1L) }
            .isInstanceOf(ProductException.DuplicateBarcode::class.java)
    }

    // ── 10. Add barcode with unique barcode succeeds ─────────────────────

    @Test
    fun `addBarcode with unique barcode succeeds`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        `when`(itemDataNumberRepository.findByNumber("NEW-BARCODE")).thenReturn(emptyList())
        `when`(itemDataRepository.findByNumber("NEW-BARCODE", 1L)).thenReturn(null)

        // numberType "CUSTOM" (not a recognized GS1 type, SC18) -- this test targets uniqueness
        // logic, not barcode format, so it deliberately keeps a non-digit free-form value.
        val request = CreateItemDataNumberRequest(number = "NEW-BARCODE", numberType = "CUSTOM")

        val result = productService.addBarcode(1L, request, 1L)

        assertThat(result.number).isEqualTo("NEW-BARCODE")
        assertThat(result.numberType).isEqualTo("CUSTOM")
    }

    // ── 11. Add packaging unit with explicit packingLevel ─────────────────

    @Test
    fun `addPackagingUnit with packingLevel 2 returns and persists packingLevel 2`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        mockPackagingUnitPersistWithIdAssignment()

        val request = CreatePackagingUnitRequest(
            name = "Carton",
            amount = BigDecimal("12"),
            packingLevel = 2,
        )

        val result = productService.addPackagingUnit(1L, request, 1L)

        // Response mapping line (toPackagingUnitResponse) is pinned here.
        assertThat(result.packingLevel).isEqualTo(2)

        // Apply-block line (`this.packingLevel = request.packingLevel`) is pinned here,
        // independent of the response mapping -- inspect the real PackagingUnit entity that
        // the apply-block mutated and that was appended to product.packagingUnits.
        assertThat(product.packagingUnits).hasSize(1)
        val persistedEntity = product.packagingUnits.single()
        assertThat(persistedEntity.packingLevel).isEqualTo(2)
    }

    // ── 12. Add packaging unit with packingLevel omitted defaults to 0 ────

    @Test
    fun `addPackagingUnit with packingLevel omitted persists default 0`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        mockPackagingUnitPersistWithIdAssignment()

        val request = CreatePackagingUnitRequest(
            name = "Each",
            amount = BigDecimal("1"),
        )

        val result = productService.addPackagingUnit(1L, request, 1L)

        assertThat(result.packingLevel).isEqualTo(0)

        assertThat(product.packagingUnits).hasSize(1)
        val persistedEntity = product.packagingUnits.single()
        assertThat(persistedEntity.packingLevel).isEqualTo(0)
    }

    private fun buildPackagingUnit(id: Long, product: ItemData, name: String = "Carton"): PackagingUnit {
        val pu = PackagingUnit()
        pu.id = id
        pu.name = name
        pu.itemData = product
        pu.amount = BigDecimal("12")
        pu.created = Instant.now()
        pu.modified = Instant.now()
        return pu
    }

    // ── 13. Update product with own defaultPackagingUnitId ────────────────

    @Test
    fun `updateProduct with own defaultPackagingUnitId sets field and echoes it`() {
        val product = buildItemData()
        val pu = buildPackagingUnit(id = 5L, product = product)
        product.packagingUnits.add(pu)
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        `when`(packagingUnitRepository.findByIdAndItemData(5L, 1L)).thenReturn(pu)

        val result = productService.updateProduct(1L, UpdateProductRequest(defaultPackagingUnitId = Patchable.Value(5L)), 1L)

        // Entity mutation pinned independently of the response mapping.
        assertThat(product.defaultPackagingUnitId).isEqualTo(5L)
        assertThat(result.defaultPackagingUnitId).isEqualTo(5L)
    }

    // ── 14. Update product with a foreign packaging unit id ───────────────

    @Test
    fun `updateProduct with foreign defaultPackagingUnitId throws InvalidPackagingUnit`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        `when`(packagingUnitRepository.findByIdAndItemData(999L, 1L)).thenReturn(null)

        assertThatThrownBy {
            productService.updateProduct(1L, UpdateProductRequest(defaultPackagingUnitId = Patchable.Value(999L)), 1L)
        }.isInstanceOf(ProductException.InvalidPackagingUnit::class.java)
        assertThat(product.defaultPackagingUnitId).isNull()
    }

    // ── 13a. D2 tri-state: description/defaultPackagingUnitId (Absent/Null/Value) ─────────

    @Test
    fun `updateProduct omitting description leaves it unchanged`() {
        val product = buildItemData().apply { description = "original description" }
        `when`(itemDataRepository.findById(1L)).thenReturn(product)

        productService.updateProduct(1L, UpdateProductRequest(name = "Renamed"), 1L)

        assertThat(product.description).isEqualTo("original description")
    }

    @Test
    fun `updateProduct with explicit null clears description`() {
        val product = buildItemData().apply { description = "original description" }
        `when`(itemDataRepository.findById(1L)).thenReturn(product)

        val result = productService.updateProduct(1L, UpdateProductRequest(description = Patchable.Null), 1L)

        assertThat(product.description).isNull()
        assertThat(result.description).isNull()
    }

    @Test
    fun `updateProduct with description value sets it`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)

        val result = productService.updateProduct(1L, UpdateProductRequest(description = Patchable.Value("new description")), 1L)

        assertThat(product.description).isEqualTo("new description")
        assertThat(result.description).isEqualTo("new description")
    }

    @Test
    fun `updateProduct with explicit null clears defaultPackagingUnitId without hitting the repository`() {
        val product = buildItemData().apply { defaultPackagingUnitId = 5L }
        `when`(itemDataRepository.findById(1L)).thenReturn(product)

        val result = productService.updateProduct(1L, UpdateProductRequest(defaultPackagingUnitId = Patchable.Null), 1L)

        assertThat(product.defaultPackagingUnitId).isNull()
        assertThat(result.defaultPackagingUnitId).isNull()
        org.mockito.Mockito.verifyNoInteractions(packagingUnitRepository)
    }

    // ── 15. Removing the default packaging unit clears the default ────────

    @Test
    fun `removePackagingUnit of current default nulls defaultPackagingUnitId`() {
        val product = buildItemData()
        val pu = buildPackagingUnit(id = 5L, product = product)
        product.packagingUnits.add(pu)
        product.defaultPackagingUnitId = 5L
        `when`(itemDataRepository.findById(1L)).thenReturn(product)

        productService.removePackagingUnit(1L, 5L, 1L)

        assertThat(product.packagingUnits).isEmpty()
        assertThat(product.defaultPackagingUnitId).isNull()
    }

    // ── 16. Removing a non-default packaging unit keeps the default ───────

    @Test
    fun `removePackagingUnit of non-default keeps defaultPackagingUnitId`() {
        val product = buildItemData()
        val defaultPu = buildPackagingUnit(id = 5L, product = product)
        val otherPu = buildPackagingUnit(id = 6L, product = product, name = "Pallet")
        product.packagingUnits.add(defaultPu)
        product.packagingUnits.add(otherPu)
        product.defaultPackagingUnitId = 5L
        `when`(itemDataRepository.findById(1L)).thenReturn(product)

        productService.removePackagingUnit(1L, 6L, 1L)

        assertThat(product.packagingUnits).hasSize(1)
        assertThat(product.defaultPackagingUnitId).isEqualTo(5L)
    }

    // ── 17. Add barcode with manufacturerName ─────────────────────────────

    @Test
    fun `addBarcode with manufacturerName persists and echoes it`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        `when`(itemDataNumberRepository.findByNumber("EAN-789")).thenReturn(emptyList())
        `when`(itemDataRepository.findByNumber("EAN-789", 1L)).thenReturn(null)

        // numberType "CUSTOM" (not a recognized GS1 type, SC18) -- this test targets
        // manufacturerName persistence, not barcode format, so it keeps a non-digit value.
        val request = CreateItemDataNumberRequest(
            number = "EAN-789",
            numberType = "CUSTOM",
            manufacturerName = "Acme Mfg",
        )

        val result = productService.addBarcode(1L, request, 1L)

        assertThat(result.manufacturerName).isEqualTo("Acme Mfg")
        // Real entity added to the owning collection (persists via JPA cascade off ItemData —
        // there is no itemDataNumberRepository.persist call, same idiom as packaging units).
        assertThat(product.numbers).hasSize(1)
        assertThat(product.numbers.single().manufacturerName).isEqualTo("Acme Mfg")
    }

    // ── 18. SC18 GS1 check-digit validation on the barcode write path ─────
    // Real (unmocked) ProductService bean -- these exercise the actual Gs1BarcodeValidation
    // math, unlike ProductResourceTest's wiring-only coverage (productService is @InjectMock
    // there, so it can't observe real validation logic).

    @Test
    fun `addBarcode with valid EAN-13 succeeds`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        `when`(itemDataNumberRepository.findByNumber("4006381333931")).thenReturn(emptyList())
        `when`(itemDataRepository.findByNumber("4006381333931", 1L)).thenReturn(null)

        val request = CreateItemDataNumberRequest(number = "4006381333931", numberType = "EAN13")

        val result = productService.addBarcode(1L, request, 1L)

        assertThat(result.number).isEqualTo("4006381333931")
        assertThat(product.numbers).hasSize(1)
    }

    @Test
    fun `addBarcode with EAN-13 wrong check digit throws ValidationFailed naming the expected digit`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)

        // 4006381333932 -- same payload as the known-good 4006381333931, wrong trailing digit.
        // gs1Mod10("400638133393") = 1 (CheckDigitTest, hand-verified), so the message must
        // name "1" as the expected digit.
        val request = CreateItemDataNumberRequest(number = "4006381333932", numberType = "EAN13")

        assertThatThrownBy { productService.addBarcode(1L, request, 1L) }
            .isInstanceOf(ProductException.ValidationFailed::class.java)
            .hasMessageContaining("expected 1")

        // No repository interaction beyond the initial product lookup -- validation short-circuits
        // before the uniqueness checks, and nothing was appended to the product.
        assertThat(product.numbers).isEmpty()
    }

    @Test
    fun `addBarcode with EAN-8 known-good vector succeeds`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        `when`(itemDataNumberRepository.findByNumber("96385074")).thenReturn(emptyList())
        `when`(itemDataRepository.findByNumber("96385074", 1L)).thenReturn(null)

        val result = productService.addBarcode(1L, CreateItemDataNumberRequest(number = "96385074", numberType = "EAN8"), 1L)

        assertThat(result.number).isEqualTo("96385074")
    }

    @Test
    fun `addBarcode with SSCC-18 known-good vector succeeds`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        `when`(itemDataNumberRepository.findByNumber("106141411234567897")).thenReturn(emptyList())
        `when`(itemDataRepository.findByNumber("106141411234567897", 1L)).thenReturn(null)

        val result = productService.addBarcode(
            1L,
            CreateItemDataNumberRequest(number = "106141411234567897", numberType = "SSCC"),
            1L,
        )

        assertThat(result.number).isEqualTo("106141411234567897")
    }

    @Test
    fun `addBarcode with UPC-A known-good vector succeeds`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        `when`(itemDataNumberRepository.findByNumber("036000291452")).thenReturn(emptyList())
        `when`(itemDataRepository.findByNumber("036000291452", 1L)).thenReturn(null)

        val result = productService.addBarcode(1L, CreateItemDataNumberRequest(number = "036000291452", numberType = "UPC-A"), 1L)

        assertThat(result.number).isEqualTo("036000291452")
    }

    @Test
    fun `addBarcode with non-GS1 numberType skips validation entirely`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)
        `when`(itemDataNumberRepository.findByNumber("NOT-A-BARCODE")).thenReturn(emptyList())
        `when`(itemDataRepository.findByNumber("NOT-A-BARCODE", 1L)).thenReturn(null)

        // Free-form "internal" numberType: not digits-only, not GS1 length -- must be accepted
        // unchanged (pre-SC18 parity for unrecognized numberType values).
        val request = CreateItemDataNumberRequest(number = "NOT-A-BARCODE", numberType = "internal")

        val result = productService.addBarcode(1L, request, 1L)

        assertThat(result.number).isEqualTo("NOT-A-BARCODE")
        assertThat(result.numberType).isEqualTo("internal")
    }

    @Test
    fun `addBarcode with GS1 numberType but non-digit payload throws ValidationFailed`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)

        val request = CreateItemDataNumberRequest(number = "4006381X33931", numberType = "EAN13")

        assertThatThrownBy { productService.addBarcode(1L, request, 1L) }
            .isInstanceOf(ProductException.ValidationFailed::class.java)
            .hasMessageContaining("digits only")
    }

    @Test
    fun `addBarcode with GS1 numberType but wrong length throws ValidationFailed`() {
        val product = buildItemData()
        `when`(itemDataRepository.findById(1L)).thenReturn(product)

        val request = CreateItemDataNumberRequest(number = "40063813339", numberType = "EAN13")

        assertThatThrownBy { productService.addBarcode(1L, request, 1L) }
            .isInstanceOf(ProductException.ValidationFailed::class.java)
            .hasMessageContaining("13")
    }
}
