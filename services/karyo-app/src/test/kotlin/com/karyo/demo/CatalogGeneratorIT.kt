package com.karyo.demo

import com.karyo.demo.gen.CatalogGenerator
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@QuarkusTest
class CatalogGeneratorIT {
    @Inject lateinit var gen: CatalogGenerator
    @Inject lateinit var em: EntityManager

    @Test @TestTransaction
    fun `generates the configured catalog with distinct order_index`() {
        val refs = gen.generate(clientId = 1L)
        assertTrue(refs.skus.size >= 20, "expected >=20 skus, got ${refs.skus.size}")
        assertTrue(refs.locations.size >= 30, "expected >=30 locations")
        // order_index values are distinct (proximity signal)
        assertEquals(refs.locations.size, refs.locations.map { it.orderIndex }.distinct().size)
        // rows actually persisted
        val locCount = em.createNativeQuery("SELECT count(*) FROM karyo.storage_locations WHERE client_id=1").singleResult as Number
        assertTrue(locCount.toInt() >= 30)
    }

    @Test @TestTransaction
    fun `is idempotent by name`() {
        val a = gen.generate(1L)
        val b = gen.generate(1L)
        assertEquals(a.skus.size, b.skus.size)
        assertEquals(a.locations.map { it.name }.toSet(), b.locations.map { it.name }.toSet())
    }

    @Test @TestTransaction
    fun `seeds deterministic capacity, temperature-zone, handling-class and kind metadata`() {
        val refs = gen.generate(clientId = 1L)
        val locationNames = refs.locations.map { it.name }

        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            "SELECT capacity, temperature_zone, handling_class, kind FROM karyo.storage_locations WHERE client_id=1 AND name IN (:names)",
        ).setParameter("names", locationNames)
            .resultList as List<Array<Any?>>

        assertTrue(rows.isNotEmpty(), "expected seeded locations")
        // Every row is fully populated -- no fabrication, but no honest gap either since the
        // seeder derives all four deterministically.
        rows.forEach { row ->
            assertTrue(row[0] is Number, "capacity should be set: $row")
            assertTrue(row[1] is String, "temperature_zone should be set: $row")
            assertTrue(row[2] is String, "handling_class should be set: $row")
            assertTrue(row[3] is String, "kind should be set: $row")
        }
        // A believable spread of kinds, not one shared value for all 40 locations.
        val kinds = rows.map { it[3] as String }.toSet()
        assertTrue(kinds.size > 1, "expected a spread of location kinds, got $kinds")
    }
}
