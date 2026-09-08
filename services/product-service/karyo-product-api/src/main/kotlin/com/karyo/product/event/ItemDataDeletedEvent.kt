package com.karyo.product.event

data class ItemDataDeletedEvent(
    val itemDataId: Long,
    val number: String,
    val name: String,
    val clientId: Long,
)
