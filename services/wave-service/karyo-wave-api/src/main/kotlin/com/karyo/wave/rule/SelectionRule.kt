package com.karyo.wave.rule

import com.fasterxml.jackson.databind.JsonNode

/**
 * Rule-based wave candidate selection (selection-rules sprint, Task 1). One nesting level of
 * [groups] is allowed; total depth cap = 2 (a top-level [SelectionRule] plus one level of nested
 * groups) -- enforced where the rule is evaluated/validated, not by this data shape itself.
 */
data class SelectionRule(
    val combinator: String, // "AND" | "OR"
    val conditions: List<SelectionCondition> = emptyList(),
    val groups: List<SelectionRule> = emptyList(), // one nesting level; total depth cap = 2
)

data class SelectionCondition(val field: String, val op: String, val value: JsonNode? = null)
