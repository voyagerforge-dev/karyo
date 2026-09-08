package com.karyo.demo.gen

/** Deterministic A/B/C mover partition (first 4 = A, next 8 = B, rest = C). */
enum class MoverClass { A, B, C }

object VelocitySkew {
    fun classify(skus: List<String>): Map<String, MoverClass> =
        skus.mapIndexed { i, sku ->
            sku to when {
                i < 4 -> MoverClass.A
                i < 12 -> MoverClass.B
                else -> MoverClass.C
            }
        }.toMap()

    fun dailyPickProbability(m: MoverClass): Double = when (m) {
        MoverClass.A -> 1.0
        MoverClass.B -> 0.4
        MoverClass.C -> 0.08
    }
}
