package com.karyo.wave.spi

data class ConsolidationGroupRef(val groupId: Long, val waveId: Long, val destinationKey: String, val clientId: Long)

interface ConsolidationCustomizer {
    /** Staging location for this group, or null for unassigned. */
    fun assignStagingLocation(group: ConsolidationGroupRef, wave: WaveRef): Long? = null

    /** Throw to block the READY transition. Default: allow. */
    fun validateReady(group: ConsolidationGroupRef) { }
}
