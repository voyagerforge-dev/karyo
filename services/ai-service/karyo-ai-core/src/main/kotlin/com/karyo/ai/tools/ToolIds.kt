package com.karyo.ai.tools

import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Seam for proposal ID generation — swapped to a deterministic stub in unit tests. */
@ApplicationScoped
class ToolIds {
    fun next(): String = UUID.randomUUID().toString()
}
