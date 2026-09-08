package com.karyo.reporting.dto

import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class CreateReportDefinitionRequest(
    @field:NotBlank(message = "Name is required")
    val name: String,
    @field:NotBlank(message = "Report type is required")
    val reportType: String,
    val params: String = "{}",
    val owner: String? = null,
)

data class ReportDefinitionResponse(
    val id: Long,
    val name: String,
    val reportType: String,
    val params: String,
    val owner: String?,
    val created: Instant,
)
