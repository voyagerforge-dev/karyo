package com.karyo.orders.vo

/**
 * How orders on a strategy get released (order streaming, B3). MANUAL and WAVE behave identically
 * today (spec ruling 3: WAVE documents intent, every existing strategy keeps waving exactly as
 * before); only STREAM changes behavior (the streaming scheduler owns the order). Stored as the
 * `releaseMode` extension property on OrderStrategy and as `delivery_orders.release_mode_override`.
 */
enum class ReleaseMode {
    MANUAL, WAVE, STREAM;

    companion object {
        /** Case-insensitive parse; null/blank/unknown -> null (callers decide the default). */
        fun parseOrNull(raw: String?): ReleaseMode? =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { v -> entries.firstOrNull { it.name.equals(v, ignoreCase = true) } }
    }
}
