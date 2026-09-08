package com.karyo.layout.dto

data class StorageAreaResponse(
    val id: Long,
    val name: String,
    val clusterIds: List<Long>,
    val created: String,
    val modified: String,
    /** PT15: true when every location in this area is a transfer-staging waypoint. */
    val transferStaging: Boolean = false,
)
