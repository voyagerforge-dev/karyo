package com.karyo.common.pagination

import io.quarkus.panache.common.Sort

/**
 * Parses "field,direction" sort strings into Panache Sort objects.
 * Accepts: ["name,asc", "created,desc"] or null (returns defaultSort).
 *
 * Validates field names against an allowlist to prevent SQL injection.
 */
object SortParser {
    fun parse(
        sortParams: List<String>?,
        allowedFields: Set<String>,
        defaultSort: Sort = Sort.ascending("id"),
    ): Sort {
        if (sortParams.isNullOrEmpty()) return defaultSort

        var sort: Sort? = null
        for (param in sortParams) {
            val parts = param.split(",", limit = 2)
            val field = parts[0].trim()
            if (field !in allowedFields) continue

            val direction = if (parts.size > 1 && parts[1].trim().equals("desc", ignoreCase = true))
                Sort.Direction.Descending else Sort.Direction.Ascending

            sort = if (sort == null) Sort.by(field, direction)
            else sort.and(field, direction)
        }
        return sort ?: defaultSort
    }
}
