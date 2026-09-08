package com.karyo.inventory.api.vo

enum class StockState(val code: Int) {
    UNDEFINED(0),
    INCOMING(100),
    ON_STOCK(300),
    PICKED(600),
    PACKED(650),
    SHIPPED(680),
    DELETABLE(1000);

    companion object {
        fun fromCode(code: Int) = entries.first { it.code == code }
    }
}
