package com.karyo.inventory.api.dto

import java.math.BigDecimal
import java.time.Instant

data class JournalEntryResponse(
    val id: Long,
    val recordType: Int,
    val recordTypeName: String,
    val productNumber: String?,
    val productName: String?,
    val amount: BigDecimal?,
    val stockUnitAmount: BigDecimal?,
    val fromUnitLoad: String?,
    val toUnitLoad: String?,
    val fromStorageLocation: String?,
    val toStorageLocation: String?,
    val lotNumber: String?,
    val activityCode: String?,
    val operatorName: String?,
    val correlationId: String?,
    val created: Instant,
    /** Source IP of an auth event (record types 10-12, SC19); null on stock-movement rows. */
    val ipAddress: String? = null,
)
