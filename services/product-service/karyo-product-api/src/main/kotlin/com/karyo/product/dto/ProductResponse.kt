package com.karyo.product.dto

import java.math.BigDecimal

data class ProductResponse(
    val id: Long,
    val number: String,
    val name: String,
    val description: String?,
    val state: Int,
    val itemUnit: ItemUnitResponse,
    val scale: Int,
    val weight: BigDecimal?,
    val height: BigDecimal?,
    val width: BigDecimal?,
    val depth: BigDecimal?,
    val volume: BigDecimal?,
    val lotMandatory: Boolean,
    val bestBeforeMandatory: Boolean,
    val shelflife: Int?,
    val serialNoRecordType: String,
    val defaultUnitLoadTypeId: Long?,
    val defaultStorageStrategyId: Long?,
    val defaultPackagingUnitId: Long? = null,
    val zoneId: Long?,
    val tradeGroup: String?,
    val imageUrl: String?,
    val numbers: List<ItemDataNumberResponse>,
    val packagingUnits: List<PackagingUnitResponse>,
    val created: String,
    val modified: String,
)
