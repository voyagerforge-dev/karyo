package com.karyo.orders.config

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Holds the `karyo.receiving.allow-over-receipt` knob (env
 * `KARYO_RECEIVING_ALLOW_OVER_RECEIPT`) as its own bean -- same pattern as
 * `WorkDispatchConfig`/`ForecastConfig`. Kept OUT of `GoodsReceiptService`'s
 * constructor, which is AT the detekt `LongParameterList` limit (10 params);
 * `com.karyo.orders.service.OverReceiptGuard` injects this instead, and
 * `AsnService` (which had constructor headroom, and already owns AsnLine amount
 * bookkeeping) injects `OverReceiptGuard` -- `GoodsReceiptService`'s constructor
 * is untouched.
 *
 * Non-empty default `"true"` -- the SRCFG00040 boot-trap rule (see AGENTS.md): an
 * injected `@ConfigProperty` must never default to `""`. `true` preserves the
 * pre-existing per-request-only behavior exactly. `false` makes this the STRICTER
 * gate: a per-request `allowOverReceipt=true` can no longer override it.
 *
 * SC16: this env-driven value is now the FALLBACK DEFAULT, not the authority --
 * `OverReceiptGuard` resolves the knob DB-first through the auth module's
 * `RuntimePropertyLookup` (a stored `karyo.receiving.allow-over-receipt` row for the
 * receiving client, or the client-0 instance row, wins over this value).
 */
@ApplicationScoped
class ReceivingConfig(
    @ConfigProperty(name = "karyo.receiving.allow-over-receipt", defaultValue = "true")
    val allowOverReceipt: Boolean,
)
