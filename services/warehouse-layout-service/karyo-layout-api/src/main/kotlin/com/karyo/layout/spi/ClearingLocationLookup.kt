package com.karyo.layout.spi

/**
 * A2-1: resolve THE clearing location — a facility-level physical singleton
 * (partial unique index enforces at-most-one). Deliberately unscoped: the
 * clearing location is warehouse infrastructure, not tenant data (same
 * deliberate-unscoped precedent as ClientLookup.exists).
 */
interface ClearingLocationLookup {
    fun findClearing(): ClearingLocationInfo?
}

data class ClearingLocationInfo(val id: Long, val name: String)
