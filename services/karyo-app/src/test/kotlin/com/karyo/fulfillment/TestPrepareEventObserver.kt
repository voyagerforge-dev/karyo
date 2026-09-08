package com.karyo.fulfillment

import com.karyo.fulfillment.event.PickingOrderPrepareEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

/**
 * Test-only extension observer proving row 16's "pure hook, no built-in observer" contract: an
 * OUTSIDE observer can consume candidate picks and the generator (`PickOrderService
 * .releaseToPicking`) honors it. Used exclusively by [PickPrepareEventTest].
 *
 * This bean is a CDI singleton shared by every `@QuarkusTest` in the same test run, so it is
 * disarmed by default (both fields null/false) — with nothing armed, `onPrepare` fires but never
 * touches `consumedPickIndexes`, which is behaviorally identical to "no observer exists" for
 * every OTHER test in the suite (including the regression-pinned `PickGenerationServiceTest`).
 * [PickPrepareEventTest] MUST reset both fields in an `@AfterEach` to avoid bleeding state into
 * unrelated tests that happen to share this JVM.
 */
@ApplicationScoped
class TestPrepareEventObserver {
    @Volatile
    var consumeIndex: Int? = null

    @Volatile
    var consumeAll: Boolean = false

    fun onPrepare(@Observes event: PickingOrderPrepareEvent) {
        if (consumeAll) {
            event.consumedPickIndexes.addAll(event.plannedPicks.indices)
            return
        }
        consumeIndex?.let { event.consumedPickIndexes.add(it) }
    }
}
