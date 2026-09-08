package com.karyo.layout.event

data class LocationLockChangedEvent(
    val locationId: Long,
    val locationName: String,
    val oldLockType: Int,
    val newLockType: Int,
    val clientId: Long,
)
