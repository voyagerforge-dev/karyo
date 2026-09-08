package com.karyo.ai.tools

import com.karyo.inventory.service.StockService
import com.karyo.product.service.ProductService
import com.karyo.reporting.service.KpiDashboardService
import com.karyo.reporting.service.KpiRange
import com.karyo.reporting.service.OccupancyService
import com.karyo.security.TenantContext
import dev.langchain4j.agent.tool.Tool
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Read-only @Tool beans for the warehouse copilot (Task 4).
 *
 * All methods return human-readable strings; the LLM decides how to present them.
 * Tenant scoping is applied via [TenantContext] injected at request scope.
 *
 * Actual KpiRange enum constants (com.karyo.reporting.service):
 *   D7, D30, D90, YTD  — NOT R7D/R30D/R90D/RYTD as the brief assumed.
 * Use KpiRange.from(code) companion (parses by .code string: "7D", "30D", etc.)
 * rather than valueOf(), because the enum name ≠ the user-facing code string.
 *
 * OccupancyResponse.zones (not .zoned) is the list of named zones.
 * OccupancyZone.pct is a ratio 0.0–1.0; multiply ×100 for display.
 */
@ApplicationScoped
class WarehouseReadTools(
    private val products: ProductService,
    private val stock: StockService,
    private val kpis: KpiDashboardService,
    private val occupancy: OccupancyService,
    private val tenant: TenantContext,
) {

    @Tool("Search products by name or number. Returns matching SKUs with their product number, name and id.")
    fun searchProducts(query: String): String {
        val q = query.trim().lowercase()
        val hits = products.listProducts(tenant.clientId)
            .filter { it.name.lowercase().contains(q) || it.number.lowercase().contains(q) }
            .take(20)
        if (hits.isEmpty()) return ToolFormat.none("products matching \"$query\"")
        return "number | name | id\n" + hits.joinToString("\n") { ToolFormat.row(it.number, it.name, it.id) }
    }

    @Tool("Get on-hand stock for a product, identified by its exact product number. Shows amount, state and unit load per stock unit.")
    fun getStockForProduct(productNumber: String): String {
        val product = runCatching { products.findByNumber(productNumber, tenant.clientId) }.getOrNull()
            ?: return "No product with number $productNumber."
        val units = stock.findByItemData(product.id, tenant)
        if (units.isEmpty()) return "No stock on hand for ${product.number}."
        return "amount | state | unitLoad\n" + units.joinToString("\n") {
            // unitLoad is a non-null lateinit var on StockUnit
            ToolFormat.row(it.amount, it.state, it.unitLoad.labelId)
        }
    }

    @Tool("Get warehouse KPIs (inventory accuracy, throughput, cycle time, utilization). range is one of 7D, 30D, 90D, YTD.")
    fun getKpis(range: String): String {
        // KpiRange.from() parses by .code ("7D", "30D", "90D", "YTD"); falls back to D30
        val r = KpiRange.from(range.trim().uppercase()) ?: KpiRange.D30
        val resp = kpis.build(tenant.clientId, r, Instant.now())
        return "${resp.rangeLabel}\n" + resp.tiles.joinToString("\n") { "${it.label}: ${it.value}" }
    }

    @Tool("Get current warehouse occupancy grouped by zone (occupied vs total locations).")
    fun getOccupancy(): String {
        val resp = occupancy.build(tenant.clientId)
        // pct is a ratio 0.0–1.0; format as percentage for readability
        val pctFmt = { p: Double -> "%.1f%%".format(p * 100.0) }
        val header = "Overall: ${resp.totals.occupied}/${resp.totals.total} (${pctFmt(resp.totals.pct)})"
        val zones = resp.zones.joinToString("\n") {
            "${it.zoneName}: ${it.occupied}/${it.total} (${pctFmt(it.pct)})"
        }
        val unzoned = resp.unzoned?.let { "\nUnzoned: ${it.occupied}/${it.total} (${pctFmt(it.pct)})" } ?: ""
        return "$header\n$zones$unzoned"
    }
}
