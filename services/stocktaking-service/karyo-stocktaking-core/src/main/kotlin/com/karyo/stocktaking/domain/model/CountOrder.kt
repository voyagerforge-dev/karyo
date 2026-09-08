package com.karyo.stocktaking.domain.model

import com.karyo.common.domain.TenantEntity
import com.karyo.stocktaking.vo.CountOrderState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "count_orders")
class CountOrder : TenantEntity() {

    @Column(name = "session_id", nullable = false)
    var sessionId: Long = 0

    @Column(name = "order_number", nullable = false, length = 40)
    lateinit var orderNumber: String

    @Column(name = "location_id", nullable = false)
    var locationId: Long = 0

    @Column(name = "location_name", nullable = false, length = 100)
    lateinit var locationName: String

    @Column(nullable = false)
    var state: Int = CountOrderState.GENERATED.code

    @Column(name = "blind_count", nullable = false)
    var blindCount: Boolean = true

    @Column
    var started: Instant? = null

    @Column
    var finished: Instant? = null

    @Column(name = "operator_id", length = 100)
    var operatorId: String? = null

    @Column(name = "started_by", length = 100)
    var startedBy: String? = null
}
