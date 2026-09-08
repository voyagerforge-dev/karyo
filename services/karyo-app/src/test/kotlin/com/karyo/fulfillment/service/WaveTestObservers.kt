package com.karyo.fulfillment.service

import com.karyo.fulfillment.domain.event.PickOrderAutoPackEvent
import com.karyo.fulfillment.event.WavePickActivityEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.util.Collections

/**
 * Test-only observers proving Task 4's two confirmPick hooks. CDI singletons shared across the
 * whole test-run JVM (same caveat [TestPrepareEventObserver] documents), so
 * [WavePickServiceIT] clears both lists in an `@AfterEach`.
 */
@ApplicationScoped
class TestWaveActivityObserver {
    val events: MutableList<WavePickActivityEvent> = Collections.synchronizedList(mutableListOf())

    fun onWaveActivity(@Observes event: WavePickActivityEvent) {
        events.add(event)
    }
}

@ApplicationScoped
class TestAutoPackObserver {
    val events: MutableList<PickOrderAutoPackEvent> = Collections.synchronizedList(mutableListOf())

    fun onAutoPack(@Observes event: PickOrderAutoPackEvent) {
        events.add(event)
    }
}
