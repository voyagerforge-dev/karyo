package com.karyo.product.event

data class ItemDataCreatedEvent(
    val itemDataId: Long,
    val number: String,
    val name: String,
    val clientId: Long,
)
