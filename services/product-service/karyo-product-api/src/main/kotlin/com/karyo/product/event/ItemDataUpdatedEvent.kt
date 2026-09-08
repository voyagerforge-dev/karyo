package com.karyo.product.event

data class ItemDataUpdatedEvent(
    val itemDataId: Long,
    val number: String,
    val changedFields: List<String>,
)
