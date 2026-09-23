package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.SampleReader
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import com.tropicalstream.hammerklavier.contract.stub.StubScoreCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** T2.2: the voice pool, stealing, kill slots and the noise pool (PLAN §3.14, R22, R23). */
class VoicePoolTest {
    // ── StealPolicy (pure) ──

    private fun voice(i: Int, state: Int, key: Int, levelDb: Float, ageFrames: Long, now: Long, role: Int = Voice.ROLE_MAIN): Voice =
        Voice(i).also { it.state = state; it.role = role; it.key = key; it.levelDb = levelDb; it.startedOut = now - ageFrames; it.onsetAt = 0L }

    @Test fun victimsGoFadingThenDampingThenPedalHeldThenKeyDown() {
        val now = 480_000L
        val down = BooleanArray(HK.KEYS); val damping = FloatArray(HK.KEYS)
        down[40] = true                         // key down
        damping[50] = 0.7f                      // damping
        // key 60: up, D = 0 (pedal-held); key 70: a fading re-strike
        val vs = arrayOf(voice(0, Voice.PLAYING, 40, -30f, 48_000, now), voice(1, Voice.PLAYING, 50, -30f, 48_000, now),
            voice(2, Voice.PLAYING, 60, -30f, 48_000, now), voice(3, Voice.FADING, 70, -30f, 48_000, now))
        val order = ArrayList<Int>()
        repeat(4) {
            val i = StealPolicy.pick(vs, vs.size, now, 0L, down, damping, 48_000)
            order.add(vs[i].key); vs[i].state = Voice.IDLE
        }
        assertEquals(listOf(70, 50, 60, 40), order)
        assertEquals(-1, StealPolicy.pick(vs, vs.size, now, 0L, down, damping, 48_000))
    }

    @Test fun louderAndYoungerVoicesSurviveAndUnder50msOnlyWhenNothingElse() {
        val now = 480_000L
        val down = BooleanArray(HK.KEYS); val damping = FloatArray(HK.KEYS) { 1f }
        val vs = arrayOf(voice(0, Voice.PLAYING, 40, -10f, 96_000, now), voice(1, Voice.PLAYING, 41, -30f, 96_000, now),
            voice(2, Voice.PLAYING, 42, -30f, 4800, now), voice(3, Voice.FADING, 43, -70f, 1000, now))  // 3: young
        // scores: 0: −10 − 20 − 4 = −34; 1: −30 − 20 − 4 = −54; 2: −30 − 20 − 0.2 = −50.2; 3 (young): −70 − 40
        assertEquals(1, StealPolicy.pick(vs, vs.size, now, 0L, down, damping, 48_000))
        vs[1].state = Voice.IDLE
        assertEquals(2, StealPolicy.pick(vs, vs.size, now, 0L, down, damping, 48_000))
        vs[2].state = Voice.IDLE
        assertEquals(0, StealPolicy.pick(vs, vs.size, now, 0L, down, damping, 48_000))              // the young one still waits
        vs[0].state = Voice.IDLE
        assertEquals(3, StealPolicy.pick(vs, vs.size, now, 0L, down, damping, 48_000))              // only the young one is left
        // Pending, pre-onset, kill and noise voices are never victims.
        val others = arrayOf(voice(0, Voice.PENDING, 40, -100f, 96_000, now), voice(1, Voice.PLAYING, 41, -100f, 96_000, now).also { it.onsetAt = 5L },
            voice(2, Voice.KILL, 42, -100f, 96_000, now, Voice.ROLE_KILL), voice(3, Voice.RELEASE_NOISE, 43, -100f, 96_000, now, Voice.ROLE_NOISE))
        assertEquals(-1, StealPolicy.pick(others, others.size, now, 0L, down, damping, 48_000))
    }

    // ── the storm ──

    /** 30 smooth regions (7 Hz, 5 ms raised-cosine attack, −24 dBFS, 1.2 s decay): only a click can make a large step. */
    private class SmoothBank(seconds: Int = 2) : LoadedBank {
        private val n = seconds * HK.SR
        private val waves = Array(SineBank.ROOTS) { i ->
            val w = ShortArray(2 * n)
            for (t in 96 until n) {
                val u = (t - 96).toDouble() / HK.SR
                var a = 0.063 * exp(-u / 1.2)
                if (t - 96 < 240) a *= 0.5 - 0.5 * cos(PI * (t - 96) / 240)
                if (n - 1 - t < 2400) a *= (n - 1 - t) / 2400.0
                val s = (a * 32767 * sin(2 * PI * 7.0 * u + 2 * PI * i / 30.0 + 1.0)).roundToInt().toShort()
                w[2 * t] = s; w[2 * t + 1] = s
            }
            w
        }
        private val envs = waves.map { TestBank.envOf(it) }
        private val sine = SineBank(layers = 1)
        override val info: BankInfo = sine.info
        override val generation: Int = 3
        override val regionCount: Int = SineBank.ROOTS
        override fun frames(region: Int): Int = n
        override fun onsetFrame(region: Int): Int = 96
        override fun thrFrame(region: Int): Int = 100
        override fun envByte(region: Int, tenMs: Int): Int = envs[region].let { if (tenMs < 0 || tenMs >= it.size) 255 else it[tenMs] }
        override fun newReader(): SampleReader = object : SampleReader {
            override fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int): Int {
                val w = waves[region]; val nf = w.size / 2; var c = 0
                for (i in 0 until frames) { val f = fromFrame + i
                    if (f in 0 until nf) { dst[dstOff + 2 * i] = w[2 * f]; dst[dstOff + 2 * i + 1] = w[2 * f + 1]; c++ } else { dst[dstOff + 2 * i] = 0; dst[dstOff + 2 * i + 1] = 0 } }
                return c
            }
            override fun prefetch(region: Int, fromFrame: Int, frames: Int) {}
            override val slowReads: Int get() = 0
        }
        @Volatile override var readyMask: Long = -1L
    }

    private fun storm(cap: Int, bank: LoadedBank, seconds: Int): Triple<Harness, Float, AudioStats> {
        val h = Harness(bank = bank, keyMap = KeyMapFixtures.forSineBank(1))
        h.cmd(Cmd.QUALITY, ref = QualityLadder.of(0, cap))
        val perf = StubScoreCompiler().synthetic(SyntheticScore.CHORD_STORM_64, InstrumentProfile.GRAND, 1)
        var maxMain = 0; var maxPerKey = 0; var maxKill = 0
        h.onBlock = { _ ->
            maxMain = maxOf(maxMain, h.core.pool.mainActive); maxKill = maxOf(maxKill, h.core.pool.killActive)
            val perKey = IntArray(HK.KEYS)
            for (v in h.core.pool.voices) if (v.state != Voice.IDLE && v.role == Voice.ROLE_MAIN) perKey[v.key]++
            maxPerKey = maxOf(maxPerKey, perKey.max())
        }
        h.play(perf)
        h.renderTo(seconds * HK.SR)
        assertTrue("cap $cap exceeded: $maxMain", maxMain <= cap)
        assertTrue("more than 3 voices on a key: $maxPerKey", maxPerKey <= 3)
        assertTrue("kill slots $maxKill > ${cap / 2}", maxKill <= cap / 2)
        // Largest sample-to-sample step outside the chords' own attacks (onset .. +5 ms).
        val excluded = BooleanArray(h.frames)
        for (c in 0 until 720) {
            val on = ((0.4 + 0.25 * c) * HK.SR).roundToInt()
            for (i in on - 2..on + 250) if (i in excluded.indices) excluded[i] = true
        }
        var maxStep = 0f
        for (i in 1 until h.frames) if (!excluded[i]) maxStep = maxOf(maxStep, abs(h.left(i) - h.left(i - 1)), abs(h.right(i) - h.right(i - 1)))
        val st = AudioStats(); h.core.stats(st)
        return Triple(h, maxStep, st)
    }

    @Test fun theStormStealsAheadWithoutClicksOrDrops() {
        for (cap in intArrayOf(64, 128)) {
            val (_, step, st) = storm(cap, SmoothBank(), 180)
            assertTrue("cap $cap: max step $step FS", step <= 0.02f)
            assertEquals("cap $cap dropped", 0, st.dropped)
            assertTrue("cap $cap: nothing was stolen", st.stolen > 0)
            assertEquals(cap, st.voiceCap)
        }
    }

    @Test fun theSineBankStormKeepsTheInvariantsAndIsWrittenForListening() {
        val (h, _, st) = storm(64, SineBank(layers = 1), 20)
        assertEquals(0, st.dropped)
        h.core.on(Cmd.PAUSE, 60L, 0f, null)
        OfflineRender.writeWav("storm64_cap64_sine", h.out.copyOf(2 * h.frames).also { a -> for (i in a.indices) a[i] *= 0.05f })
    }

    @Test fun noisesNeverStealMusicVoices() {
        val km = KeyMapFixtures.forSineBank(1).let { it.copy(pedalDown = intArrayOf(10), pedalUp = intArrayOf(11), pedalGain = 0.5f) }
        val h = Harness(bank = SmoothBank(seconds = 12), keyMap = km)
        h.cmd(Cmd.QUALITY, ref = QualityLadder.of(0, 64))
        // 64 held notes (12 s regions) fill the cap; the pedal then goes down and up 20 times (40 noises of 2 s each).
        val held = (0 until 64).map { N(1000.0, 6000.0, 21 + it, 90) }
        val pts = ArrayList<Pair<Long, Float>>()
        for (i in 0 until 40) pts.add((1_200_000L + 200_000L * i) to (if (i % 2 == 0) 1f else 0f))
        h.play(perfSong(held, sustainSong = stepCurve(*pts.toTypedArray())))
        var noisesSeen = 0
        h.onBlock = { b ->
            noisesSeen = maxOf(noisesSeen, h.core.pool.noiseActive)
            if (b > 1100 * 48 && b < 5990 * 48) assertEquals("block $b", 64, h.core.pool.mainActive)   // no music voice ever taken
        }
        h.renderTo(5990 * 48)
        assertEquals(HK.NOISE_SLOTS, noisesSeen)                     // the noise pool filled up and never overflowed
        for (k in 21 until 85) assertEquals(1, h.voicesOf(k).size)
    }
}
