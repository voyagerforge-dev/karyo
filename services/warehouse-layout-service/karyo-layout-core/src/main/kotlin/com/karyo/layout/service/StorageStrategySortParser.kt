package com.karyo.layout.service

import com.karyo.layout.exception.LayoutException
import com.karyo.layout.vo.StorageStrategySortType
import org.jboss.logging.Logger

/**
 * Parses `StorageStrategy.sorts` (locations-layout sprint Task 5) — a comma-separated list
 * of [StorageStrategySortType] tokens. Two asymmetric entry points, per the task brief:
 *
 * - [parseLenient] (read-time, used by [CandidateOrdering]): unknown tokens are SKIPPED,
 *   never thrown — the column stays free-text and existing rows may already hold garbage
 *   from before this task; the finder must keep working regardless. Logs at most ONE warn
 *   per call naming every skipped token together (not one warn per token).
 * - [validate] (save-time, used by [StorageStrategyService]): throws
 *   [LayoutException.InvalidSortTokens] naming every unrecognized token plus the valid set,
 *   so a NEW mistake is caught immediately instead of silently no-op'ing later at find time.
 */
object StorageStrategySortParser {
    private val log = Logger.getLogger(StorageStrategySortParser::class.java)

    private fun tokens(sorts: String?): List<String> =
        sorts?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun parseLenient(sorts: String?): List<StorageStrategySortType> {
        val unknown = mutableListOf<String>()
        val parsed = tokens(sorts).mapNotNull { token ->
            StorageStrategySortType.fromToken(token) ?: run { unknown += token; null }
        }
        if (unknown.isNotEmpty()) {
            log.warnf(
                "Storage strategy 'sorts' contains unknown token(s) %s — skipped at read time; valid values: %s",
                unknown,
                StorageStrategySortType.entries.map { it.name },
            )
        }
        return parsed
    }

    fun validate(sorts: String?) {
        val unknown = tokens(sorts).filter { StorageStrategySortType.fromToken(it) == null }
        if (unknown.isNotEmpty()) {
            throw LayoutException.InvalidSortTokens(unknown)
        }
    }
}
