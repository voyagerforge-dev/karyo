package com.karyo.inventory

import com.karyo.inventory.api.spi.StockCountingPort
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.inventory.service.StockService
import com.karyo.security.PrincipalKind
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Two residuals filed at the stocktaking-block final gate (2026-08-01), defect row 7:
 *
 * (a) [StockService.adjustAmount] carries no state guard, so any caller can write onto a
 * DELETABLE (soft-deleted) stock unit and resurrect it. The one caller that can *reach* DELETABLE
 * with a caller-controlled id is REST `POST /stock-units/{id}/adjust`
 * ([com.karyo.inventory.api.v1.StockUnitResource.adjustAmount]) — [DefaultStockCountingPort]
 * cannot, because [DefaultStockCountingPort.findStockAtLocation] filters `state != DELETABLE` at
 * plan time (pinned by `StartCountTest`'s
 * "startCount plans only live stock" case).
 *
 * (b) The emptied-UL terminal flip in [DefaultStockCountingPort.applyCount] loaded the parent
 * [UnitLoad] with a raw `findById`, no tenant predicate — a latent cross-tenant scoping hole,
 * exercised here by constructing a UL owned by one client with a stock unit owned by a different
 * client riding on it (repositories directly, bypassing REST, since the normal write paths never
 * allow this to arise).
 *
 * clientId 4404-4406 reserved for this suite.
 */
@QuarkusTest
class AdjustDeletableGuardTest {

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var port: StockCountingPort

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    // ── seed helpers ────────────────────────────────────────────────────────

    private fun createUnitLoad(label: String, locationId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"LOC-$locationId"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"ADG-${System.nanoTime().toString().takeLast(8)}",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /**
     * Seeds a UL owned by [ulOwner] with one stock unit owned by [stockOwner] riding on it —
     * bypassing REST, since no live write path lets ownership diverge like this. Used only to
     * exercise the defense-in-depth scope check on the UL-flip read.
     */
    @Transactional
    fun seedCrossOwnerRig(ulOwner: Long, stockOwner: Long, suffix: String, amount: BigDecimal): Pair<Long, Long> {
        val type = unitLoadTypeRepository.listAll().first()
        val ul = UnitLoad().apply {
            this.clientId = ulOwner
            this.labelId = "ADG-UL-$suffix"
            this.unitLoadType = type
            this.storageLocationId = 1L
            this.storageLocationName = "ADG-LOC"
        }
        unitLoadRepository.persist(ul)
        val su = StockUnit().apply {
            this.clientId = stockOwner
            this.itemDataId = 1L
            this.itemDataNumber = "ADG-SKU-$suffix"
            this.amount = amount
            this.state = StockState.ON_STOCK.code
            this.unitLoad = ul
        }
        stockUnitRepository.persist(su)
        return ul.id!! to su.id!!
    }

    // ── (a) DELETABLE-adjust refusal ──────────────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4404"), Claim(key = "tenant_code", value = "ACME")])
    fun `adjustAmount on a DELETABLE stock unit is refused, not resurrected`() {
        val locationId = 44040L
        val ulId = createUnitLoad("UL-ADG-${System.nanoTime()}", locationId)
        val suId = createStock(ulId, 4404_001L, 25.0)
        tenantContext.clientId = 4404L

        stockService.deleteStock(suId, tenantContext)
        assertThat(stockUnitRepository.findById(suId)!!.state).isEqualTo(StockState.DELETABLE.code)

        assertThatThrownBy {
            stockService.adjustAmount(suId, BigDecimal("5"), "COUNT", tenantContext)
        }.isInstanceOf(InventoryException.InvalidStateTransition::class.java)

        // Not resurrected -- amount and state both untouched by the refused call.
        val after = stockUnitRepository.findById(suId)!!
        assertThat(after.state).isEqualTo(StockState.DELETABLE.code)
        assertThat(after.amount).isEqualByComparingTo("25")
    }

    // ── (b) cross-tenant UL-flip scoping ──────────────────────────────────

    @Test
    @TestSecurity(user = "owner-a", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4405"), Claim(key = "tenant_code", value = "OWNER-A")])
    fun `applyCount zeroing an owned stock unit does not flip a foreign owner's unit load`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val (ulBId, suAId) = seedCrossOwnerRig(
            ulOwner = 4406L,
            stockOwner = 4405L,
            suffix = suffix,
            amount = BigDecimal("6"),
        )
        tenantContext.clientId = 4405L
        tenantContext.principalKind = PrincipalKind.OWNER

        port.applyCount(suAId, BigDecimal.ZERO, "ST-CROSS-$suffix")

        // The stock unit itself (owned by the caller) is soft-deleted as normal.
        assertThat(stockUnitRepository.findById(suAId)!!.state).isEqualTo(StockState.DELETABLE.code)
        // The parent UL, owned by a DIFFERENT client, must NOT be flipped by an OWNER caller
        // outside its write scope.
        assertThat(unitLoadRepository.findById(ulBId)!!.state).isNotEqualTo(StockState.DELETABLE.code)
    }
}
