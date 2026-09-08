package com.karyo.wave.vo

enum class WaveState(val code: Int) {
    PLANNED(100), RELEASED(300), PICKING(400), CONSOLIDATING(500), COMPLETED(700), CANCELLED(900);

    fun canAdvanceTo(target: WaveState): Boolean = when {
        this == target -> false
        target == CANCELLED -> code < COMPLETED.code
        else -> target.code > code && this != CANCELLED && this != COMPLETED
    }

    companion object {
        fun fromCode(code: Int): WaveState = entries.first { it.code == code }
    }
}
