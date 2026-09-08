package com.karyo.product.dto

import jakarta.validation.constraints.*
import java.math.BigDecimal

data class CreateProductRequest(
    @field:NotBlank @field:Size(max = 100) val number: String,
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:Size(max = 2000) val description: String? = null,
    @field:NotNull val itemUnitId: Long,
    @field:Min(0) val scale: Int = 0,
    val weight: BigDecimal? = null,
    val height: BigDecimal? = null,
    val width: BigDecimal? = null,
    val depth: BigDecimal? = null,
    val lotMandatory: Boolean = false,
    val bestBeforeMandatory: Boolean = false,
    @field:Min(0) val shelflife: Int? = null,
    val serialNoRecordType: String = "NO_RECORD",
    val defaultUnitLoadTypeId: Long? = null,
    val defaultStorageStrategyId: Long? = null,
    val zoneId: Long? = null,
    @field:Size(max = 100) val tradeGroup: String? = null,
)
