package com.karyo.layout.dto

data class LocationClusterResponse(
    val id: Long,
    val name: String,
    val parentClusterId: Long?,
    val created: String,
    val modified: String,
)
