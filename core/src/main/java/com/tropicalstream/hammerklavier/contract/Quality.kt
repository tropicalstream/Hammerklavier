package com.tropicalstream.hammerklavier.contract

import kotlin.math.roundToInt

/** One rung of the quality ladder (PLAN §5.11). Immutable; crosses threads by reference. */
class QualityProfile(val level: Int, val frameDivider: Int /* 0 = display rest */, val roomCap: RoomLevel,
    val mirrorFlames: Boolean, val crystals: Int, val brightnessCap: Float /* window screenBrightness; -1 = system */,
    val voiceCap: Int, val hermite: Boolean, val spectralDamping: Boolean, val dispersion: Boolean, val combs: Int, val fdnLines: Int,
    val voicingAllowed: Boolean) {
    override fun toString(): String = "Q$level(div=$frameDivider room=$roomCap voices=$voiceCap combs=$combs fdn=$fdnLines)"
}

/**
 * The §5.11 table. [of] takes the Q0 voice cap C measured by EngineBench (§3.14):
 * Q1 0.75 C (≥ 48), Q2 0.625 C (≥ 40), Q3 0.5 C (≥ 32). Q3 is display rest: divider 0, GL paused.
 * The enter/relax temperatures live in `system/ThermalPolicy`.
 */
object QualityLadder {
    const val MAX = 3

    fun of(level: Int, q0Cap: Int): QualityProfile {
        val c = q0Cap.coerceIn(HK.VOICE_CAP_MIN, HK.VOICE_CAP_MAX)
        return when (level.coerceIn(0, MAX)) {
            0 -> QualityProfile(level = 0, frameDivider = 2, roomCap = RoomLevel.SALON, mirrorFlames = true, crystals = 160,
                brightnessCap = -1f, voiceCap = c, hermite = true, spectralDamping = true, dispersion = true,
                combs = 88, fdnLines = 8, voicingAllowed = true)
            1 -> QualityProfile(level = 1, frameDivider = 2, roomCap = RoomLevel.SALON, mirrorFlames = false, crystals = 60,
                brightnessCap = -1f, voiceCap = maxOf(48, (0.75f * c).roundToInt()), hermite = true, spectralDamping = true,
                dispersion = true, combs = 88, fdnLines = 8, voicingAllowed = false)
            2 -> QualityProfile(level = 2, frameDivider = 3, roomCap = RoomLevel.STAGE, mirrorFlames = false, crystals = 0,
                brightnessCap = 0.6f, voiceCap = maxOf(40, (0.625f * c).roundToInt()), hermite = false, spectralDamping = false,
                dispersion = false, combs = 44, fdnLines = 8, voicingAllowed = false)
            else -> QualityProfile(level = 3, frameDivider = 0, roomCap = RoomLevel.STAGE, mirrorFlames = false, crystals = 0,
                brightnessCap = 0.6f, voiceCap = maxOf(32, (0.5f * c).roundToInt()), hermite = false, spectralDamping = false,
                dispersion = false, combs = 0, fdnLines = 4, voicingAllowed = false)
        }
    }
}
