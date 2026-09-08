package com.karyo.demo

import com.karyo.demo.gen.CatalogGenerator
import com.karyo.demo.gen.CycleCountGenerator
import com.karyo.demo.gen.InventoryGenerator
import com.karyo.demo.gen.MonitorSeedGenerator
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@QuarkusTest
class MonitorSeedIT {
    @Inject lateinit var catalog: CatalogGenerator
    @Inject lateinit var inv: InventoryGenerator
    @Inject lateinit var counts: CycleCountGenerator
    @Inject lateinit var monitors: MonitorSeedGenerator
    @Inject lateinit var em: EntityManager

    @Test @TestTransaction
    fun `seeds count variances, a stuck order, and shrinkage`() {
        val cat = catalog.generate(1L)
        val refs = inv.generate(1L, cat)
        val lines = counts.generate(1L, cat, refs)
        val tripped = monitors.generate(1L, cat, refs)

        assertTrue(lines >= 1)
        // count variance present
        val variance = em.createNativeQuery(
            "SELECT count(*) FROM karyo.count_lines WHERE client_id=1 AND counted_amount IS NOT NULL AND counted_amount <> planned_amount",
        ).singleResult as Number
        assertTrue(variance.toInt() >= 1)
        // count lines must be backdated (kpi_accuracy_daily buckets on count_lines.created)
        val backdatedCountLines = em.createNativeQuery(
            "SELECT count(*) FROM karyo.count_lines WHERE client_id=1 AND created < NOW() - INTERVAL '2 days'",
        ).singleResult as Number
        assertTrue(backdatedCountLines.toInt() >= 1, "count_lines must be backdated (kpi_accuracy_daily buckets on count_lines.created)")
        // stuck order: non-terminal state, modified > 24h ago
        val stuck = em.createNativeQuery(
            "SELECT count(*) FROM karyo.delivery_orders WHERE client_id=1 AND state > 0 AND state < 700 AND modified < NOW() - INTERVAL '24 hours'",
        ).singleResult as Number
        assertTrue(stuck.toInt() >= 1, "expected a stuck order")
        // shrinkage: negative journal amount rows
        val shrink = em.createNativeQuery(
            "SELECT count(*) FROM karyo.inventory_journals WHERE client_id=1 AND amount < 0",
        ).singleResult as Number
        assertTrue(shrink.toInt() >= 1)
        assertTrue(tripped.containsAll(listOf("stuck-order", "shrinkage")))
    }
}
