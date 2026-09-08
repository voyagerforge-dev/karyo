package com.karyo.replenishment

import com.karyo.auth.spi.RuntimePropertyLookup
import com.karyo.inventory.api.spi.ReplenishmentSource
import com.karyo.inventory.api.spi.ReplenishmentSourceSelector
import com.karyo.inventory.api.spi.SourceQuery
import com.karyo.inventory.api.spi.StockSummaryLookup
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.spi.FixAssignmentLookup
import com.karyo.layout.spi.FixAssignmentView
import com.karyo.layout.spi.ItemDataAreaLookup
import com.karyo.layout.spi.LocationAreaUsageLookup
import com.karyo.layout.spi.LocationLockPort
import com.karyo.replenishment.service.AreaReplenishmentService
import com.karyo.replenishment.service.ReplenishmentService
import com.karyo.replenishment.service.ReplenishmentStrategyResolver
import com.karyo.tasks.spi.ReplenishmentTaskCommand
import com.karyo.tasks.spi.TransportOrderPort
import com.karyo.tasks.spi.TransportOrderRef
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ReplenishmentServiceTest {

    private fun view(id: Long, cur: String, min: String) = FixAssignmentView(
        assignmentId = id, locationId = 100 + id, locationName = "PICK-$id",
        itemDataId = 200 + id, itemDataNumber = "SKU-$id",
        minAmount = BigDecimal(min), maxAmount = null, desiredAmount = BigDecimal("50"),
        currentAmount = BigDecimal(cur),
    )

    private fun viewWithNullAmount(id: Long, min: String) = FixAssignmentView(
        assignmentId = id, locationId = 100 + id, locationName = "PICK-$id",
        itemDataId = 200 + id, itemDataNumber = "SKU-$id",
        minAmount = BigDecimal(min), maxAmount = null, desiredAmount = BigDecimal("50"),
        currentAmount = null,
    )

    /** R12b (Task 6): every test in this class exercises the fix-face path only — the area scan
     *  is a no-op here since [ItemDataAreaLookup.listForReplenishment] returns empty. */
    private fun noAreas() = mockk<ItemDataAreaLookup>().also {
        every { it.listForReplenishment(any()) } returns emptyList()
    }
    private fun unusedStockSummary() = mockk<StockSummaryLookup>()
    private fun unusedLocationLock() = mockk<LocationLockPort>()

    /** Defect burndown 4 (Task 4): the area-only deps now live behind [AreaReplenishmentService]
     *  -- every test in this class builds one with an empty area lookup (see [noAreas]) so the
     *  area scan stays the no-op it always was here, sharing the fix-face mocks it also needs. */
    private fun areaService(
        selector: ReplenishmentSourceSelector,
        port: TransportOrderPort,
        resolver: ReplenishmentStrategyResolver,
        runtimeProperties: RuntimePropertyLookup,
    ) = AreaReplenishmentService(noAreas(), unusedStockSummary(), unusedLocationLock(), selector, port, resolver, runtimeProperties)

    @Test
    fun `scan generates a task for a below-min face with a source, skips open + above-min, reports no-source`() {
        val fixLookup = mockk<FixAssignmentLookup>()
        val selector = mockk<ReplenishmentSourceSelector>()
        val port = mockk<TransportOrderPort>().also {
            // Row 3 (defect-burndown-4, Task 5): scan() now seeds the in-pass claimed-source set
            // from this unconditionally -- every mocked port needs it stubbed.
            every { it.openReplenishmentUnitLoadIds(any()) } returns emptySet()
        }
        val strategy = com.karyo.replenishment.service.DefaultReplenishmentStrategy()
        val resolver = mockk<ReplenishmentStrategyResolver>().also { every { it.resolve(any()) } returns strategy }
        val stockUnitLookup = mockk<StockUnitLookup>().also {
            every { it.lotNumbersAtLocation(any(), any()) } returns emptySet()
        }
        val locationAreaUsageLookup = mockk<LocationAreaUsageLookup>().also {
            every { it.pickingLocationIds(any()) } returns emptySet()
        }
        val runtimeProperties = mockk<RuntimePropertyLookup>().also {
            every { it.getBoolean("karyo.replenishment.from-picking", any(), false) } returns false
        }

        every { fixLookup.listForReplenishment(1) } returns listOf(
            view(1, cur = "4", min = "10"),   // below min, has source  -> generate
            view(2, cur = "4", min = "10"),   // below min, open task    -> skip
            view(3, cur = "20", min = "10"),  // above min               -> skip
            view(4, cur = "1", min = "10"),   // below min, no source    -> shortfall
        )
        every { port.hasOpenReplenishment(1, 1) } returns false
        every { port.hasOpenReplenishment(2, 1) } returns true
        every { port.hasOpenReplenishment(4, 1) } returns false
        every {
            selector.selectSource(match { it.itemDataId == 201L })
        } returns ReplenishmentSource(unitLoadId = 999, amount = BigDecimal("48"), availableAmount = BigDecimal("48"))
        every { selector.selectSource(match { it.itemDataId == 204L }) } returns null
        val cmd = slot<ReplenishmentTaskCommand>()
        every { port.createReplenishment(capture(cmd)) } returns TransportOrderRef(id = 7, orderNumber = "RP-7", state = 100)

        val service = ReplenishmentService(
            fixLookup, selector, port, resolver, stockUnitLookup, locationAreaUsageLookup, runtimeProperties,
            areaService(selector, port, resolver, runtimeProperties),
        )
        val result = service.scan(clientId = 1)

        assertThat(result.generated).hasSize(1)
        assertThat(result.generated.first().fixAssignmentId).isEqualTo(1)
        assertThat(cmd.captured.unitLoadId).isEqualTo(999)
        assertThat(cmd.captured.destinationLocationId).isEqualTo(101)
        assertThat(cmd.captured.amount).isNull() // view(1,...) has maxAmount = null -> whole-UL, no top-up
        assertThat(result.shortfalls).hasSize(1)
        assertThat(result.shortfalls.first().reason).isEqualTo("NO_SOURCE")
    }

    // ── R13: fill-to-max top-up amount on the minted command ───────────────

    private fun viewWithMax(id: Long, cur: String, min: String, max: String?) = FixAssignmentView(
        assignmentId = id, locationId = 100 + id, locationName = "PICK-$id",
        itemDataId = 200 + id, itemDataNumber = "SKU-$id",
        minAmount = BigDecimal(min), maxAmount = max?.let { BigDecimal(it) }, desiredAmount = BigDecimal("50"),
        currentAmount = BigDecimal(cur),
    )

    private fun scanSingleAssignment(
        view: FixAssignmentView,
        sourceAmount: String,
        availableAmount: String = sourceAmount,
    ): ReplenishmentTaskCommand {
        val fixLookup = mockk<FixAssignmentLookup>()
        val selector = mockk<ReplenishmentSourceSelector>()
        val port = mockk<TransportOrderPort>().also {
            // Row 3 (defect-burndown-4, Task 5): scan() now seeds the in-pass claimed-source set
            // from this unconditionally -- every mocked port needs it stubbed.
            every { it.openReplenishmentUnitLoadIds(any()) } returns emptySet()
        }
        val strategy = com.karyo.replenishment.service.DefaultReplenishmentStrategy()
        val resolver = mockk<ReplenishmentStrategyResolver>().also { every { it.resolve(any()) } returns strategy }
        val stockUnitLookup = mockk<StockUnitLookup>().also {
            every { it.lotNumbersAtLocation(any(), any()) } returns emptySet()
        }
        val locationAreaUsageLookup = mockk<LocationAreaUsageLookup>().also {
            every { it.pickingLocationIds(any()) } returns emptySet()
        }
        val runtimeProperties = mockk<RuntimePropertyLookup>().also {
            every { it.getBoolean("karyo.replenishment.from-picking", any(), false) } returns false
        }

        every { fixLookup.listForReplenishment(1) } returns listOf(view)
        every { port.hasOpenReplenishment(view.assignmentId, 1) } returns false
        every {
            selector.selectSource(match { it.itemDataId == view.itemDataId })
        } returns ReplenishmentSource(
            unitLoadId = 999,
            amount = BigDecimal(sourceAmount),
            availableAmount = BigDecimal(availableAmount),
        )
        val cmd = slot<ReplenishmentTaskCommand>()
        every { port.createReplenishment(capture(cmd)) } returns TransportOrderRef(id = 7, orderNumber = "RP-7", state = 100)

        ReplenishmentService(
            fixLookup, selector, port, resolver, stockUnitLookup, locationAreaUsageLookup, runtimeProperties,
            areaService(selector, port, resolver, runtimeProperties),
        ).scan(clientId = 1)
        return cmd.captured
    }

    @Test
    fun `R13 top-up deficit smaller than the source amount is passed on the command`() {
        // minAmount=50 (> currentAmount, triggers needsReplenishment); maxAmount=100,
        // currentAmount=40 -> deficit=60; source has 200 -> requested=60 < 200 -> amount=60
        val view = viewWithMax(id = 10, cur = "40", min = "50", max = "100")
        val cmd = scanSingleAssignment(view, sourceAmount = "200")
        assertThat(cmd.amount).isEqualByComparingTo(BigDecimal("60"))
    }

    @Test
    fun `R13 deficit at or above the source amount is a whole-UL move -- command amount is null`() {
        // minAmount=50, maxAmount=100, currentAmount=40 -> deficit=60; source only has 30 ->
        // requested=30 == source.amount -> null
        val view = viewWithMax(id = 11, cur = "40", min = "50", max = "100")
        val cmd = scanSingleAssignment(view, sourceAmount = "30")
        assertThat(cmd.amount).isNull()
    }

    @Test
    fun `R13 no maxAmount configured -- command amount is null (parity unchanged)`() {
        val view = viewWithMax(id = 12, cur = "4", min = "10", max = null)
        val cmd = scanSingleAssignment(view, sourceAmount = "48")
        assertThat(cmd.amount).isNull()
    }

    // ── Row 5: top-up caps at the source's AVAILABLE amount, not its gross amount ──

    @Test
    fun `R13 top-up caps at the source's available amount, not its gross amount`() {
        // minAmount=80, maxAmount=100, currentAmount=70 -> deficit=30; source gross=40 but only
        // 25 is available (reservedAmount=15) -> requested = min(available=25, deficit=30) = 25,
        // NOT 30 (the pre-fix behavior capped against the gross 40, which never even bound here).
        val view = viewWithMax(id = 13, cur = "70", min = "80", max = "100")
        val cmd = scanSingleAssignment(view, sourceAmount = "40", availableAmount = "25")
        assertThat(cmd.amount).isEqualByComparingTo(BigDecimal("25"))
    }

    // ── Row 1: a face whose currentAmount read failed (null) mints NO order ─────

    @Test
    fun `scan skips a face whose currentAmount is null instead of treating it as zero`() {
        val fixLookup = mockk<FixAssignmentLookup>()
        val selector = mockk<ReplenishmentSourceSelector>()
        val port = mockk<TransportOrderPort>().also {
            // Row 3 (defect-burndown-4, Task 5): scan() now seeds the in-pass claimed-source set
            // from this unconditionally -- every mocked port needs it stubbed.
            every { it.openReplenishmentUnitLoadIds(any()) } returns emptySet()
        }
        val strategy = com.karyo.replenishment.service.DefaultReplenishmentStrategy()
        val resolver = mockk<ReplenishmentStrategyResolver>().also { every { it.resolve(any()) } returns strategy }
        val stockUnitLookup = mockk<StockUnitLookup>().also {
            every { it.lotNumbersAtLocation(any(), any()) } returns emptySet()
        }
        val locationAreaUsageLookup = mockk<LocationAreaUsageLookup>().also {
            every { it.pickingLocationIds(any()) } returns emptySet()
        }
        val runtimeProperties = mockk<RuntimePropertyLookup>().also {
            every { it.getBoolean("karyo.replenishment.from-picking", any(), false) } returns false
        }

        val nullAmountView = viewWithNullAmount(id = 20, min = "10")
        every { fixLookup.listForReplenishment(1) } returns listOf(nullAmountView)

        val service = ReplenishmentService(
            fixLookup, selector, port, resolver, stockUnitLookup, locationAreaUsageLookup, runtimeProperties,
            areaService(selector, port, resolver, runtimeProperties),
        )
        val result = service.scan(clientId = 1)

        // Today: currentAmount == null is coerced to zero, needsReplenishment(0, 10, ...) is true,
        // and (with no selector/port stub for it) the call blows up trying to select a source --
        // a null-amount face must never reach that far. The honest fix mints nothing at all.
        assertThat(result.generated).isEmpty()
        assertThat(result.shortfalls).isEmpty()
        io.mockk.verify(exactly = 0) { selector.selectSource(any()) }
        io.mockk.verify(exactly = 0) { port.hasOpenReplenishment(any(), any()) }
    }

    // ── Row 3 (defect-burndown-4, Task 5): source unit-load claim exclusion ─────

    @Test
    fun `scan excludes a source claimed by an earlier fix assignment in the SAME pass from the next query`() {
        val fixLookup = mockk<FixAssignmentLookup>()
        val selector = mockk<ReplenishmentSourceSelector>()
        val port = mockk<TransportOrderPort>().also {
            every { it.openReplenishmentUnitLoadIds(1) } returns emptySet()
            every { it.hasOpenReplenishment(any(), any()) } returns false
        }
        val strategy = com.karyo.replenishment.service.DefaultReplenishmentStrategy()
        val resolver = mockk<ReplenishmentStrategyResolver>().also { every { it.resolve(any()) } returns strategy }
        val stockUnitLookup = mockk<StockUnitLookup>().also {
            every { it.lotNumbersAtLocation(any(), any()) } returns emptySet()
        }
        val locationAreaUsageLookup = mockk<LocationAreaUsageLookup>().also {
            every { it.pickingLocationIds(any()) } returns emptySet()
        }
        val runtimeProperties = mockk<RuntimePropertyLookup>().also {
            every { it.getBoolean("karyo.replenishment.from-picking", any(), false) } returns false
        }

        every { fixLookup.listForReplenishment(1) } returns listOf(
            view(30, cur = "4", min = "10"),
            view(31, cur = "4", min = "10"),
        )
        val capturedQueries = mutableListOf<SourceQuery>()
        every { selector.selectSource(capture(capturedQueries)) } returnsMany listOf(
            ReplenishmentSource(unitLoadId = 501, amount = BigDecimal("40"), availableAmount = BigDecimal("40")),
            ReplenishmentSource(unitLoadId = 502, amount = BigDecimal("40"), availableAmount = BigDecimal("40")),
        )
        every { port.createReplenishment(any()) } returnsMany listOf(
            TransportOrderRef(id = 30, orderNumber = "RP-30", state = 100),
            TransportOrderRef(id = 31, orderNumber = "RP-31", state = 100),
        )

        val service = ReplenishmentService(
            fixLookup, selector, port, resolver, stockUnitLookup, locationAreaUsageLookup, runtimeProperties,
            areaService(selector, port, resolver, runtimeProperties),
        )
        val result = service.scan(clientId = 1)

        assertThat(result.generated).hasSize(2)
        assertThat(capturedQueries).hasSize(2)
        // The FIRST query (assignment 30) starts with no in-pass claims yet.
        assertThat(capturedQueries[0].excludeUnitLoadIds).isEmpty()
        // The SECOND query (assignment 31) must exclude the source the FIRST assignment just
        // claimed -- without Row 3's fix, both queries would be identical (no exclusion at all),
        // letting the same FIFO-first source unit-load be picked twice in one pass.
        assertThat(capturedQueries[1].excludeUnitLoadIds).contains(501L)
        assertThat(result.generated.map { it.unitLoadId }).containsExactlyInAnyOrder(501L, 502L)
    }
}
