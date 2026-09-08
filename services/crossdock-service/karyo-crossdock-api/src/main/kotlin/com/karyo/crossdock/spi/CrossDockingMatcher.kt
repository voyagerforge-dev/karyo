package com.karyo.crossdock.spi

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Extension seam for opportunistic cross-dock matching (Advanced Fulfillment pack). The
 * built-in implementation is `DefaultCrossDockingMatcher` in crossdock-core (earliest
 * ship-by open line whose open amount covers the received amount); a client extension jar may
 * register a higher-[priority] matcher (e.g. lot/best-before aware) without touching the
 * interceptor. The interceptor tries every registered matcher in descending [priority] order and
 * takes the first non-null result -- see `CrossDockInterceptor.opportunisticMatch` in
 * crossdock-core (not linkable from this module: api consumers do not depend on core).
 */
interface CrossDockingMatcher {
    fun findMatch(context: CrossDockMatchContext): CrossDockMatchResult?
    fun priority(): Int = 0
}

data class CrossDockMatchContext(
    val goodsReceiptLineId: Long,
    val itemDataId: Long,
    val amount: BigDecimal,
    val lotNumber: String?,
    val bestBefore: LocalDate?,
    val clientId: Long,
)

/**
 * [amount] and [stagingLocationId] are reserved for future use and currently IGNORED by
 * [com.karyo.crossdock.service.CrossDockInterceptor.opportunisticMatch] (crossdock-core; not
 * linkable from this module) -- the received event's own `amount` and the ordinary
 * `StagingLocationSelector` win instead. A matcher that populates either field today has no
 * effect; only [deliveryOrderLineId] and [matchScore] are consumed.
 */
data class CrossDockMatchResult(
    val deliveryOrderLineId: Long,
    val amount: BigDecimal,
    val matchScore: Double,
    val stagingLocationId: Long? = null,
)
