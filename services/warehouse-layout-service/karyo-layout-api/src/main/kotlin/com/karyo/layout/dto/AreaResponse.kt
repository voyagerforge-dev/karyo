package com.karyo.layout.dto

data class AreaResponse(
    val id: Long,
    val name: String,
    val usages: List<String>,
    val created: String,
    val modified: String,
)
