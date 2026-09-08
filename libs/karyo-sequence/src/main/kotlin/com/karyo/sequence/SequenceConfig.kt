package com.karyo.sequence

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Holds the `karyo.sequence.generator` knob (env `KARYO_SEQUENCE_GENERATOR`) as its own bean —
 * same pattern as `WorkDispatchConfig`/`ForecastConfig`/etc — so [SequenceNumberService] stays
 * constructible in plain unit tests without CDI.
 *
 * Non-empty default `"TIMESTAMP_RANDOM"` — the SRCFG00040 boot-trap rule (see AGENTS.md): an
 * injected `@ConfigProperty` string must never default to `""`.
 */
@ApplicationScoped
class SequenceConfig(
    @ConfigProperty(name = "karyo.sequence.generator", defaultValue = "TIMESTAMP_RANDOM")
    val generatorName: String,
)
