package com.karyo.inventory.domain.model

import com.karyo.common.domain.TenantEntity
import com.karyo.inventory.api.vo.JournalRecordType
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "inventory_journals")
class InventoryJournal : TenantEntity() {
    @Column(name = "from_unit_load", updatable = false, length = 255)
    var fromUnitLoad: String? = null

    @Column(name = "to_unit_load", updatable = false, length = 255)
    var toUnitLoad: String? = null

    @Column(name = "from_storage_location", updatable = false, length = 255)
    var fromStorageLocation: String? = null

    @Column(name = "to_storage_location", updatable = false, length = 255)
    var toStorageLocation: String? = null

    @Column(name = "activity_code", updatable = false, length = 50)
    var activityCode: String? = null

    @Column(name = "product_number", updatable = false, length = 100)
    var productNumber: String? = null

    @Column(name = "product_name", updatable = false, length = 255)
    var productName: String? = null

    @Column(name = "lot_number", updatable = false, length = 255)
    var lotNumber: String? = null

    @Column(name = "serial_number", updatable = false, length = 255)
    var serialNumber: String? = null

    @Column(name = "record_type", nullable = false, updatable = false)
    var recordType: Int = JournalRecordType.CREATED.code

    @Column(precision = 17, scale = 4, updatable = false)
    var amount: BigDecimal? = null

    @Column(name = "stock_unit_amount", precision = 17, scale = 4, updatable = false)
    var stockUnitAmount: BigDecimal? = null

    @Column(name = "operator_name", updatable = false, length = 100)
    var operatorName: String? = null

    @Column(name = "correlation_id", updatable = false, length = 100)
    var correlationId: String? = null

    @Column(name = "stock_unit_id", updatable = false)
    var stockUnitId: Long? = null

    @Column(name = "item_data_id", updatable = false)
    var itemDataId: Long? = null

    /** Source IP of an auth event (SC19, record types 10-12); null on stock-movement rows. */
    @Column(name = "ip_address", updatable = false, length = 45)
    var ipAddress: String? = null
}
