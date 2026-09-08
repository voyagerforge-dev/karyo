package com.karyo.layout.dto

/** One entry of a [StorageStrategyResponse]'s ordered `areas` list. `id` is the storage
 *  area's id (not the join row's), for direct use with `StorageAreaService.clustersForAreas`. */
data class StrategyAreaResponse(
    val id: Long,
    val name: String,
    val orderIndex: Int,
)
