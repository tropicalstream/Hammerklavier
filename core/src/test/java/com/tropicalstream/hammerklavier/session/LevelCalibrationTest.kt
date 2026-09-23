package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.EngineCoreApi
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.MixSettings
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.ScoreSpec
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.contract.stub.FixedRoom
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.PerfFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import com.tropicalstream.hammerklavier.contract.stub.SineCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * T12.8 (M5): the v127 C-major triad at C4 with the pedal down must peak at −6 ± 2 dBFS before
 * the limiter in the Player design; the measured masterDb becomes the Settings default
 * ([SessionKeys.DEFAULT_MASTER_DB]). The measurement harness ([LevelProbe]) is exercised now
 * against SineCore; the real test needs WP2's EngineCore, WP3's DspFactory and RoomAcoustics, and
 * WP11's exported test regions (§6.5 step 13).
 */
class LevelCalibrationTest {

    /** Drives any EngineCoreApi through its command handler and measures the output peak. */
    object LevelProbe {
        /** The §3.13 calibration score: C4 E4 G4 at v127, 3 s, sustain pedal down throughout. */
        fun triad(profile: InstrumentProfile, generation: Int = 1): Performance {
            val keys = byteArrayOf(60, 64, 67)
            val spec = ScoreSpec(onUs = LongArray(3), offUs = LongArray(3) { 3_000_000L }, key = keys,
                vel = ByteArray(3) { 127 }, cc64 = PedalCurve(longArrayOf(0L, 4_000_000L), floatArrayOf(1f, 1f)),
                cc66 = PedalCurve.EMPTY, cc67 = PedalCurve.EMPTY)
            return PerfFixtures.build(notes = spec, sustain = spec.cc64, profile = profile, generation = generation,
                id = "calibration:triad", title = "calibration")
        }

        /**
         * [preLimiterPeak] reads the linear peak before the limiter since the last call (WP3's MasterProcessor tap,
         * docs/requests/WP12.md item 5). Without it the probe measures the post-limiter output, which T12.8 must
         * never use: that needs [allowPostLimiter] = true and is only for exercising the harness.
         */
        fun peakDbfs(core: EngineCoreApi, bank: LoadedBank, keyMap: KeyMap, profile: InstrumentProfile, room: RoomDesign,
                     masterDb: Float, seconds: Float = 4f, preLimiterPeak: ((EngineCoreApi) -> Float)? = null,
                     allowPostLimiter: Boolean = false): Float {
            require(preLimiterPeak != null || allowPostLimiter) { "T12.8 needs the pre-limiter tap; the core has none" }
            val prepared = core.prepareBank(bank, keyMap, profile)
            core.on(Cmd.SET_BANK, 0L, 0f, prepared)
            core.on(Cmd.SET_KEYMAP, 0L, 0f, core.prepareKeyMap(keyMap, bank.info, profile))
            core.on(Cmd.ROOM, 0L, 0f, room)
            core.on(Cmd.MIX, 0L, 0f, MixSettings(ReverbMode.ROOM, ResonanceMode.NATURAL, SpeakerBass.OFF, masterDb))
            core.on(Cmd.SET_PERF, 0L, 1f, triad(profile))
            val out = FloatArray(2 * HK.BLOCK)
            var peak = 0f
            val blocks = (seconds * HK.SR / HK.BLOCK).toInt()
            for (b in 0 until blocks) {
                core.render(out, b.toLong() * HK.BLOCK)
                if (preLimiterPeak != null) { val a = preLimiterPeak(core); if (a > peak) peak = a }
                else for (x in out) { val a = abs(x); if (a > peak) peak = a }
            }
            return if (peak <= 0f) -200f else (20.0 * log10(peak.toDouble())).toFloat()
        }
    }

    @Test fun probeMeasuresAnEngineAndTheTriadIsWellFormed() {
        val p = LevelProbe.triad(InstrumentProfile.GRAND)
        assertEquals(3, p.noteCount)
        assertEquals(HK.PRE_ROLL_US, p.onUs[0])
        val bank = SineBank(layers = 2)
        val km = KeyMapFixtures.forSineBank(layers = 2, mode = KeyMapFixtures.Mode.HARD, stops = 1, readyMask = bank.readyMask)
        val db = LevelProbe.peakDbfs(SineCore(), bank, km, InstrumentProfile.GRAND, FixedRoom.PLAYER, 0f, allowPostLimiter = true)
        assertTrue("SineCore produced sound: $db dBFS", db > -60f && db < 12f)
    }

    @Test fun probeRefusesToMeasureWithoutAPreLimiterTap() {
        val bank = SineBank(layers = 2)
        val km = KeyMapFixtures.forSineBank(layers = 2, mode = KeyMapFixtures.Mode.HARD, stops = 1, readyMask = bank.readyMask)
        val e = runCatching { LevelProbe.peakDbfs(SineCore(), bank, km, InstrumentProfile.GRAND, FixedRoom.PLAYER, 0f) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Ignore("needs WP2 EngineCore + WP3 DspFactory/RoomAcoustics + wp11 exported test regions (M5)")
    @Test fun triadPeaksAtMinus6dBfsPreLimiterInThePlayerDesign() {
        // At M5: core = EngineCore(DspFactory.create(HK.SR), VoiceCursorBoard(), HeadPose()); bank = the exported
        // C3/C4/C5 regions; room = RoomAcoustics.design(KonzertzimmerAcoustics.GEOMETRY, grand placement,
        // source, ListenerRooms.resolve(GRAND, anchors, placement, PLAYER, 0).pose, ROOM, bench, embeddedRoomDb);
        // the pre-limiter tap comes from WP3's MasterProcessor (docs/requests/WP12.md).
        // masterDb = DEFAULT_MASTER_DB + (−6 − measured); assert −6 ± 2 at that masterDb; record it in SessionKeys.
        throw AssertionError("not runnable before M5")
    }
}
