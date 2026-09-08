package com.karyo.inventory.api.vo

enum class PickingType(val code: Int) {
    PICK(1),
    COMPLETE(2);

    companion object {
        fun fromCode(code: Int) = entries.first { it.code == code }
    }
}
