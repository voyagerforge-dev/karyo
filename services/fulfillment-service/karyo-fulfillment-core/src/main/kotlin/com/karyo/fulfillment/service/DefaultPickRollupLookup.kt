package com.karyo.fulfillment.service

import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.spi.PickRollupLookup
import com.karyo.fulfillment.vo.PickRollup
import com.karyo.fulfillment.vo.TerminalSliceAmount
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Tuple
import java.math.BigDecimal

/**
 * Default in-process implementation of [PickRollupLookup], scoped to the current tenant like
 * [DefaultShipmentLookup] -- the ambient [TenantContext] clientId is applied inside the grouped
 * query, no explicit clientId parameter on the SPI. A line with no PICKED picks is simply
 * absent from the result map -- callers (e.g. orders) render zero for it, never fabricate a
 * picked quantity.
 */
@ApplicationScoped
class DefaultPickRollupLookup(
    private val pickRepository: PickRepository,
    private val tenantContext: TenantContext,
) : PickRollupLookup {

    override fun pickedAmountsByLineIds(lineIds: Set<Long>): Map<Long, PickRollup> {
        if (lineIds.isEmpty()) return emptyMap()
        return pickRepository.sumPickedByLineIds(lineIds, tenantContext.clientId)
            .groupBy { (it.get("lineId") as Number).toLong() }
            .mapValues { (_, rows) -> toRollup(rows) }
    }

    /**
     * [clientId] is the caller-supplied ORDER owner, deliberately NOT [tenantContext] — see the
     * SPI KDoc (an OPS-principal cancel must not be scoped to client 0).
     */
    override fun terminalPlannedBySlice(lineIds: Set<Long>, clientId: Long): List<TerminalSliceAmount> {
        if (lineIds.isEmpty()) return emptyList()
        return pickRepository.sumTerminalPlannedByLineIds(lineIds, clientId).map { row ->
            TerminalSliceAmount(
                deliveryOrderLineId = (row.get("lineId") as Number).toLong(),
                sourceStockUnitId = (row.get("stockUnitId") as Number).toLong(),
                plannedAmount = row.get("planned") as BigDecimal,
            )
        }
    }

    /** Rows are per-(line, substituted-SKU); a null substituted SKU is the ordered-SKU bucket. */
    private fun toRollup(rows: List<Tuple>): PickRollup {
        var picked = BigDecimal.ZERO
        var substituted = BigDecimal.ZERO
        rows.forEach { row ->
            val sum = row.get("picked") as BigDecimal
            if (row.get("substitutedItemDataId") == null) {
                picked = picked.add(sum)
            } else {
                substituted = substituted.add(sum)
            }
        }
        return PickRollup(pickedAmount = picked, substitutedAmount = substituted)
    }
}
