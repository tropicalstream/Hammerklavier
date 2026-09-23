package com.tropicalstream.hammerklavier.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.tropicalstream.hammerklavier.contract.AudioClock
import com.tropicalstream.hammerklavier.contract.AudioControl
import com.tropicalstream.hammerklavier.contract.AudioListener
import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.ClockStats
import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.CommandHandler
import com.tropicalstream.hammerklavier.contract.CommandRing
import com.tropicalstream.hammerklavier.contract.CoreClockState
import com.tropicalstream.hammerklavier.contract.EnergyRing
import com.tropicalstream.hammerklavier.contract.EngineCoreApi
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.MixSettings
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.RouteInfo
import com.tropicalstream.hammerklavier.contract.SettingsStore
import com.tropicalstream.hammerklavier.contract.SongClock
import com.tropicalstream.hammerklavier.contract.StatusCode
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import com.tropicalstream.hammerklavier.contract.stub.FakeClock
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * WP4's [AudioControl] (PLAN §3.1, §2.5): owns the AudioTrack and the HKAudio loop, publishes the
 * clock and the energy lanes, parks when idle, rebuilds the track on output errors, protects
 * itself when headroom runs short, and never lets an exception out of HKAudio.
 *
 * Threads: every public method runs on main; commands reach the engine only through the SPSC
 * [CommandRing]; stats come back through a seqlocked [AtomicIntegerArray]; onEnded / onOverload /
 * onEngineError are posted with preallocated Runnables whose payloads are `@Volatile` fields.
 * After warm-up the HKAudio loop allocates nothing (§2.1 rule 1) and does no transcendental maths.
 *
 * With no AudioTrack at all (two failed builds) the app runs silently: [clock] switches to a
 * [FakeClock] mirroring the transport, and `onEngineError(AUDIO_UNAVAILABLE)` is posted.
 */
@Suppress("unused")
class AudioOutput internal constructor(
    private val core: EngineCoreApi,
    private val cursors: VoiceCursorBoard,
    private val head: HeadPose,
    private val settings: SettingsStore,
    private val sinks: SinkFactory,
    private val post: (Runnable) -> Unit,
    private val main: Handler?,
    focusFactory: ((AudioFocusGate.Target) -> AudioFocusGate?)?,
    private val clockSource: NanoSource = NanoSource { System.nanoTime() },
) : AudioControl, AudioFocusGate.Target {

    /** The frozen constructor (PLAN §2.3); build on main. */
    constructor(ctx: Context, core: EngineCoreApi, cursors: VoiceCursorBoard, head: HeadPose, settings: SettingsStore) :
        this(core, cursors, head, settings, SinkFactory { AudioTrackSink(it) }, mainPost(), Handler(Looper.getMainLooper()),
            { t -> AudioFocusGate(ctx.applicationContext, t) })

    private val ring = CommandRing(256)
    private val audioClock = AudioClock()
    private val fake = FakeClock { clockSource.now() }
    @Volatile private var unavailable = false
    private val switchClock = object : SongClock {
        override fun sample(nanoTime: Long, out: ClockSample) {
            if (unavailable) fake.sample(nanoTime, out) else audioClock.sample(nanoTime, out)
        }
    }
    override val clock: SongClock get() = switchClock
    override val energy: EnergyRing = EnergyRing()
    private val routes = RouteMonitor { info -> onRouteChanged(info) }
    override val route: RouteInfo get() = routes.current
    private val focus: AudioFocusGate? = focusFactory?.invoke(this)
    val prefetcher = Prefetcher(cursors, switchClock)

    // ---- main-thread state (replayed by start() after stop()) ----
    private var listener: AudioListener? = null
    private var running = false
    private var everStopped = false
    private var thread: Thread? = null
    /** The last HKAudio thread started; a new one waits for it to exit before touching shared state. */
    private var lastThread: Thread? = null
    @Volatile private var threadGen = 0
    private var lastBank: LoadedBank? = null
    private var lastProfile: InstrumentProfile? = null
    private var lastBankToken: Any? = null
    private var lastKeyMapToken: Any? = null
    private var lastPerf: Performance? = null
    private var lastQuality: QualityProfile? = null
    private var lastRoom: RoomDesign? = null
    private var lastRoomGlide = 0
    private var lastMix: MixSettings? = null
    private var registration = 3
    private var userDuck = 1f
    private var focusDuck = 1f
    private var rate = 1f
    private var endedSilent = -1

    // ---- posted to main: preallocated Runnables with volatile payloads ----
    @Volatile private var endedPayload = -1
    @Volatile private var overloadPayload = 0
    @Volatile private var errorCode = StatusCode.AUDIO_STOPPED
    @Volatile private var errorDetail = ""
    private val endedRunnable = Runnable { listener?.onEnded(endedPayload) }
    private val overloadRunnable = Runnable { listener?.onOverload(overloadPayload) }
    private val errorRunnable = Runnable { listener?.onEngineError(errorCode, errorDetail) }
    private val routingRunnable = Runnable { routes.onRouting(sinkForRouting) }
    @Volatile private var sinkForRouting: OutputSink? = null

    // ---- cross-thread stats (seqlock over atomic ints) ----
    private val statsVer = java.util.concurrent.atomic.AtomicInteger(0)
    private val statsBoard = AtomicIntegerArray(S_COUNT)
    /** Latency allowance per route class in frames (measured; defaults per §2.5). */
    private val latAllowance = AtomicIntegerArray(OutputRoute.entries.size).also { a ->
        for (r in OutputRoute.entries) a.set(r.ordinal, RouteMonitor.defaultLatencyFrames(r))
    }
    /** Routes whose allowance came from a steady run of timestamps this process (only these persist). */
    private val latMeasured = AtomicIntegerArray(OutputRoute.entries.size)
    @Volatile private var lastSongUs = 0L
    /** Permanent focus loss: HKAudio parks at its next block. */
    @Volatile private var parkRequested = false
    @Volatile private var lowLatencyForced = false

    // ---- HKAudio-confined ----
    private val out = FloatArray(2 * HK.BLOCK)
    private val lanes = FloatArray(HK.LANES)
    private val zeroLanes = FloatArray(HK.LANES)
    private val st = CoreClockState()
    private val tsBuf = LongArray(2)
    private val scratch = AudioStats()
    private var sink: OutputSink? = null
    private var framesAccepted = 0L
    private var trackBaseFrame = 0L
    private var blocks = 0L
    private var idleFrames = 0L
    private var gotTs = false
    private var parked = false
    private var lastEnded = -1
    private var routeOrdinal = 0
    private var baseCap = 96
    private var unparkRequested = false
    private val latEma = IntArray(OutputRoute.entries.size)
    private val latSamples = IntArray(OutputRoute.entries.size)
    private var drainedAny = false
    private var lastRenderIdle = false
    private var renderMaxNs = 0L
    private var renderSumNs = 0L
    private var renderCount = 0
    private val hist = IntArray(HIST)
    private var p50 = 0; private var p99 = 0; private var pMax = 0; private var cpu = 0f
    private var underruns = 0
    private var bufferFrames = 0
    private var trackRebuilds = 0
    private var tid = 0
    private val supervisor = TrackSupervisor()
    private val renderGuard = RenderGuard()
    private val headroom = HeadroomGuard()
    /** The last accepted timestamp (absolute output frame, nanos); -1 = none this session. */
    private var tsFrame = -1L; private var tsNanos = 0L
    /** Render time / block period, smoothed; and the underrun count at the last headroom sample. */
    private var loadEma = 0f; private var urAtSample = 0
    private val tuner = LatencyTuner()
    private val wakeLock = Object()
    @Volatile private var wakeFlag = false
    @Volatile private var stopFlag = false

    /** Intercepts the commands AudioOutput must see, then forwards every one to the engine. */
    private val tap = CommandHandler { code, l, f, ref ->
        drainedAny = true
        when (code) {
            Cmd.PLAY, Cmd.SEEK, Cmd.SET_PERF, Cmd.SET_BANK, Cmd.BENCH -> unparkRequested = true
            Cmd.QUALITY -> if (ref is QualityProfile) baseCap = ref.voiceCap
            Cmd.ROUTE -> routeOrdinal = l.toInt()
        }
        core.on(code, l, f, ref)
        if (code == Cmd.QUALITY && headroom.steps > 0) core.on(Cmd.VOICE_CAP, headroom.cap(baseCap).toLong(), 0f, null)
    }

    // =====================================================================================
    // AudioControl (main)
    // =====================================================================================

    override fun start() {
        if (running) return
        running = true
        stopFlag = false
        lowLatencyForced = settings.getBool(KEY_LOW_LATENCY, LOW_LATENCY_DEFAULT)
        for (r in OutputRoute.entries) {
            val v = settings.getInt(KEY_LAT + r.name.lowercase(), -1)
            if (v > 0) latAllowance.set(r.ordinal, v)
        }
        if (everStopped) replay()
        val gen = ++threadGen
        // A previous HKAudio that outlived stop()'s join may still be inside a write. The new thread
        // waits for it to exit so the two never share the sink, counters, buffers or the ring consumer.
        val prev = lastThread?.takeIf { it.isAlive }
        val t = Thread(null, {
            if (prev != null) try { prev.join() } catch (e: InterruptedException) { return@Thread }
            if (gen == threadGen) hkAudio(gen)
        }, HK.TAG_AUDIO)
        t.priority = Thread.MAX_PRIORITY
        thread = t
        lastThread = t
        t.start()
        prefetcher.start()
    }

    override fun stop() {
        if (!running) return
        running = false
        stopFlag = true
        threadGen++
        wake()
        val t = thread
        thread = null
        t?.join(STOP_JOIN_MS)
        prefetcher.stop()
        focus?.abandon()
        everStopped = true
        if (t == null || !t.isAlive) ring.drain(Int.MAX_VALUE, DISCARD)   // single consumer again
        fake.pause()
        for (r in OutputRoute.entries) if (latMeasured.get(r.ordinal) != 0)
            settings.putInt(KEY_LAT + r.name.lowercase(), latAllowance.get(r.ordinal))
    }

    private fun replay() {
        // HKAudio resets the engine before draining (every voice killed on start).
        lastBankToken?.let { offer(Cmd.SET_BANK, ref = it) }
        lastKeyMapToken?.let { offer(Cmd.SET_KEYMAP, ref = it) }
        lastQuality?.let { offer(Cmd.QUALITY, ref = it) }
        lastRoom?.let { offer(Cmd.ROOM, lastRoomGlide.toLong(), ref = it) }
        lastMix?.let { offer(Cmd.MIX, ref = it) }
        offer(Cmd.REGISTRATION, registration.toLong())
        offer(Cmd.RATE, f = rate)
        offer(Cmd.DUCK, f = userDuck * focusDuck)
        val p = lastPerf
        if (p != null) offer(Cmd.SET_PERF, lastSongUs, 0f, p)
        offer(Cmd.ROUTE, route.route.ordinal.toLong(), latAllowance.get(route.route.ordinal) / 48f)
    }

    /**
     * Bank and key-map tables are built off main (EngineCoreApi.prepare* are any-thread; the stub
     * bank's took ≈ 1 s on the AR1 and stalled the UI at M1), in order, on [prepareWorker]; the
     * result is published on main. [prepareSeq] drops a prepare overtaken by a newer setBank.
     * Without a main Handler (JVM tests) it runs inline as before.
     */
    private val prepareWorker: java.util.concurrent.ExecutorService? = if (main == null) null else
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "HKPrepare").apply { isDaemon = true } }
    private var prepareSeq = 0

    /** [newBank]: a setBank (bumps the sequence); a key map keeps it and is dropped only by a newer bank. */
    private fun offMain(newBank: Boolean, work: () -> Any, publish: (Any) -> Unit) {
        val w = prepareWorker ?: return publish(work())
        val seq = if (newBank) ++prepareSeq else prepareSeq
        w.execute {
            val token = work()
            post(Runnable { if (seq == prepareSeq) publish(token) })
        }
    }

    private var requestedBank: LoadedBank? = null; private var requestedProfile: InstrumentProfile? = null

    override fun setBank(bank: LoadedBank, keyMap: KeyMap, profile: InstrumentProfile) {
        requestedBank = bank; requestedProfile = profile                   // setKeyMap may follow before the publish
        offMain(true, { core.prepareBank(bank, keyMap, profile) }) { token ->
            lastBank = bank; lastProfile = profile; lastBankToken = token; lastKeyMapToken = null
            prefetcher.bank = bank; prefetcher.keyMap = keyMap; prefetcher.wake()
            offer(Cmd.SET_BANK, ref = token)
        }
    }

    override fun setKeyMap(keyMap: KeyMap) {
        val bank = requestedBank ?: lastBank ?: return
        val profile = requestedProfile ?: lastProfile ?: return
        offMain(false, { core.prepareKeyMap(keyMap, bank.info, profile) }) { token ->
            lastKeyMapToken = token
            prefetcher.keyMap = keyMap
            offer(Cmd.SET_KEYMAP, ref = token)
        }
    }

    override fun setPerformance(p: Performance?, startUs: Long, autoPlay: Boolean) {
        lastPerf = p
        prefetcher.perf = p
        val keep = if (startUs < 0) fake.songUsNow() else startUs
        fake.pause()
        fake.setPerformance(p?.generation ?: -1, if (p == null) 0L else keep, p?.durationUs ?: Long.MAX_VALUE)
        if (autoPlay && p != null) { focus?.request(); fake.play() }
        offer(Cmd.SET_PERF, startUs, if (autoPlay) 1f else 0f, p)
        prefetcher.wake()
    }

    override fun play() {
        focus?.request()
        if (lastPerf != null) fake.play()
        offer(Cmd.PLAY); prefetcher.wake()
    }
    override fun pause(fadeMs: Int) { fake.pause(); offer(Cmd.PAUSE, fadeMs.toLong()) }
    override fun seek(us: Long) { fake.seek(us); offer(Cmd.SEEK, us); prefetcher.wake() }
    override fun setRate(rate: Float) {
        val r = rate.coerceIn(0.5f, 1.5f)
        this.rate = r; fake.setRate(r); offer(Cmd.RATE, f = r)
    }
    override fun setQuality(q: QualityProfile) { lastQuality = q; offer(Cmd.QUALITY, ref = q) }
    override fun setRoom(d: RoomDesign, glideMs: Int) { lastRoom = d; lastRoomGlide = glideMs; offer(Cmd.ROOM, glideMs.toLong(), ref = d) }
    override fun setMix(m: MixSettings) { lastMix = m; offer(Cmd.MIX, ref = m) }
    override fun setRegistration(mask: Int) { registration = mask; fake.setRegistration(mask); offer(Cmd.REGISTRATION, mask.toLong()) }
    override fun setDuck(gain: Float) { userDuck = gain.coerceIn(0f, 1f); offer(Cmd.DUCK, f = userDuck * focusDuck) }
    override fun bench(seconds: Int) { offer(Cmd.BENCH, seconds.toLong()) }
    override fun setListener(l: AudioListener?) { listener = l }

    override fun focusDuck(gain: Float) { focusDuck = gain; offer(Cmd.DUCK, f = userDuck * focusDuck) }
    override fun focusPause(permanent: Boolean) {
        pause(60)
        if (permanent) {                                   // §3.1: permanent loss → pause and park
            parkRequested = true
            wake()
            focus?.abandon()
        }
    }

    override fun stats(out: AudioStats) {
        readStats(out)
        out.energyMiss = energy.misses
        if (unavailable) {
            val p = lastPerf
            if (p != null && fake.atEnd() && endedSilent != p.generation) {
                endedSilent = p.generation; fake.pause(); listener?.onEnded(p.generation)
            }
            out.generation = fake.currentGeneration; out.parked = true
        }
        out.bankGeneration = if (out.bankGeneration >= 0) out.bankGeneration else lastBank?.generation ?: -1
        out.bankStub = lastBank?.info?.isStub ?: false
    }

    override fun clockStats(out: ClockStats) {
        audioClock.stats(out)
        out.latFrames = latAllowance.get(route.route.ordinal)
    }

    override fun diagnostics(): Map<String, String> {
        val s = AudioStats(); readStats(s)
        return mapOf("audio" to (if (unavailable) "unavailable (FakeClock)" else "AudioTrack"),
            "route" to "${route.key} type=${route.deviceType} flags=${route.outputFlags}",
            "fastTrack" to s.fastTrack.toString(), "bufferFrames" to s.bufferFrames.toString(),
            "latFrames" to OutputRoute.entries.joinToString { "${it.name.lowercase()}=${latAllowance.get(it.ordinal)}" },
            "rebuilds" to s.trackRebuilds.toString(), "underruns" to s.underruns.toString(), "voiceCap" to s.voiceCap.toString(),
            "prefetchBank" to prefetcher.bankGeneration.toString())
    }

    private fun offer(code: Int, l: Long = 0L, f: Float = 0f, ref: Any? = null) {
        ring.offer(code, l, f, ref)
        wake()
    }

    private fun wake() { synchronized(wakeLock) { wakeFlag = true; wakeLock.notifyAll() } }

    private fun onRouteChanged(info: RouteInfo) {
        offer(Cmd.ROUTE, info.route.ordinal.toLong(), latAllowance.get(info.route.ordinal) / 48f)
        listener?.onRouteChanged(info)
    }

    // =====================================================================================
    // HKAudio
    // =====================================================================================

    private fun alive(gen: Int) = !stopFlag && gen == threadGen

    private fun hkAudio(gen: Int) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        tid = runCatching { Process.myTid() }.getOrDefault(0)
        try {
            if (!openSink()) {
                unavailable = true
                errorCode = StatusCode.AUDIO_UNAVAILABLE; errorDetail = "no AudioTrack"
                post(errorRunnable)
                return
            }
            unavailable = false
            core.reset()                                   // a stale tail can never replay
            beginSession()
            while (alive(gen)) {
                if (parked) { parkWait(gen); continue }
                if (!block(gen)) break
            }
        } catch (t: Throwable) {
            errorCode = StatusCode.AUDIO_STOPPED; errorDetail = t.javaClass.simpleName
            post(errorRunnable)
        } finally {
            val s = sink
            sink = null
            if (s != null) runCatching { s.release() }
            publishStats()
        }
    }

    /** Two attempts: the normal path, then LOW_LATENCY (§3.1 fallback). */
    private fun openSink(): Boolean {
        for (attempt in 0 until 2) {
            val low = lowLatencyForced || attempt == 1
            val s = try { sinks.create(low) } catch (t: Throwable) { null } ?: continue
            sink = s
            // A new track counts its head from 0 at this frame; pause/play (park) never reset it.
            trackBaseFrame = framesAccepted
            sinkForRouting = s
            main?.let { h -> runCatching { s.addRoutingListener({ h.post(routingRunnable) }, h) } }
            post(routingRunnable)
            return true
        }
        return false
    }

    /** clock.reset → play → prime one silent block (SpyHunt rule: never prime before play). */
    private fun beginSession() {
        audioClock.reset(); energy.reset(); cursors.clearAll()
        gotTs = false; tsFrame = -1L; idleFrames = 0; parked = false; headroom.reset(); tuner.reset()
        val s = sink ?: return
        s.play()
        java.util.Arrays.fill(out, 0f)
        writeAll(out)
    }

    /** One block; false = give up (output lost / audio stopped). */
    private fun block(gen: Int): Boolean {
        drainedAny = false
        wakeFlag = false
        if (!alive(gen)) return false                      // never drain a successor's commands
        ring.drain(16, tap)
        if (unparkRequested) { unparkRequested = false; idleFrames = 0 }
        if (parkRequested) { parkRequested = false; park(); return true }
        val frame = framesAccepted
        val skipDsp = lastRenderIdle && !drainedAny && idleFrames > 0
        if (skipDsp) {
            java.util.Arrays.fill(out, 0f)
            energy.write(frame, st.epoch, zeroLanes)
        } else {
            val t0 = clockSource.now()
            try {
                core.render(out, frame)
                core.clockState(st)
                val ep = core.energy(lanes)
                energy.write(frame, ep, lanes)
            } catch (t: Throwable) {
                java.util.Arrays.fill(out, 0f)
                if (renderGuard.onException(clockSource.now())) {
                    runCatching { core.reset() }
                } else {
                    errorCode = StatusCode.AUDIO_STOPPED; errorDetail = t.javaClass.simpleName
                    post(errorRunnable)
                    return false
                }
            }
            noteRenderTime(clockSource.now() - t0)
            lastRenderIdle = st.idle
        }
        if (!writeAll(out)) return rebuildOrStop()
        audioClock.publishBlock(frame, st)
        lastSongUs = st.songUs
        if (st.endedGeneration >= 0 && st.endedGeneration != lastEnded) {
            lastEnded = st.endedGeneration
            endedPayload = lastEnded
            post(endedRunnable)
        }
        blocks++
        val s = sink ?: return false
        // Timestamps every 16 blocks (and so every idle wake); the estimate until the first one.
        if (blocks and 15L == 0L) {
            val ok = s.timestamp(tsBuf)
            if (ok) {
                val now = clockSource.now()
                if (audioClock.publishTimestamp(trackBaseFrame + tsBuf[0], tsBuf[1])) {
                    gotTs = true
                    tsFrame = trackBaseFrame + tsBuf[0]; tsNanos = tsBuf[1]
                    val h = trackBaseFrame + tsBuf[0] + (now - tsBuf[1]) * HK.SR / 1_000_000_000L
                    val lat = (framesAccepted - h).toInt()
                    if (lat in 1..96_000) noteLatency(routeOrdinal.coerceIn(0, OutputRoute.entries.size - 1), lat)
                }
            }
            if (supervisor.onTimestamp(ok, st.playing)) return rebuildOrStop()
            underruns = s.underrunCount()
            bufferFrames = s.bufferFrames
            if (s.fast) { val nb = tuner.onUnderruns(underruns, bufferFrames); if (nb > 0) bufferFrames = s.setBufferFrames(nb) }
            publishStats()
        }
        if (!gotTs) audioClock.publishEstimate(framesAccepted, latAllowance.get(routeOrdinal.coerceIn(0, OutputRoute.entries.size - 1)), clockSource.now())
        // Headroom.
        // Frames written but not yet presented at the DAC (M1: this route drains the client buffer in
        // 7,680-frame chunks, so framesAccepted − playbackHeadPosition dips to one block after every
        // pull although ~16k frames are in the pipe). Client queue only until the first timestamp.
        val queued = if (gotTs && tsFrame >= 0) (framesAccepted - (tsFrame + (clockSource.now() - tsNanos) * HK.SR / 1_000_000_000L)).toInt()
            else (framesAccepted - (trackBaseFrame + s.playbackHeadPosition())).toInt()
        // Overload shows as underruns or render time near the block period even while the DAC-side
        // measure stays high (the server fills with silence and the timestamp stalls; M1 storm64):
        // either counts as no headroom for the guard.
        val stressed = underruns > urAtSample || loadEma > OVERLOAD_LOAD
        urAtSample = underruns
        val q = if (stressed) 0 else queued
        when (if (gotTs) headroom.sample(framesAccepted, q, baseCap) else 0) {       // warm-up: from the first timestamp
            1 -> { val cap = headroom.cap(baseCap); core.on(Cmd.VOICE_CAP, cap.toLong(), 0f, null); overloadPayload = cap; post(overloadRunnable) }
            -1 -> core.on(Cmd.VOICE_CAP, headroom.cap(baseCap).toLong(), 0f, null)
        }
        // Idle → park.
        if (st.idle) {
            idleFrames += HK.BLOCK
            if (idleFrames >= IDLE_PARK_FRAMES) park()
        } else idleFrames = 0
        return true
    }

    /** Writes the whole block; a short write re-writes the rest. false = error. */
    private fun writeAll(buf: FloatArray): Boolean {
        val s = sink ?: return false
        var off = 0
        var left = HK.BLOCK
        var spins = 0
        while (left > 0) {
            val n = s.write(buf, 2 * off, left)
            if (n < 0) return false
            framesAccepted += n
            off += n; left -= n
            if (n == 0 && ++spins > 1000) return false
            if (stopFlag) return true
        }
        return true
    }

    private fun rebuildOrStop(): Boolean {
        if (!supervisor.allowRebuild(clockSource.now())) {
            errorCode = StatusCode.AUDIO_STOPPED; errorDetail = "output lost"
            post(errorRunnable)
            return false
        }
        sink?.let { runCatching { it.release() } }
        sink = null
        if (!openSink()) {
            errorCode = StatusCode.AUDIO_STOPPED; errorDetail = "output lost"
            post(errorRunnable)
            return false
        }
        trackRebuilds++
        beginSession()                                    // the engine is not reset: same song position
        return true
    }

    private fun park() {
        sink?.let { runCatching { it.pause() } }
        parked = true
        publishStats()
    }

    private fun parkWait(gen: Int) {
        synchronized(wakeLock) {
            while (!wakeFlag && alive(gen)) wakeLock.wait(1000)
            wakeFlag = false
        }
        if (!alive(gen)) return
        drainedAny = false
        ring.drain(Int.MAX_VALUE, tap)
        parkRequested = false                              // already parked
        if (unparkRequested) {
            unparkRequested = false
            lastRenderIdle = false
            beginSession()                                // clock.reset, play, prime; estimate until a timestamp
        }
    }

    /**
     * Smooths the measured latency (EMA, 1/8 per sample, first sample seeds it) so one jittery
     * reading never becomes the allowance; after [LAT_STEADY] samples the route counts as measured
     * and stop() persists it.
     */
    private fun noteLatency(r: Int, lat: Int) {
        val n = latSamples[r]
        val e = if (n == 0) lat else latEma[r] + (lat - latEma[r]) / 8
        latEma[r] = e
        latSamples[r] = n + 1
        if (n + 1 >= LAT_STEADY) { latAllowance.set(r, e); latMeasured.set(r, 1) }
        else if (latMeasured.get(r) == 0 && n + 1 >= 4) latAllowance.set(r, e)
    }

    private fun noteRenderTime(ns: Long) {
        loadEma += (ns.toFloat() / BLOCK_NS - loadEma) * (1f / 64f)          // ≈ 0.34 s
        val us = (ns / 1000).toInt()
        if (ns > renderMaxNs) renderMaxNs = ns
        renderSumNs += ns; renderCount++
        hist[(us / HIST_US).coerceIn(0, HIST - 1)]++
        if (renderCount >= 1024) {
            var acc = 0; var a50 = -1; var a99 = -1
            for (i in 0 until HIST) {
                acc += hist[i]
                if (a50 < 0 && acc * 2 >= renderCount) a50 = i
                if (a99 < 0 && acc * 100 >= renderCount * 99) { a99 = i; break }
            }
            p50 = (a50 + 1) * HIST_US; p99 = (a99 + 1) * HIST_US; pMax = (renderMaxNs / 1000).toInt()
            cpu = 100f * renderSumNs / (renderCount * BLOCK_NS)
            java.util.Arrays.fill(hist, 0); renderCount = 0; renderSumNs = 0; renderMaxNs = 0
        }
    }

    private fun publishStats() {
        val e = scratch
        e.voices = 0; e.voicesPeak = 0; e.noiseVoices = 0; e.stolen = 0; e.dropped = 0; e.combsActive = 0
        e.blockP50Us = 0; e.blockP99Us = 0; e.blockMaxUs = 0; e.cpuPct = 0f; e.slowReads = 0
        e.epoch = 0; e.generation = -1; e.bankGeneration = -1; e.voiceCap = 0
        runCatching { core.stats(e) }
        val b = statsBoard
        val v = statsVer.get()
        statsVer.set(v + 1)
        b.set(S_VOICES, e.voices); b.set(S_PEAK, e.voicesPeak); b.set(S_NOISE, e.noiseVoices); b.set(S_STOLEN, e.stolen)
        b.set(S_DROPPED, e.dropped); b.set(S_COMBS, e.combsActive)
        b.set(S_P50, if (e.blockP50Us > 0) e.blockP50Us else p50); b.set(S_P99, if (e.blockP99Us > 0) e.blockP99Us else p99)
        b.set(S_MAX, maxOf(e.blockMaxUs, pMax)); b.set(S_CPU, (if (e.cpuPct > 0f) e.cpuPct else cpu).toRawBits())
        b.set(S_UNDER, underruns); b.set(S_SLOW, e.slowReads); b.set(S_BUF, bufferFrames)
        b.set(S_CAP, if (e.voiceCap > 0) e.voiceCap else headroom.cap(baseCap)); b.set(S_HEAD, headroom.lastWindowMin)
        b.set(S_PARKED, if (parked || sink == null) 1 else 0); b.set(S_REBUILDS, trackRebuilds)
        b.set(S_EPOCH, e.epoch); b.set(S_GEN, e.generation); b.set(S_BANKGEN, e.bankGeneration)
        b.set(S_FAST, if (sink?.fast == true) 1 else 0); b.set(S_TID, tid)
        statsVer.set(v + 2)
    }

    private fun readStats(out: AudioStats) {
        val b = statsBoard
        for (attempt in 0 until 8) {
            val v0 = statsVer.get()
            if (v0 and 1 != 0) { Thread.yield(); continue }
            out.voices = b.get(S_VOICES); out.voicesPeak = b.get(S_PEAK); out.noiseVoices = b.get(S_NOISE); out.stolen = b.get(S_STOLEN)
            out.dropped = b.get(S_DROPPED); out.combsActive = b.get(S_COMBS); out.blockP50Us = b.get(S_P50); out.blockP99Us = b.get(S_P99)
            out.blockMaxUs = b.get(S_MAX); out.cpuPct = Float.fromBits(b.get(S_CPU)); out.underruns = b.get(S_UNDER)
            out.slowReads = b.get(S_SLOW); out.bufferFrames = b.get(S_BUF); out.voiceCap = b.get(S_CAP); out.headroomMinFrames = b.get(S_HEAD)
            out.parked = b.get(S_PARKED) != 0 || !running; out.trackRebuilds = b.get(S_REBUILDS); out.epoch = b.get(S_EPOCH)
            out.generation = b.get(S_GEN); out.bankGeneration = b.get(S_BANKGEN); out.fastTrack = b.get(S_FAST) != 0; out.tid = b.get(S_TID)
            if (statsVer.get() == v0) return
        }
    }

    /** Test hook: the latency allowance in frames for [r]. */
    internal fun latencyAllowance(r: OutputRoute): Int = latAllowance.get(r.ordinal)
    internal val isUnavailable: Boolean get() = unavailable
    internal fun sampleClock(out: ClockSample) = switchClock.sample(clockSource.now(), out)

    companion object {
        const val STOP_JOIN_MS = 350L
        const val LAT_STEADY = 16
        const val IDLE_PARK_FRAMES = HK.IDLE_PARK_MS.toLong() * HK.SR / 1000
        const val KEY_LOW_LATENCY = "audio.lowLatency"       // --ez lowlatency true|false (DebugControl writes it)
        /**
         * Integrator, M1 (deviation from §3.1, for the plan owner): on the X3 Pro a
         * PERFORMANCE_MODE_NONE music track is routed to the DEEP_BUFFER output (7,680-frame pulls,
         * ~300 ms write-to-DAC, timestamp pairs jittering to p99 1.1–2.1 ms: the M1 drift line fails),
         * not the primary normal mixer the plan measured. The LOW_LATENCY request lands on the primary
         * output's FastMixer with the plan's 4,096-frame buffer: ~108 ms, drift p99 0.25–0.41 ms,
         * 0 underruns. `--ez lowlatency false` restores NONE.
         */
        const val LOW_LATENCY_DEFAULT = true
        const val KEY_LAT = "audio.latFrames."
        private const val HIST = 256
        private const val HIST_US = 50
        private const val BLOCK_NS = HK.BLOCK * 1_000_000_000L / HK.SR
        private const val OVERLOAD_LOAD = 0.92f

        private const val S_VOICES = 0; private const val S_PEAK = 1; private const val S_NOISE = 2; private const val S_STOLEN = 3
        private const val S_DROPPED = 4; private const val S_COMBS = 5; private const val S_P50 = 6; private const val S_P99 = 7
        private const val S_MAX = 8; private const val S_CPU = 9; private const val S_UNDER = 10; private const val S_SLOW = 11
        private const val S_BUF = 12; private const val S_CAP = 13; private const val S_HEAD = 14; private const val S_PARKED = 15
        private const val S_REBUILDS = 16; private const val S_EPOCH = 17; private const val S_GEN = 18; private const val S_BANKGEN = 19
        private const val S_FAST = 20; private const val S_TID = 21; private const val S_COUNT = 22

        private val DISCARD = CommandHandler { _, _, _, _ -> }

        private fun mainPost(): (Runnable) -> Unit {
            val h = Handler(Looper.getMainLooper())
            return { r -> h.post(r) }
        }
    }
}

/** A primitive nanosecond source (a `() -> Long` would box on every call on HKAudio). */
fun interface NanoSource { fun now(): Long }
