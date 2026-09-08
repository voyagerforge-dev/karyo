package com.karyo.fulfillment.event

/**
 * Fired by [com.karyo.fulfillment.spi.BatchPickPort]'s implementation (via
 * `PickOrderService.confirmPick`) whenever a Pick belonging to a wave-linked PickOrder
 * (`PickOrder.waveId != null`) is confirmed. Declared in fulfillment-api (not domain/event, which
 * is fulfillment-core-internal) so wave-core can `@Observes` it without a cross-core Gradle edge.
 */
data class WavePickActivityEvent(
    val waveId: Long, val pickOrderId: Long, val clientId: Long,
    /** True when this confirm made every pick of every PickOrder of the wave terminal. */
    val waveAllTerminal: Boolean,
)
