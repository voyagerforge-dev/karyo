package com.karyo.layout.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * A short-lived soft reservation of a storage location for an in-flight putaway.
 *
 * Created by the location finder when it suggests a location, keyed by the requesting
 * [transportOrderId]. The reservation's [percent] is added to the location's base
 * allocation when computing *effective* allocation, so the finder will not hand the
 * same nearly full location to two concurrent putaways. Released explicitly on task
 * completion/cancel ([com.karyo.layout.spi.LocationFinder.releaseReservation]) or swept
 * once [expiresAt] passes (a @Scheduled job).
 *
 * Not a [com.karyo.common.domain.TenantEntity]: reservations are an internal mechanism
 * keyed by location/transport-order, not a tenant-owned aggregate.
 */
@Entity
@Table(name = "location_reservations")
class LocationReservation : BaseEntity() {

    @Column(name = "location_id", nullable = false)
    var locationId: Long = 0

    @Column(name = "transport_order_id", nullable = false)
    var transportOrderId: Long = 0

    /** Allocation load this reservation contributes (0–100); a full UL reserves 100. */
    @Column(nullable = false, precision = 15, scale = 2)
    var percent: BigDecimal = BigDecimal("100")

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now()
}
