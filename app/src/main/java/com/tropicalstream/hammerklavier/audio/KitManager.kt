package com.tropicalstream.hammerklavier.audio

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.FallbackReason
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.KitCallback
import com.tropicalstream.hammerklavier.contract.KitService
import com.tropicalstream.hammerklavier.contract.KitState
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.TuningSpec
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import com.tropicalstream.hammerklavier.kit.DecodePlan
import com.tropicalstream.hammerklavier.kit.KeyMapBuilder
import com.tropicalstream.hammerklavier.kit.KitIndex
import com.tropicalstream.hammerklavier.kit.KitMapCodec
import com.tropicalstream.hammerklavier.kit.MappedBank
import com.tropicalstream.hammerklavier.kit.PcmCacheFormat
import com.tropicalstream.hammerklavier.kit.SampleStore
import com.tropicalstream.hammerklavier.kit.SynthBank
import java.io.File
import java.io.RandomAccessFile
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP4's [KitService] (PLAN §3.2, §3.3, §3.4, §3.19): `map.json` → validated index → PCM cache
 * (voiced progressively and resumably on HKVoicer, never while music plays) → mmap'd
 * [MappedBank]. Falls back to the APK stub bank (`instruments/stub/`, BANK_MISSING) when a kit's
 * map is missing or invalid, and to the in-code [SynthBank] when no decoder works, the probe
 * fails, or storage is too short even for the reduced grand. Callbacks are posted to main.
 */
class KitManager(ctx: Context, private val voicer: ExecutorService, private val loader: ExecutorService,
                 /** Integrator (M1): true = every instrument opens the stub kit (the stand-in bank). */
                 private val standIn: () -> Boolean = { false }) : KitService {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheDir = File(app.noBackupFilesDir, "pcm")
    private val scheduler = VoicingScheduler { SystemClock.elapsedRealtime() }
    private val probe = DecoderProbe(app, prefs)
    private val decoder = KitDecoder()                   // HKVoicer only
    private val generation = AtomicInteger(0)
    private val kits = EnumMap<InstrumentId, Kit>(InstrumentId::class.java)
    private val idleQueue = LinkedHashSet<InstrumentId>()
    @Volatile private var lastBench = ""

    /** Per-instrument state; fields written on HKVoicer, read on main through @Volatile. */
    private inner class Kit(val id: InstrumentId) {
        val callbacks = ArrayList<KitCallback>()          // main only
        @Volatile var index: KitIndex? = null
        @Volatile var cacheId: String = id.key
        @Volatile var bank: LoadedBank? = null
        @Volatile var state: KitState = KitState.Missing
        @Volatile var fallback: FallbackReason? = null
        @Volatile var opening = false
        @Volatile var voicingQueued = false
        @Volatile var released = false
        @Volatile var playableSent = false
        @Volatile var completeSent = false
        var plan: DecodePlan? = null                      // HKVoicer
        var ready: PcmCacheFormat.ReadyState = PcmCacheFormat.ReadyState.EMPTY
        var layout: PcmCacheFormat.Layout? = null
        var channel: java.nio.channels.FileChannel? = null
    }

    private fun kit(id: InstrumentId): Kit = synchronized(kits) { kits.getOrPut(id) { Kit(id) } }

    // =====================================================================================
    // KitService
    // =====================================================================================

    override fun state(id: InstrumentId): KitState = synchronized(kits) { kits[id]?.state } ?: KitState.Missing

    override fun info(id: InstrumentId): BankInfo? {
        val k = kit(id)
        k.bank?.let { return it.info }
        val idx = k.index ?: loadIndex(id.key)?.also { k.index = it } ?: return null
        return idx.toBankInfo(id = id)
    }

    override fun open(id: InstrumentId, cb: KitCallback) {
        val k = kit(id)
        k.released = false
        if (!k.callbacks.contains(cb)) k.callbacks += cb
        scheduler.update(scheduler.playing, id, null, scheduler.batteryTenths)
        val b = k.bank
        if (b != null) {
            // Already open: replay the current state to this callback.
            val st = k.state
            if (st is KitState.Voicing) cb.onProgress(id, st.fraction)
            if (k.playableSent) { cb.onPlayable(b); k.fallback?.let { cb.onFallback(b, it) } }
            if (k.completeSent) { cb.onProgress(id, 1f); cb.onComplete(b) }
            kickVoicing(k)
            return
        }
        if (k.opening) return
        k.opening = true
        voicer.execute { openTask(k) }
    }

    override fun keyMap(bank: LoadedBank, tuning: TuningSpec): KeyMap = when (bank) {
        is MappedBank -> KeyMapBuilder.build(bank.index, tuning, bank.readyMask)
        is SynthBank -> bank.keyMap(tuning)
        is SineBank -> KeyMapFixtures.forSineBank(bank.layers, KeyMapFixtures.Mode.HARD, bank.stops, bank.readyMask)
        else -> KeyMapFixtures.forSineBank(bank.info.layers, KeyMapFixtures.Mode.HARD, bank.info.stops, bank.readyMask)
    }

    override fun setPlaybackHint(playing: Boolean, activeId: InstrumentId?, q: QualityProfile, batteryTenths: Int) {
        scheduler.update(playing, activeId, q, batteryTenths)
        if (!playing) {
            activeId?.let { id -> synchronized(kits) { kits[id] }?.let { kickVoicing(it) } }
            if (idleQueue.isNotEmpty()) main.postDelayed({ drainIdleQueue() }, VoicingScheduler.OTHER_IDLE_MS + 100)
        }
    }

    override fun decodeWhenIdle(ids: List<InstrumentId>) {
        idleQueue.addAll(ids)
        main.postDelayed({ drainIdleQueue() }, scheduler.otherKitWaitMs() + 100)
    }

    override fun release(id: InstrumentId) {
        val k = synchronized(kits) { kits[id] } ?: return
        k.released = true                                 // voicing stops at the next unit boundary
        k.callbacks.clear()
        // The bank reference is dropped here; the engine and the prefetcher keep their own until
        // they hold a newer bank, and the mapping lives until GC (never unmapped).
        synchronized(kits) { kits.remove(id) }
    }

    override fun diagnostics(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["kits"] = "KitManager"
        for ((id, k) in synchronized(kits) { kits.toMap() }) {
            val st = k.state
            m[id.key] = when (st) {
                is KitState.Voicing -> "voicing ${(st.fraction * 100).toInt()}% playable=${st.playable}"
                is KitState.Fallback -> "fallback ${st.reason}"
                KitState.Complete -> "complete"
                KitState.Missing -> "missing"
            } + " kit=${k.index?.kit} mask=0x${java.lang.Long.toHexString(k.ready.mask)}"
        }
        (probe.last as? DecoderProbe.Result.Ok)?.let { m["probeOffset"] = "${it.offset} (${it.note})" }
        (probe.last as? DecoderProbe.Result.Failed)?.let { m["probe"] = it.reason }
        m["codec"] = decoder.codecName
        if (lastBench.isNotEmpty()) m["decodeBench"] = lastBench
        return m
    }

    // =====================================================================================
    // HKVoicer
    // =====================================================================================

    private fun post(r: () -> Unit) { main.post(r) }

    private fun loadIndex(dir: String): KitIndex? {
        val base = "instruments/$dir"
        val json = runCatching { app.assets.open("$base/map.json").use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull() ?: return null
        val env = runCatching { app.assets.open("$base/env.bin").use { it.readBytes() } }.getOrNull() ?: ByteArray(0)
        val files = runCatching { app.assets.list("$base/u")?.toSet() }.getOrNull() ?: emptySet()
        return when (val r = KitMapCodec.decode(json, env) { f -> f.removePrefix("u/") in files }) {
            is KitMapCodec.Result.Ok -> r.index
            is KitMapCodec.Result.Invalid -> { Log.w(HK.TAG_KIT, "$base/map.json invalid: ${r.reasons.take(5)}"); null }
        }
    }

    private fun openTask(k: Kit) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            val forceStub = runCatching { standIn() }.getOrDefault(false)
            var idx = if (forceStub) null else k.index ?: loadIndex(k.id.key)
            var reason: FallbackReason? = null
            var assetDir = k.id.key
            if (forceStub) Log.i(HK.TAG_KIT, "${k.id.key}: stand-in stub kit (kit.standIn)")
            if (idx == null) {
                reason = FallbackReason.BANK_MISSING
                idx = loadIndex("stub")
                assetDir = "stub"
                if (idx == null) { useSynth(k, FallbackReason.BANK_MISSING); return }
            } else k.index = idx
            when (val p = probe.run(decoder)) {
                is DecoderProbe.Result.Failed -> {
                    useSynth(k, if (p.decoderMissing) FallbackReason.DECODER_UNAVAILABLE else FallbackReason.PROBE_FAILED); return
                }
                is DecoderProbe.Result.Ok -> {}
            }
            k.cacheId = if (assetDir == "stub") "stub" else k.id.key
            if (!prepareCache(k, idx, assetDir, reason)) return
            runVoicing(k, idx, assetDir)
        } catch (t: Throwable) {
            Log.e(HK.TAG_KIT, "open ${k.id.key} failed", t)
            if (k.bank == null) useSynth(k, FallbackReason.DECODER_UNAVAILABLE)
        } finally {
            k.opening = false
        }
    }

    private fun useSynth(k: Kit, reason: FallbackReason) {
        val b = SynthBank(k.id, generation.incrementAndGet(), reason)
        k.bank = b; k.fallback = reason; k.state = KitState.Fallback(reason)
        k.playableSent = true; k.completeSent = true
        Log.w(HK.TAG_KIT, "${k.id.key}: SynthBank stand-in ($reason)")
        post {
            for (cb in k.callbacks.toList()) { cb.onProgress(k.id, 1f); cb.onPlayable(b); cb.onFallback(b, reason); cb.onComplete(b) }
        }
    }

    private fun bootCount(): Long = runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong() }.getOrDefault(-1L)

    /** Cache files, header, ready mask, CRC checks, storage; opens the bank. false = fell back. */
    private fun prepareCache(k: Kit, idx: KitIndex, assetDir: String, reason: FallbackReason?): Boolean {
        cacheDir.mkdirs()
        val cid = k.cacheId
        val names = cacheDir.list()?.toList() ?: emptyList()
        for (n in DecodePlan.staleFiles(names, cid, idx.sha8)) File(cacheDir, n).delete()
        val pcm = File(cacheDir, PcmCacheFormat.pcmName(cid, idx.sha8))
        val readyF = File(cacheDir, PcmCacheFormat.readyName(cid, idx.sha8))
        val okF = File(cacheDir, PcmCacheFormat.okName(cid, idx.sha8))
        val layout = PcmCacheFormat.layout(idx)
        var allocated = pcm.isFile && RandomAccessFile(pcm, "r").use { PcmCacheFormat.readHeader(it.channel, idx.sha1) is PcmCacheFormat.Header.Ok }
        var ready = if (allocated) PcmCacheFormat.readReady(readyF) ?: PcmCacheFormat.ReadyState.EMPTY else PcmCacheFormat.ReadyState.EMPTY
        if (!allocated) { readyF.delete(); okF.delete() }
        // Only units this kit has.
        val known = idx.units.fold(0L) { m, u -> m or (1L shl u.id) }
        if (ready.mask and known.inv() != 0L) ready = PcmCacheFormat.ReadyState(ready.mask and known, ready.crc, ready.bootCount)
        val boot = bootCount()
        var plan = DecodePlan.plan(idx, ready, okF.isFile, boot, cacheDir.usableSpace, allocated)
        if (!plan.storageOk) { useSynth(k, FallbackReason.LOW_STORAGE); return false }
        if (!allocated) { PcmCacheFormat.create(pcm, layout); allocated = true }
        val ch = RandomAccessFile(pcm, "rw").channel
        if (plan.verify.isNotEmpty()) {
            for (u in plan.verify) if (!PcmCacheFormat.verifyUnit(ch, layout, idx, ready, u)) {
                Log.w(HK.TAG_KIT, "${k.id.key}: unit $u failed its CRC; re-voicing")
                ready = ready.without(u)
            }
            Log.i(HK.TAG_KIT, "${k.id.key}: CRC check of units ${plan.verify.joinToString(",")} (boot or unclean exit); mask now 0x${java.lang.Long.toHexString(ready.mask)}")
            ready = PcmCacheFormat.ReadyState(ready.mask, ready.crc, boot)
            PcmCacheFormat.writeReady(readyF, ready)
            okF.delete()
            plan = DecodePlan.plan(idx, ready, false, boot, cacheDir.usableSpace, true)
        }
        k.ready = ready; k.layout = layout; k.channel = ch; k.plan = plan
        val fb = reason ?: if (plan.reduced) FallbackReason.LOW_STORAGE else null
        k.fallback = fb
        val store = SampleStore.open(pcm, idx.sha1)
        val bank = MappedBank(idx, store, generation.incrementAndGet(), k.id, fb, ready.mask)
        k.bank = bank
        publishState(k, idx)
        if (plan.playable(ready.mask)) announcePlayable(k, bank)
        if (plan.complete) announceComplete(k, bank, okF)
        Log.i(HK.TAG_KIT, "${k.id.key}: kit=${idx.kit} assets=$assetDir cache=${pcm.name} mask=0x${java.lang.Long.toHexString(ready.mask)} " +
            "pending=${plan.pending.joinToString(",")} reduced=${plan.reduced}")
        return true
    }

    private fun publishState(k: Kit, idx: KitIndex) {
        val plan = k.plan ?: return
        val f = plan.fraction(idx, k.ready.mask)
        val fb = k.fallback
        k.state = when {
            fb != null && fb != FallbackReason.LOW_STORAGE -> KitState.Fallback(fb)
            plan.complete -> KitState.Complete
            else -> KitState.Voicing(f, plan.playable(k.ready.mask))
        }
        val id = k.id
        post { for (cb in k.callbacks.toList()) cb.onProgress(id, f) }
    }

    private fun announcePlayable(k: Kit, bank: LoadedBank) {
        if (k.playableSent) {
            post { for (cb in k.callbacks.toList()) cb.onLayersChanged(bank) }
        } else {
            k.playableSent = true
            val fb = k.fallback
            post { for (cb in k.callbacks.toList()) { cb.onPlayable(bank); if (fb != null) cb.onFallback(bank, fb) } }
        }
        preRead(bank)
    }

    private fun announceComplete(k: Kit, bank: LoadedBank, okF: File) {
        if (!okF.isFile) runCatching { okF.writeText("ok\n") }
        if (k.completeSent) return
        k.completeSent = true
        post { for (cb in k.callbacks.toList()) { cb.onProgress(k.id, 1f); cb.onComplete(bank) } }
    }

    /** §3.4.1: the first 150 ms of every ready region, positional reads, on HKLoader. */
    private fun preRead(bank: LoadedBank) {
        val mb = bank as? MappedBank ?: return
        loader.execute {
            val rd = mb.newReader()
            val mask = mb.readyMask
            val whole = mayPreloadWhole(mb)
            for (r in mb.index.regions) if ((mask ushr r.unit) and 1L == 1L)
                rd.prefetch(r.id, 0, if (whole) r.frames else HEAD_FRAMES)
        }
    }

    private fun mayPreloadWhole(b: MappedBank): Boolean {
        if (b.store.layout.totalBytes > PRELOAD_MAX) return false
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return !mi.lowMemory && mi.availMem - b.store.layout.totalBytes > 4 * mi.threshold
    }

    private fun kickVoicing(k: Kit) {
        if (k.released || k.voicingQueued || k.opening) return
        val plan = k.plan ?: return
        val idx = k.index ?: return
        if (plan.complete) return
        k.voicingQueued = true
        voicer.execute {
            k.voicingQueued = false
            try { runVoicing(k, idx, if (k.cacheId == "stub") "stub" else k.id.key) }
            catch (t: Throwable) { Log.e(HK.TAG_KIT, "voicing ${k.id.key}", t) }
        }
    }

    private fun drainIdleQueue() {
        val it = idleQueue.iterator()
        while (it.hasNext()) {
            val id = it.next()
            val k = kit(id)
            if (k.bank == null && !k.opening) { k.opening = true; voicer.execute { openTask(k) } } else kickVoicing(k)
        }
    }

    private fun activeComplete(except: InstrumentId): Boolean {
        val a = scheduler.activeId ?: return true
        if (a == except) return true
        return synchronized(kits) { kits[a] }?.plan?.complete ?: true
    }

    /** Voices pending units in order while the scheduler allows. HKVoicer. */
    private fun runVoicing(k: Kit, idx: KitIndex, assetDir: String) {
        val layout = k.layout ?: return
        val ch = k.channel ?: return
        val okF = File(cacheDir, PcmCacheFormat.okName(k.cacheId, idx.sha8))
        val readyF = File(cacheDir, PcmCacheFormat.readyName(k.cacheId, idx.sha8))
        val offset = (probe.last as? DecoderProbe.Result.Ok)?.offset ?: 0
        while (!k.released) {
            val plan = k.plan ?: return
            if (plan.complete) { k.bank?.let { announceComplete(k, it, okF) }; publishState(k, idx); return }
            val playable = plan.playable(k.ready.mask)
            if (!scheduler.mayVoice(k.id, playable, activeComplete(k.id))) {
                if (k.id != scheduler.activeId) idleQueue.add(k.id)
                return
            }
            // The first playable set: two codecs in parallel (the second on a helper thread).
            var helper: Thread? = null
            val helperUnits = if (!playable) plan.playableSet.filter { it >= 62 && !k.ready.has(it) } else emptyList()
            val unit = plan.pending.firstOrNull { it !in helperUnits } ?: plan.next
            if (helperUnits.isNotEmpty() && unit !in helperUnits) {
                helper = Thread({
                    val d2 = KitDecoder()
                    try { for (u in helperUnits) if (!voiceUnit(k, idx, assetDir, d2, u, layout, ch, offset, readyF)) break }
                    finally { d2.release() }
                }, "HKVoicer2").also { it.priority = Thread.MIN_PRIORITY; it.start() }
            }
            val ok = voiceUnit(k, idx, assetDir, decoder, unit, layout, ch, offset, readyF)
            helper?.join()
            if (!ok) return
        }
    }

    /** One unit; false = stop voicing (yield, failure or release). */
    private fun voiceUnit(k: Kit, idx: KitIndex, assetDir: String, d: KitDecoder, unit: Int, layout: PcmCacheFormat.Layout,
                          ch: java.nio.channels.FileChannel, offset: Int, readyF: File): Boolean {
        if (k.released) return false
        val u = idx.unit(unit) ?: return false
        val regions = idx.regions.filter { it.unit == unit }
        val afd = try { app.assets.openFd("instruments/$assetDir/${u.file}") } catch (e: Exception) {
            Log.e(HK.TAG_KIT, "${k.id.key}: ${u.file} unreadable", e); return false
        }
        val res = afd.use { d.decodeUnit(it, regions, layout, ch, offset) { scheduler.shouldYield() || k.released } }
        when (res) {
            is DecodeResult.Done -> {
                val bank = k.bank
                synchronized(k) {
                    k.ready = k.ready.with(unit, res.crc, bootCount())
                    PcmCacheFormat.writeReady(readyF, k.ready)
                    k.plan = k.plan?.advance(k.ready.mask)
                    bank?.readyMask = k.ready.mask
                }
                val audioS = u.frames.toDouble() / HK.SR
                Log.i(HK.TAG_KIT, "${k.id.key}: unit $unit (${u.label}) voiced, ${"%.1f".format(audioS)} s audio in ${res.wallMs} ms " +
                    "(${"%.0f".format(audioS * 1000 / res.wallMs.coerceAtLeast(1))}× real time)")
                publishState(k, idx)
                val p = k.plan
                if (bank != null && p != null && p.playable(k.ready.mask)) announcePlayable(k, bank)
                return true
            }
            DecodeResult.Yielded -> return false
            is DecodeResult.Failed -> {
                Log.e(HK.TAG_KIT, "${k.id.key}: unit $unit failed: ${res.reason}")
                if (!k.playableSent) useSynth(k, FallbackReason.DECODER_UNAVAILABLE)
                return false
            }
        }
    }

    /**
     * The M1 decode bench (`--ez bench true`): decodes [asset] (default the 60 s bench stream)
     * into memory and reports × real time and the setup cost. HKVoicer; returns the report.
     */
    fun decodeBench(asset: String = BENCH_ASSET): String {
        val t0 = SystemClock.elapsedRealtimeNanos()
        var firstPcm = -1L
        var frames = 0L
        val err = try {
            app.assets.openFd(asset).use { fd ->
                decoder.decodeStream(fd, { false }) { _, n, _ -> if (firstPcm < 0) firstPcm = SystemClock.elapsedRealtimeNanos(); frames += n }
            }
        } catch (e: Exception) { e.toString() }
        val wall = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        val setupMs = if (firstPcm > 0) (firstPcm - t0) / 1e6 else -1.0
        lastBench = if (err != null) "failed: $err" else
            "${"%.1f".format(frames.toDouble() / HK.SR)} s in ${"%.2f".format(wall)} s = ${"%.0f".format(frames / HK.SR / wall)}× real time, setup ${"%.0f".format(setupMs)} ms, codec ${decoder.codecName}"
        Log.i(HK.TAG_KIT, "decode bench: $lastBench")
        return lastBench
    }

    companion object {
        const val PREFS = "hk_kits"
        const val HEAD_FRAMES = HK.SR * 150 / 1000
        const val PRELOAD_MAX = 96L * 1024 * 1024
        const val BENCH_ASSET = "instruments/stub/u/bench.opus"
    }
}
