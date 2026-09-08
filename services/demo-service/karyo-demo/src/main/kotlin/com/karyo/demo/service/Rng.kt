package com.karyo.demo.service

import java.util.Random
import kotlin.math.exp

/** Deterministic PRNG wrapper (fixed seed) — all demo randomness flows through this. */
class Rng(seed: Long) {
    private val r = Random(seed)
    fun nextInt(bound: Int): Int = r.nextInt(bound)
    fun nextDouble(): Double = r.nextDouble()
    fun <T> pick(list: List<T>): T = list[r.nextInt(list.size)]
    fun bool(p: Double): Boolean = r.nextDouble() < p
    /** Knuth Poisson sampler — for per-day order counts around a mean. */
    fun poisson(mean: Double): Int {
        val l = exp(-mean); var k = 0; var p = 1.0
        do { k++; p *= r.nextDouble() } while (p > l)
        return k - 1
    }
}
