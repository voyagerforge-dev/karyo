package com.karyo.inventory.service

import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Integration test (real beans + DB) for [DefaultStockUnitLookup.itemStocksByLocationIds] and
 * [DefaultStockUnitLookup.fifoConsolidationRefs] (location-finder sprint Task 6, LF10
 * foundation). Mirrors [StockUnitLookupOccupantsTest]'s direct-entity seeding pattern -- REST is
 * bound to the fixed test principal (client 1), so cross-client/arbitrary-state/arbitrary-lot
 * fixtures need entity persistence.
 */
@QuarkusTest
class StockUnitLookupConsolidationTest {

    @Inject
    lateinit var lookup: StockUnitLookup

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var stockUnitRepository: com.karyo.inventory.repository.StockUnitRepository

    private fun suffix() = System.nanoTime()

    @Transactional
    fun seedStock(
        locationId: Long,
        itemDataId: Long,
        clientId: Long,
        amount: BigDecimal = BigDecimal.TEN,
        state: Int = 300,
        lockType: Int = 0,
        lotNumber: String? = null,
        bestBefore: LocalDate? = null,
        strategyDate: Instant? = null,
        created: Instant? = null,
    ): Long {
        val em = stockUnitRepository.getEntityManager()
        val ulType = em.find(UnitLoadType::class.java, 1L)
        val ul = UnitLoad().apply {
            labelId = "UL-CONS-${System.nanoTime()}"
            unitLoadType = ulType
            storageLocationId = locationId
            storageLocationName = "CONS-LOC-$locationId"
            this.clientId = clientId
        }
        em.persist(ul)
        val su = StockUnit().apply {
            this.clientId = clientId
            this.itemDataId = itemDataId
            itemDataNumber = "CONS-SKU"
            this.amount = amount
            unitLoad = ul
            this.state = state
            this.lockType = lockType
            this.lotNumber = lotNumber
            this.bestBefore = bestBefore
            this.strategyDate = strategyDate
            if (created != null) this.created = created
        }
        em.persist(su)
        return su.id!!
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `itemStocks returns same-item stock on the given locations with lot and id`() {
        val s = suffix()
        val itemDataId = 61_000_000L + (s % 1_000_000)
        val loc = 71_000_000L + (s % 1_000_000)

        val stockUnitId = seedStock(loc, itemDataId, clientId = 1L, lotNumber = "LOT-A")

        tenantContext.clientId = 1L
        val result = lookup.itemStocksByLocationIds(itemDataId, setOf(loc), 1L)

        assertThat(result).hasSize(1)
        val ref = result.single()
        assertThat(ref.stockUnitId).isEqualTo(stockUnitId)
        assertThat(ref.locationId).isEqualTo(loc)
        assertThat(ref.lotNumber).isEqualTo("LOT-A")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `itemStocks is tenant-scoped and item-scoped - other items and clients invisible`() {
        val s = suffix()
        val itemDataId = 61_100_000L + (s % 1_000_000)
        val otherItemDataId = itemDataId + 1
        val loc = 71_100_000L + (s % 1_000_000)
        val foreignClientId = s

        seedStock(loc, itemDataId, clientId = 1L, lotNumber = "LOT-B")
        seedStock(loc, otherItemDataId, clientId = 1L, lotNumber = "LOT-C") // wrong item
        seedStock(loc, itemDataId, clientId = foreignClientId, lotNumber = "LOT-D") // wrong client

        tenantContext.clientId = 1L
        val result = lookup.itemStocksByLocationIds(itemDataId, setOf(loc), 1L)

        assertThat(result).hasSize(1)
        assertThat(result.single().lotNumber).isEqualTo("LOT-B")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `fifo refs come back strategyDate ASC then amount ASC then created ASC then id ASC`() {
        val s = suffix()
        val itemDataId = 61_200_000L + (s % 1_000_000)
        val loc = 71_200_000L + (s % 1_000_000)
        val base = Instant.now().minus(30, ChronoUnit.DAYS)
        val laterDate = base.plus(1, ChronoUnit.DAYS)
        val earlierCreated = base.plus(1, ChronoUnit.DAYS)
        val laterCreated = base.plus(2, ChronoUnit.DAYS)

        // Unique earliest strategyDate, largest amount -- strategyDate still wins over amount.
        val idDateOnly = seedStock(
            loc, itemDataId, clientId = 1L,
            amount = BigDecimal(100), strategyDate = base,
        )
        // Same (later) strategyDate as the group below, larger amount -- amount ASC ranks it last
        // within the date group.
        val idAmountHigh = seedStock(
            loc, itemDataId, clientId = 1L,
            amount = BigDecimal(9), strategyDate = laterDate, created = laterCreated,
        )
        // Same date, smaller amount, earlier created -- sorts before the amount-tied group below.
        val idAmountLow = seedStock(
            loc, itemDataId, clientId = 1L,
            amount = BigDecimal(2), strategyDate = laterDate, created = earlierCreated,
        )
        // Same date and amount as [idAmountLow]'s tie group, later created -- three-way id tie
        // group below shares this exact (date, amount, created) triple.
        val idCreatedTie1 = seedStock(
            loc, itemDataId, clientId = 1L,
            amount = BigDecimal(2), strategyDate = laterDate, created = laterCreated,
        )
        val idCreatedTie2 = seedStock(
            loc, itemDataId, clientId = 1L,
            amount = BigDecimal(2), strategyDate = laterDate, created = laterCreated,
        )
        // strategyDate null -- NULLS LAST puts it after every dated row.
        val idNullDate = seedStock(loc, itemDataId, clientId = 1L, amount = BigDecimal.ONE, strategyDate = null)

        tenantContext.clientId = 1L
        val result = lookup.fifoConsolidationRefs(itemDataId, null, null, 1L, 100)

        assertThat(result.map { it.stockUnitId }).containsExactly(
            idDateOnly, idAmountLow, idCreatedTie1, idCreatedTie2, idAmountHigh, idNullDate,
        )
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `fifo refs filter lot and bestBefore only when specified`() {
        val s = suffix()
        val itemDataId = 61_300_000L + (s % 1_000_000)
        val loc = 71_300_000L + (s % 1_000_000)
        val bbA = LocalDate.now().plusDays(30)
        val bbB = LocalDate.now().plusDays(60)

        val idLotABbA = seedStock(loc, itemDataId, clientId = 1L, lotNumber = "LOT-A", bestBefore = bbA)
        val idLotBBbB = seedStock(loc, itemDataId, clientId = 1L, lotNumber = "LOT-B", bestBefore = bbB)

        tenantContext.clientId = 1L

        // No filters: both visible.
        val unfiltered = lookup.fifoConsolidationRefs(itemDataId, null, null, 1L, 100)
        assertThat(unfiltered.map { it.stockUnitId }).containsExactlyInAnyOrder(idLotABbA, idLotBBbB)

        // Lot only: narrows to the matching lot regardless of bestBefore.
        val byLot = lookup.fifoConsolidationRefs(itemDataId, "LOT-A", null, 1L, 100)
        assertThat(byLot.map { it.stockUnitId }).containsExactly(idLotABbA)

        // bestBefore only: narrows to the matching date regardless of lot.
        val byBestBefore = lookup.fifoConsolidationRefs(itemDataId, null, bbB, 1L, 100)
        assertThat(byBestBefore.map { it.stockUnitId }).containsExactly(idLotBBbB)

        // Both: narrows to the exact combination.
        val byBoth = lookup.fifoConsolidationRefs(itemDataId, "LOT-A", bbA, 1L, 100)
        assertThat(byBoth.map { it.stockUnitId }).containsExactly(idLotABbA)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `fifo refs exclude non-ON_STOCK and locked stock`() {
        val s = suffix()
        val itemDataId = 61_400_000L + (s % 1_000_000)
        val loc = 71_400_000L + (s % 1_000_000)

        val idOnStockUnlocked = seedStock(loc, itemDataId, clientId = 1L, state = 300, lockType = 0)
        seedStock(loc, itemDataId, clientId = 1L, state = 100, lockType = 0) // INCOMING
        seedStock(loc, itemDataId, clientId = 1L, state = 600, lockType = 0) // PICKED
        seedStock(loc, itemDataId, clientId = 1L, state = 300, lockType = 1) // locked

        tenantContext.clientId = 1L
        val result = lookup.fifoConsolidationRefs(itemDataId, null, null, 1L, 100)

        assertThat(result.map { it.stockUnitId }).containsExactly(idOnStockUnlocked)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `fifo refs honor the limit`() {
        val s = suffix()
        val itemDataId = 61_500_000L + (s % 1_000_000)
        val loc = 71_500_000L + (s % 1_000_000)
        val base = Instant.now().minus(5, ChronoUnit.DAYS)

        val ids = (0 until 5).map {
            seedStock(
                loc, itemDataId, clientId = 1L,
                amount = BigDecimal.ONE, strategyDate = base.plus(it.toLong(), ChronoUnit.DAYS),
            )
        }

        tenantContext.clientId = 1L
        val result = lookup.fifoConsolidationRefs(itemDataId, null, null, 1L, 2)

        assertThat(result).hasSize(2)
        assertThat(result.map { it.stockUnitId }).containsExactly(ids[0], ids[1])
    }
}
