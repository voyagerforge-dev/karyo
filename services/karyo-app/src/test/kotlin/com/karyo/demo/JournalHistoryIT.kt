package com.karyo.demo

import com.karyo.demo.gen.CatalogGenerator
import com.karyo.demo.gen.CycleCountGenerator
import com.karyo.demo.gen.HistoryGenerator
import com.karyo.demo.gen.InventoryGenerator
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class JournalHistoryIT {
    @Inject lateinit var catalog: CatalogGenerator
    @Inject lateinit var inv: InventoryGenerator
    @Inject lateinit var history: HistoryGenerator
    @Inject lateinit var counts: CycleCountGenerator
    @Inject lateinit var em: EntityManager

    @Test @TestTransaction
    fun `seeded movements produce backdated journal rows on both sides`() {
        val cat = catalog.generate(1L)
        val inventory = inv.generate(1L, cat)
        history.generate(1L, cat, inventory)
        counts.generate(1L, cat, inventory)

        // placement + receipts write an inbound (to_storage_location) row
        val inbound = em.createNativeQuery(
            "SELECT count(*) FROM karyo.inventory_journals WHERE client_id=1 AND to_storage_location IS NOT NULL",
        ).singleResult as Number
        assertTrue(inbound.toInt() >= 1, "expected inbound (to-location) journal rows")

        // picks write an outbound (from_storage_location) row
        val outbound = em.createNativeQuery(
            "SELECT count(*) FROM karyo.inventory_journals WHERE client_id=1 AND from_storage_location IS NOT NULL",
        ).singleResult as Number
        assertTrue(outbound.toInt() >= 1, "expected outbound (from-location) journal rows")

        // all backdated (created strictly before now)
        val future = em.createNativeQuery(
            "SELECT count(*) FROM karyo.inventory_journals WHERE client_id=1 AND created > now()",
        ).singleResult as Number
        assertTrue(future.toInt() == 0, "journal rows must be backdated")

        // B10: the counted locations get a backdated last_counted_at; every other seeded
        // location honestly stays null.
        val countedLocations = em.createNativeQuery(
            "SELECT count(*) FROM karyo.storage_locations WHERE client_id=1 AND last_counted_at IS NOT NULL",
        ).singleResult as Number
        assertTrue(countedLocations.toInt() >= 1, "expected at least one location with a non-null last_counted_at")

        val futureCounted = em.createNativeQuery(
            "SELECT count(*) FROM karyo.storage_locations WHERE client_id=1 AND last_counted_at > now()",
        ).singleResult as Number
        assertTrue(futureCounted.toInt() == 0, "last_counted_at must be backdated")
    }
}
