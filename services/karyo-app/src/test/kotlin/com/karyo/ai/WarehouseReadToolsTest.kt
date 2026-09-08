package com.karyo.ai

import com.karyo.ai.tools.WarehouseReadTools
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.service.StockService
import com.karyo.product.dto.ProductResponse
import com.karyo.product.service.ProductService
import com.karyo.reporting.api.v1.dto.KpiChart
import com.karyo.reporting.api.v1.dto.KpiDashboardResponse
import com.karyo.reporting.api.v1.dto.KpiTile
import com.karyo.reporting.api.v1.dto.OccupancyResponse
import com.karyo.reporting.api.v1.dto.OccupancyTotals
import com.karyo.reporting.api.v1.dto.OccupancyZone
import com.karyo.reporting.service.KpiDashboardService
import com.karyo.reporting.service.OccupancyService
import com.karyo.security.TenantContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class WarehouseReadToolsTest {

    private val products = mockk<ProductService>()
    private val stock = mockk<StockService>(relaxed = true)
    private val kpis = mockk<KpiDashboardService>(relaxed = true)
    private val occupancy = mockk<OccupancyService>(relaxed = true)
    private val tenant = TenantContext().apply { clientId = 1L }

    private fun tools() = WarehouseReadTools(products, stock, kpis, occupancy, tenant)

    // ── searchProducts ────────────────────────────────────────────────────────

    @Test
    fun `searchProducts returns matching product number and name`() {
        every { products.listProducts(1L) } returns listOf(
            mockProduct(1L, "DEMO-MOUSE", "Wireless Mouse"),
            mockProduct(2L, "DEMO-KBD", "Keyboard"),
        )
        val out = tools().searchProducts("mouse")
        assertTrue(out.contains("DEMO-MOUSE"), "should contain matching SKU number")
        assertTrue(out.contains("Wireless Mouse"), "should contain matching product name")
        assertFalse(out.contains("DEMO-KBD"), "should not contain non-matching product")
    }

    @Test
    fun `searchProducts returns none message when no match`() {
        every { products.listProducts(1L) } returns listOf(
            mockProduct(1L, "DEMO-MOUSE", "Wireless Mouse"),
        )
        val out = tools().searchProducts("xyz-no-match")
        assertTrue(out.contains("No products matching"), "should return ToolFormat.none 'No products matching...' message")
    }

    // ── getStockForProduct ────────────────────────────────────────────────────

    @Test
    fun `getStockForProduct returns amount and label when stock exists`() {
        val product = mockProduct(42L, "DEMO-MOUSE", "Wireless Mouse")
        every { products.findByNumber("DEMO-MOUSE", 1L) } returns product

        val unitLoad = mockk<UnitLoad>(relaxed = true)
        every { unitLoad.labelId } returns "UL-0001"

        val stockUnit = mockk<StockUnit>(relaxed = true)
        every { stockUnit.amount } returns BigDecimal("10.0000")
        every { stockUnit.state } returns 300
        every { stockUnit.unitLoad } returns unitLoad

        every { stock.findByItemData(42L, tenant) } returns listOf(stockUnit)

        val out = tools().getStockForProduct("DEMO-MOUSE")
        assertTrue(out.contains("UL-0001"), "should contain unit load label")
        assertTrue(out.contains("300"), "should contain state code")
    }

    @Test
    fun `getStockForProduct returns not-found message when product missing`() {
        every { products.findByNumber("MISSING", 1L) } throws RuntimeException("not found")
        val out = tools().getStockForProduct("MISSING")
        assertTrue(out.contains("MISSING"), "should mention the product number in error message")
    }

    @Test
    fun `getStockForProduct returns no-stock message when product has no stock units`() {
        val product = mockProduct(99L, "EMPTY-SKU", "Empty Product")
        every { products.findByNumber("EMPTY-SKU", 1L) } returns product
        every { stock.findByItemData(99L, tenant) } returns emptyList()
        val out = tools().getStockForProduct("EMPTY-SKU")
        assertTrue(out.lowercase().contains("no stock"), "should say no stock")
    }

    // ── getKpis ───────────────────────────────────────────────────────────────

    @Test
    fun `getKpis returns rangeLabel and tile labels`() {
        val tile = KpiTile("accuracy", "Inventory accuracy", "98.5%", "+0.2%", "up", emptyList())
        val resp = KpiDashboardResponse("30D", "Last 30 days", listOf(tile), KpiChart(emptyList(), emptyList()))
        every { kpis.build(eq(1L), any(), any()) } returns resp

        val out = tools().getKpis("30D")
        assertTrue(out.contains("Last 30 days"), "should include range label")
        assertTrue(out.contains("Inventory accuracy"), "should include tile label")
        assertTrue(out.contains("98.5%"), "should include tile value")
    }

    @Test
    fun `getKpis falls back to D30 for unknown range codes`() {
        val resp = KpiDashboardResponse("30D", "Last 30 days", emptyList(), KpiChart(emptyList(), emptyList()))
        every { kpis.build(eq(1L), any(), any()) } returns resp
        // should not throw
        val out = tools().getKpis("UNKNOWN")
        assertTrue(out.contains("Last 30 days"))
    }

    // ── getOccupancy ──────────────────────────────────────────────────────────

    @Test
    fun `getOccupancy returns overall totals and zone breakdown`() {
        val zone = OccupancyZone(1L, "Zone A", 3, 5, 0.6, emptyList())
        val totals = OccupancyTotals(3, 5, 0.6)
        val resp = OccupancyResponse(listOf(zone), null, totals)
        every { occupancy.build(1L) } returns resp

        val out = tools().getOccupancy()
        assertTrue(out.contains("Overall"), "should include overall summary")
        assertTrue(out.contains("Zone A"), "should include zone name")
        assertTrue(out.contains("3/5"), "should include occupied/total counts")
        assertTrue(out.contains("60.0%"), "pct ratio must be rendered x100")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun mockProduct(id: Long, number: String, name: String): ProductResponse =
        mockk(relaxed = true) {
            every { this@mockk.id } returns id
            every { this@mockk.number } returns number
            every { this@mockk.name } returns name
        }
}
