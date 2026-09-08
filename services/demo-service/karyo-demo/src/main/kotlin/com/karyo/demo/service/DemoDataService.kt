package com.karyo.demo.service

import com.karyo.demo.config.DemoConfig
import com.karyo.demo.dto.DemoSeedSummary
import com.karyo.demo.gen.CatalogGenerator
import com.karyo.demo.gen.CycleCountGenerator
import com.karyo.demo.gen.HistoryGenerator
import com.karyo.demo.gen.InventoryGenerator
import com.karyo.demo.gen.MonitorSeedGenerator
import com.karyo.demo.gen.ReportDefinitionSeedGenerator
import io.quarkus.cache.CacheInvalidateAll
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional

/**
 * Task 7 capstone: orchestrates the five demo generators in FK order and assembles the
 * REST-facing [DemoSeedSummary]; also owns [reset], the single native `TRUNCATE` that clears
 * every operational/catalog table the generators touch so a demo instance can be reseeded from
 * a clean slate.
 *
 * [seed] itself is NOT idempotent (see [InventoryGenerator]'s KDoc) — [reset]ting first is what
 * makes a re-seed safe, and that reset is deliberately invoked by
 * [com.karyo.demo.api.v1.DemoResource.seed] as its own bean call rather than folded in here: an
 * intra-bean `this.reset()` self-invocation would bypass the CDI interceptor chain and silently
 * skip every `@CacheInvalidateAll` on [reset] (see that method's KDoc), resurrecting the
 * 2026-07-21 stale-Caffeine-id bug. Task 9 (defect-burndown).
 *
 * `clientId` is hardcoded to 1 — the demo engine only ever seeds a single-tenant showcase
 * instance (see [com.karyo.demo.api.v1.DemoResource] KDoc: gated off by default, ADMIN or
 * MANAGER).
 */
@ApplicationScoped
class DemoDataService(
    private val catalogGenerator: CatalogGenerator,
    private val inventoryGenerator: InventoryGenerator,
    private val historyGenerator: HistoryGenerator,
    private val cycleCountGenerator: CycleCountGenerator,
    private val monitorSeedGenerator: MonitorSeedGenerator,
    private val reportDefinitionSeedGenerator: ReportDefinitionSeedGenerator,
    private val em: EntityManager,
) {
    @Transactional
    fun seed(): DemoSeedSummary {
        val catalog = catalogGenerator.generate(CLIENT_ID)
        val inventory = inventoryGenerator.generate(CLIENT_ID, catalog)
        val history = historyGenerator.generate(CLIENT_ID, catalog, inventory)
        val countLines = cycleCountGenerator.generate(CLIENT_ID, catalog, inventory)
        val tripped = monitorSeedGenerator.generate(CLIENT_ID, catalog, inventory)
        reportDefinitionSeedGenerator.generate(CLIENT_ID)

        return DemoSeedSummary(
            locations = catalog.locations.size,
            skus = catalog.skus.size,
            orders = history.orders,
            picks = history.picks,
            shipments = history.shipments,
            counts = countLines,
            goodsReceipts = history.goodsReceipts,
            transportOrders = history.putawayBacklog,
            alertsTripped = tripped,
        )
    }

    /**
     * Wipes every operational/catalog table the generators populate, in one `TRUNCATE ...
     * RESTART IDENTITY CASCADE` (CASCADE handles FK order so the table list order doesn't
     * matter). Deliberately preserves the Flyway reference-seed rows other tests/generators rely
     * on: `item_units` (V201 -- PCS/KG/L/M/BOX/PAL; fixed 2026-07-21, consolidation sprint Task
     * 3b -- it had been in the truncate list below despite this same comment already promising
     * durable Flyway seeds are preserved, so any spec/generator running after a `reset()` with no
     * prior `demo/seed` call to re-create it via `CatalogGenerator.ensureItemUnit()` would see an
     * empty `/api/v1/item-units` and fail "item units must be seeded"), `unit_load_types` (V105),
     * `order_strategies` (V405), `monitor_config`,
     * `webhook_subscription`/`webhook_delivery`/`webhook_fanout_cursor`, and `flyway_schema_history`
     * — none of those are named below and none holds an FK to a truncated table. `item_data` (and
     * its dependents `item_data_numbers`/`packaging_units`/`item_substitutions`) IS still
     * truncated; excluding `item_units` from the TRUNCATE list is safe regardless, since the FK
     * runs `item_data.item_unit_id -> item_units.id` (removing the referencing rows never
     * requires removing the referenced ones).
     *
     * NOTE: `storage_strategies` is NOT preserved — although it is not named below, it holds an FK
     * to `zones` (which IS truncated), so `TRUNCATE ... CASCADE` transitively clears it. This is
     * acceptable: `storage_strategies` has no Flyway seed (empty by default) and `StrategyResolver`
     * falls back to code defaults, so a reset simply returns strategy config to defaults. Any
     * strategy an admin configured via the Strategies UI on a demo instance is intentionally reset.
     *
     * `storage_areas` (L1, layout V311) IS named below — it's a root config table with no FK
     * of its own (mirrors `zones`/`location_types`/`location_clusters`'s explicit treatment,
     * not `storage_strategies`'s implicit one). `storage_area_clusters` and
     * `storage_strategy_areas` are deliberately NOT named — both are pure join tables whose FKs
     * (to `storage_areas`/`location_clusters`/`storage_strategies` respectively) are all
     * already-truncated tables, so `TRUNCATE ... CASCADE` clears them transitively (same
     * "via cascade" treatment as `storage_strategies` itself). `item_data_areas` IS named below
     * even though it would also cascade transitively via its `storage_area_id` FK — named
     * explicitly per its direct dependency on `item_data` (also truncated), so the relationship
     * survives even if the `storage_area_id` FK is ever relaxed.
     *
     * `working_areas` (layout V316) IS named below (final-review F6 fix) — another root config
     * table with no FK of its own, same treatment as `storage_areas`/`zones`/`location_types`/
     * `location_clusters`. Before this fix it was reachable ONLY via its join table
     * `working_area_clusters`' cascade (through `location_clusters`), so a reset wiped every
     * cluster membership but left the `working_areas` NAME rows behind — an orphan filter that
     * silently matches nothing post-reset. `working_area_clusters` itself is still deliberately
     * NOT named (pure join table, FKs to two now-both-truncated tables, cleared transitively).
     * `inventory_journals` is partitioned (`inventory_journals_default`); TRUNCATE on the parent
     * cascades to partitions.
     *
     * `cross_dock_orders` (crossdock V1300, Task 8) IS named below even though nothing else in
     * this list holds a real FK to it (the module carries only bare Long ids -- house rule, no
     * cross-module FKs) -- without an explicit entry a reset would leave orphaned rows dangling
     * on now-deleted `delivery_order_lines`/`goods_receipt_lines`/`unit_loads`/`stock_units`
     * ids, exactly the same class of gap `working_areas` closed above for a different reason.
     *
     * `RESTART IDENTITY` recycles every truncated table's id sequence back to 1, but the
     * Caffeine L1 read caches in front of `item_data`/`storage_locations`/`zones`/`areas`/
     * `location_types`/`location_clusters`/`storage_strategies` (60M/30M TTL, see
     * `application.yaml`) are keyed on those same ids and are otherwise never told the rows
     * they cached no longer exist. Without eviction, the next reseed hands out a recycled id
     * to a brand-new row while a stale cache entry for that id (from before this reset) keeps
     * serving the OLD row's data on every read until the TTL expires — found live 2026-07-21
     * (consolidation-sprint Task 6) via `scenarios.spec.ts` reading back a product it had just
     * created and getting a different, older product's fields. `@CacheInvalidateAll` clears
     * every affected cache in the same call so a post-reset read is always a genuine miss.
     */
    @Transactional
    @CacheInvalidateAll(cacheName = "products-by-id")
    @CacheInvalidateAll(cacheName = "products-by-number")
    @CacheInvalidateAll(cacheName = "products-by-barcode")
    @CacheInvalidateAll(cacheName = "locations-by-id")
    @CacheInvalidateAll(cacheName = "locations-by-scan-code")
    @CacheInvalidateAll(cacheName = "zones")
    @CacheInvalidateAll(cacheName = "areas")
    @CacheInvalidateAll(cacheName = "location-types")
    @CacheInvalidateAll(cacheName = "clusters")
    @CacheInvalidateAll(cacheName = "storage-strategies")
    @CacheInvalidateAll(cacheName = "storage-areas")
    fun reset() {
        em.createNativeQuery(RESET_SQL).executeUpdate()
    }

    companion object {
        private const val CLIENT_ID = 1L

        @Suppress("MaxLineLength")
        private val RESET_SQL = """
            TRUNCATE TABLE karyo.delivery_orders, karyo.delivery_order_lines, karyo.order_line_reservations,
             karyo.asns, karyo.asn_lines, karyo.goods_receipts, karyo.goods_receipt_lines,
             karyo.pick_orders, karyo.picks, karyo.shipments, karyo.shipping_units, karyo.shipping_unit_lines,
             karyo.transport_orders, karyo.cross_dock_orders, karyo.count_campaigns, karyo.count_sessions, karyo.count_orders, karyo.count_lines,
             karyo.stock_units, karyo.unit_loads, karyo.inventory_journals, karyo.location_reservations,
             karyo.fix_assignments, karyo.storage_locations, karyo.location_clusters, karyo.zones, karyo.areas,
             karyo.location_types, karyo.storage_areas, karyo.working_areas, karyo.item_data, karyo.item_data_numbers, karyo.item_data_areas,
             karyo.packaging_units, karyo.item_substitutions, karyo.inactive_products, karyo.alerts, karyo.outbox_events,
             karyo.work_groups, karyo.work_group_members, karyo.report_definitions, karyo.documents
             RESTART IDENTITY CASCADE
        """.trimIndent()
    }
}
