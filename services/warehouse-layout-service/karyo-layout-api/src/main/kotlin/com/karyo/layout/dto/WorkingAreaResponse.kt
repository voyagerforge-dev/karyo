package com.karyo.layout.dto

data class WorkingAreaResponse(
    val id: Long,
    val name: String,
    val clusterIds: List<Long>,
    val created: String,
    val modified: String,
)
