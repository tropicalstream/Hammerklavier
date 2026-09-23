package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.CoreClockState
import com.tropicalstream.hammerklavier.contract.DspSet
import com.tropicalstream.hammerklavier.contract.EngineCoreApi
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.MixSettings
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.SampleReader
import com.tropicalstream.hammerklavier.contract.SoftKind
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import kotlin.math.sqrt

/**
 * The prepared key-map token of [EngineCore.prepareKeyMap] (any thread): the key map, its damper
 * and spectral tables, the seam low-pass indices and the resonance processor's prepared tables.
 */
class PreparedKeyMap internal constructor(
    val keyMap: KeyMap, val info: BankInfo, val profile: InstrumentProfile,
    internal val dm: DamperModel, internal val seamIdx: FloatArray, internal val resonance: Any)

/** The prepared bank token of [EngineCore.prepareBank] (any thread): the bank, its HKAudio reader and its key map. */
class PreparedBank internal constructor(
    val bank: LoadedBank, internal val reader: SampleReader, val keyMap: PreparedKeyMap, val profile: InstrumentProfile)

/**
 * WP2's engine (PLAN §2.5, §3.5–§3.10, §3.14, §3.15): the per-block render graph on HKAudio.
 *
 * **Clocks.** The song position is kept in 32.32 fixed-point song frames and advances by exactly
 * `256 · rateFixed` per playing block (drift-free, R9). Scheduling uses **play frames** — output
 * frames counted only while playing — so a pause freezes every pending countdown (damper landings
 * count playing frames only, §2.5), and inside a playing block play frame `pf + i` is output frame
 * `outFrame + i`.
 *
 * **Events.** At the start of each playing block the [Sequencer] dispatches every event less than
 * `BLOCK + LOOK_FRAMES` output frames ahead at its exact offset `k = round((E − S₀) / r)`: a
 * note-on becomes PENDING voices that start reading at `k − onsetOut` (4′: minus the stagger) so the
 * sampled attack lands on the event frame; everything else (key-up, latch, pedal noise, end, and
 * each note's own key-down) is queued in [PendingEvents] and applied in the block that contains
 * its frame. Voices dispatched after their start frame skip into the region by the missing frames,
 * so the onset still lands exactly (after a resume, a seek or a late retry).
 *
 * **Transport across pending work (R5).** PAUSE, SEEK, SET_PERF, SET_BANK (and RATE) return every
 * voice that has not reached its sampled onset to IDLE without a fade and drop the queued state
 * events; PAUSE, SET_BANK and RATE also rewind the cursor to the lowest event not yet in effect
 * and mark the note-ons already in effect to be skipped once. Allocation-free after construction
 * (T2.10); no transcendental maths on the render path (tables from [DecayTables] and
 * [DamperModel], prepared off-thread).
 *
 * Frozen constructor (§2.3): `EngineCore(dsp, cursors, head, sampleRate = HK.SR)`.
 */
class EngineCore(private val dsp: DspSet, private val cursors: VoiceCursorBoard, private val head: HeadPose,
                 private val sampleRate: Int = HK.SR) : EngineCoreApi, Dispatcher {

    internal val pool = VoicePool(sampleRate, cursors)
    internal val keys = KeyState()
    internal val seq = Sequencer(sampleRate)
    internal val events = PendingEvents()

    /** The bench run by `Cmd.BENCH` in ≈ 2 ms slices per block; its [EngineBench.result] is read by the platform. */
    val bench = EngineBench(sampleRate)
    /** The CPU frequency (MHz) the platform read during the bench (for the 2.0 GHz normalisation). */
    @Volatile var benchCpuMhz = 2000

    // ── bank and key map ──
    private var bankTok: PreparedBank? = null
    private var kmTok: PreparedKeyMap? = null
    private var profile: InstrumentProfile = InstrumentProfile.GRAND

    // ── transport ──
    private var perf: Performance? = null
    private var pos = 0L                              // song frames, 32.32
    private var rate = 1f
    private var rateFixed = 1L shl 32
    private var rateD = 1.0                           // rateFixed / 2^32
    internal var playing = false
    private var epoch = 0
    private var generation = -1
    private var registration = HK.REG_8 or HK.REG_4
    private var endPending = false
    private var endStartOut = 0L
    private var endedGeneration = -1
    /** Play frame at the start of the next block. */
    internal var pf = 0L
    /** Output frame at the start of the next block (frames rendered since construction or reset). */
    internal var outFrame = 0L

    // ── quality, mix, route ──
    private var qualityCap = HK.VOICE_CAP_MIN
    private var protectCap = HK.VOICE_CAP_MAX
    private var hermite = true
    private var spectralAllowed = true
    private var combs = 88
    private var dispersion = true
    private var resonanceMode = ResonanceMode.NATURAL
    private var releaseNoises = true
    private var pedalNoises = true
    private var masterLin = 1f
    private var duck = 1f
    private var latencySec = 0.14f
    private var roomRestoreBlocks = 0

    // ── block buffers ──
    private val dryL = FloatArray(HK.BLOCK); private val dryR = FloatArray(HK.BLOCK)
    private val softL = FloatArray(HK.BLOCK); private val softR = FloatArray(HK.BLOCK)
    private val wetL = FloatArray(HK.BLOCK); private val wetR = FloatArray(HK.BLOCK)
    private val mix = FloatArray(HK.BLOCK)
    internal val self = FloatArray(HK.LANES * HK.BLOCK)
    internal val selfRows = BooleanArray(HK.LANES)
    private val laneMs = FloatArray(HK.LANES)
    private val combMs = FloatArray(HK.LANES)
    private val lanes = FloatArray(HK.LANES)
    private val state = CoreClockState()
    private var laneEpoch = 0

    private val susCursor = PedalCurve.Cursor()
    private var pSus = 0f
    private var pedalDownRr = 0
    private var pedalUpRr = 0

    // Scratch for one note's voices (≤ 2 stops × 2 layers).
    private val nsStop = IntArray(4); private val nsSk = IntArray(4); private val nsW = FloatArray(4); private val nsOnset = LongArray(4)

    // debugOnset (T-ALIGN): the newest isolated onset.
    private var dbgScheduled = -1L
    private var dbgDetected = -1L
    private var dbgArmedOut = -1L
    private var dbgThreshold = 0f

    init {
        dsp.resonance.setMode(resonanceMode, profile.id, combs, dispersion)
        dsp.soft.configure(profile.softKind)
        keys.configure(profile)
    }

    // ═══════════ preparation (any thread) ═══════════

    override fun prepareBank(bank: LoadedBank, keyMap: KeyMap, profile: InstrumentProfile): Any =
        PreparedBank(bank = bank, reader = bank.newReader(), keyMap = prepareKeyMap(keyMap, bank.info, profile) as PreparedKeyMap, profile = profile)

    override fun prepareKeyMap(keyMap: KeyMap, info: BankInfo, profile: InstrumentProfile): Any {
        val dm = DamperModel(keyMap, info, profile, sampleRate)
        val seam = FloatArray(keyMap.lpHz.size) { if (keyMap.lpHz[it] > 0f) DecayTables.lpIndexOf(keyMap.lpHz[it].toDouble()) else -1f }
        val res = dsp.resonance.prepare(keyMap, info, profile)
        return PreparedKeyMap(keyMap = keyMap, info = info, profile = profile, dm = dm, seamIdx = seam, resonance = res)
    }

    // ═══════════ commands (HKAudio, drained at the block boundary) ═══════════

    override fun on(code: Int, l: Long, f: Float, ref: Any?) {
        when (code) {
            Cmd.SET_BANK -> if (ref is PreparedBank) setBank(ref)
            Cmd.SET_KEYMAP -> if (ref is PreparedKeyMap) { kmTok = ref; dsp.resonance.apply(ref.resonance, 200) }
            Cmd.SET_PERF -> setPerf(ref as? Performance, l, f >= 0.5f)
            Cmd.PLAY -> play()
            Cmd.PAUSE -> pause(if (l > 0) l.toInt() else 60)
            Cmd.SEEK -> seek(l)
            Cmd.RATE -> setRate(f)
            Cmd.QUALITY -> if (ref is QualityProfile) setQuality(ref)
            Cmd.ROOM -> if (ref is RoomDesign) dsp.room.setDesign(ref, l.toInt())
            Cmd.MIX -> if (ref is MixSettings) setMix(ref)
            Cmd.ROUTE -> {
                val r = l.toInt()
                if (r in 0..2) dsp.master.setRoute(ROUTES[r])
                latencySec = if (f > 0f) f / 1000f else latencySec
            }
            Cmd.DUCK -> { duck = f; dsp.master.setGain(masterLin * duck) }
            Cmd.REGISTRATION -> registration = l.toInt() and (HK.REG_8 or HK.REG_4)
            Cmd.VOICE_CAP -> protectCap = l.toInt().coerceIn(8, HK.VOICE_CAP_MAX)
            Cmd.BENCH -> bench.begin(if (l > 0) l.toFloat() else 2f, dsp, benchCpuMhz)
            Cmd.RESET -> reset()
            else -> {}
        }
    }

    private fun setBank(tok: PreparedBank) {
        if (playing) rewindForTransport()
        dropNotYetHeard()
        pool.fadeAll(ms(30))
        bankTok = tok
        kmTok = tok.keyMap
        profile = tok.profile
        keys.configure(profile)
        dsp.resonance.apply(tok.keyMap.resonance, 0)
        dsp.resonance.setMode(resonanceMode, profile.id, combs, dispersion)
        dsp.soft.configure(profile.softKind)
        epoch++
    }

    private fun setPerf(p: Performance?, startUs: Long, autoPlay: Boolean) {
        val target = if (startUs < 0) songUs() else startUs
        dropNotYetHeard()
        pool.fadeAll(ms(30))
        perf = p
        generation = p?.generation ?: -1
        setPos(target)
        seq.bind(p, target)
        susCursor.bind(p?.sustain ?: PedalCurve.EMPTY); susCursor.seek(target)
        keys.rebuild(p, target)
        epoch++
        endPending = false
        playing = p != null && autoPlay
        if (playing) { dsp.room.setInputGain(1f, 10f) }
    }

    private fun play() {
        if (perf == null || playing) return
        playing = true
        pool.transportFade(1f, ms(60))
        dsp.room.setInputGain(1f, 60f)
    }

    private fun pause(fadeMs: Int) {
        if (!playing) {
            pool.transportFade(0f, ms(fadeMs.coerceAtLeast(1)))
            return
        }
        rewindForTransport()
        dropNotYetHeard()
        playing = false
        pool.transportFade(0f, ms(fadeMs.coerceAtLeast(1)))
        dsp.room.setInputGain(0f, 60f)
    }

    private fun seek(us: Long) {
        dropNotYetHeard()
        seq.clearSkip()
        pool.fadeAll(ms(10))
        dsp.resonance.reset()
        dsp.room.setInputGain(0f, 10f)
        roomRestoreBlocks = 2
        setPos(us)
        seq.seek(us)
        susCursor.seek(us)
        keys.rebuild(perf, us)
        epoch++
        endPending = false
    }

    private fun setRate(r: Float) {
        val nr = r.coerceIn(0.5f, 1.5f)
        if (nr == rate) return
        if (playing) { rewindForTransport(); dropNotYetHeard() }
        rate = nr
        rateFixed = Math.round(nr.toDouble() * TWO32)
        rateD = rateFixed / TWO32
    }

    private fun setQuality(q: QualityProfile) {
        qualityCap = q.voiceCap.coerceIn(8, HK.VOICE_CAP_MAX)
        hermite = q.hermite
        spectralAllowed = q.spectralDamping
        combs = q.combs
        dispersion = q.dispersion
        dsp.room.setLines(q.fdnLines)
        dsp.resonance.setMode(resonanceMode, profile.id, combs, dispersion)
    }

    private fun setMix(m: MixSettings) {
        resonanceMode = m.resonance
        releaseNoises = m.releaseNoises
        pedalNoises = m.pedalNoises
        masterLin = DecayTables.db2lin(m.masterDb)
        dsp.master.setGain(masterLin * duck)
        dsp.master.setSpeakerBass(m.speakerBass)
        dsp.resonance.setMode(resonanceMode, profile.id, combs, dispersion)
    }

    override fun reset() {
        pool.clearAll()
        events.clear()
        seq.bind(null, 0L)
        keys.clear()
        perf = null; generation = -1; playing = false; pos = 0L; pf = 0L
        endPending = false; endedGeneration = -1
        susCursor.bind(PedalCurve.EMPTY)
        dsp.resonance.reset(); dsp.room.reset(); dsp.soft.reset(); dsp.master.reset()
        java.util.Arrays.fill(lanes, 0f)
        java.util.Arrays.fill(selfRows, false); java.util.Arrays.fill(self, 0f)
        epoch++
        pool.peak = 0; pool.stolen = 0; seq.dropped = 0
    }

    /** Cap in effect: the quality ladder's, lowered by AudioOutput's self-protection. */
    internal val cap: Int get() = if (protectCap < qualityCap) protectCap else qualityCap

    // ═══════════ transport helpers ═══════════

    /** Rewinds the cursor to the lowest event not yet in effect; note-ons in effect at or after it are skipped once. */
    private fun rewindForTransport() {
        var m = seq.cursor
        val e = events.minEvIndex(); if (e < m) m = e
        val q = seq.minPendingEv(); if (q < m) m = q
        val vs = pool.voices
        for (i in 0 until VoicePool.TOTAL) {
            val v = vs[i]
            if (v.state == Voice.IDLE || v.role != Voice.ROLE_MAIN || v.evIndex < 0) continue
            if (v.eventAt >= pf && v.evIndex < m) m = v.evIndex
        }
        seq.clearSkip()
        for (i in 0 until VoicePool.TOTAL) {
            val v = vs[i]
            if (v.state == Voice.IDLE || v.state == Voice.PENDING || v.role != Voice.ROLE_MAIN || v.evIndex < 0) continue
            if (v.eventAt < pf && v.evIndex >= m) seq.addSkip(v.note)
        }
        seq.cursor = m
    }

    /** Voices that have not reached their sampled onset go IDLE without a fade; queued state events are dropped. */
    private fun dropNotYetHeard() {
        val vs = pool.voices
        for (i in 0 until VoicePool.TOTAL) {
            val v = vs[i]
            if (v.state == Voice.IDLE || v.role == Voice.ROLE_KILL) continue
            if (v.state == Voice.PENDING || v.onsetAt >= pf) pool.toIdle(v)
        }
        seq.clearPending()
        events.clear()
    }

    private fun setPos(us: Long) {
        val u = if (us < 0) 0L else us
        pos = Math.round(u.toDouble() * sampleRate / 1e6 * TWO32)
    }

    private fun songFrames(): Double = pos / TWO32
    private fun songUs(): Long = (songFrames() * 1e6 / sampleRate).toLong()
    private fun ms(m: Int): Int = (m * sampleRate / 1000).coerceAtLeast(1)

    // ═══════════ Dispatcher (called by the Sequencer inside render) ═══════════

    override fun stateEvent(type: Int, arg: Int, evIndex: Int, framePf: Long) = events.add(framePf, type, arg, evIndex)

    override fun noteOn(note: Int, evIndex: Int, eventPf: Long): Boolean {
        val bt = bankTok ?: return true
        val km = kmTok ?: return true
        val p = perf ?: return true
        if (eventPf + LATE_DROP_FRAMES < pf) { seq.dropped++; return true }     // a retry that came far too late
        val map = km.keyMap
        val dm = km.dm
        val key = p.key[note].toInt() and 0x7F
        val vel = p.vel[note].toInt() and 0x7F
        val stops = minOf(map.stops, bt.bank.info.stops).coerceAtLeast(1)
        val layers = map.layers
        var n = 0
        for (s in 0 until stops) {
            if (stops >= 2) {
                if (s == HK.STOP_MAIN && registration and HK.REG_8 == 0) continue
                if (s == HK.STOP_4FT && registration and HK.REG_4 == 0) continue
            }
            val onset = if (s == HK.STOP_4FT) eventPf - dm.staggerFrames[vel] else eventPf
            val la = map.velLayerA[vel].toInt()
            if (la >= 0 && map.velGainA[vel] > 0f) {
                val sk = (s * layers + la) * HK.KEYS + key
                if (map.region[sk] >= 0) { nsStop[n] = s; nsSk[n] = sk; nsW[n] = map.velGainA[vel]; nsOnset[n] = onset; n++ }
            }
            val lb = map.velLayerB[vel].toInt()
            if (lb >= 0 && map.velGainB[vel] > 0f) {
                val sk = (s * layers + lb) * HK.KEYS + key
                if (map.region[sk] >= 0) { nsStop[n] = s; nsSk[n] = sk; nsW[n] = map.velGainB[vel]; nsOnset[n] = onset; n++ }
            }
        }
        if (n == 0) return true

        // At most 3 voices per key: the oldest goes to a kill slot.
        val c = cap
        val killCap = c / 2
        var existing = pool.countKey(key)
        while (existing + n > MAX_PER_KEY) {
            val o = pool.oldestOfKey(key) ?: break
            if (o.state == Voice.PENDING || o.onsetAt >= pf) pool.toIdle(o)
            else if (pool.killActive < killCap) pool.moveToKill(o)
            else break
            existing--
        }
        // Steal ahead into kill slots, never ramp inside a block (R22).
        while (pool.mainActive + n > c) {
            val vi = StealPolicy.pick(pool.voices, VoicePool.TOTAL, outFrame, pf, keys.held, keys.damping, sampleRate)
            if (vi < 0 || pool.killActive >= killCap) return false
            pool.moveToKill(pool.voices[vi])
        }
        val soft = profile.softKind != SoftKind.NONE && p.soft.valueAt(p.onUs[note]) >= 0.5f
        val hash = if (profile.velocityGain) 1f else DecayTables.hashGain(note)
        for (j in 0 until n) {
            val v = pool.alloc(Voice.ROLE_MAIN) ?: return j > 0
            val sk = nsSk[j]
            val region = map.region[sk]
            val r = map.rate[sk]
            v.state = Voice.PENDING
            v.runState = Voice.PLAYING
            v.key = key; v.stop = nsStop[j]; v.region = region; v.note = note; v.evIndex = evIndex; v.vel = vel
            v.bus = if (soft) Voice.SOFT else Voice.DRY
            v.reader = bt.reader; v.bank = bt.bank; v.regionFrames = bt.bank.frames(region)
            v.inc = incOf(r)
            v.eventAt = eventPf
            v.onsetAt = nsOnset[j]
            var start = nsOnset[j] - map.onsetOut[sk]
            if (start < pf) {
                val miss = pf - start
                v.skipFixed = Math.round(miss * r.toDouble() * TWO32)
                start = pf
            }
            v.startAt = start
            v.base = map.gain[sk] * nsW[j] * hash
            val seam = km.seamIdx[sk]
            if (seam >= 0f) { v.spectral = true; v.lpIdx = seam }
            armDebugOnset(v)
        }
        return true
    }

    private fun incOf(rate: Float): Long {
        val r = if (rate > 3.9f) 3.9f else if (rate < 0.05f) 0.05f else rate
        return Math.round(r.toDouble() * TWO32)
    }

    // ═══════════ render (HKAudio) ═══════════

    /**
     * WP4's output clock jumped (a skipped or repeated block): move every output-frame stamp by the
     * same delta so CoreClockState, debug onsets, END timing and steal ages follow WP4's timeline.
     */
    private fun resyncOutFrame(to: Long) {
        val d = to - outFrame
        outFrame = to
        endStartOut += d
        if (dbgScheduled >= 0) dbgScheduled += d
        if (dbgArmedOut >= 0) dbgArmedOut += d
        if (dbgDetected >= 0) dbgDetected += d
        val vs = pool.voices
        for (i in 0 until VoicePool.TOTAL) vs[i].startedOut += d
    }

    override fun render(out: FloatArray, blockStartFrame: Long) {
        val block = HK.BLOCK
        if (blockStartFrame != outFrame) resyncOutFrame(blockStartFrame)
        val s0 = songFrames()
        val songNow = (s0 * 1e6 / sampleRate).toLong()
        state.songUs = songNow; state.rate = rate; state.playing = playing; state.epoch = epoch
        state.generation = generation; state.registration = registration

        java.util.Arrays.fill(dryL, 0f); java.util.Arrays.fill(dryR, 0f)
        java.util.Arrays.fill(softL, 0f); java.util.Arrays.fill(softR, 0f)
        for (i in 0 until HK.LANES) if (selfRows[i]) { java.util.Arrays.fill(self, i * block, (i + 1) * block, 0f); selfRows[i] = false }
        java.util.Arrays.fill(laneMs, 0f); java.util.Arrays.fill(combMs, 0f)
        keys.beginBlock()

        val p = perf
        pSus = if (p != null) susCursor.advanceTo(songNow) else 0f
        if (playing && p != null) {
            enforceCap()
            seq.retryPending(this)
            seq.dispatch(this, s0, rateD, pf, block + HK.LOOK_FRAMES)
            applyEvents(p)
            applyCountdowns(p)
        }
        keys.update(pSus)
        val km = kmTok
        pool.render(pf, outFrame, playing, keys, km?.dm, hermite, spectralAllowed, dryL, dryR, softL, softR, self, selfRows, laneMs)

        for (i in 0 until block) mix[i] = 0.5f * (dryL[i] + dryR[i] + softL[i] + softR[i])
        dsp.resonance.process(mix, self, selfRows, keys.gate, keys.softFeed, keys.damping, dryL, dryR, block)
        dsp.soft.process(softL, softR, dryL, dryR, block)
        val yaw = head.yaw() + head.omega() * latencySec
        dsp.room.process(dryL, dryR, wetL, wetR, block, yaw)
        dsp.master.process(wetL, wetR, block, out)
        dsp.resonance.energy(combMs)
        for (i in 0 until HK.LANES) lanes[i] = sqrt(laneMs[i] + combMs[i])
        laneEpoch = epoch

        detectDebugOnset(out)
        if (playing) { pos += block.toLong() * rateFixed; pf += block }
        outFrame += block
        if (roomRestoreBlocks > 0 && --roomRestoreBlocks == 0 && playing) dsp.room.setInputGain(1f, 10f)

        if (endPending) {
            if (pool.allBelow(VoicePool.CULL_DB) || outFrame - endStartOut >= 4L * sampleRate) {
                endedGeneration = generation; endPending = false
            }
        }
        state.endedGeneration = endedGeneration
        state.idle = (!playing || p == null) && !pool.anyAudible() && !dsp.room.tailActive && !bench.running   // the bench steps inside render()

        if (bench.running) {
            if (!bench.step(BENCH_SLICE_NS)) {
                // The bench drove the live stages with its own buffers: restore their modes and clear their state.
                dsp.resonance.setMode(resonanceMode, profile.id, combs, dispersion)
                dsp.resonance.reset(); dsp.soft.reset()
            }
        }
    }

    /** After the cap was lowered: steal the excess into kill slots, a block at a time. */
    private fun enforceCap() {
        val c = cap
        while (pool.mainActive > c && pool.killActive < c / 2) {
            val vi = StealPolicy.pick(pool.voices, VoicePool.TOTAL, outFrame, pf, keys.held, keys.damping, sampleRate)
            if (vi < 0) return
            pool.moveToKill(pool.voices[vi])
        }
    }

    private fun applyEvents(p: Performance) {
        val end = pf + HK.BLOCK
        val km = kmTok
        while (events.headBefore(end)) {
            val fr = events.headFrame()
            val o = if (fr < pf) 0 else (fr - pf).toInt()
            val a = events.headArg()
            when (events.headType()) {
                PendingEvents.KEY_DOWN -> {
                    val key = p.key[a].toInt() and 0x7F
                    keys.noteOn(key)
                    keys.newestSoft[key] = profile.softKind != SoftKind.NONE && p.soft.valueAt(p.onUs[a]) >= 0.5f
                    if (km != null) pool.restrike(key, a, if (pSus < 0.33f) km.dm.restrikeMulUp else km.dm.restrikeMulPedal)
                }
                PendingEvents.KEY_UP -> {
                    val key = p.key[a].toInt() and 0x7F
                    if (km != null) keys.keyUp(key, a, pf + o, km.dm.lagFrames, km.dm.quill8Frames, km.dm.quill4Frames)
                    else keys.keyUp(key, a, pf + o, 0, 0, 0)
                }
                PendingEvents.LATCH -> if (a in p.latchLo.indices) keys.setLatch(p.latchLo[a], p.latchHi[a])
                PendingEvents.PEDAL_NOISE -> if (pedalNoises) startPedalNoise(a, pf + o)
                PendingEvents.END -> { endPending = true; endStartOut = outFrame + o }
                else -> {}
            }
            events.pop()
        }
    }

    private fun applyCountdowns(p: Performance) {
        val end = pf + HK.BLOCK
        val km = kmTok ?: return
        val dm = km.dm
        for (k in 0 until HK.KEYS) {
            val la = keys.landAt[k]
            if (la < end) {
                val o = if (la < pf) 0 else (la - pf).toInt()
                keys.land(k, o)
                onLanding(p, km, k, o)
            }
            if (dm.harpsichord) {
                val q8 = keys.q8At[k]
                if (q8 < end) { keys.q8At[k] = KeyState.NONE; onQuill(p, km, k, HK.STOP_MAIN, if (q8 < pf) 0 else (q8 - pf).toInt()) }
                val q4 = keys.q4At[k]
                if (q4 < end) { keys.q4At[k] = KeyState.NONE; onQuill(p, km, k, HK.STOP_4FT, if (q4 < pf) 0 else (q4 - pf).toInt()) }
            }
        }
    }

    /** The damper lands on key [k] at offset [o] of this block (§3.7, §3.9). */
    private fun onLanding(p: Performance, km: PreparedKeyMap, k: Int, o: Int) {
        val dm = km.dm
        if (dm.harpsichord) return                         // releases at the quill passes; D = 1 from now on
        val lands = k <= dm.lastDamper && !keys.latched(k)
        if (dm.releaseCarriesTail) {
            if (lands && keys.pedalDamp >= 0.999f) handoff(km, k, HK.STOP_MAIN, o)
            return                                          // otherwise no release: the damped tail is in it
        }
        if (!releaseNoises) return
        val rel = km.keyMap.release[k]
        if (rel < 0) return
        val note = keys.lastNote[k]
        var g = km.keyMap.releaseGain[k]
        if (note >= 0) {
            val v = p.vel[note].toInt() and 0x7F
            val age = (p.offUs[note] - p.onUs[note]) * 1e-6f
            val ea = DecayTables.expAge(age)
            g *= DecayTables.VEL07[v] * (if (ea < 0.25f) 0.25f else ea)
        }
        if (!(lands && keys.pedalDamp > 0.5f)) g *= MINUS_9DB
        startNoise(Voice.RELEASE_NOISE, rel, km.keyMap.releaseRate[k], g, pf + o, k, 0)
    }

    /** Harpsichord quill pass of [stop] on key [k] (§3.9, §3.10). */
    private fun onQuill(p: Performance, km: PreparedKeyMap, k: Int, stop: Int, o: Int) {
        if (km.dm.releaseCarriesTail) { handoff(km, k, stop, o); return }
        if (!releaseNoises) return
        val stops = minOf(km.keyMap.stops, 2)
        if (stop >= stops) return
        if (stop == HK.STOP_MAIN && registration and HK.REG_8 == 0) return
        if (stop == HK.STOP_4FT && registration and HK.REG_4 == 0) return
        val i = stop * HK.KEYS + k
        val rel = km.keyMap.release[i]
        if (rel < 0) return
        val note = keys.lastNote[k]
        var g = km.keyMap.releaseGain[i]
        if (note >= 0) g *= DecayTables.VEL07[p.vel[note].toInt() and 0x7F]
        startNoise(Voice.RELEASE_NOISE, rel, km.keyMap.releaseRate[i], g, pf + o, k, 0)
    }

    /**
     * Tail-carrying kits: the release starts level-matched to the loudest sustain voice of
     * (key, stop) and the sustain voices cross over to it in 30 ms, equal power (§3.7).
     */
    private fun handoff(km: PreparedKeyMap, k: Int, stop: Int, o: Int) {
        val i = stop * HK.KEYS + k
        if (i >= km.keyMap.release.size) return
        val rel = km.keyMap.release[i]
        if (rel < 0 || !releaseNoises) return
        val bt = bankTok ?: return
        var loud: Voice? = null
        val vs = pool.voices
        for (j in 0 until VoicePool.TOTAL) {
            val v = vs[j]
            if (v.role != Voice.ROLE_MAIN || v.key != k || v.stop != stop) continue
            if (v.state != Voice.PLAYING && v.state != Voice.FADING) continue
            if (v.onsetAt >= pf) continue
            if (loud == null || v.levelDb > loud.levelDb) loud = v
        }
        if (loud == null) return
        val relEnv = DecayTables.ENV_DB[bt.bank.envByte(rel, bt.bank.onsetFrame(rel) / 480)]
        var db = loud.levelDb - relEnv
        if (db < -30f) db = -30f else if (db > 6f) db = 6f
        val x = startNoise(Voice.RELEASE_NOISE, rel, km.keyMap.releaseRate[i], DecayTables.db2lin(db), pf + o, k, km.dm.handoffFrames)
        if (!x) return
        for (j in 0 until VoicePool.TOTAL) {
            val v = vs[j]
            if (v.role != Voice.ROLE_MAIN || v.key != k || v.stop != stop) continue
            if (v.state != Voice.PLAYING && v.state != Voice.FADING) continue
            if (v.onsetAt >= pf) continue
            v.state = Voice.HANDOFF
            v.fade0 = v.fade
            v.fadeMode = Voice.FADE_HANDOFF
            v.handX = 0f
            v.fadeStep = 1f / km.dm.handoffFrames
        }
    }

    private fun startPedalNoise(arg: Int, atPf: Long) {
        val km = kmTok ?: return
        val map = km.keyMap
        val down = arg and 1 == 1
        val speed = (arg ushr 1) and 3
        val list = if (down) map.pedalDown else map.pedalUp
        if (list.isEmpty()) return
        val idx: Int
        if (down) { idx = pedalDownRr % list.size; pedalDownRr++ } else { idx = pedalUpRr % list.size; pedalUpRr++ }
        val region = list[idx]
        if (region < 0) return
        startNoise(Voice.PEDAL_NOISE, region, 1f, map.pedalGain * PEDAL_SPEED_GAIN[speed], atPf, 0, 0)
    }

    /** A release or pedal-noise voice in the 12-slot noise pool, its onset at [atPf]. */
    private fun startNoise(kind: Int, region: Int, rate: Float, gain: Float, atPf: Long, key: Int, fadeInFrames: Int): Boolean {
        val bt = bankTok ?: return false
        if (pool.noiseActive >= HK.NOISE_SLOTS) {
            val o = pool.oldestNoise() ?: return false
            if (o.state == Voice.PENDING) pool.toIdle(o)
            else if (pool.killActive < cap / 2) pool.moveToKill(o)
            else return false
        }
        val v = pool.alloc(Voice.ROLE_NOISE) ?: return false
        v.state = Voice.PENDING
        v.runState = kind
        v.key = key; v.region = region; v.note = -1; v.evIndex = -1
        v.reader = bt.reader; v.bank = bt.bank; v.regionFrames = bt.bank.frames(region)
        v.inc = incOf(rate)
        v.startAt = atPf; v.onsetAt = atPf; v.eventAt = atPf
        v.skipFixed = bt.bank.onsetFrame(region).toLong() shl 32      // the recorded onset lands on atPf
        v.base = gain
        if (fadeInFrames > 0) { v.fadeIn = 0f; v.fadeInStep = 1f / fadeInFrames }
        return true
    }

    // ═══════════ debugOnset (T-ALIGN, §8.4) ═══════════

    private fun armDebugOnset(v: Voice) {
        if (pool.sounding() > 0) return                    // isolated onsets only (SYNC_CLICK)
        val bank = v.bank ?: return
        val r = v.region
        // where the −40 dB crossing should appear: the onset frame plus (thrFrame − onsetFrame) source frames at the voice's rate
        dbgScheduled = outFrame + (v.onsetAt - pf) + Math.round((bank.thrFrame(r) - bank.onsetFrame(r)) * TWO32 / v.inc)
        dbgArmedOut = outFrame + (v.startAt - pf)
        val peakEnv = DecayTables.ENV_DB[bank.envByte(r, (bank.onsetFrame(r) + 480) / 480)]
        dbgThreshold = 0.01f * DecayTables.db2lin(peakEnv) * v.base * masterLin * 1.414f
        dbgDetected = -1L
    }

    private fun detectDebugOnset(out: FloatArray) {
        if (dbgArmedOut < 0 || dbgDetected >= 0) return
        val from = dbgArmedOut - outFrame
        if (from >= HK.BLOCK) return
        val i0 = if (from < 0) 0 else from.toInt()
        for (i in i0 until HK.BLOCK) {
            val x = out[2 * i]
            if (x >= dbgThreshold || -x >= dbgThreshold) { dbgDetected = outFrame + i; return }
        }
    }

    override fun debugOnset(out: LongArray): Boolean {
        if (dbgDetected < 0) return false
        out[0] = dbgScheduled; out[1] = dbgDetected
        return true
    }

    // ═══════════ publishing ═══════════

    override fun clockState(out: CoreClockState) {
        out.songUs = state.songUs; out.rate = state.rate; out.playing = state.playing; out.epoch = state.epoch
        out.generation = state.generation; out.registration = state.registration
        out.endedGeneration = state.endedGeneration; out.idle = state.idle
    }

    override fun energy(outLanes: FloatArray): Int {
        System.arraycopy(lanes, 0, outLanes, 0, HK.LANES)
        return laneEpoch
    }

    override fun stats(out: AudioStats) {
        out.voices = pool.mainActive; out.voicesPeak = pool.peak; out.noiseVoices = pool.noiseActive
        out.stolen = pool.stolen; out.dropped = seq.dropped
        out.combsActive = dsp.resonance.active; out.voiceCap = cap
        out.epoch = epoch; out.generation = generation
        val bt = bankTok
        out.bankGeneration = bt?.bank?.generation ?: -1
        out.bankStub = bt?.bank?.info?.isStub ?: false
        out.slowReads = bt?.reader?.slowReads ?: 0
    }

    companion object {
        const val TWO32 = 4294967296.0
        const val MAX_PER_KEY = 3
        const val MINUS_9DB = 0.35481339f
        /** A pending-list onset more than 50 ms past its frame is dropped (counted) instead of started. */
        const val LATE_DROP_FRAMES = 2400L
        const val BENCH_SLICE_NS = 2_000_000L
        private val ROUTES = OutputRoute.values()
        @JvmField val PEDAL_SPEED_GAIN = floatArrayOf(0.35f, 0.55f, 0.8f, 1.0f)
    }
}
