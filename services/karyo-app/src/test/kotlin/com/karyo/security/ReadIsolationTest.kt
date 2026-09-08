package com.karyo.security

import com.karyo.inventory.api.spi.UnitLoadLookup
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.inventory.service.UnitLoadService
import com.karyo.product.domain.model.ItemData
import com.karyo.product.repository.ItemDataRepository
import com.karyo.product.repository.ItemUnitRepository
import com.karyo.product.spi.ProductLookup
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Read isolation is the half of the model that must NOT relax: the paid 3PL client
 * read-only portals depend on a goods owner never seeing another owner's rows.
 * Ops staff, by contrast, read across all owners.
 */
@QuarkusTest
class ReadIsolationTest {

    @Inject
    lateinit var productLookup: ProductLookup

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var itemDataRepository: ItemDataRepository

    @Inject
    lateinit var itemUnitRepository: ItemUnitRepository

    @Inject
    lateinit var unitLoadService: UnitLoadService

    @Inject
    lateinit var unitLoadLookup: UnitLoadLookup

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    @Transactional
    fun persistItemData(itemUnitId: Long, number: String, name: String, owner: Long): Long {
        val itemUnit = itemUnitRepository.findById(itemUnitId)!!
        val entity = ItemData().apply {
            this.clientId = owner
            this.number = number
            this.name = name
            this.itemUnit = itemUnit
        }
        itemDataRepository.persist(entity)
        return entity.id!!
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `OWNER principal cannot read another owner's product`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val itemUnitId = createItemUnit("RI-IU-$suffix")
        val mine = persistItemData(itemUnitId, "RI-MINE-$suffix", "Mine", owner = 1L)
        val theirs = persistItemData(itemUnitId, "RI-THEIRS-$suffix", "Theirs", owner = 2L)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        assertThat(productLookup.findById(mine)).isNotNull()
        assertThat(productLookup.findById(theirs)).isNull()
        assertThat(productLookup.findNamesByIds(setOf(mine, theirs)))
            .containsKey(mine)
            .doesNotContainKey(theirs)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["product-read", "product-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `OPS principal reads across owners`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val itemUnitId = createItemUnit("RI-OPS-$suffix")
        val ownerOne = persistItemData(itemUnitId, "RI-O1-$suffix", "Owner One", owner = 1L)
        val ownerTwo = persistItemData(itemUnitId, "RI-O2-$suffix", "Owner Two", owner = 2L)

        tenantContext.clientId = 0L
        tenantContext.principalKind = PrincipalKind.OPS

        assertThat(productLookup.findById(ownerOne)).isNotNull()
        assertThat(productLookup.findById(ownerTwo)).isNotNull()
        assertThat(productLookup.findNamesByIds(setOf(ownerOne, ownerTwo)))
            .containsKeys(ownerOne, ownerTwo)
    }

    // --- unit-load read sites: UnitLoadService.findById / findByLabelId / findByLocation
    // --- and the cross-module DefaultUnitLoadLookup SPI.

    @Transactional
    fun persistUnitLoad(owner: Long, label: String): Long {
        val type = unitLoadTypeRepository.listAll().first()
        val ul = UnitLoad().apply {
            this.clientId = owner
            this.labelId = label
            this.unitLoadType = type
            this.storageLocationId = RI_LOCATION_ID
            this.storageLocationName = "RI-LOC"
        }
        unitLoadRepository.persist(ul)
        return ul.id!!
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `OWNER principal cannot read another owner's unit load by id, label or location`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val foreignLabel = "RI-UL-F-$suffix"
        val foreignId = persistUnitLoad(owner = 2L, label = foreignLabel)
        val mineId = persistUnitLoad(owner = 1L, label = "RI-UL-M-$suffix")

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        assertThat(unitLoadService.findById(mineId, tenantContext).id).isEqualTo(mineId)
        assertThatThrownBy { unitLoadService.findById(foreignId, tenantContext) }
            .hasMessageContaining("UnitLoad")
        assertThatThrownBy { unitLoadService.findByLabelId(foreignLabel, tenantContext) }
            .hasMessageContaining("UnitLoad")
        assertThat(unitLoadService.findByLocation(RI_LOCATION_ID, tenantContext).map { it.id })
            .contains(mineId)
            .doesNotContain(foreignId)
        // Cross-module SPI returns null (rather than throwing) for a foreign owner.
        assertThat(unitLoadLookup.findById(foreignId)).isNull()
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `OPS principal reads unit loads across owners`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val foreignLabel = "RI-ULO-F-$suffix"
        val foreignId = persistUnitLoad(owner = 2L, label = foreignLabel)

        tenantContext.clientId = 0L
        tenantContext.principalKind = PrincipalKind.OPS

        assertThat(unitLoadService.findById(foreignId, tenantContext).id).isEqualTo(foreignId)
        assertThat(unitLoadService.findByLabelId(foreignLabel, tenantContext).id).isEqualTo(foreignId)
        assertThat(unitLoadLookup.findById(foreignId)).isNotNull()
    }

    companion object {
        /** Any existing location id — these tests only assert owner filtering, not placement. */
        private const val RI_LOCATION_ID = 1L
    }
}
