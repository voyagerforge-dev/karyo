package com.karyo.wave.vo

enum class WavePickMode {
    HYBRID, COMPLETE_ONLY, PICK_ONLY,
    /** Bulk Allocation: every slice to the batch path, SKU-aggregated pick units, sort station mandatory. */
    BULK,
}
