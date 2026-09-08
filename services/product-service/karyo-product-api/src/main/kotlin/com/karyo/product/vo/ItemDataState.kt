package com.karyo.product.vo

enum class ItemDataState(val code: Int) {
    ACTIVE(100),
    INACTIVE(700);

    companion object {
        fun fromCode(code: Int): ItemDataState =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown ItemDataState code: $code")
    }
}
