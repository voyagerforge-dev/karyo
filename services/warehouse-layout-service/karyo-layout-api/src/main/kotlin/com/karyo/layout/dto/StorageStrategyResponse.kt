package com.karyo.layout.dto

data class StorageStrategyResponse(
    val id: Long,
    val name: String,
    val zoneId: Long?,
    val mixItem: Boolean,
    val mixClient: Boolean,
    val nearPickingLocation: Boolean,
    val sorts: String?,
    val onlyClientLocation: Boolean,
    val manualSearch: Boolean,
    val useAreaStrategyDate: Boolean,
    val useItemDataArea: Boolean,
    val areas: List<StrategyAreaResponse>,
    val created: String,
    val modified: String,
)
