package com.karyo.stocktaking.service

import com.karyo.layout.spi.LocationLockPort
import com.karyo.stocktaking.spi.CountScopeRequest
import com.karyo.stocktaking.spi.CountScopeStrategy
import jakarta.enterprise.context.ApplicationScoped

/** Built-in [CountScopeStrategy] (name `"EXPLICIT"`, the default for a CYCLE count): unions the
 *  explicit location ids in the request, all locations in the optional area, and all locations
 *  matching the optional [CountScopeRequest.locationNamePattern] — then dedups.
 *
 *  The [Int.MAX_VALUE] priority is now vestigial: strategies are selected BY NAME (see
 *  [CountScopeStrategy]), so nothing "takes precedence" over this one — a caller who wants a
 *  different scope asks for it by name. The value is kept only so the unnamed resolve (which no
 *  production caller uses) stays deterministic. */
@ApplicationScoped
class ExplicitLocationScope(
    private val locationLockPort: LocationLockPort,
) : CountScopeStrategy {
    override val priority: Int = Int.MAX_VALUE
    override val name: String = NAME

    override fun resolveLocations(request: CountScopeRequest, clientId: Long): List<Long> {
        val fromArea = request.areaId?.let { locationLockPort.expandAreaToLocations(it, clientId) } ?: emptyList()
        val fromPattern = request.locationNamePattern?.takeIf { it.isNotBlank() }
            ?.let { locationLockPort.findIdsByNamePattern(it, clientId) } ?: emptyList()
        return (request.locationIds + fromArea + fromPattern).distinct()
    }

    companion object {
        const val NAME = "EXPLICIT"
    }
}
