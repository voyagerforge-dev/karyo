package com.karyo.layout.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "storage_locations")
class StorageLocation : TenantEntity() {
    @Column(nullable = false, unique = true, length = 100)
    lateinit var name: String

    @Column(name = "scan_code", length = 100)
    var scanCode: String? = null

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "location_type_id", nullable = false)
    lateinit var locationType: LocationType

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "area_id", nullable = false)
    lateinit var area: Area

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    var zone: Zone? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_cluster_id")
    var locationCluster: LocationCluster? = null

    @Column(nullable = false, precision = 15, scale = 2)
    var allocation: BigDecimal = BigDecimal.ZERO

    @Column(name = "lock_type", nullable = false)
    var lockType: Int = 0

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0

    @Column(name = "x_pos", nullable = false)
    var xPos: Int = 0

    @Column(name = "y_pos", nullable = false)
    var yPos: Int = 0

    @Column(name = "z_pos", nullable = false)
    var zPos: Int = 0

    @Column(length = 50)
    var rack: String? = null

    @Column(name = "field", length = 50)
    var field: String? = null

    @Column(length = 50)
    var section: String? = null

    // Phase B (B9/B10/B12): honest, seeder-derived metadata. Nullable — un-set rows are an
    // honest "—" gap on the frontend, never fabricated.
    @Column
    var capacity: Int? = null

    @Column(name = "temperature_zone", length = 20)
    var temperatureZone: String? = null

    @Column(name = "handling_class", length = 20)
    var handlingClass: String? = null

    @Column(length = 20)
    var kind: String? = null

    @Column(name = "last_counted_at")
    var lastCountedAt: Instant? = null

    // A2-1 (six-hard-items §2.3): explicit, queryable clearing-location flag — NOT the
    // free-text `kind` taxonomy, NOT a magic id (myWMS's id=1 is not carried over). A partial
    // unique index (V310) enforces at most one row with isClearing=true per instance.
    @Column(name = "is_clearing", nullable = false)
    var isClearing: Boolean = false

    // L3 (locations-layout sprint, Task 6): myWMS `plcCode` — free-text automation-system
    // (PLC/WCS) address. Search/display only, zero finder semantics (ADR-034's future
    // EquipmentAdapter address bridge).
    @Column(name = "plc_code", length = 64)
    var plcCode: String? = null

    // L3: myWMS `allocationState` — 0 = normal/searchable; any non-zero value is an
    // operator "mark full/blocked" that excludes the location from the putaway finder
    // (see StorageLocationRepository.findPutawayCandidates). Written only by the admin
    // UI/API today — no business writer flips it automatically.
    @Column(name = "allocation_state", nullable = false)
    var allocationState: Int = 0
}
