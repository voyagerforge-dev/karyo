package com.karyo.slotting.dto

data class ReSlotSuggestionDto(
    val sku: String,
    val abcClass: String,
    val velocityRank: Int,
    val currentLocation: String,
    val currentOrderIndex: Int,
    val direction: String,
    val reason: String,
    val severity: Double,
)
