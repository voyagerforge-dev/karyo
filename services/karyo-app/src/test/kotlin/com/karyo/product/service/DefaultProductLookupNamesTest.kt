package com.karyo.product.service

import com.karyo.product.domain.model.ItemData
import com.karyo.product.repository.ItemDataRepository
import com.karyo.product.repository.ItemUnitRepository
import com.karyo.product.spi.ProductLookup
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Verifies [ProductLookup.findNamesByIds] -- the batch id -> name lookup that backs the
 * itemDataName field on order-line / stock-unit DTOs -- and [ProductLookup.findMeasuresByIds]
 * (WORKLIST row 19, backs pick-order weight/volume). Both are tenant-scoped exactly like
 * [ProductLookup.findById]: unknown ids and foreign-tenant ids are simply absent from the
 * result map (honest gap), never fabricated.
 */
@QuarkusTest
class DefaultProductLookupNamesTest {

    @Inject
    lateinit var productLookup: ProductLookup

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var itemDataRepository: ItemDataRepository

    @Inject
    lateinit var itemUnitRepository: ItemUnitRepository

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, name: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"$name","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProductWithMeasures(
        number: String,
        name: String,
        itemUnitId: Long,
        weight: String? = null,
        height: String? = null,
        width: String? = null,
        depth: String? = null,
    ): Long {
        val measureFields = buildString {
            weight?.let { append(""","weight":$it""") }
            height?.let { append(""","height":$it""") }
            width?.let { append(""","width":$it""") }
            depth?.let { append(""","depth":$it""") }
        }
        return given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"$name","itemUnitId":$itemUnitId$measureFields}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Persists an ItemData directly (bypassing the REST/tenant-JWT layer) so a
     * foreign-tenant row can be seeded within a single test method. */
    @Transactional
    fun persistForeignTenantItemData(itemUnitId: Long, number: String, name: String, foreignClientId: Long): Long {
        val itemUnit = itemUnitRepository.findById(itemUnitId)!!
        val entity = ItemData().apply {
            this.clientId = foreignClientId
            this.number = number
            this.name = name
            this.itemUnit = itemUnit
        }
        itemDataRepository.persist(entity)
        return entity.id!!
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findNamesByIds returns names for own-tenant ids, omits unknown and foreign-tenant ids`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("PLN-IU-${suffix.toString().takeLast(8)}")
        val id1 = createProduct("PLN-SKU-A-$suffix", "Alpha Widget", itemUnitId)
        val id2 = createProduct("PLN-SKU-B-$suffix", "Beta Widget", itemUnitId)
        val foreignId = persistForeignTenantItemData(itemUnitId, "PLN-SKU-F-$suffix", "Foreign Widget", foreignClientId = 999L)
        val unknownId = 987654321L

        tenantContext.clientId = 1L
        val names = productLookup.findNamesByIds(setOf(id1, id2, foreignId, unknownId))

        assertThat(names).containsEntry(id1, "Alpha Widget")
        assertThat(names).containsEntry(id2, "Beta Widget")
        assertThat(names).doesNotContainKey(foreignId)
        assertThat(names).doesNotContainKey(unknownId)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findNamesByIds returns empty map for empty input`() {
        tenantContext.clientId = 1L
        assertThat(productLookup.findNamesByIds(emptySet())).isEmpty()
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findMeasuresByIds returns weight and volume for own-tenant ids in ONE query, omits unknown and foreign-tenant ids`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("MSR-IU-${suffix.toString().takeLast(8)}")
        val withMeasures = createProductWithMeasures(
            "MSR-SKU-A-$suffix", "Measured Widget", itemUnitId,
            weight = "4.5", height = "1", width = "2", depth = "3",
        )
        val foreignId = persistForeignTenantItemData(itemUnitId, "MSR-SKU-F-$suffix", "Foreign Widget", foreignClientId = 999L)
        val unknownId = 987654322L

        tenantContext.clientId = 1L
        val measures = productLookup.findMeasuresByIds(setOf(withMeasures, foreignId, unknownId))

        assertThat(measures).containsKey(withMeasures)
        assertThat(measures.getValue(withMeasures).weight).isEqualByComparingTo(BigDecimal("4.5"))
        // volume = height * width * depth = 1 * 2 * 3 = 6
        assertThat(measures.getValue(withMeasures).volume).isEqualByComparingTo(BigDecimal("6"))
        assertThat(measures).doesNotContainKey(foreignId)
        assertThat(measures).doesNotContainKey(unknownId)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findMeasuresByIds volume is null when a dimension is missing, even though weight is set`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("MSR-PIU-${suffix.toString().takeLast(8)}")
        // weight + height + width, but NO depth -> ItemData.volume computes null.
        val partialId = createProductWithMeasures(
            "MSR-PART-$suffix", "Partial Widget", itemUnitId,
            weight = "10", height = "1", width = "1",
        )

        tenantContext.clientId = 1L
        val measures = productLookup.findMeasuresByIds(setOf(partialId))

        assertThat(measures.getValue(partialId).weight).isEqualByComparingTo(BigDecimal("10"))
        assertThat(measures.getValue(partialId).volume).isNull()
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findMeasuresByIds returns empty map for empty input`() {
        tenantContext.clientId = 1L
        assertThat(productLookup.findMeasuresByIds(emptySet())).isEmpty()
    }
}
