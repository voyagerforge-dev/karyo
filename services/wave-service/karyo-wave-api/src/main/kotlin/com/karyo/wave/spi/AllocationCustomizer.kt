package com.karyo.wave.spi

import com.karyo.orders.spi.ShortageView
import com.karyo.orders.spi.WaveOrderView
import com.karyo.wave.vo.AllocationShortageAction

data class WaveRef(val waveId: Long, val waveNumber: String, val clientId: Long, val wavePickMode: String, val shortageAction: String)

interface AllocationCustomizer {
    /** Order the wave's delivery orders before stock allocation. Default: prio DESC, created ASC. */
    fun prioritizeForAllocation(orders: List<WaveOrderView>, wave: WaveRef): List<WaveOrderView> =
        orders.sortedWith(compareByDescending<WaveOrderView> { it.prio }.thenBy { it.created })

    /** Called once per shorted line. Default: the wave's configured action. */
    fun onShortage(line: ShortageView, wave: WaveRef): AllocationShortageAction =
        AllocationShortageAction.valueOf(wave.shortageAction)
}
