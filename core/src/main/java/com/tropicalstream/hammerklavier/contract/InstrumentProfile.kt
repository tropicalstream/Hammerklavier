package com.tropicalstream.hammerklavier.contract

import kotlin.math.pow

/**
 * Per-instrument mechanical and musical constants (PLAN §3.7 table "Profiles"). Immutable.
 * Always construct with named arguments (growth rules, §7.1 rule 2).
 */
class InstrumentProfile(
    val id: InstrumentId, val displayName: String,
    val lowKey: Int, val highKey: Int, val lastDamper: Int,
    val keyDipMm: Float, val keyReturnMs: Float, val repeatMinMs: Float, val partialReturnMs: Float,
    val blowMm: Float, val letOffMm: Float, val checkMm: Float, val actionRatio: Float, val travelScale: Float,
    val usesSustain: Boolean, val usesSostenuto: Boolean, val softKind: SoftKind,
    val legatoHold: Boolean, val velocityGain: Boolean, val stops: Int,
    val restrikeTauUpMs: Float, val restrikeTauPedalMs: Float, val defaultTuning: TuningSpec) {

    /**
     * DERIVED, real time (ms after note-off at which the damper lands):
     * KeyReturn.damperLandMs(keyReturnMs) for pianos, HarpsiTiming.damperLandMs for the harpsichord.
     */
    val damperLagMs: Float =
        if (id == InstrumentId.HARPSICHORD) HarpsiTiming.damperLandMs else KeyReturn.damperLandMs(keyReturnMs)

    /**
     * Grand: 1 for 21–28, 2 for 29–48, 3 for 49–108 (notes 1–8 / 9–28 / 29–88); upright 1/2/3 by
     * the same bands; harpsichord 1 per stop. Shared by StringsMesh, KeyState and ResonanceBank.
     */
    fun stringsPerKey(key: Int): Int = when {
        id == InstrumentId.HARPSICHORD -> 1
        key <= 28 -> 1
        key <= 48 -> 2
        else -> 3
    }

    /** The bank's value is authoritative. */
    fun withLastDamper(n: Int): InstrumentProfile = InstrumentProfile(
        id = id, displayName = displayName, lowKey = lowKey, highKey = highKey, lastDamper = n,
        keyDipMm = keyDipMm, keyReturnMs = keyReturnMs, repeatMinMs = repeatMinMs, partialReturnMs = partialReturnMs,
        blowMm = blowMm, letOffMm = letOffMm, checkMm = checkMm, actionRatio = actionRatio, travelScale = travelScale,
        usesSustain = usesSustain, usesSostenuto = usesSostenuto, softKind = softKind,
        legatoHold = legatoHold, velocityGain = velocityGain, stops = stops,
        restrikeTauUpMs = restrikeTauUpMs, restrikeTauPedalMs = restrikeTauPedalMs, defaultTuning = defaultTuning)

    /**
     * Default damper T60 `T60d(key)` in seconds (PLAN §3.7), the value the stub kit and SynthBank
     * ship in `BankInfo.damperT60`: grand `0.12 + 1.2·((88−n)/67)²` (n = MIDI key, clamped to
     * 21..88), upright grand × 1.2, harpsichord `0.10 + 0.15·(88−n)/59`. Off-thread only (no table).
     */
    fun defaultDamperT60(key: Int): Float = when (id) {
        InstrumentId.GRAND -> grandDamperT60(key)
        InstrumentId.UPRIGHT -> 1.2f * grandDamperT60(key)
        InstrumentId.HARPSICHORD -> {
            val n = key.coerceIn(29, 88)
            0.10f + 0.15f * (88 - n) / 59f
        }
    }

    /**
     * Default free (undamped) T60 `T60f(stop, key)` in seconds (PLAN §3.7), shipped per stop in
     * `BankInfo.freeT60`: grand `min(30, 6.24·10^(−0.0275(n−60)))`, upright grand × 0.8,
     * harpsichord 8′ `20·(f/f29)^−0.45`, 4′ `0.8·20·(2f/f29)^−0.45`. Off-thread only.
     */
    fun defaultFreeT60(stop: Int, key: Int): Float = when (id) {
        InstrumentId.GRAND -> grandFreeT60(key)
        InstrumentId.UPRIGHT -> 0.8f * grandFreeT60(key)
        InstrumentId.HARPSICHORD -> {
            val ratio = 2.0.pow((key - 29) / 12.0)            // f / f29
            if (stop == HK.STOP_4FT) (0.8 * 20.0 * (2.0 * ratio).pow(-0.45)).toFloat()
            else (20.0 * ratio.pow(-0.45)).toFloat()
        }
    }

    override fun toString(): String = "InstrumentProfile(${id.key}, $lowKey..$highKey, lastDamper=$lastDamper)"

    companion object {
        private fun grandDamperT60(key: Int): Float {
            val n = key.coerceIn(21, 88)
            val x = (88 - n) / 67f
            return 0.12f + 1.2f * x * x
        }

        private fun grandFreeT60(key: Int): Float =
            minOf(30.0, 6.24 * 10.0.pow(-0.0275 * (key - 60))).toFloat()

        /** Values in PLAN §3.7 table "Profiles". */
        val GRAND: InstrumentProfile = InstrumentProfile(
            id = InstrumentId.GRAND, displayName = "Grand",
            lowKey = 21, highKey = 108, lastDamper = 88,
            keyDipMm = 10.16f, keyReturnMs = 35f, repeatMinMs = 67f, partialReturnMs = 12f,
            blowMm = 47f, letOffMm = 1.5f, checkMm = 15f, actionRatio = 5.0f, travelScale = 1.0f,
            usesSustain = true, usesSostenuto = true, softKind = SoftKind.UNA_CORDA,
            legatoHold = false, velocityGain = true, stops = 1,
            restrikeTauUpMs = 60f, restrikeTauPedalMs = 200f, defaultTuning = TuningSpec.A440_EQUAL)

        val UPRIGHT: InstrumentProfile = InstrumentProfile(
            id = InstrumentId.UPRIGHT, displayName = "Upright",
            lowKey = 21, highKey = 108, lastDamper = 90,
            keyDipMm = 10.0f, keyReturnMs = 50f, repeatMinMs = 143f, partialReturnMs = 40f,
            blowMm = 47f, letOffMm = 3.2f, checkMm = 16f, actionRatio = 4.9f, travelScale = 1.05f,
            usesSustain = true, usesSostenuto = false, softKind = SoftKind.HAMMER_RAIL,
            legatoHold = false, velocityGain = true, stops = 1,
            restrikeTauUpMs = 60f, restrikeTauPedalMs = 200f, defaultTuning = TuningSpec.A440_EQUAL)

        /** Blow/let-off/check do not apply (0); the jack moves 1.0 × the key; timing is HarpsiTiming's. */
        val HARPSICHORD: InstrumentProfile = InstrumentProfile(
            id = InstrumentId.HARPSICHORD, displayName = "Harpsichord · Bach era",
            lowKey = 29, highKey = 89, lastDamper = 127,
            keyDipMm = 6.0f, keyReturnMs = 55f, repeatMinMs = 60f, partialReturnMs = 30f,
            blowMm = 0f, letOffMm = 0f, checkMm = 0f, actionRatio = HarpsiTiming.JACK_RATIO, travelScale = 1.0f,
            usesSustain = false, usesSostenuto = false, softKind = SoftKind.NONE,
            legatoHold = true, velocityGain = false, stops = 2,
            restrikeTauUpMs = 30f, restrikeTauPedalMs = 30f, defaultTuning = TuningSpec.A415_WERCKMEISTER)

        fun of(id: InstrumentId): InstrumentProfile = when (id) {
            InstrumentId.GRAND -> GRAND
            InstrumentId.UPRIGHT -> UPRIGHT
            InstrumentId.HARPSICHORD -> HARPSICHORD
        }
    }
}
