package com.karyo.wave.event

import java.time.Instant

data class WaveStateChangedEvent(
    val waveId: Long,
    val waveNumber: String,
    val oldState: Int,
    val newState: Int,
    val clientId: Long,
    val occurredAt: Instant,
)
