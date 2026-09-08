package com.karyo.demo

import com.karyo.demo.gen.CatalogGenerator
import com.karyo.demo.gen.InventoryGenerator
import com.karyo.security.TenantContext
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@QuarkusTest
class InventoryGeneratorIT {
    @Inject lateinit var catalog: CatalogGenerator
    @Inject lateinit var inv: InventoryGenerator
    @Inject lateinit var em: EntityManager
    @Inject lateinit var tenantContext: TenantContext

    @Test @TestTransaction
    fun `seeds on-stock units, near-expiry stock, and a below-min fix assignment`() {
        // TenantContext is @RequestScoped, populated by TenantFilter only during REST requests
        // (see UnitLoadWeightTest's packContainer test for the same pattern); this test calls
        // the generator directly, and InventoryGenerator now reaches ProductLookup.
        // findMeasuresByIds (row :1449, ambient-tenant-scoped) via UnitLoadWeightCalculator.
        // recalculateAll, so clientId must match the generator's own hardcoded seed client (1,
        // ACME) or the product weights it needs would silently scope-filter to nothing.
        tenantContext.clientId = 1L
        val cat = catalog.generate(1L)
        val refs = inv.generate(1L, cat)
        assertTrue(refs.stock.size >= 20)
        // occupancy: many state-300 stock units exist
        val onStock = em.createNativeQuery("SELECT count(*) FROM karyo.stock_units WHERE client_id=1 AND state=300").singleResult as Number
        assertTrue(onStock.toInt() >= 20)
        // occupancy: placed locations carry a real allocation (mirrors the live
        // UnitLoadTransferredObserver, +100 per UL) so the Locations ring reads a
        // true %, not "Empty/0%" over populated bins; empty bins stay 0.
        val occupied = em.createNativeQuery("SELECT count(*) FROM karyo.storage_locations WHERE client_id=1 AND allocation >= 100").singleResult as Number
        assertTrue(occupied.toInt() >= 1, "expected occupied locations to carry allocation=100")
        val emptyBins = em.createNativeQuery("SELECT count(*) FROM karyo.storage_locations WHERE client_id=1 AND allocation = 0").singleResult as Number
        assertTrue(emptyBins.toInt() >= 1, "expected some empty locations to keep allocation=0")
        // expiry-risk: at least one best_before within 7 days
        val expiring = em.createNativeQuery("SELECT count(*) FROM karyo.stock_units WHERE client_id=1 AND best_before IS NOT NULL AND best_before <= (CURRENT_DATE + 7)").singleResult as Number
        assertTrue(expiring.toInt() >= 1, "expected near-expiry stock")
        assertTrue(refs.expiringSkus.isNotEmpty())
        // bin-below-reorder: a fix assignment exists with min set
        val fix = em.createNativeQuery("SELECT count(*) FROM karyo.fix_assignments WHERE client_id=1 AND min_amount IS NOT NULL").singleResult as Number
        assertTrue(fix.toInt() >= 1)
        assertNotNull(refs.belowMinSku)
        // exactly one below-min condition: mirrors the real detector's PER-LOCATION on-hand check
        // (FixAssignmentService.enrichStockAmount(itemDataId, locationId)) — proves fix-assignments
        // are co-located with their SKU's real stock, not landing on an unrelated empty bin.
        val belowMin = em.createNativeQuery(
            """
            SELECT count(*) FROM karyo.fix_assignments fa
            WHERE fa.client_id = 1 AND fa.min_amount IS NOT NULL
              AND COALESCE((
                SELECT sum(su.amount - su.reserved_amount) FROM karyo.stock_units su
                JOIN karyo.unit_loads ul ON ul.id = su.unit_load_id
                WHERE su.client_id = 1 AND su.item_data_id = fa.item_data_id
                  AND ul.storage_location_id = fa.location_id
              ), 0) < fa.min_amount
            """.trimIndent()
        ).singleResult as Number
        assertEquals(1, belowMin.toInt(), "exactly one intended below-min SKU (belowMinSku), no empty-bin false positives")
        // row :1449: every placed unit load gets a real weight via UnitLoadWeightCalculator.
        // recalculateAll, not the null every seeded pallet read before this generator called it.
        val placedUnitLoads = refs.stock.map { it.unitLoadId }.distinct().size
        val weighed = em.createNativeQuery(
            "SELECT count(*) FROM karyo.unit_loads WHERE client_id=1 AND weight IS NOT NULL AND weight > 0"
        ).singleResult as Number
        assertEquals(placedUnitLoads, weighed.toInt(), "every placed unit load should carry a real, positive weight")
    }
}
