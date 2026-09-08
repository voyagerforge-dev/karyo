package com.karyo.product.spi

/** In-process substitution lookup (mirrors [ProductLookup]). Returns active substitutes ordered by priority. */
interface SubstitutionLookup {
    fun findSubstitutes(itemDataId: Long): List<SubstituteItem>
}

/** One substitute candidate for a primary item. */
data class SubstituteItem(
    val substituteItemDataId: Long,
    val priority: Int,
)
