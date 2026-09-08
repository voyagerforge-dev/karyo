package com.karyo.product.event

data class ItemDataStateChangedEvent(
    val itemDataId: Long,
    val number: String,
    val oldState: Int,
    val newState: Int,
    val clientId: Long,
)
