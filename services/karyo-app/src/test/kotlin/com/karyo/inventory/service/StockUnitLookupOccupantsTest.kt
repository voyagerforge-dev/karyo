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

/**
 * Integration test (real beans + DB) for [DefaultStockUnitLookup.occupantsByLocationIds]
 * (location-finder sprint Task 4) -- the deliberately UNSCOPED per-location occupant read
 * Task 5's client-mixing pass will consume. Mirrors [DefaultStockUnitLookupTest]'s direct-entity
 * seeding pattern: REST is bound to the fixed test principal (client 1), so cross-client and
 * arbitrary-state fixtures need entity persistence.
 */
@QuarkusTest
class StockUnitLookupOccupantsTest {

    @Inject
    lateinit var lookup: StockUnitLookup

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var stockUnitRepository: com.karyo.inventory.repository.StockUnitRepository

    private fun suffix() = System.nanoTime()

    @Transactional
    fun seedStock(locationId: Long, itemDataId: Long, clientId: Long, amount: BigDecimal, state: Int): Long {
        val em = stockUnitRepository.getEntityManager()
        val ulType = em.find(UnitLoadType::class.java, 1L)
        val ul = UnitLoad().apply {
            labelId = "UL-OCC-${System.nanoTime()}"
            unitLoadType = ulType
            storageLocationId = locationId
            storageLocationName = "OCC-LOC"
            this.clientId = clientId
        }
        em.persist(ul)
        val su = StockUnit().apply {
            this.clientId = clientId
            this.itemDataId = itemDataId
            itemDataNumber = "OCC-SKU"
            this.amount = amount
            unitLoad = ul
            this.state = state
        }
        em.persist(su)
        return su.id!!
    }

    /** A second stock-unit row on a NEW unit load, same location/client/item -- for the
     * duplicate-triple collapse test. */
    @Transactional
    fun seedSecondLot(locationId: Long, itemDataId: Long, clientId: Long, amount: BigDecimal, state: Int): Long =
        seedStock(locationId, itemDataId, clientId, amount, state)

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `occupants are reported per location with client and item`() {
        val s = suffix()
        val itemDataId = 60_000_000L + (s % 1_000_000)
        val locA = 70_000_000L + (s % 1_000_000)
        val locB = locA + 1
        val locUntouched = locA + 2

        seedStock(locA, itemDataId, clientId = 1L, amount = BigDecimal.TEN, state = 300)
        seedStock(locB, itemDataId, clientId = 1L, amount = BigDecimal.ONE, state = 300)

        tenantContext.clientId = 1L
        val result = lookup.occupantsByLocationIds(setOf(locA, locB, locUntouched))

        assertThat(result).hasSize(2)
        assertThat(result).allMatch { it.clientId == 1L && it.itemDataId == itemDataId }
        assertThat(result.map { it.locationId }).containsExactlyInAnyOrder(locA, locB)
        assertThat(result.none { it.locationId == locUntouched }).isTrue()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `another client's stock IS visible - deliberately unscoped`() {
        val s = suffix()
        val itemDataId = 60_100_000L + (s % 1_000_000)
        val loc = 70_100_000L + (s % 1_000_000)
        val foreignClientId = s

        seedStock(loc, itemDataId, clientId = foreignClientId, amount = BigDecimal.TEN, state = 300)

        // The caller (test principal) is client 1; the occupant is a different owner entirely.
        tenantContext.clientId = 1L
        val result = lookup.occupantsByLocationIds(setOf(loc))

        assertThat(result).hasSize(1)
        assertThat(result.single().clientId).isEqualTo(foreignClientId)
        assertThat(result.single().itemDataId).isEqualTo(itemDataId)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `DELETABLE and zero-amount stock are not occupants`() {
        val s = suffix()
        val itemDataId = 60_200_000L + (s % 1_000_000)
        val loc = 70_200_000L + (s % 1_000_000)

        seedStock(loc, itemDataId, clientId = 1L, amount = BigDecimal.TEN, state = 1000) // DELETABLE
        seedStock(loc, itemDataId + 1, clientId = 1L, amount = BigDecimal.ZERO, state = 300) // zero amount

        tenantContext.clientId = 1L
        val result = lookup.occupantsByLocationIds(setOf(loc))

        assertThat(result).isEmpty()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `duplicate triples collapse - one row per distinct occupant`() {
        val s = suffix()
        val itemDataId = 60_300_000L + (s % 1_000_000)
        val loc = 70_300_000L + (s % 1_000_000)

        // Two separate lots (unit loads), same location, same owner, same item.
        seedStock(loc, itemDataId, clientId = 1L, amount = BigDecimal.ONE, state = 300)
        seedSecondLot(loc, itemDataId, clientId = 1L, amount = BigDecimal("2.5"), state = 300)

        tenantContext.clientId = 1L
        val result = lookup.occupantsByLocationIds(setOf(loc))

        assertThat(result).hasSize(1)
        assertThat(result.single().locationId).isEqualTo(loc)
        assertThat(result.single().clientId).isEqualTo(1L)
        assertThat(result.single().itemDataId).isEqualTo(itemDataId)
    }
}
