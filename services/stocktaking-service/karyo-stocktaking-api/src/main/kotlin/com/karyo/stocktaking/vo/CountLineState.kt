package com.karyo.stocktaking.vo

enum class CountLineState(val code: Int) {
    PLANNED(50), COUNTED(500), FINISHED(700), CANCELLED(800);

    fun canAdvanceTo(target: CountLineState): Boolean = when {
        target == this -> false
        target == CANCELLED -> code < FINISHED.code
        else -> target.code > code
    }

    companion object {
        fun fromCode(code: Int): CountLineState =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown CountLineState code: $code")
    }
}
