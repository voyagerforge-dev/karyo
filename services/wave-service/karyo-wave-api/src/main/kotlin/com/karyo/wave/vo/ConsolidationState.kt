package com.karyo.wave.vo

enum class ConsolidationState(val code: Int) {
    PENDING(100), IN_PROGRESS(200), READY(400), SHIPPED(700);

    companion object {
        fun fromCode(code: Int): ConsolidationState = entries.first { it.code == code }
    }
}
