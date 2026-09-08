package com.karyo.inventory.service

import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.security.TenantContext
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.repository.InventoryJournalRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
class JournalService(
    private val repository: InventoryJournalRepository,
) {

    /**
     * Appends one [InventoryJournal] row for a movement of [stockUnit].
     *
     * Attribution splits deliberately across two fields, and they are NOT interchangeable:
     *
     * - `clientId` records **whose goods moved** — taken from the stock unit, which inherits
     *   its owner from its parent unit load (see `StockService.createStock`).
     * - `operatorName` records **who acted** — taken from [tenant], the acting principal.
     *
     * These differ whenever an ops principal handles a goods owner's stock, which is the
     * normal case in a 3PL: operating-company staff physically move every owner's goods and
     * act under `client_id = 0` (the SYS tenant). Sourcing `clientId` from [tenant] would
     * file those rows under SYS, and since `JournalResource.list` scopes an OWNER principal's
     * reads by `clientId`, the goods owner's audit log would silently never show the
     * movement. The journal is the audit trail for the goods, so it follows the goods.
     *
     * [tenant] is therefore still required — it is the source of `operatorName` — but it must
     * not become the source of `clientId`.
     */
    fun record(
        recordType: JournalRecordType,
        stockUnit: StockUnit,
        tenant: TenantContext,
        amount: BigDecimal? = null,
        fromUnitLoad: String? = null,
        toUnitLoad: String? = null,
        fromLocation: String? = null,
        toLocation: String? = null,
        activityCode: String? = null,
        correlationId: String? = null,
    ) {
        val journal = InventoryJournal().apply {
            // Whose goods moved — NOT tenant.clientId (who acted); see the KDoc above.
            clientId = stockUnit.clientId
            this.recordType = recordType.code
            stockUnitId = stockUnit.id
            itemDataId = stockUnit.itemDataId
            productNumber = stockUnit.itemDataNumber
            lotNumber = stockUnit.lotNumber
            serialNumber = stockUnit.serialNumber
            this.amount = amount
            stockUnitAmount = stockUnit.amount
            this.fromUnitLoad = fromUnitLoad
            this.toUnitLoad = toUnitLoad
            fromStorageLocation = fromLocation
            toStorageLocation = toLocation
            this.activityCode = activityCode
            operatorName = tenant.username
            this.correlationId = correlationId
        }
        repository.persist(journal)
    }

    /**
     * Appends one [InventoryJournal] row for a unit load itself going terminal — hard delete
     * ([com.karyo.inventory.service.UnitLoadService.delete]) or the emptied-UL soft flip
     * ([DefaultStockCountingPort.applyCount]). The UL-level counterpart to [record], for the case
     * where there is no surviving [StockUnit] to journal against: a hard-deleted unit load has
     * none, and the soft-flip's last stock unit is already DELETABLE by the time the UL itself
     * terminates.
     *
     * Attribution mirrors [record]: `clientId` is the UL's own owner ([clientId] param — the
     * entity whose slot is being released), `operatorName` is taken from [tenant] (who acted).
     * All product fields (`itemDataId`, `productNumber`, lot/serial) are left null — there is no
     * stock unit backing this row, and those columns are nullable.
     */
    fun recordUnitLoadTrashed(
        clientId: Long,
        labelId: String,
        locationName: String,
        tenant: TenantContext,
        activityCode: String? = null,
        correlationId: String? = null,
    ) {
        val journal = InventoryJournal().apply {
            this.clientId = clientId
            this.recordType = JournalRecordType.DELETED.code
            fromUnitLoad = labelId
            fromStorageLocation = locationName
            this.activityCode = activityCode
            operatorName = tenant.username
            this.correlationId = correlationId
        }
        repository.persist(journal)
    }

    /**
     * Bulk Allocation Sprint C: the symmetric counterpart of [recordUnitLoadTrashed], appended
     * when a unit load tombstoned DELETABLE purely because it went empty is brought back to
     * receive returned goods (`StockPicker.reviveDrainedContainer`). Recorded as
     * [JournalRecordType.CREATED] -- the unit load is in existence again -- and written to the
     * `to*` columns rather than [recordUnitLoadTrashed]'s `from*` ones, since this is an arrival
     * back into the slot, not a departure from it. Stock-unit-less like its counterpart: the
     * stock rows this call also revives are journaled individually by the caller.
     */
    fun recordUnitLoadRevived(
        clientId: Long,
        labelId: String,
        locationName: String,
        tenant: TenantContext,
        activityCode: String? = null,
        correlationId: String? = null,
    ) {
        val journal = InventoryJournal().apply {
            this.clientId = clientId
            this.recordType = JournalRecordType.CREATED.code
            toUnitLoad = labelId
            toStorageLocation = locationName
            this.activityCode = activityCode
            operatorName = tenant.username
            this.correlationId = correlationId
        }
        repository.persist(journal)
    }

    /**
     * Appends one [InventoryJournal] row for a user authentication event (SC19 — LOGIN /
     * LOGOUT / LOGIN_FAILED, sourced from the Keycloak admin Events API by
     * `com.karyo.app.auth.KeycloakEventPoller`). The third stock-unit-less append after
     * [recordUnitLoadTrashed]: all product/location fields stay null.
     *
     * Attribution follows the [record] doctrine — `clientId` = whose data, `operatorName` =
     * who acted. For an auth event the actor and the affected party are the same person, so
     * [clientId] is the *actor's own* `client_id` Keycloak user attribute (0 when unknown,
     * e.g. a failed login for a nonexistent user), and [username] is the Keycloak username.
     * There is no [TenantContext] parameter by design: the poller runs from a scheduler with
     * no request context, so the caller passes both fields explicitly.
     *
     * The row is backdated: `created`/`modified` are set to [occurredAt], the Keycloak event
     * timestamp (BaseEntity timestamps are plain assignable fields, and the journals table's
     * default partition accepts any range). That backdated `created` is also one leg of the
     * poller's dedup identity — see `InventoryJournalRepository.existsAuthEvent`.
     *
     * [correlationId] carries the Keycloak session id (null on some LOGIN_ERROR events),
     * [activityCode] the raw Keycloak event type string.
     */
    /**
     * Row 18: appends the [InventoryJournal] row for a stock unit about to be HARD-DELETED by
     * `StockPurgeService`, written before the physical row removal (see that class's KDoc for
     * why the ordering matters). Runs from the `@Scheduled` reaper -- same no-[TenantContext]
     * reasoning as [recordAuthEvent]: there is no acting principal to read, so `operatorName`
     * is the fixed [SYSTEM_OPERATOR] literal rather than a tenant's username.
     */
    fun recordStockUnitPurged(su: StockUnit, correlationId: String? = null) {
        val journal = InventoryJournal().apply {
            clientId = su.clientId
            recordType = JournalRecordType.DELETED.code
            stockUnitId = su.id
            itemDataId = su.itemDataId
            productNumber = su.itemDataNumber
            lotNumber = su.lotNumber
            serialNumber = su.serialNumber
            amount = su.amount
            stockUnitAmount = su.amount
            fromUnitLoad = su.unitLoad.labelId
            fromStorageLocation = su.unitLoad.storageLocationName
            activityCode = PURGE_ACTIVITY_CODE
            operatorName = SYSTEM_OPERATOR
            this.correlationId = correlationId
        }
        repository.persist(journal)
    }

    /**
     * Row 18: the unit-load counterpart to [recordStockUnitPurged] -- appends the journal row
     * for a unit load about to be hard-deleted once every stock unit it carried has itself been
     * purged. Same no-[TenantContext] reasoning; there is no surviving [StockUnit] to journal
     * against, mirroring [recordUnitLoadTrashed]'s stock-unit-less shape.
     */
    fun recordUnitLoadPurged(clientId: Long, labelId: String, locationName: String, correlationId: String? = null) {
        val journal = InventoryJournal().apply {
            this.clientId = clientId
            recordType = JournalRecordType.DELETED.code
            fromUnitLoad = labelId
            fromStorageLocation = locationName
            activityCode = PURGE_ACTIVITY_CODE
            operatorName = SYSTEM_OPERATOR
            this.correlationId = correlationId
        }
        repository.persist(journal)
    }

    @Transactional
    fun recordAuthEvent(
        clientId: Long,
        username: String?,
        recordType: JournalRecordType,
        activityCode: String,
        correlationId: String?,
        ipAddress: String?,
        occurredAt: Instant,
    ) {
        val journal = InventoryJournal().apply {
            this.clientId = clientId
            this.recordType = recordType.code
            this.activityCode = activityCode
            operatorName = username
            this.correlationId = correlationId
            this.ipAddress = ipAddress
            created = occurredAt
            modified = occurredAt
        }
        repository.persist(journal)
    }

    private companion object {
        /** [recordStockUnitPurged]/[recordUnitLoadPurged]'s fixed operator literal -- there is
         *  no acting principal on a `@Scheduled` thread, see either KDoc. */
        const val SYSTEM_OPERATOR = "system"
        const val PURGE_ACTIVITY_CODE = "PURGE"
    }
}
