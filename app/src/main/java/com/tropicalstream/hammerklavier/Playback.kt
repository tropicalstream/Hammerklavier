package com.tropicalstream.hammerklavier

import android.util.Log
import com.tropicalstream.hammerklavier.contract.AudioListener
import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.ClockStats
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.FallbackReason
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KitCallback
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.RouteInfo
import com.tropicalstream.hammerklavier.contract.StatusCode
import com.tropicalstream.hammerklavier.contract.SyntheticSpecs
import com.tropicalstream.hammerklavier.audio.KitManager
import java.io.File

/**
 * Integrator's interim playback driver (M1), until WP12's SessionController merges: opens the
 * instrument's kit and hands each playable bank to the audio output, turns `--es play <name>` into a
 * Performance on HKLoader, runs `--ez bench true`, and logs the `HKAudio stats` line and the
 * `HKClock` drift line every 10 s (PLAN §8.4 T-CPU, T-CLOCK). Main thread only.
 */
class Playback(private val w: Wiring) {
    private var instrument = InstrumentId.GRAND
    private val profile: InstrumentProfile get() = InstrumentProfile.of(instrument)
    private var generation = 0
    private var bank: LoadedBank? = null
    private var playingName: String? = null
    private val stats = AudioStats()
    private val cs = ClockStats()
    private val sample = ClockSample()
    private var running = false
    /** Set by AppController: the EngineBench's q0Cap once measured (§3.14). */
    var onVoiceCap: ((Int) -> Unit)? = null
    /** Set by AppController: a performance started (playback hint to the kits). */
    var onPlaying: (() -> Unit)? = null

    private val kitCb = object : KitCallback {
        override fun onProgress(id: InstrumentId, fraction: Float) {}
        override fun onPlayable(bank: LoadedBank) = useBank(bank)
        override fun onLayersChanged(bank: LoadedBank) = useBank(bank)
        override fun onComplete(bank: LoadedBank) { Log.i(HK.TAG_KIT, "${instrument.key}: complete gen=${bank.generation} stub=${bank.info.isStub}") }
        override fun onFallback(bank: LoadedBank, reason: FallbackReason) { Log.i(HK.TAG_KIT, "${instrument.key}: fallback $reason") }
    }

    private val listener = object : AudioListener {
        override fun onEnded(generation: Int) { Log.i(HK.TAG_AUDIO, "ended gen=$generation name=$playingName"); w.audio.pause() }
        override fun onOverload(newCap: Int) { Log.w(HK.TAG_AUDIO, "overload: voice cap -> $newCap") }
        override fun onEngineError(code: StatusCode, detail: String) { Log.e(HK.TAG_AUDIO, "engine error $code $detail") }
        override fun onRouteChanged(route: RouteInfo) { Log.i(HK.TAG_AUDIO, "route ${route.key} type=${route.deviceType}") }
    }

    private val tick = object : Runnable {
        override fun run() { if (!running) return; logStats(); w.main.postDelayed(this, 10_000) }
    }

    fun start() {
        if (running) return
        running = true
        w.audio.setListener(listener)
        w.kits.open(instrument, kitCb)
        w.main.postDelayed(tick, 10_000)
    }

    fun stop() { running = false; w.main.removeCallbacks(tick) }

    private fun useBank(b: LoadedBank) {
        val tuning = profile.defaultTuning
        val prof = profile
        w.loader.execute {
            val km = w.kits.keyMap(b, tuning)
            w.main.post {
                bank = b
                w.audio.setBank(b, km, prof)
                Log.i(HK.TAG_KIT, "${instrument.key}: bank gen=${b.generation} stub=${b.info.isStub} regions=${b.regionCount} -> audio")
            }
        }
    }

    /** `--es play synth:<n>` | `test:<n>` (asset midi/test/<n>.mid) | `asset:<path>`. */
    fun play(name: String) {
        val gen = ++generation
        val prof = profile
        w.loader.execute {
            val t0 = System.nanoTime()
            val perf = try {
                when {
                    name.startsWith("synth:") -> SyntheticSpecs.kindOf(name)?.let { w.compiler.synthetic(it, prof, gen) }
                    else -> {
                        val path = if (name.startsWith("test:")) "midi/test/${name.removePrefix("test:")}.mid" else name.removePrefix("asset:")
                        val bytes = runCatching { w.app.assets.open(path).use { it.readBytes() } }.getOrNull()
                            ?: runCatching { File(path).readBytes() }.getOrNull()
                        when (val r = bytes?.let { w.compiler.compile(it, name, gen, prof) }) {
                            is CompileResult.Ok -> r.perf
                            is CompileResult.Failed -> { Log.w(HK.TAG_LOADER, "play $name rejected ${r.reason} ${r.detail}"); null }
                            null -> null
                        }
                    }
                }
            } catch (t: Throwable) { Log.e(HK.TAG_LOADER, "play $name failed", t); null }
            val ms = (System.nanoTime() - t0) / 1e6
            w.main.post {
                if (perf == null) { Log.w(HK.TAG_LOADER, "play $name: nothing to play"); return@post }
                playingName = name
                w.audio.setPerformance(perf, 0L, true)
                onPlaying?.invoke()
                Log.i(HK.TAG_LOADER, "play $name gen=$gen notes=${perf.onUs.size} durationMs=${perf.durationUs / 1000} compiled in ${"%.1f".format(ms)} ms")
                w.main.postDelayed({ logClock("play") }, 1000)
            }
        }
    }

    /** After a resume: the smoke's clock cycle wants fromTimestamp within 1 s. */
    fun logClock(why: String) {
        w.audio.clock.sample(System.nanoTime(), sample)
        w.audio.clockStats(cs)
        Log.i(HK.TAG_CLOCK, "$why fromTimestamp=${sample.fromTimestamp} valid=${sample.valid} playing=${sample.playing} songMs=${sample.songUs / 1000} session=${cs.session}")
    }

    /** `--ez bench true`: the decode bench on HKVoicer, then EngineBench on HKAudio; results logged. */
    fun bench(seconds: Int) {
        (w.kits as? KitManager)?.let { km -> w.voicer.execute { km.decodeBench() } }
        w.engine.benchCpuMhz = cpuMhz()
        val before = w.engine.bench.completed
        w.audio.bench(seconds)
        val t0 = System.currentTimeMillis()
        w.main.postDelayed(object : Runnable {
            override fun run() {
                if (w.engine.bench.completed == before) {
                    if (System.currentTimeMillis() - t0 < 120_000) w.main.postDelayed(this, 500) else Log.w(HK.TAG_PERF, "bench: no result")
                    return
                }
                val r = w.engine.bench.result
                fun f(x: Double) = "%.1f".format(x)
                Log.i(HK.TAG_PERF, "bench cpuMhz=${r.cpuMhz} q0Cap=${r.q0Cap} nsVoiceHermite=${f(r.nsVoiceHermite)} nsVoiceLinear=${f(r.nsVoiceLinear)} " +
                    "nsVoiceCopy=${f(r.nsVoiceCopy)} nsVoice2=${f(r.nsVoice2)} nsSpectral=${f(r.nsSpectral)} nsComb=${f(r.nsComb)} nsCombNoDisp=${f(r.nsCombNoDispersion)} " +
                    "nsSoft=${f(r.nsSoft)} nsRoom=${f(r.nsRoom)} nsMaster=${f(r.nsMaster)} (normalised comb ${f(r.normalised(r.nsComb))} ns)")
                onVoiceCap?.invoke(r.q0Cap)
            }
        }, 1000)
    }

    private fun cpuMhz(): Int {
        var best = 0
        for (c in 0 until 8) runCatching {
            File("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000
        }.getOrNull()?.let { if (it > best) best = it }
        return if (best > 0) best else 2000
    }

    fun logStats() {
        w.audio.stats(stats); w.audio.clockStats(cs)
        Log.i(HK.TAG_AUDIO, "stats voices=${stats.voices} peak=${stats.voicesPeak} cap=${stats.voiceCap} noise=${stats.noiseVoices} stolen=${stats.stolen} " +
            "dropped=${stats.dropped} combs=${stats.combsActive} p50=${stats.blockP50Us} p99=${stats.blockP99Us} max=${stats.blockMaxUs} " +
            "cpu=${"%.1f".format(stats.cpuPct)} ur=${stats.underruns} slow=${stats.slowReads} head=${stats.headroomMinFrames} buf=${stats.bufferFrames} " +
            "parked=${stats.parked} fast=${stats.fastTrack} bankGen=${stats.bankGeneration} stub=${stats.bankStub} rebuilds=${stats.trackRebuilds} energyMiss=${stats.energyMiss}")
        Log.i(HK.TAG_CLOCK, "drift p99=${"%.3f".format(cs.driftP99Frames * 1000f / HK.SR)}ms fsFit=${"%.2f".format(cs.fsFit)} " +
            "dev=${"%.4f".format((cs.fsFit - HK.SR) * 100f / HK.SR)}% tsAcc=${cs.tsAccepted} tsRej=${cs.tsRejected} clockMiss=${cs.clockMiss} lat=${cs.latFrames} session=${cs.session}")
    }

    /** One line for the debug overlay. */
    fun debugLine(): String {
        w.audio.stats(stats); w.audio.clockStats(cs); w.audio.clock.sample(System.nanoTime(), sample)
        return "v ${stats.voices}/${stats.voiceCap} p50 ${stats.blockP50Us} p99 ${stats.blockP99Us}µs head ${stats.headroomMinFrames} " +
            "clk ${if (sample.fromTimestamp) "ts" else "est"} miss ${cs.clockMiss} ur ${stats.underruns}"
    }
}
