package com.karyo.work

import com.karyo.layout.spi.WorkingAreaLookup
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkItem
import com.karyo.work.dto.WorkRef
import com.karyo.work.exception.WorkClaimConflictException
import com.karyo.work.service.StrictPriorityDispatchStrategy
import com.karyo.work.service.WorkDispatchConfig
import com.karyo.work.service.WorkDispatchService
import com.karyo.work.spi.WorkDispatchStrategy
import com.karyo.work.spi.WorkEligibilityResolver
import com.karyo.work.spi.WorkProvider
import com.karyo.work.vo.WorkState
import com.karyo.work.vo.WorkType
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkDispatchServiceTest {

    /** None of the pre-existing tests here pass a `workingAreaId`, so this stub is never invoked. */
    private fun noopWorkingAreaLookup(): WorkingAreaLookup = mockk(relaxed = true)

    private fun item(type: WorkType, id: Long, prio: Int, age: Long) = WorkItem(
        ref = WorkRef(type, id), workType = type, priority = prio, state = WorkState.OPEN,
        claimedBy = null, zone = null, primaryLocation = "L$id", destination = null,
        summary = "$type $id", createdAt = Instant.ofEpochSecond(age),
    )

    /** Minimal Instance<T> fake backed by a list (only the iterable surface is used). */
    private fun <T> inst(items: List<T>): Instance<T> {
        val i = mockk<Instance<T>>(relaxed = true)
        every { i.iterator() } answers { items.toMutableList().iterator() }
        return i
    }

    private fun eligibility(types: Set<WorkType>): WorkEligibilityResolver =
        object : WorkEligibilityResolver {
            override val priority = 0
            override val name = "TEST"
            override fun resolve(operatorId: String, clientId: Long) = types
        }

    private fun strategy(): WorkDispatchStrategy = StrictPriorityDispatchStrategy()

    @Test
    fun `getNext claims the highest-priority eligible item across providers`() {
        val pickProvider = mockk<WorkProvider>()
        every { pickProvider.workTypes() } returns setOf(WorkType.PICK)
        every { pickProvider.listOpen(any()) } returns listOf(item(WorkType.PICK, 1, prio = 50, age = 10))

        val moveProvider = mockk<WorkProvider>()
        every { moveProvider.workTypes() } returns setOf(WorkType.MOVE)
        every { moveProvider.listOpen(any()) } returns listOf(item(WorkType.MOVE, 2, prio = 90, age = 20))
        val claimed = item(WorkType.MOVE, 2, 90, 20).copy(state = WorkState.CLAIMED, claimedBy = "alice")
        every { moveProvider.claim(WorkRef(WorkType.MOVE, 2), "alice") } returns claimed

        val svc = WorkDispatchService(
            inst(listOf(pickProvider, moveProvider)),
            inst(listOf(strategy())),
            inst(listOf(eligibility(setOf(WorkType.PICK, WorkType.MOVE)))),
            noopWorkingAreaLookup(),
        )

        val next = svc.getNext("alice", 1L, WorkFilter())
        assertThat(next?.ref).isEqualTo(WorkRef(WorkType.MOVE, 2)) // prio 90 beats 50
        assertThat(next?.claimedBy).isEqualTo("alice")
    }

    @Test
    fun `getNext skips a lost race and claims the next candidate`() {
        val p = mockk<WorkProvider>()
        every { p.workTypes() } returns setOf(WorkType.PICK)
        every { p.listOpen(any()) } returns listOf(
            item(WorkType.PICK, 1, prio = 90, age = 10),
            item(WorkType.PICK, 2, prio = 50, age = 20),
        )
        every { p.claim(WorkRef(WorkType.PICK, 1), "alice") } throws WorkClaimConflictException("taken")
        val second = item(WorkType.PICK, 2, 50, 20).copy(state = WorkState.CLAIMED, claimedBy = "alice")
        every { p.claim(WorkRef(WorkType.PICK, 2), "alice") } returns second

        val svc = WorkDispatchService(
            inst(listOf(p)), inst(listOf(strategy())), inst(listOf(eligibility(setOf(WorkType.PICK)))),
            noopWorkingAreaLookup(),
        )

        assertThat(svc.getNext("alice", 1L, WorkFilter())?.ref).isEqualTo(WorkRef(WorkType.PICK, 2))
    }

    @Test
    fun `getNext returns null when operator is eligible for nothing`() {
        val svc = WorkDispatchService(
            inst(emptyList()), inst(listOf(strategy())), inst(listOf(eligibility(emptySet()))),
            noopWorkingAreaLookup(),
        )
        assertThat(svc.getNext("alice", 1L, WorkFilter())).isNull()
    }

    // ── St6: config-name-driven strategy resolver ─────────────────────────────────────────────

    @Test
    fun `an unresolvable configured dispatch-strategy name fails loudly rather than falling back silently`() {
        val svc = WorkDispatchService(
            inst(emptyList()), inst(listOf(strategy())), inst(listOf(eligibility(emptySet()))),
            noopWorkingAreaLookup(),
            WorkDispatchConfig("BOGUS_NAME"),
        )

        assertThatThrownBy { svc.mine("alice") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("BOGUS_NAME")
    }

    @Test
    fun `a lower-priority third-party strategy bean does not silently displace the default STRICT_PRIORITY selection`() {
        val p = mockk<WorkProvider>()
        every { p.workTypes() } returns setOf(WorkType.PICK)
        every { p.listClaimedBy("alice") } returns listOf(
            item(WorkType.PICK, 1, prio = 50, age = 10),
            item(WorkType.PICK, 2, prio = 50, age = 20),
        )

        // Pre-St6, minByOrNull { it.priority } would have picked THIS bean over
        // StrictPriorityDispatchStrategy (Int.MAX_VALUE) purely because it registered with a
        // lower priority value -- exactly the silent-takeover bug St6 closes.
        val rogue = object : WorkDispatchStrategy {
            override val priority = 1
            override val name = "ROGUE"
            override fun order(items: List<WorkItem>) = items.reversed()
        }

        val svc = WorkDispatchService(
            inst(listOf(p)), inst(listOf(strategy(), rogue)), inst(listOf(eligibility(setOf(WorkType.PICK)))),
            noopWorkingAreaLookup(),
            // dispatchConfig omitted -> Kotlin default "STRICT_PRIORITY", same as the real
            // config's non-empty default.
        )

        // STRICT_PRIORITY (equal priority=50, createdAt ASC tiebreak) keeps [1, 2]; ROGUE would
        // have reversed it to [2, 1].
        assertThat(svc.mine("alice").map { it.ref })
            .containsExactly(WorkRef(WorkType.PICK, 1), WorkRef(WorkType.PICK, 2))
    }

    // ── Boot-time validation of dispatch-strategy config ──────────────────────────────────────

    @Test
    fun `validateOnStartup with unresolvable strategy name throws IllegalStateException naming the property`() {
        val svc = WorkDispatchService(
            inst(emptyList()), inst(listOf(strategy())), inst(listOf(eligibility(emptySet()))),
            noopWorkingAreaLookup(),
            WorkDispatchConfig("BOGUS_NAME"),
        )

        assertThatThrownBy { svc.validateOnStartup(StartupEvent()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("BOGUS_NAME")
            .hasMessageContaining("karyo.work.dispatch-strategy")
    }

    @Test
    fun `validateOnStartup with duplicate strategy names throws IllegalStateException naming the duplicates`() {
        val dup1 = object : WorkDispatchStrategy {
            override val priority = Int.MAX_VALUE
            override val name = "DUPLICATE"
            override fun order(items: List<WorkItem>) = items
        }
        val dup2 = object : WorkDispatchStrategy {
            override val priority = 99
            override val name = "DUPLICATE"
            override fun order(items: List<WorkItem>) = items
        }

        val svc = WorkDispatchService(
            inst(emptyList()), inst(listOf(dup1, dup2)), inst(listOf(eligibility(emptySet()))),
            noopWorkingAreaLookup(),
            WorkDispatchConfig("DUPLICATE"),
        )

        assertThatThrownBy { svc.validateOnStartup(StartupEvent()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Duplicate WorkDispatchStrategy name")
            .hasMessageContaining("DUPLICATE")
    }

    @Test
    fun `validateOnStartup with valid config returns normally`() {
        val svc = WorkDispatchService(
            inst(emptyList()), inst(listOf(strategy())), inst(listOf(eligibility(emptySet()))),
            noopWorkingAreaLookup(),
            WorkDispatchConfig("STRICT_PRIORITY"),
        )

        // Should not throw
        svc.validateOnStartup(StartupEvent())
    }
}
