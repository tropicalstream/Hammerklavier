package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.AudioControl
import com.tropicalstream.hammerklavier.contract.AudioListener
import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.ClockStats
import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.EnergyRing
import com.tropicalstream.hammerklavier.contract.ImportResult
import com.tropicalstream.hammerklavier.contract.ImportScan
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.LibraryService
import com.tropicalstream.hammerklavier.contract.ListenerPose
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.MechanicsEvaluator
import com.tropicalstream.hammerklavier.contract.MixSettings
import com.tropicalstream.hammerklavier.contract.Movement
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.Placement
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.RenderControl
import com.tropicalstream.hammerklavier.contract.RenderOverrides
import com.tropicalstream.hammerklavier.contract.RenderSettings
import com.tropicalstream.hammerklavier.contract.RenderStats
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.RoomDesigner
import com.tropicalstream.hammerklavier.contract.RouteInfo
import com.tropicalstream.hammerklavier.contract.SceneFactory
import com.tropicalstream.hammerklavier.contract.ScoreCompiler
import com.tropicalstream.hammerklavier.contract.ScoreFacts
import com.tropicalstream.hammerklavier.contract.Shelf
import com.tropicalstream.hammerklavier.contract.SongClock
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.UiEvent
import com.tropicalstream.hammerklavier.contract.VenueGeometry
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.Work
import com.tropicalstream.hammerklavier.contract.stub.FakeClock
import com.tropicalstream.hammerklavier.contract.stub.FixedRoom
import com.tropicalstream.hammerklavier.contract.stub.MemSettings
import com.tropicalstream.hammerklavier.contract.stub.NullAudio
import com.tropicalstream.hammerklavier.contract.stub.StubKits
import com.tropicalstream.hammerklavier.contract.stub.StubScenes
import com.tropicalstream.hammerklavier.contract.stub.StubScoreCompiler
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Shared call log: every fake appends "audio.x" / "render.x" / "kits.x" / "compile" entries in call order. */
class CallLog { val calls = ArrayList<String>(); fun add(s: String) { calls.add(s) }
    fun indexOf(prefix: String, from: Int = 0): Int { for (i in from until calls.size) if (calls[i].startsWith(prefix)) return i; return -1 }
    fun count(prefix: String) = calls.count { it.startsWith(prefix) }
    fun clear() = calls.clear() }

/** NullAudio with a call log. */
class RecordingAudio(val log: CallLog, val fake: FakeClock) : AudioControl {
    val inner = NullAudio(fake)
    val perfs = ArrayList<Triple<Performance?, Long, Boolean>>()
    val rooms = ArrayList<RoomDesign>()
    val seeks = ArrayList<Long>()
    override val clock: SongClock get() = inner.clock
    override val energy: EnergyRing get() = inner.energy
    override val route: RouteInfo get() = inner.route
    override fun start() { log.add("audio.start"); inner.start() }
    override fun stop() { log.add("audio.stop"); inner.stop() }
    override fun setBank(bank: LoadedBank, keyMap: KeyMap, profile: InstrumentProfile) { log.add("audio.setBank ${bank.info.instrument}"); inner.setBank(bank, keyMap, profile) }
    override fun setKeyMap(keyMap: KeyMap) { log.add("audio.setKeyMap"); inner.setKeyMap(keyMap) }
    override fun setPerformance(p: Performance?, startUs: Long, autoPlay: Boolean) {
        log.add("audio.setPerformance gen=${p?.generation} start=$startUs auto=$autoPlay"); perfs.add(Triple(p, startUs, autoPlay))
        inner.setPerformance(p, startUs, autoPlay)
    }
    override fun play() { log.add("audio.play"); inner.play() }
    override fun pause(fadeMs: Int) { log.add("audio.pause $fadeMs"); inner.pause(fadeMs) }
    override fun seek(us: Long) { log.add("audio.seek $us"); seeks.add(us); inner.seek(us) }
    override fun setRate(rate: Float) { log.add("audio.setRate $rate"); inner.setRate(rate) }
    override fun setQuality(q: QualityProfile) { log.add("audio.setQuality ${q.level}"); inner.setQuality(q) }
    override fun setRoom(d: RoomDesign, glideMs: Int) { log.add("audio.setRoom"); rooms.add(d); inner.setRoom(d, glideMs) }
    override fun setMix(m: MixSettings) { log.add("audio.setMix"); inner.setMix(m) }
    override fun setRegistration(mask: Int) { log.add("audio.setRegistration $mask"); inner.setRegistration(mask) }
    override fun setDuck(gain: Float) { inner.setDuck(gain) }
    override fun bench(seconds: Int) {}
    override fun setListener(l: AudioListener?) { inner.setListener(l) }
    override fun stats(out: AudioStats) = inner.stats(out)
    override fun clockStats(out: ClockStats) = inner.clockStats(out)
}

class RecordingRender(val log: CallLog) : RenderControl {
    var flash = false; var shownTitle: String? = null
    override fun bind(clock: SongClock, energy: EnergyRing, mech: MechanicsEvaluator, scenes: SceneFactory) {}
    override fun setPerformance(p: Performance?, profile: InstrumentProfile) { log.add("render.setPerformance gen=${p?.generation}") }
    override fun setInstrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int) { log.add("render.setInstrument $id") }
    override fun setView(v: ViewId, framing: Int) { log.add("render.setView $v $framing") }
    override fun setQuality(q: QualityProfile) { log.add("render.setQuality ${q.level}") }
    override fun setSettings(s: RenderSettings) { log.add("render.setSettings") }
    override fun setStereo(on: Boolean) {}
    override fun setIdle(idle: Boolean) {}
    override fun setSyncFlash(on: Boolean) { flash = on; log.add("render.setSyncFlash $on") }
    override fun setTitle(text: String?) { shownTitle = text }
    override fun setStageHidden(hidden: Boolean) {}
    override fun setOverrides(o: RenderOverrides) {}
    override fun recenter() { log.add("render.recenter") }
    override fun onResume() {}
    override fun onPause() {}
    override fun stats(out: RenderStats) {}
}

class RecordingDesigner(val log: CallLog) : RoomDesigner {
    class Call(val listener: ListenerPose, val source: FloatArray, val mode: ReverbMode, val bench: Float, val embeddedDb: Float, val placement: Placement)
    val calls = ArrayList<Call>()
    override fun design(g: VenueGeometry, placement: Placement, sourcePiano: FloatArray, listener: ListenerPose,
                        mode: ReverbMode, benchDistanceM: Float, embeddedRoomDb: Float): RoomDesign {
        calls.add(Call(listener, sourcePiano.copyOf(), mode, benchDistanceM, embeddedRoomDb, placement)); return FixedRoom.PLAYER
    }
}

/** Compiles any movement to the SCALE fixture (tagged with the movement id); logs "compile <id> gen=<g>". */
class RecordingCompiler(val log: CallLog) : ScoreCompiler {
    private val stub = StubScoreCompiler()
    override fun sniff(head: ByteArray) = true
    override fun inspect(bytes: ByteArray): ScoreFacts = stub.inspect(bytes)
    override fun compile(bytes: ByteArray, id: String, generation: Int, profile: InstrumentProfile, opts: CompileOptions): CompileResult {
        log.add("compile $id gen=$generation ${profile.id} flat=${opts.flatVelocity}")
        return CompileResult.Ok(stub.synthetic(SyntheticScore.SCALE, profile, generation))
    }
    override fun synthetic(kind: SyntheticScore, profile: InstrumentProfile, generation: Int): Performance {
        log.add("synthetic $kind gen=$generation"); return stub.synthetic(kind, profile, generation)
    }
}

class FakeLibrary(val model: LibraryModel) : LibraryService {
    override fun load(): LibraryModel = model
    override fun readBytes(m: Movement): ByteArray = m.id.toByteArray()
    override fun rescan(): ImportScan = ImportScan(0, emptyList(), false)
    override fun importFile(tmp: File, relativeName: String): ImportResult = ImportResult(false, relativeName, null, null, null)
    override fun importZip(tmp: File): List<ImportResult> = emptyList()
    override fun delete(movementId: String): Boolean = false
    override val scoresDir: File = File("Scores")
}

/** An executor that queues until [runAll]. */
class QueueExecutor : Executor {
    val q = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { q.add(command) }
    fun runAll() { while (q.isNotEmpty()) q.poll().run() }
}

object TestLibrary {
    private fun mv(id: String, work: String, title: String) = Movement(id = id, workId = work, title = title, asset = null, file = null,
        sha1 = "", durationSec = 60f, lowKey = 21, highKey = 108, hasSustain = false, hasSoft = false, hasSostenuto = false,
        pedalMode = PedalMode.NONE)
    private fun work(id: String, mids: List<String>, inst: InstrumentId = InstrumentId.GRAND, policy: String = "as-is") = Work(
        id = id, composer = "Composer $id", composerShort = "C$id", title = "Work $id", shortTitle = "W$id", catalogue = null,
        year = null, era = "baroque", defaultInstrument = inst, altInstruments = InstrumentId.entries.toList(), sourceId = "s",
        tier = "A", velocityPolicy = policy, tuning = null, movementIds = mids, imported = false)

    /** Shelf "bach": w1 (a, b, c), w2 (a), w3 (a, b). Shelf "other": w4 (a, harpsichord), w5 (a, flat). Start here: w3.a, w1.a, w4.a. */
    fun model(): LibraryModel {
        val works = linkedMapOf(
            "w1" to work("w1", listOf("w1.a", "w1.b", "w1.c")), "w2" to work("w2", listOf("w2.a")),
            "w3" to work("w3", listOf("w3.a", "w3.b")), "w4" to work("w4", listOf("w4.a"), InstrumentId.HARPSICHORD),
            "w5" to work("w5", listOf("w5.a"), policy = "flat"))
        val movements = LinkedHashMap<String, Movement>()
        for (w in works.values) for (m in w.movementIds) movements[m] = mv(m, w.id, "Mvt ${m.substringAfter('.')}")
        return LibraryModel(shelves = listOf(Shelf("bach", "Bach", listOf("w1", "w2", "w3")), Shelf("other", "Other", listOf("w4", "w5"))),
            works = works, movements = movements, sources = emptyMap(), startHere = listOf("w3.a", "w1.a", "w4.a"))
    }
}

/** A SessionController on the stubs with a manual clock (ms and ns) and direct or queued loader. */
class SessionRig(val settings: MemSettings = MemSettings(), val queued: Boolean = false) {
    var tNanos = 1_000_000_000L
    val log = CallLog()
    val fake = FakeClock { tNanos }
    val audio = RecordingAudio(log, fake)
    val render = RecordingRender(log)
    val kits = StubKits()
    val designer = RecordingDesigner(log)
    val compiler = RecordingCompiler(log)
    val library = FakeLibrary(TestLibrary.model())
    val loaderQ = QueueExecutor()
    val events = ArrayList<UiEvent>()
    val c = SessionController(audio, render, kits, library, compiler, designer, StubScenes(), settings,
        if (queued) loaderQ else Executor { it.run() }, { it.run() }, { tNanos / 1_000_000L }).also {
        it.nanoTime = { tNanos }
        it.onUiEvent = { e -> events.add(e) }
    }

    fun advanceMs(ms: Long) { tNanos += ms * 1_000_000L }
    fun start(): SessionRig { c.start(); loaderQ.runAll(); return this }
    fun drain() = loaderQ.runAll()
}
