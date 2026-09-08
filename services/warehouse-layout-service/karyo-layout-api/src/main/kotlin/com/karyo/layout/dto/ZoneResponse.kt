package com.karyo.layout.dto

data class ZoneResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val overflowZoneId: Long?,
    val created: String,
    val modified: String,
)
