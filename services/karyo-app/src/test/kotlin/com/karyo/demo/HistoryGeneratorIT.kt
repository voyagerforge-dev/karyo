package com.karyo.demo

import com.karyo.demo.gen.CatalogGenerator
import com.karyo.demo.gen.HistoryGenerator
import com.karyo.demo.gen.InventoryGenerator
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(HistoryGeneratorIT.SmallWindow::class)
class HistoryGeneratorIT {
    // Keep the IT fast: a short window still proves backdating + all row types.
    class SmallWindow : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "karyo.demo.history-days" to "30",
            "karyo.demo.orders-per-day" to "6",
        )
    }

    @Inject lateinit var catalog: CatalogGenerator
    @Inject lateinit var inv: InventoryGenerator
    @Inject lateinit var hist: HistoryGenerator
    @Inject lateinit var em: EntityManager

    @Test @TestTransaction
    fun `generates backdated picks and shipments spread over the window plus an open putaway backlog`() {
        val cat = catalog.generate(1L)
        val refs = inv.generate(1L, cat)
        val counts = hist.generate(1L, cat, refs)

        assertTrue(counts.orders >= 30, "expected orders across the window, got ${counts.orders}")
        assertTrue(counts.picks >= 30)
        // picks are backdated across multiple distinct days (not all "now")
        val distinctPickDays = em.createNativeQuery(
            "SELECT count(DISTINCT date_trunc('day', created)) FROM karyo.picks WHERE client_id=1",
        ).singleResult as Number
        assertTrue(distinctPickDays.toInt() >= 10, "picks should span many days, got $distinctPickDays")
        // shipments have shipped_at set and backdated
        val shipped = em.createNativeQuery(
            "SELECT count(*) FROM karyo.shipments WHERE client_id=1 AND shipped_at IS NOT NULL " +
                "AND shipped_at < NOW() - INTERVAL '1 day'",
        ).singleResult as Number
        assertTrue(shipped.toInt() >= 1)
        // putaway backlog: >=15 open PUTAWAY transport orders (crosses monitor threshold)
        val backlog = em.createNativeQuery(
            "SELECT count(*) FROM karyo.transport_orders WHERE client_id=1 AND transport_type='PUTAWAY' " +
                "AND finished IS NULL",
        ).singleResult as Number
        assertTrue(backlog.toInt() >= 15, "expected putaway backlog >=15, got $backlog")
        // goods receipt lines must be backdated (units_received KPI reads grl.created via
        // date_trunc('day', grl.created) — if created defaults to now(), all volume collapses
        // onto today and defeats the goods-receipt backdating)
        val backdatedGrl = em.createNativeQuery(
            "SELECT count(*) FROM karyo.goods_receipt_lines WHERE created < NOW() - INTERVAL '2 days'",
        ).singleResult as Number
        assertTrue(backdatedGrl.toInt() >= 1, "goods receipt lines must be backdated (units_received KPI reads grl.created)")
        // completed historical orders must be terminal (FINISHED, not SHIPPED) or the
        // stuck-order monitor (state > 0 AND state < 700 AND modified <= now-24h) false-flags
        // every historical order in the demo
        val falseStuck = em.createNativeQuery(
            "SELECT count(*) FROM karyo.delivery_orders WHERE client_id=1 AND state > 0 AND state < 700 " +
                "AND modified < NOW() - INTERVAL '24 hours'",
        ).singleResult as Number
        assertEquals(0, falseStuck.toInt(), "historical (completed) orders must be FINISHED, not flagged stuck")
        // B7: seeded delivery order lines carry a deterministic per-SKU unit_price (order-value tile)
        val pricedLines = em.createNativeQuery(
            "SELECT count(*) FROM karyo.delivery_order_lines dol " +
                "JOIN karyo.delivery_orders dord ON dord.id = dol.delivery_order_id " +
                "WHERE dord.client_id=1 AND dol.unit_price IS NOT NULL AND dol.unit_price > 0",
        ).singleResult as Number
        assertTrue(pricedLines.toInt() >= 30, "seeded order lines should carry a unit_price, got $pricedLines")
        // demo-polish (460d6d1): seeded shipments carry carrier/service/tracking, so the Orders
        // FULFILL slot (B6) shows real carrier data instead of "—" on a freshly-seeded demo
        val shipmentsWithCarrier = em.createNativeQuery(
            "SELECT count(*) FROM karyo.shipments " +
                "WHERE client_id=1 AND carrier_name IS NOT NULL AND carrier_service IS NOT NULL " +
                "AND tracking_number IS NOT NULL",
        ).singleResult as Number
        assertTrue(
            shipmentsWithCarrier.toInt() >= 1,
            "seeded shipments should carry carrier_name/carrier_service/tracking_number, got $shipmentsWithCarrier",
        )
    }
}
