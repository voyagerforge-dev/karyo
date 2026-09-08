package com.karyo.common.exception

/**
 * Base exception class for all Karyo service exceptions.
 * Each service creates its own sealed exception class extending this.
 * Example: sealed class InventoryException(msg: String) : KaryoException(msg)
 */
open class KaryoException(message: String) : RuntimeException(message)
