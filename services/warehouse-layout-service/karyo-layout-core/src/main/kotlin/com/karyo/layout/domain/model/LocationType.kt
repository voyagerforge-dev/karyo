package com.karyo.layout.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "location_types")
class LocationType : BaseEntity() {
    @Column(nullable = false, unique = true, length = 100)
    lateinit var name: String

    @Column(precision = 16, scale = 3)
    var height: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var width: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var depth: BigDecimal? = null

    @Column(name = "lifting_capacity", precision = 16, scale = 3)
    var liftingCapacity: BigDecimal? = null

    // L4 (locations-layout sprint, Task 7): myWMS `fieldLiftingCapacity`/`sectionLiftingCapacity`
    // — a cap on the TOTAL weight resting across every StorageLocation sharing this location
    // type's (area, rack, field) or (area, section) group, enforced by the finder's group-weight
    // check (see LocationFinderService/GroupCapacityReader). Null = unrestricted, same convention
    // as `liftingCapacity`.
    @Column(name = "field_lifting_capacity", precision = 16, scale = 3)
    var fieldLiftingCapacity: BigDecimal? = null

    @Column(name = "section_lifting_capacity", precision = 16, scale = 3)
    var sectionLiftingCapacity: BigDecimal? = null
}
