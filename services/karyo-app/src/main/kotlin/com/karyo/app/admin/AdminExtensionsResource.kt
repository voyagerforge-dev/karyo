package com.karyo.app.admin

import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.spi.BeanManager
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * One row per curated SPI interface: [fqn] to look up via [Class.forName], plus the owning
 * [module] name (matches the `services/` folder naming used by the static frontend catalog,
 * `frontend/web/src/pages/admin/spi-catalog.ts`).
 */
private data class SpiSeamRef(val fqn: String, val module: String)

/** One resolved row in the live SPI registry response. */
data class ExtensionInfo(
    val spiInterface: String,
    val spiFqn: String,
    val module: String,
    val implementations: List<String>,
    val implementationCount: Int,
)

/**
 * Live SPI-registry endpoint (Phase B14) — the runtime counterpart of the static
 * `SPI_CATALOG` in `frontend/web/src/pages/admin/spi-catalog.ts`.
 *
 * `karyo-app` depends on the domain `-core` modules via non-transitive `implementation`,
 * so it cannot import the SPI interface types at compile time. Every module's classes are
 * present on the runtime classpath, though, so this resource resolves each curated
 * fully-qualified interface name via [Class.forName] and asks the CDI [BeanManager] which
 * beans actually satisfy it. An interface that fails to resolve (module not on the
 * classpath) is silently dropped -- honest, not fabricated. An interface that resolves but
 * has zero loaded beans is still reported, with an empty `implementations` list, because a
 * declared seam with no active implementation is itself useful information.
 */
@Path("/api/v1/admin/extensions")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class AdminExtensionsResource {

    @Inject
    lateinit var beanManager: BeanManager

    @GET
    @RolesAllowed("ADMIN")
    fun listExtensions(): List<ExtensionInfo> =
        CURATED_SPI_SEAMS
            .mapNotNull { seam -> resolve(seam) }
            .sortedWith(compareBy({ it.module }, { it.spiInterface }))

    private fun resolve(seam: SpiSeamRef): ExtensionInfo? {
        val spiClass = runCatching { Class.forName(seam.fqn) }.getOrNull() ?: return null
        val implementations = beanManager.getBeans(spiClass)
            .map { it.beanClass.simpleName }
            .distinct()
            .sorted()
        return ExtensionInfo(
            spiInterface = spiClass.simpleName,
            spiFqn = seam.fqn,
            module = seam.module,
            implementations = implementations,
            implementationCount = implementations.size,
        )
    }

    companion object {
        // Curated seam list. Every FQN here was verified against the source (grep for
        // "interface <Name>" under services/<module>/<sub-module>/src/main/kotlin) before
        // being added -- nothing on this list is guessed. Packages vary per module (some are
        // ".spi", some ".api.spi"); the exact package is preserved from source, not normalized.
        private val CURATED_SPI_SEAMS = listOf(
            // product
            SpiSeamRef("com.karyo.product.spi.ProductLookup", "karyo-product"),
            SpiSeamRef("com.karyo.product.spi.SubstitutionLookup", "karyo-product"),
            SpiSeamRef("com.karyo.product.spi.BarcodeResolver", "karyo-product"),
            // inventory (note: package is api.spi, not spi)
            SpiSeamRef("com.karyo.inventory.api.spi.StockUnitLookup", "karyo-inventory"),
            SpiSeamRef("com.karyo.inventory.api.spi.StockSelectionFilter", "karyo-inventory"),
            SpiSeamRef("com.karyo.inventory.api.spi.JournalEnricher", "karyo-inventory"),
            SpiSeamRef("com.karyo.inventory.api.spi.ReplenishmentSourceSelector", "karyo-inventory"),
            SpiSeamRef("com.karyo.inventory.api.spi.StockCountingPort", "karyo-inventory"),
            SpiSeamRef("com.karyo.inventory.api.spi.StockPicker", "karyo-inventory"),
            SpiSeamRef("com.karyo.inventory.api.spi.StockReceiver", "karyo-inventory"),
            SpiSeamRef("com.karyo.inventory.api.spi.StockReserver", "karyo-inventory"),
            SpiSeamRef("com.karyo.inventory.api.spi.UnitLoadLookup", "karyo-inventory"),
            SpiSeamRef("com.karyo.inventory.api.spi.UnitLoadMover", "karyo-inventory"),
            SpiSeamRef("com.karyo.inventory.api.spi.StockSummaryLookup", "karyo-inventory"),
            // fulfillment
            SpiSeamRef("com.karyo.fulfillment.spi.CarrierAdapter", "karyo-fulfillment"),
            SpiSeamRef("com.karyo.fulfillment.spi.ShortfallStrategy", "karyo-fulfillment"),
            SpiSeamRef("com.karyo.fulfillment.spi.PickDifferenceStrategy", "karyo-fulfillment"),
            SpiSeamRef("com.karyo.fulfillment.spi.PackoutStrategy", "karyo-fulfillment"),
            SpiSeamRef("com.karyo.fulfillment.spi.PickOrderGroupingStrategy", "karyo-fulfillment"),
            SpiSeamRef("com.karyo.fulfillment.spi.DocumentAvailabilityStrategy", "karyo-fulfillment"),
            SpiSeamRef("com.karyo.fulfillment.spi.ShipmentLookup", "karyo-fulfillment"),
            // orders
            SpiSeamRef("com.karyo.orders.spi.DeliveryOrderLookup", "karyo-orders"),
            SpiSeamRef("com.karyo.orders.spi.OrderStrategyResolver", "karyo-orders"),
            SpiSeamRef("com.karyo.orders.spi.OrderProgressionPort", "karyo-orders"),
            SpiSeamRef("com.karyo.orders.spi.GoodsReceiptLookup", "karyo-orders"),
            SpiSeamRef("com.karyo.orders.spi.OrderReleaseValidator", "karyo-orders"),
            SpiSeamRef("com.karyo.orders.spi.OrderStrategyLookup", "karyo-orders"),
            // layout
            SpiSeamRef("com.karyo.layout.spi.PutawayLocationStrategy", "karyo-layout"),
            SpiSeamRef("com.karyo.layout.spi.LocationFinder", "karyo-layout"),
            SpiSeamRef("com.karyo.layout.spi.LocationLockPort", "karyo-layout"),
            SpiSeamRef("com.karyo.layout.spi.FixAssignmentLookup", "karyo-layout"),
            SpiSeamRef("com.karyo.layout.spi.LocationFilter", "karyo-layout"),
            SpiSeamRef("com.karyo.layout.spi.StagingLocationLookup", "karyo-layout"),
            SpiSeamRef("com.karyo.layout.spi.LocationAreaUsageLookup", "karyo-layout"),
            SpiSeamRef("com.karyo.layout.spi.ItemDataAreaLookup", "karyo-layout"),
            // v1.7 paid engines
            SpiSeamRef("com.karyo.monitors.spi.Detector", "karyo-monitors"),
            SpiSeamRef("com.karyo.monitors.spi.AlertDeliveryChannel", "karyo-monitors"),
            SpiSeamRef("com.karyo.forecasting.spi.ForecastModel", "karyo-forecasting"),
            SpiSeamRef("com.karyo.slotting.spi.SlottingStrategy", "karyo-slotting"),
            SpiSeamRef("com.karyo.simulation.spi.ReorderPolicySimulator", "karyo-simulation"),
            // replenishment / stocktaking / tasks
            SpiSeamRef("com.karyo.replenishment.spi.ReplenishmentStrategy", "karyo-replenishment"),
            SpiSeamRef("com.karyo.stocktaking.spi.CountScopeStrategy", "karyo-stocktaking"),
            SpiSeamRef("com.karyo.tasks.spi.TransportOrderPort", "karyo-tasks"),
            // sequence
            SpiSeamRef("com.karyo.sequence.spi.SequenceNumberGenerator", "karyo-sequence"),
            // work
            SpiSeamRef("com.karyo.work.spi.WorkProvider", "karyo-work"),
            SpiSeamRef("com.karyo.work.spi.WorkDispatchStrategy", "karyo-work"),
            SpiSeamRef("com.karyo.work.spi.WorkEligibilityResolver", "karyo-work"),
            // webhooks
            SpiSeamRef("com.karyo.webhooks.spi.WebhookSigner", "karyo-webhooks"),
            SpiSeamRef("com.karyo.webhooks.spi.DeliveryRetryPolicy", "karyo-webhooks"),
        )
    }
}
