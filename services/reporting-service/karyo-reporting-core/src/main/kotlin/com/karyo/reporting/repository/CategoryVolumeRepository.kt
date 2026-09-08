package com.karyo.reporting.repository

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.time.Instant

data class CategoryVolumeRow(val category: String, val volume: BigDecimal, val lineCount: Long)

/**
 * Phase B (B18): direct native aggregation over `picks` joined to `item_data` — no view, no
 * migration. Mirrors [KpiViewRepository]'s native-query + `Number` coercion pattern.
 */
@ApplicationScoped
class CategoryVolumeRepository(private val em: EntityManager) {

    private fun long(v: Any?): Long = (v as Number?)?.toLong() ?: 0
    private fun bigDecimal(v: Any?): BigDecimal = when (v) {
        is BigDecimal -> v
        is Number -> BigDecimal.valueOf(v.toDouble())
        else -> BigDecimal.ZERO
    }

    @Suppress("UNCHECKED_CAST")
    fun volumeByCategory(clientId: Long, start: Instant): List<CategoryVolumeRow> =
        (em.createNativeQuery(
            "SELECT COALESCE(i.trade_group, 'Uncategorized') AS category, " +
            "       SUM(p.picked_amount) AS volume, COUNT(*) AS lines " +
            "FROM karyo.picks p " +
            "JOIN karyo.item_data i ON p.item_data_id = i.id " +
            "WHERE p.client_id = ?1 AND p.created >= ?2 AND p.picking_type <> 'EXTINGUISH' " +
            "GROUP BY COALESCE(i.trade_group, 'Uncategorized') " +
            "ORDER BY volume DESC"
        ).setParameter(1, clientId)
         .setParameter(2, java.sql.Timestamp.from(start))
         .resultList as List<Array<Any?>>)
            .map { CategoryVolumeRow(it[0] as String, bigDecimal(it[1]), long(it[2])) }
}
