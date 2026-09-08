package com.karyo.auth.config

import jakarta.enterprise.context.ApplicationScoped

enum class PropertyType { STRING, BOOLEAN, INTEGER }

data class PropertyDefinition(
    val key: String,
    val type: PropertyType,
    val group: String,
    val description: String,
    val defaultValue: String?,
    /**
     * Write-only key: the effective view masks any present value (for ALL principals, SYS
     * included) so credentials stored instance-wide never leak to a goods-owner admin's GET.
     * The resolution ladder itself is unmasked — in-process consumers read the real value.
     */
    val secret: Boolean = false,
    /**
     * When false, only an ops (SYS) principal may PUT/DELETE rows for this key — a
     * goods-owner admin must not be able to loosen an operator-set hard stop by writing
     * its own client row above the client-0/env fallback. Reads are unaffected.
     */
    val ownerWritable: Boolean = true,
)

/**
 * The code-owned system-property catalog (SC16), modelled on `MonitorCatalog`: users edit
 * per-client VALUES at runtime, but the set of known keys, their types, groups and defaults
 * live here in code. Non-catalog keys may still be stored (extension/custom knobs) — they
 * simply get no type validation and no metadata in the effective view.
 *
 * Grows as existing `@ConfigProperty` knobs migrate onto the runtime store.
 */
@ApplicationScoped
class SystemPropertyCatalog {
    private val defs = listOf(
        PropertyDefinition(
            "karyo.receiving.allow-over-receipt", PropertyType.BOOLEAN, "Receiving",
            "Allow receiving more than the notified/advised amount", "true",
            ownerWritable = false,
        ),
        PropertyDefinition(
            "karyo.alerts.email.recipients", PropertyType.STRING, "Alerts",
            "Comma-separated email recipients for monitor alerts (empty = channel refuses delivery)", null,
        ),
        PropertyDefinition(
            "karyo.alerts.slack.webhook-url", PropertyType.STRING, "Alerts",
            "Slack incoming-webhook URL for monitor alerts (ops-controlled: the scheduler POSTs " +
                "server-side to this URL, so URL authority stays with the operator)", null,
            secret = true,
            ownerWritable = false,
        ),
        // R15 (replenishment sprint Task 4): whether a fix-face replenishment scan may pull a
        // source unit load off a PICKING-usage location. Public behavioral contract:
        // docs/functional/replenishment.md#2-source-selection. The default is deliberately
        // `false`: a picking-area candidate is a live pick
        // face's own stock, not spare reserve, so the safer default is "never" until an operator
        // opts in per instance/client. A warehouse-ops preference with no security surface (unlike
        // the slack webhook key above), so goods-owners may tune it themselves.
        PropertyDefinition(
            "karyo.replenishment.from-picking", PropertyType.BOOLEAN, "Replenishment",
            "Allow fix-face replenishment scans to pull a reserve unit-load off a PICKING-usage location " +
                "(myWMS KEY_REPLENISH_FROM_PICKING analogue; default false)", "false",
        ),
        // Outbound-completion sprint Task 4: knobs for the paid CartonizationPackout strategy
        // (`karyo-license` key "cartonization"). Both are owner-writable -- box sizing is a
        // warehouse-ops preference with no security surface, same rationale as the
        // replenishment `from-picking` knob above.
        PropertyDefinition(
            "karyo.packing.cartonization.max-lines-per-box", PropertyType.INTEGER, "Packing",
            "CartonizationPackout: maximum distinct pick lines per box (default 1)", "1",
        ),
        PropertyDefinition(
            "karyo.packing.cartonization.max-amount-per-box", PropertyType.INTEGER, "Packing",
            "CartonizationPackout: maximum amount of a single line per box, splitting it across " +
                "boxes when it forces (default 0 = unlimited)", "0",
        ),
        // Outbound-completion sprint Task 8 (S6): opt-in unit-load label rename on dispatch.
        // Append "-" + the unit load's own id to labelId (idempotent via endsWith) to free the
        // original barcode for reuse once the container
        // ships out. A warehouse-ops preference with no security surface, same rationale as the
        // replenishment `from-picking` knob above.
        PropertyDefinition(
            "karyo.shipping.rename-unit-load", PropertyType.BOOLEAN, "Shipping",
            "On dispatch, append \"-\" + the unit load's own id to its labelId, freeing the " +
                "original barcode for reuse (idempotent; default false)", "false",
        ),
        // Row 18 (stock-and-orders sprint): the DELETABLE reaper's per-client retention window.
        // ownerWritable = false -- retention is an audit-horizon policy, not a warehouse-ops
        // preference (unlike the replenishment/packing/shipping keys above): a goods owner must
        // not be able to shorten its own audit trail by writing a client row above the
        // client-0/env fallback. Reads (and the reaper itself) are unaffected.
        PropertyDefinition(
            "karyo.inventory.purge.retention-days", PropertyType.INTEGER, "Inventory",
            "Days a DELETABLE stock unit must sit untouched before the reaper hard-deletes it " +
                "(default 30; the reaper itself is off by default, see KARYO_INVENTORY_PURGE)", "30",
            ownerWritable = false,
        ),
        // Cross-docking sprint (Task 5): knobs for the paid CrossDockInterceptor (`karyo-license`
        // key "advanced-fulfillment"). The two toggles are owner-writable, same rationale as the
        // replenishment `from-picking` knob above -- a warehouse-ops preference with no security
        // surface, both default false (cross-docking is opt-in per rung). The window/expiry-action
        // pair is NOT owner-writable, same rationale as `karyo.inventory.purge.retention-days`
        // above: how long staged stock is allowed to sit and what happens when it doesn't move is
        // an operational-safety policy, not a warehouse-ops preference a goods owner should be
        // able to loosen on its own.
        PropertyDefinition(
            "karyo.crossdock.pre-distributed", PropertyType.BOOLEAN, "Cross-docking",
            "Enable the pre-distributed cross-dock rung (ASN line already targeted at a " +
                "delivery order; default false)", "false",
        ),
        PropertyDefinition(
            "karyo.crossdock.opportunistic", PropertyType.BOOLEAN, "Cross-docking",
            "Enable the opportunistic cross-dock rung (CrossDockingMatcher SPI against open " +
                "delivery-order lines; default false)", "false",
        ),
        PropertyDefinition(
            "karyo.crossdock.staging-window", PropertyType.INTEGER, "Cross-docking",
            "Hours a matched unit load may sit on its staging location before the expiry sweep " +
                "acts on it (default 4)", "4",
            ownerWritable = false,
        ),
        PropertyDefinition(
            "karyo.crossdock.expiry-action", PropertyType.STRING, "Cross-docking",
            "Action the expiry sweep takes on a staging deadline miss (default AUTO_PUTAWAY)", "AUTO_PUTAWAY",
            ownerWritable = false,
        ),
        // Wave bulk fulfillment sprint (Task 9): per-tenant kill-switch for WaveScheduler's
        // auto-release loop (`karyo-license` key "advanced-fulfillment"). Owner-writable, same
        // rationale as the crossdock rung toggles above -- a warehouse-ops preference with no
        // security surface, default false (a strategy's own `waveAutoRelease` flag must ALSO be
        // set before this scheduler ever mints a wave for that strategy).
        PropertyDefinition(
            "karyo.wave.auto-release", PropertyType.BOOLEAN, "Waves",
            "Enable scheduled wave auto-release for strategies opting in via waveAutoRelease " +
                "(default false; the per-strategy flag must also be set)", "false",
        ),

        // Order streaming (B3): per-tenant kill-switch for StreamingReleaseScheduler (license key
        // "advanced-fulfillment"). Owner-writable, default false; a strategy's releaseMode must
        // ALSO be STREAM (or an order must carry releaseModeOverride = STREAM) before anything streams.
        PropertyDefinition(
            "karyo.streaming.enabled", PropertyType.BOOLEAN, "Streaming",
            "Enable the order streaming scheduler for this tenant (default false; strategies opt in via releaseMode = STREAM)", "false",
        ),
    )

    fun all(): List<PropertyDefinition> = defs
    fun byKey(key: String): PropertyDefinition? = defs.find { it.key == key }
}
