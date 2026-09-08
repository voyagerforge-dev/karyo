package com.karyo.work.service

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Holds the `karyo.work.dispatch-strategy` knob (env `KARYO_WORK_DISPATCH_STRATEGY`) as its own
 * bean — same pattern as `ForecastConfig`/`SlottingConfig`/`SimulationConfig` — rather than an
 * `@ConfigProperty` param directly on [WorkDispatchService]'s constructor, so
 * [WorkDispatchService] stays constructible with a Kotlin default in plain unit tests
 * (`WorkDispatchServiceTest`) that build it by hand without CDI.
 *
 * Non-empty default `"STRICT_PRIORITY"` — the SRCFG00040 boot-trap rule (see AGENTS.md): an
 * injected `@ConfigProperty` string must never default to `""`.
 */
@ApplicationScoped
class WorkDispatchConfig(
    @ConfigProperty(name = "karyo.work.dispatch-strategy", defaultValue = "STRICT_PRIORITY")
    val dispatchStrategyName: String,
)
