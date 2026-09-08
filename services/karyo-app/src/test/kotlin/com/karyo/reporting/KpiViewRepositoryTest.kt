package com.karyo.reporting

import com.karyo.reporting.repository.KpiViewRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.TestTransaction
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@QuarkusTest
class KpiViewRepositoryTest {
    @Inject lateinit var repo: KpiViewRepository
    @Inject lateinit var em: EntityManager

    @Test @TestTransaction
    fun `accuracy aggregates clean vs total count lines for the tenant in window`() {
        // seed two count_lines for client 4242: one accurate (counted=planned), one off.
        val coId = seedCountOrder(4242)
        em.createNativeQuery(
            "INSERT INTO karyo.count_lines (client_id, count_order_id, stock_unit_id, item_data_id, item_data_number, planned_amount, counted_amount, state, created) " +
            "VALUES (4242, $coId, 1, 1, 'X', 10, 10, 90, now()), (4242, $coId, 2, 1, 'X', 10, 7, 90, now())"
        ).executeUpdate()
        // end must map to tomorrow so the date filter `day < end_date` includes today's rows
        val rows = repo.accuracy(4242, Instant.now().minusSeconds(3600), Instant.now().plus(Duration.ofDays(1)))
        val total = rows.sumOf { it.totalLines }
        val accurate = rows.sumOf { it.accurateLines }
        assertEquals(2, total)
        assertEquals(1, accurate)
    }

    @Test @TestTransaction
    fun `utilization returns occupied and usable counts`() {
        val (occ, usable) = repo.utilization(0) // client 0 may have seeded layout; just assert non-negative & occ<=usable
        assertTrue(usable >= 0)
        assertTrue(occ <= usable)
    }

    @Test @TestTransaction
    fun `throughput executes and returns rows for the tenant`() {
        val suffix = System.nanoTime()
        // picks has a real FK to pick_orders; seed parent first
        val poId = (em.createNativeQuery(
            "INSERT INTO karyo.pick_orders (client_id, pick_order_number, delivery_order_id, delivery_order_number, version, created, modified) " +
            "VALUES (5151, 'PO-$suffix', 1, 'DO-$suffix', 0, now(), now()) RETURNING id"
        ).singleResult as Number).toLong()
        em.createNativeQuery(
            "INSERT INTO karyo.picks (client_id, pick_order_id, delivery_order_line_id, item_data_id, item_data_number, " +
            "source_stock_unit_id, planned_amount, picked_amount, version, created, modified) " +
            "VALUES (5151, $poId, 1, 1, 'SKU-TEST', 1, 5, 5, 0, now(), now())"
        ).executeUpdate()
        val rows = repo.throughput(5151, Instant.now().minusSeconds(3600), Instant.now().plus(Duration.ofDays(1)))
        assertNotNull(rows)
        assertTrue(rows.sumOf { it.unitsPicked } >= 5.0)
    }

    @Test @TestTransaction
    fun `throughput units_picked excludes EXTINGUISH picks`() {
        val suffix = System.nanoTime()
        val client = 5152L
        val poId = (em.createNativeQuery(
            "INSERT INTO karyo.pick_orders (client_id, pick_order_number, delivery_order_id, delivery_order_number, version, created, modified) " +
            "VALUES ($client, 'PO-EXT-$suffix', 1, 'DO-EXT-$suffix', 0, now(), now()) RETURNING id"
        ).singleResult as Number).toLong()
        em.createNativeQuery(
            "INSERT INTO karyo.picks (client_id, pick_order_id, delivery_order_line_id, item_data_id, item_data_number, " +
            "source_stock_unit_id, planned_amount, picked_amount, picking_type, version, created, modified) " +
            "VALUES (:client, :poId, 1, 1, 'SKU-EXT-TEST', 1, 5, 5, 'PICK', 0, now(), now()), " +
            "       (:client, :poId, 1, 1, 'SKU-EXT-TEST', 1, 7, 7, 'EXTINGUISH', 0, now(), now())"
        )
            .setParameter("client", client)
            .setParameter("poId", poId)
            .executeUpdate()
        val rows = repo.throughput(client, Instant.now().minusSeconds(3600), Instant.now().plus(Duration.ofDays(1)))
        assertEquals(5.0, rows.sumOf { it.unitsPicked })
    }

    @Test @TestTransaction
    fun `cycleTime executes and returns a list without error`() {
        // No shipments for this tenant; exercises the SQL join without needing seed data
        val result = repo.cycleTime(999999, Instant.now().minusSeconds(86400), Instant.now())
        assertNotNull(result)
    }

    /** Minimal parent count_order row so the FK holds; returns its id. */
    private fun seedCountOrder(client: Long): Long {
        val suffix = System.nanoTime()
        val sessId = (em.createNativeQuery(
            "INSERT INTO karyo.count_sessions (client_id, session_number, created, modified, version) " +
            "VALUES ($client, 'S-$suffix', now(), now(), 0) RETURNING id"
        ).singleResult as Number).toLong()
        return (em.createNativeQuery(
            "INSERT INTO karyo.count_orders (client_id, session_id, order_number, location_id, location_name, state, created, modified, version) " +
            "VALUES ($client, $sessId, 'CO-$suffix', 1, 'A-01', 90, now(), now(), 0) RETURNING id"
        ).singleResult as Number).toLong()
    }
}
