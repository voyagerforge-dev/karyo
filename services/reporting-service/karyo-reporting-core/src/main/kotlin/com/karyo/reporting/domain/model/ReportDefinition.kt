package com.karyo.reporting.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * A saved report configuration (Phase B / B19) — name + report type + arbitrary JSONB params,
 * scoped to the tenant. [params] mirrors [com.karyo.orders.domain.model.OrderStrategy]'s
 * `extensionProperties` JSONB-as-raw-String pattern.
 */
@Entity
@Table(name = "report_definitions")
class ReportDefinition : TenantEntity() {

    @Column(nullable = false, length = 200)
    lateinit var name: String

    @Column(name = "report_type", nullable = false, length = 60)
    lateinit var reportType: String

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    var params: String = "{}"

    @Column(length = 100)
    var owner: String? = null
}
