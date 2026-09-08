package com.karyo.stocktaking.vo

enum class CountSessionState(val code: Int) {
    OPEN(100), CLOSED(700);
    fun canAdvanceTo(target: CountSessionState): Boolean = target != this && target.code > code
    companion object {
        fun fromCode(code: Int): CountSessionState =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown CountSessionState code: $code")
    }
}
