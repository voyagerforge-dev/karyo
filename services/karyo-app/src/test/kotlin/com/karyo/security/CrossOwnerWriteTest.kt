package com.karyo.security

import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.inventory.service.StockService
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
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Writes are owner-blind for an OPS principal only: a picker on a mixed route physically
 * handles every owner's cartons, so an OPS mutation must not be gated on the actor's own
 * client_id. A goods-owner (OWNER) principal stays scoped to its own rows on writes exactly
 * as it does on reads — a mutation endpoint echoes the mutated row back, so an unscoped write
 * would also be an unscoped read, defeating the read-isolation the tenant model exists to
 * establish. `client_id` on an OPS write is attribution ("whose goods are these"), never
 * authorization; on an OWNER write it is both.
 */
@QuarkusTest
class CrossOwnerWriteTest {

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var unitLoadService: com.karyo.inventory.service.UnitLoadService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    /** Seeds a stock unit owned by [owner], bypassing REST so a foreign owner can be created. */
    @Transactional
    fun seedStock(owner: Long, amount: BigDecimal, suffix: String): Long {
        val type = unitLoadTypeRepository.listAll().first()
        val ul = UnitLoad().apply {
            this.clientId = owner
            this.labelId = "COW-UL-$suffix"
            this.unitLoadType = type
            this.storageLocationId = 1L
            this.storageLocationName = "COW-LOC"
        }
        unitLoadRepository.persist(ul)
        val su = StockUnit().apply {
            this.clientId = owner
            this.itemDataId = 1L
            this.itemDataNumber = "COW-SKU-$suffix"
            this.amount = amount
            this.unitLoad = ul
        }
        stockUnitRepository.persist(su)
        return su.id!!
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `ops principal adjusts a stock unit owned by a different goods owner`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val foreignStockId = seedStock(owner = 2L, amount = BigDecimal("10"), suffix = suffix)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val adjusted = stockService.adjustAmount(
            foreignStockId, BigDecimal("7"), "COUNT", tenantContext,
        )

        assertThat(adjusted.amount).isEqualByComparingTo(BigDecimal("7"))
        // Attribution is preserved — the goods still belong to owner 2.
        assertThat(adjusted.clientId).isEqualTo(2L)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `OWNER principal cannot mutate another owner's stock unit`() {
        // A goods-owner principal is scoped on writes exactly as it is on reads. Before this
        // fix, findByIdForWrite was unconditionally unscoped, so this call would 200 and hand
        // back owner 2's full stock row through the mutation response — a cross-owner read
        // through a write endpoint. It must instead 404, matching the read path and the branch
        // base's behavior.
        val suffix = System.nanoTime().toString().takeLast(8)
        val foreignStockId = seedStock(owner = 2L, amount = BigDecimal("10"), suffix = suffix)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        assertThatThrownBy {
            stockService.adjustAmount(foreignStockId, BigDecimal("4"), "COUNT", tenantContext)
        }.isInstanceOf(InventoryException.NotFound::class.java)
            .hasMessageContaining("StockUnit")
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `owner principal still cannot READ another owner's stock unit`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val foreignStockId = seedStock(owner = 2L, amount = BigDecimal("5"), suffix = suffix)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        org.assertj.core.api.Assertions.assertThatThrownBy {
            stockService.findById(foreignStockId, tenantContext)
        }.hasMessageContaining("StockUnit")
    }

    @Test
    @TestSecurity(user = "tenant2-operator", roles = ["inventory-read", "inventory-write", "OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "2"),
            Claim(key = "tenant_code", value = "GLOBEX"),
            Claim(key = "principal_kind", value = "owner"),
        ],
    )
    fun `OWNER principal cannot read a foreign owner's stock through the transfer endpoint`() {
        // Closes the reported hole end-to-end: an unconditional findByIdForWrite let a
        // goods-owner principal call a mutation endpoint on another owner's unit load and get
        // back its full stockUnits list (itemDataNumber, amount, lotNumber, state) in a 200 —
        // a cross-owner READ smuggled through a WRITE endpoint. Must now 404, with no
        // foreign-owner data anywhere in the response.
        val suffix = System.nanoTime().toString().takeLast(8)
        val acmeUnitLoadId = seedUnitLoadWithStock(
            owner = 1L,
            labelId = "COW-XFER-$suffix",
            itemDataNumber = "COW-XFER-SKU-$suffix",
        )

        given()
            .contentType(ContentType.JSON)
            .body("""{"destinationLocationId":999,"destinationLocationName":"COW-DEST"}""")
            .`when`().post("/api/v1/unit-loads/$acmeUnitLoadId/transfer")
            .then().statusCode(404)
            .body(not(org.hamcrest.Matchers.containsString("COW-XFER-SKU-$suffix")))
    }

    /** Seeds a unit load with one stock unit owned by [owner], bypassing REST. */
    @Transactional
    fun seedUnitLoadWithStock(owner: Long, labelId: String, itemDataNumber: String): Long {
        val type = unitLoadTypeRepository.listAll().first()
        val ul = UnitLoad().apply {
            this.clientId = owner
            this.labelId = labelId
            this.unitLoadType = type
            this.storageLocationId = 1L
            this.storageLocationName = "COW-LOC"
        }
        unitLoadRepository.persist(ul)
        val su = StockUnit().apply {
            this.clientId = owner
            this.itemDataId = 1L
            this.itemDataNumber = itemDataNumber
            this.amount = BigDecimal("3")
            this.unitLoad = ul
        }
        stockUnitRepository.persist(su)
        return ul.id!!
    }

    /** Seeds a bare unit load (no stock) owned by [owner], bypassing REST. */
    @Transactional
    fun seedUnitLoad(owner: Long, labelId: String, isCarrier: Boolean = false): Long {
        val type = unitLoadTypeRepository.listAll().first()
        val ul = UnitLoad().apply {
            this.clientId = owner
            this.labelId = labelId
            this.unitLoadType = type
            this.storageLocationId = 1L
            this.storageLocationName = "COW-LOC"
            this.isCarrier = isCarrier
        }
        unitLoadRepository.persist(ul)
        return ul.id!!
    }

    // --- Finding 1: createStock loads the parent unit load unscoped ---------------------------

    @Test
    @TestSecurity(user = "tenant2-operator", roles = ["inventory-read", "inventory-write", "OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "2"),
            Claim(key = "tenant_code", value = "GLOBEX"),
            Claim(key = "principal_kind", value = "owner"),
        ],
    )
    fun `OWNER cannot create stock on a foreign owner's unit load`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val acmeUnitLoadId = seedUnitLoad(owner = 1L, labelId = "COW-CREATE-$suffix")

        tenantContext.clientId = 2L
        tenantContext.principalKind = PrincipalKind.OWNER

        val request = com.karyo.inventory.api.dto.CreateStockUnitRequest(
            itemDataId = 1L,
            itemDataNumber = "COW-CREATE-SKU-$suffix",
            amount = BigDecimal("5"),
            unitLoadId = acmeUnitLoadId,
        )

        assertThatThrownBy {
            stockService.createStock(request, tenantContext)
        }.isInstanceOf(InventoryException.NotFound::class.java)
            .hasMessageContaining("UnitLoad")

        // Nothing was attributed to owner 1's foreign unit load.
        assertThat(stockUnitRepository.findByUnitLoadId(acmeUnitLoadId)).isEmpty()
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `OPS can create stock on any owner's unit load, attributed to the unit load's owner`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val globexUnitLoadId = seedUnitLoad(owner = 2L, labelId = "COW-CREATE-OPS-$suffix")

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val request = com.karyo.inventory.api.dto.CreateStockUnitRequest(
            itemDataId = 1L,
            itemDataNumber = "COW-CREATE-OPS-SKU-$suffix",
            amount = BigDecimal("5"),
            unitLoadId = globexUnitLoadId,
        )

        val created = stockService.createStock(request, tenantContext)

        // Attributed to the unit load's owner (2), not the acting OPS principal's own
        // client_id (1) -- this is the capability the program exists to add.
        assertThat(created.clientId).isEqualTo(2L)
        assertThat(stockUnitRepository.findByUnitLoadId(globexUnitLoadId))
            .anyMatch { it.id == created.id }
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `OWNER principal cannot changeClient a foreign unit load - Forbidden before any row load`() {
        // changeClient's OPS gate fires BEFORE findByIdForWrite, so an OWNER principal gets
        // Forbidden (403) uniformly — for a foreign unit load, its own, or a nonexistent id —
        // rather than the 404 of the scoped-load paths. The endpoint is not an existence oracle.
        val suffix = System.nanoTime().toString().takeLast(8)
        val foreignUnitLoadId = seedUnitLoadWithStock(
            owner = 2L,
            labelId = "COW-CC-$suffix",
            itemDataNumber = "COW-CC-SKU-$suffix",
        )

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        assertThatThrownBy {
            unitLoadService.changeClient(foreignUnitLoadId, 1L, null, tenantContext)
        }.isInstanceOf(InventoryException.Forbidden::class.java)

        // Attribution untouched — owner 2 still owns the load and its stock.
        assertThat(unitLoadRepository.findById(foreignUnitLoadId)!!.clientId).isEqualTo(2L)
        assertThat(stockUnitRepository.findByUnitLoadId(foreignUnitLoadId))
            .allSatisfy { assertThat(it.clientId).isEqualTo(2L) }
    }

    // --- Finding 2: transferStock loads the target unit load unscoped -------------------------

    @Test
    @TestSecurity(user = "tenant2-operator", roles = ["inventory-read", "inventory-write", "OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "2"),
            Claim(key = "tenant_code", value = "GLOBEX"),
            Claim(key = "principal_kind", value = "owner"),
        ],
    )
    fun `OWNER cannot transfer stock onto a foreign owner's unit load`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val acmeTargetUnitLoadId = seedUnitLoad(owner = 1L, labelId = "COW-XFER-TARGET-$suffix")
        val sourceStockId = seedStock(owner = 2L, amount = BigDecimal("10"), suffix = "src-$suffix")

        tenantContext.clientId = 2L
        tenantContext.principalKind = PrincipalKind.OWNER

        assertThatThrownBy {
            stockService.transferStock(
                sourceStockId, acmeTargetUnitLoadId, BigDecimal("4"), "TRANSFER", tenantContext,
            )
        }.isInstanceOf(InventoryException.NotFound::class.java)
            .hasMessageContaining("UnitLoad")
    }

    // --- D1 (2026-07-25): transferStock refuses cross-owner movement outright -----------------

    /**
     * Seeds a target unit load owned by [owner] using unit-load-type [typeId], plus one existing
     * stock unit on it for [itemDataId] (lotNumber left null, matching [seedStock]'s convention,
     * so it lines up with a source stock unit for the same itemDataId). Returns
     * (unitLoadId, existingStockUnitId).
     */
    @Transactional
    fun seedTargetWithMatchingStock(
        owner: Long,
        labelId: String,
        itemDataId: Long,
        amount: BigDecimal,
        typeId: Long,
    ): Pair<Long, Long> {
        val type = unitLoadTypeRepository.findById(typeId)!!
        val ul = UnitLoad().apply {
            this.clientId = owner
            this.labelId = labelId
            this.unitLoadType = type
            this.storageLocationId = 1L
            this.storageLocationName = "COW-LOC"
        }
        unitLoadRepository.persist(ul)
        val su = StockUnit().apply {
            this.clientId = owner
            this.itemDataId = itemDataId
            this.itemDataNumber = "COW-EXIST-$labelId"
            this.amount = amount
            this.unitLoad = ul
        }
        stockUnitRepository.persist(su)
        return ul.id!! to su.id!!
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `OPS transfer onto a pallet owned by another client is refused 409 cross-owner`() {
        // Exact defect scenario: target UL is an aggregate-stocks type (unitLoadTypeId=2, "Pick
        // Bin", seeded aggregate_stocks=TRUE) already holding a matching SKU/lot stock unit, so
        // the merge branch would otherwise silently grow another owner's balance.
        val suffix = System.nanoTime().toString().takeLast(8)
        val sourceStockId = seedStock(owner = 1L, amount = BigDecimal("10"), suffix = "src-$suffix")
        val (targetUnitLoadId, existingStockId) = seedTargetWithMatchingStock(
            owner = 2L,
            labelId = "COW-MERGE-$suffix",
            itemDataId = 1L,
            amount = BigDecimal("5"),
            typeId = 2L,
        )

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        assertThatThrownBy {
            stockService.transferStock(
                sourceStockId, targetUnitLoadId, BigDecimal("4"), "TRANSFER", tenantContext,
            )
        }.isInstanceOf(InventoryException.CrossOwner::class.java)

        // Neither side moved.
        assertThat(stockUnitRepository.findById(existingStockId)!!.amount).isEqualByComparingTo(BigDecimal("5"))
        assertThat(stockUnitRepository.findById(sourceStockId)!!.amount).isEqualByComparingTo(BigDecimal("10"))
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `OPS transfer onto a foreign empty pallet is refused too - new-stock branch`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val sourceStockId = seedStock(owner = 1L, amount = BigDecimal("10"), suffix = "src2-$suffix")
        val targetUnitLoadId = seedUnitLoad(owner = 2L, labelId = "COW-EMPTY-$suffix")

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        assertThatThrownBy {
            stockService.transferStock(
                sourceStockId, targetUnitLoadId, BigDecimal("4"), "TRANSFER", tenantContext,
            )
        }.isInstanceOf(InventoryException.CrossOwner::class.java)

        assertThat(stockUnitRepository.findById(sourceStockId)!!.amount).isEqualByComparingTo(BigDecimal("10"))
        assertThat(stockUnitRepository.findByUnitLoadId(targetUnitLoadId)).isEmpty()
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `same-owner transfer still merges and creates normally`() {
        // Belt for regression: the guard must not fire when source and target share an owner.
        val suffix = System.nanoTime().toString().takeLast(8)
        val sourceStockId = seedStock(owner = 1L, amount = BigDecimal("10"), suffix = "same-$suffix")
        val (targetUnitLoadId, existingStockId) = seedTargetWithMatchingStock(
            owner = 1L,
            labelId = "COW-SAME-$suffix",
            itemDataId = 1L,
            amount = BigDecimal("5"),
            typeId = 2L,
        )

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val merged = stockService.transferStock(
            sourceStockId, targetUnitLoadId, BigDecimal("4"), "TRANSFER", tenantContext,
        )

        assertThat(merged.id).isEqualTo(existingStockId)
        assertThat(merged.amount).isEqualByComparingTo(BigDecimal("9"))
        assertThat(stockUnitRepository.findById(sourceStockId)!!.amount).isEqualByComparingTo(BigDecimal("6"))
    }

    // --- D1 (2026-07-31): transferToCarrier refuses cross-owner nesting outright (WORKLIST F3) --

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `OPS transferToCarrier onto another owner's carrier is refused 409 cross-owner`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val ulId = seedUnitLoad(owner = 1L, labelId = "COW-TTC-SUB-$suffix")
        val carrierId = seedUnitLoad(owner = 2L, labelId = "COW-TTC-CARR-$suffix", isCarrier = true)

        given()
            .contentType(ContentType.JSON)
            .body("""{"carrierUnitLoadId":$carrierId}""")
            .`when`().post("/api/v1/unit-loads/$ulId/transfer-to-carrier")
            .then().statusCode(409)
            .body("type", org.hamcrest.Matchers.containsString("cross-owner"))

        // Neither side moved.
        assertThat(unitLoadRepository.findById(ulId)!!.carrierUnitLoad).isNull()
    }
}
