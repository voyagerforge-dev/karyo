package com.karyo.ai.tools

object ToolFormat {
    fun row(vararg cells: Any?): String = cells.joinToString(" | ") { it?.toString() ?: "-" }
    fun none(what: String): String = "No $what found."
}
