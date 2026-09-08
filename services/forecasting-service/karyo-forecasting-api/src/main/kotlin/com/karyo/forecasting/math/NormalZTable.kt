package com.karyo.forecasting.math

/**
 * Hand-tabulated inverse-normal CDF (service-level → z) with linear interpolation between
 * tabulated points and clamping outside the table. Deterministic, pure, stateless — no
 * external stats library. Promoted out of ReorderCalculator (v1.7d) so the forecasting
 * reorder math and the simulation engine share one source of z-values.
 */
object NormalZTable {
    /** Tabulated (serviceLevel, z) points, ascending by serviceLevel. */
    private val zTable: List<Pair<Double, Double>> = listOf(
        0.50 to 0.0,
        0.80 to 0.8416,
        0.90 to 1.2816,
        0.95 to 1.6449,
        0.975 to 1.9600,
        0.99 to 2.3263,
        0.999 to 3.0902,
    )

    fun zFor(serviceLevel: Double): Double {
        if (serviceLevel <= zTable.first().first) return zTable.first().second
        if (serviceLevel >= zTable.last().first) return zTable.last().second

        for (i in 0 until zTable.size - 1) {
            val (lowLevel, lowZ) = zTable[i]
            val (highLevel, highZ) = zTable[i + 1]
            if (serviceLevel in lowLevel..highLevel) {
                val fraction = (serviceLevel - lowLevel) / (highLevel - lowLevel)
                return lowZ + fraction * (highZ - lowZ)
            }
        }
        // Unreachable given the clamps above, but keeps the function total.
        return zTable.last().second
    }
}
