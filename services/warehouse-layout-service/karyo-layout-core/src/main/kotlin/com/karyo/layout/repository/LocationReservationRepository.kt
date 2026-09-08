package com.karyo.layout.repository

import com.karyo.layout.domain.model.LocationReservation
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
class LocationReservationRepository : PanacheRepository<LocationReservation> {

    /** Sum of active (non-expired) reservation load per location, for the given location ids. */
    fun activeLoadByLocation(locationIds: Collection<Long>, now: Instant): Map<Long, BigDecimal> {
        if (locationIds.isEmpty()) return emptyMap()
        return list("locationId in ?1 and expiresAt > ?2", locationIds, now)
            .groupBy { it.locationId }
            .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.percent } }
    }

    fun findByTransportOrderId(transportOrderId: Long): List<LocationReservation> =
        list("transportOrderId", transportOrderId)

    fun deleteByTransportOrderId(transportOrderId: Long): Long =
        delete("transportOrderId", transportOrderId)

    fun deleteExpired(now: Instant): Long =
        delete("expiresAt <= ?1", now)
}
