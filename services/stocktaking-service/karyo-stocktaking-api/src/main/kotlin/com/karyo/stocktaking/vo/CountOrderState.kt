package com.karyo.stocktaking.vo

enum class CountOrderState(val code: Int) {
    GENERATED(50), COUNTED(500), FINISHED(700), CANCELLED(800);

    fun canAdvanceTo(target: CountOrderState): Boolean = when {
        target == this -> false
        target == CANCELLED -> code < FINISHED.code
        else -> target.code > code
    }

    companion object {
        fun fromCode(code: Int): CountOrderState =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown CountOrderState code: $code")
    }
}
