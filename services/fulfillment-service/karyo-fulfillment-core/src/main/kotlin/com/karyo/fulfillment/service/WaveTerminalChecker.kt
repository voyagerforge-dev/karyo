package com.karyo.fulfillment.service

import com.karyo.fulfillment.repository.PickRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * Task 4 (wave bulk fulfillment): "is every pick of every PickOrder of this wave terminal
 * (PICKED or CANCELED)" -- extracted into its own leaf bean (a repository only, no service
 * dependencies) so both [PickOrderService.confirmPick] (fires [com.karyo.fulfillment.event
 * .WavePickActivityEvent] with this as its `waveAllTerminal` payload) and [WavePickService]
 * (implements [com.karyo.fulfillment.spi.BatchPickPort.allTerminal] by delegating here) can use
 * the identical query without either one injecting the other -- `WavePickService` already injects
 * `PickOrderService` (to reuse its internal COMPLETE/PICK-classification helpers), so the reverse
 * edge (`PickOrderService` injecting `WavePickService`/`BatchPickPort`) would be a circular CDI
 * bean graph. This class breaks that cycle.
 *
 * Task 4 review fix (IMPORTANT-5): [allTerminal] runs on every confirm of every wave-linked pick
 * (target: 50K pick lines/hr) -- the original implementation hydrated every [com.karyo.fulfillment
 * .domain.model.Pick] entity of the wave just to `.all {}` over their states. Now delegates to
 * [PickRepository.countOpenByWaveId], a single scalar `count(...)` query, no entity hydration.
 */
@ApplicationScoped
class WaveTerminalChecker(
    private val pickRepository: PickRepository,
) {

    /** Vacuously true for a wave with no PickOrders yet (nothing outstanding to be non-terminal). */
    fun allTerminal(waveId: Long, clientId: Long): Boolean =
        pickRepository.countOpenByWaveId(waveId, clientId) == 0L
}
