package com.karyo.replenishment.service

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Holds the `karyo.replenishment.auto-scan-enabled` / `karyo.replenishment.scan-interval`
 * knobs (env `KARYO_REPLENISHMENT_AUTO_SCAN` / `KARYO_REPLENISHMENT_SCAN_INTERVAL`) as their
 * own bean -- same pattern as `ReceivingConfig`. `autoScanEnabled` defaults `false`: R11 is an
 * opt-in wrapper around the existing on-demand `ReplenishmentService.scan`, not a behavior
 * change for tenants that never asked for a background sweep.
 *
 * Non-empty defaults on both properties -- the SRCFG00040 boot-trap rule (see AGENTS.md): an
 * injected `@ConfigProperty` must never default to `""`. `scanInterval` is also referenced
 * directly by `@Scheduled(every = "{karyo.replenishment.scan-interval}")` on
 * [ReplenishmentScheduler], so it needs a valid duration string regardless of whether this
 * bean is ever injected -- keeping both defaults non-empty here covers both paths.
 */
@ApplicationScoped
class ReplenishmentScanConfig(
    @ConfigProperty(name = "karyo.replenishment.auto-scan-enabled", defaultValue = "false")
    val autoScanEnabled: Boolean,
    @ConfigProperty(name = "karyo.replenishment.scan-interval", defaultValue = "10m")
    val scanInterval: String,
)
