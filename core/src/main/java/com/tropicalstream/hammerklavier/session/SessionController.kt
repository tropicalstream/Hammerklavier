package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.ImportResult

import com.tropicalstream.hammerklavier.contract.AudioControl
import com.tropicalstream.hammerklavier.contract.AudioListener
import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.CompanionCommands
import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.FallbackReason
import com.tropicalstream.hammerklavier.contract.InstrumentAnchors
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KitCallback
import com.tropicalstream.hammerklavier.contract.KitService
import com.tropicalstream.hammerklavier.contract.KitState
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.LibraryService
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.Movement
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.Playlist
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.TuningSpec
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.RenderControl
import com.tropicalstream.hammerklavier.contract.RenderSettings
import com.tropicalstream.hammerklavier.contract.RoomDesigner
import com.tropicalstream.hammerklavier.contract.RouteInfo
import com.tropicalstream.hammerklavier.contract.SceneFactory
import com.tropicalstream.hammerklavier.contract.ScoreCompiler
import com.tropicalstream.hammerklavier.contract.SettingsStore
import com.tropicalstream.hammerklavier.contract.StatusCode
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.SyntheticSpecs
import com.tropicalstream.hammerklavier.contract.UiAction
import com.tropicalstream.hammerklavier.contract.UiEvent
import com.tropicalstream.hammerklavier.contract.UiFacts
import com.tropicalstream.hammerklavier.contract.VenueGeometry
import com.tropicalstream.hammerklavier.contract.ViewId
import java.util.concurrent.Executor

/**
 * The session orchestration of PLAN §2.6 and §4.9 (WP12, pure). Every public method runs on
 * main; work that reads files, compiles scores or builds key maps runs on [loader] and comes back
 * through [post]. AppController (WP0) forwards Android events here and draws [facts].
 *
 * - **Playback:** [onAction] / [CompanionCommands] → `playMovement`: a new Performance per
 *   request with generation + 1 (the counter never goes back), compiled on HKLoader with the
 *   effective profile and the work's `velocityPolicy`; stale results (a newer request exists) are
 *   dropped. The next playlist item is pre-compiled when a movement starts.
 * - **Playlists** (§1.5): movement → rest of its work → the works after it on the chosen shelf;
 *   [SHELF_START_HERE] and [SHELF_RECENT] play their lists; `Previous` > 3 s in restarts.
 * - **Instrument switch** (§2.6 step 5): `audio.pause(30)` → `kits.open` → KeyMap (HKLoader) →
 *   new Performance from the same bytes → `audio.setBank` → `audio.setPerformance(p, −1,
 *   wasPlaying)` → new room design. The renderer gets `setInstrument` / `setPerformance` and is
 *   never waited for. The choice is remembered per work.
 * - **View change:** `render.setView` and exactly one `audio.setRoom` with the §5.6 listener.
 * - **Thermal level → quality**, the **sync test**, **seek units** ([SeekUnits]), **status**
 *   ([StatusBoard]), **resume point and recently played** (saved every 5 s, on pause, on leave).
 */
class SessionController(
    private val audio: AudioControl, private val render: RenderControl, private val kits: KitService,
    private val library: LibraryService, private val compiler: ScoreCompiler, private val designer: RoomDesigner,
    private val scenes: SceneFactory, private val settings: SettingsStore, private val loader: Executor,
    private val post: (Runnable) -> Unit, private val nowMs: () -> Long) : CompanionCommands, AudioListener {

    // ─── wiring hooks (set by AppController) ───
    /** Forwarded to UiStateMachine.onEvent. */
    var onUiEvent: ((UiEvent) -> Unit)? = null
    /** Window brightness cap of the quality rung (-1 = system). */
    var onBrightnessCap: ((Float) -> Unit)? = null
    /** UiAction.Leave: the activity leaves (moveTaskToBack / finish). */
    var onLeave: (() -> Unit)? = null
    /** UiAction.RotateToken: the companion token owner rotates it. */
    var onRotateToken: (() -> Unit)? = null
    /** The clock time base passed to SongClock.sample (System.nanoTime on the device). */
    var nanoTime: () -> Long = { System.nanoTime() }
    /** Status priorities; AppController passes WP10's UiText table. */
    var statusPriority: (StatusCode) -> Int = StatusBoard::defaultPriority
        set(v) { field = v; status = StatusBoard(v).also { nb -> copyStatus(status, nb) } }

    var companionUrl: String? = null
    var companionToken: String = ""
    var version: String = ""
    var debugLine: String? = null
    var batteryTenths: Int = 0

    // ─── state (main) ───
    private val prefs = SessionSettings(settings)
    var status = StatusBoard(statusPriority); private set
    private val playlist = Playlist()
    private val sample = ClockSample()
    private val stats = AudioStats()

    var model: LibraryModel? = null; private set
    var instrument: InstrumentId = InstrumentId.GRAND; private set
    var view: ViewId = ViewId.PLAYER; private set
    var framing: Int = 0; private set
    var qualityLevel: Int = 0; private set
    var quality: QualityProfile = QualityLadder.of(0, HK_DEFAULT_CAP); private set
    var shelfId: String? = null; private set
    var movementId: String? = null; private set
    var current: Performance? = null; private set
    var route: RouteInfo = audio.route; private set
    var sessions: Int = 0; private set
    var resume: ResumePoint? = null; private set

    /** The highest generation handed out (every new Performance gets ++). */
    var generation: Int = 0; private set
    /** The generation last sent to audio.setPerformance. */
    var sentGeneration: Int = -1; private set

    private var wanted: Request? = null                 // the request whose result will be sent
    private var pendingSend: Pair<Request, Performance>? = null  // compiled, waiting for its instrument's bank
    private var precompiled: Pair<Request, Performance>? = null
    private var bankInstrument: InstrumentId? = null    // instrument whose bank audio has
    private val banks = HashMap<InstrumentId, LoadedBank>()
    private val anchors = HashMap<InstrumentId, InstrumentAnchors>()
    private var venue: VenueGeometry? = null
    private var kitPlayableSent = false
    private var lastResumeSaveMs = Long.MIN_VALUE
    private var syncReturn: ResumePoint? = null
    private var syncOn = false
    /** What the user asked for (send autoPlay, toggle, end); the clock lags SET_PERF by a block. */
    private var intendedPlaying = false
    private var roomAfterSend = false
    private var q0Cap = HK_DEFAULT_CAP
    @Volatile private var loaderBytes: Pair<String, ByteArray>? = null   // HKLoader only: bytes of the last movement read

    @Volatile private var nowPlaying: String = "{}"

    private enum class Kind { NEW, SWITCH, RESTORE, SYNC, PRECOMPILE }
    private class Request(val gen: Int, val movementId: String, val instrument: InstrumentId, val startUs: Long,
                          val autoPlay: Boolean, val kind: Kind)

    // ═══════════ lifecycle ═══════════

    /** App start (§2.6 step 1): settings, listener, mix, quality, renderer state, the kit, the library. */
    fun start() {
        sessions = settings.getInt(SessionKeys.SESSIONS, 0) + 1
        settings.putInt(SessionKeys.SESSIONS, sessions)
        q0Cap = settings.getInt(SessionKeys.Q0_CAP, HK_DEFAULT_CAP)
        instrument = InstrumentId.of(settings.getString(SessionKeys.INSTRUMENT, "")) ?: InstrumentId.GRAND
        view = runCatching { ViewId.valueOf(settings.getString(SessionKeys.VIEW, ViewId.PLAYER.name)) }.getOrDefault(ViewId.PLAYER)
        framing = settings.getInt(SessionKeys.FRAMING, 0).coerceIn(0, 1)
        resume = ResumePoint.load(settings)
        audio.setListener(this)
        audio.setMix(prefs.mix())
        audio.setRate(prefs.tempoPct / 100f)
        audio.setRegistration(prefs.registration)
        applyQuality(qualityLevel)
        render.setInstrument(instrument, look(), lastDamper(instrument))
        render.setView(view, framing)
        pushRenderSettings()
        openKit(instrument)
        loader.execute {
            val m = runCatching { library.load() }.getOrNull()
            post(Runnable { if (m != null) model = m; refreshNowPlaying() })
        }
    }

    /** EngineBench's Q0 voice cap (§3.14). */
    fun setQ0Cap(cap: Int) {
        q0Cap = cap; settings.putInt(SessionKeys.Q0_CAP, cap); applyQuality(qualityLevel)
    }

    /** §2.6 step 7: ThermalGovernor's level. */
    fun onThermalLevel(level: Int) {
        val l = level.coerceIn(0, QualityLadder.MAX)
        if (l == qualityLevel && quality.voiceCap == QualityLadder.of(l, q0Cap).voiceCap) return
        val wasRest = qualityLevel == QualityLadder.MAX
        applyQuality(l)
        val isRest = l == QualityLadder.MAX
        if (isRest && !wasRest) { status.post(StatusCode.DISPLAY_REST, emptyList(), nowMs(), StatusBoard.PERSISTENT); onUiEvent?.invoke(UiEvent.REST_ON) }
        if (!isRest && wasRest) { status.clear(StatusCode.DISPLAY_REST); onUiEvent?.invoke(UiEvent.REST_OFF) }
    }

    private fun applyQuality(level: Int) {
        qualityLevel = level
        quality = QualityLadder.of(level, q0Cap)
        audio.setQuality(quality)
        render.setQuality(quality)
        onBrightnessCap?.invoke(quality.brightnessCap)
        kits.setPlaybackHint(intendedPlaying, instrument, quality, batteryTenths)
    }

    /** Every 1 s (§2.6 step 8): stats (which also report the end of a movement), now-playing, resume every 5 s. */
    fun tick() {
        audio.stats(stats)
        val now = nowMs()
        if (intendedPlaying && (lastResumeSaveMs == Long.MIN_VALUE || now - lastResumeSaveMs >= RESUME_EVERY_MS)) saveResume()
        refreshNowPlaying()
    }

    /** The app is left (§1.10): the resume point is saved; AppController pauses audio as §1.10 says. */
    fun onAppLeft() { saveResume() }

    /** A status from another producer (import results, crash of the last session, …). */
    fun postStatus(code: StatusCode, args: List<String> = emptyList(), durationMs: Long = StatusBoard.DEFAULT_MS) =
        status.post(code, args, nowMs(), durationMs)

    // ═══════════ UiActions ═══════════

    fun onAction(a: UiAction) {
        when (a) {
            UiAction.Enter -> enter()
            UiAction.PlayPause -> toggle()
            UiAction.Next -> next()
            UiAction.Previous -> previous()
            is UiAction.Play -> playMovement(a.movementId, null, 0L, a.shelfId, rebuild = true)
            is UiAction.Seek -> seekSongUs(a.us)
            is UiAction.SetView -> setView(a.view, a.framing)
            UiAction.Recenter -> render.recenter()
            UiAction.ShowHud -> {}
            UiAction.Leave -> { saveResume(); onLeave?.invoke() }
            is UiAction.SetInstrument -> switchInstrument(a.id)
            is UiAction.SetTempo -> { prefs.tempoPct = a.pct; audio.setRate(prefs.tempoPct / 100f) }
            is UiAction.SetTuning -> setTuning(a.instrument, a.tuning)
            is UiAction.SetRegistration -> { prefs.registration = a.mask; audio.setRegistration(prefs.registration) }
            is UiAction.SetMix -> setMix(a)
            is UiAction.SetSight -> setSight(a)
            is UiAction.SetPresenceFloor -> { prefs.presenceFloor = a.level; pushRenderSettings() }
            is UiAction.SetAvLead -> { prefs.setAvLead(route.key, a.ms); pushRenderSettings() }
            is UiAction.SyncTest -> syncTest(a.on)
            UiAction.Rescan -> rescan()
            UiAction.RotateToken -> onRotateToken?.invoke()
        }
    }

    /** CONTROL `--el seek <display ms>` (§8): converted to song µs. */
    fun controlSeekDisplayMs(ms: Long) = seekSongUs(SeekUnits.displayMsToSongUs(ms))

    /** CONTROL `--es play <name>`: a movement id or a `synth:` / `test:` / `asset:` name. */
    fun controlPlay(name: String, instrument: InstrumentId? = null) = playMovement(name, instrument, 0L, null, rebuild = true)

    private fun enter() {
        render.recenter()
        if (view != ViewId.PLAYER || framing != 0) setView(ViewId.PLAYER, 0)
        onUiEvent?.invoke(UiEvent.ENTERED)
        val r = resume
        if (r != null) {
            setPlaylist(r.playlist, r.index)
            playMovement(r.movementId, r.instrument, r.songUs, r.shelfId, rebuild = false)
        } else {
            val first = model?.startHere?.firstOrNull() ?: return
            playMovement(first, null, 0L, SHELF_START_HERE, rebuild = true)
        }
    }

    // ═══════════ CompanionCommands (posted to main) ═══════════

    override fun play(movementId: String, instrument: InstrumentId?) = playMovement(movementId, instrument, 0L, null, rebuild = true)

    override fun toggle() {
        if (current == null) { enter(); return }
        if (intendedPlaying) {
            intendedPlaying = false
            audio.pause()
            saveResume()
            kits.setPlaybackHint(false, instrument, quality, batteryTenths)
            onUiEvent?.invoke(UiEvent.PAUSED)
        } else {
            val p = current!!
            audio.clock.sample(nanoTime(), sample)
            if (sample.generation == p.generation && sample.songUs >= p.durationUs) audio.seek(0L)   // ended: start over
            intendedPlaying = true
            audio.play()
            kits.setPlaybackHint(true, instrument, quality, batteryTenths)
            onUiEvent?.invoke(UiEvent.RESUMED)
        }
    }

    override fun next() {
        val id = playlist.next() ?: return
        playMovement(id, null, 0L, shelfId, rebuild = false)
    }

    override fun previous() {
        val id = playlist.previous(SeekUnits.songUsToDisplaySec(positionUs())) ?: return
        if (id == movementId && current != null) seekSongUs(0L) else playMovement(id, null, 0L, shelfId, rebuild = false)
    }

    override fun seek(us: Long) = seekSongUs(us)
    override fun instrument(id: InstrumentId) = switchInstrument(id)
    override fun view(v: ViewId, framing: Int) = setView(v, framing)
    override fun importsChanged() = reloadLibrary()
    override fun nowPlayingJson(): String = nowPlaying

    // ═══════════ AudioListener (main) ═══════════

    override fun onEnded(generation: Int) {
        val p = current ?: return
        if (generation != p.generation || syncOn) return
        if (playlist.peekNext() != null) { next(); return }
        intendedPlaying = false
        saveResume(songUs = 0L)                          // the next Enter starts the finished movement over
        onUiEvent?.invoke(UiEvent.PAUSED)
    }

    override fun onOverload(newCap: Int) {}

    override fun onEngineError(code: StatusCode, detail: String) = postStatus(code, listOf(detail))

    override fun onRouteChanged(route: RouteInfo) {
        this.route = route
        if (route.route == OutputRoute.BLUETOOTH) {
            val seen = SessionKeys.splitIds(settings.getString(SessionKeys.BT_SEEN, ""))
            if (route.key !in seen) {
                settings.putString(SessionKeys.BT_SEEN, SessionKeys.joinIds(seen + route.key))
                postStatus(StatusCode.NEW_BT_DEVICE, listOf(route.name))
            }
        }
        pushRenderSettings()
    }

    // ═══════════ playback ═══════════

    private fun playMovement(id: String, inst: InstrumentId?, startUs: Long, shelf: String?, rebuild: Boolean) {
        val m = movementOf(id)
        val work = model?.works?.get(m.workId)
        val target = inst ?: prefs.workInstrument(m.workId) ?: work?.defaultInstrument ?: instrument
        if (rebuild) buildPlaylist(id, shelf) else if (playlist.current != id) setPlaylist(listOf(id), 0)
        shelfId = if (rebuild || shelf != null) shelf else shelfId
        if (inst != null) prefs.setWorkInstrument(m.workId, inst)
        if (target != instrument) changeInstrument(target, pauseFirst = current != null)
        val pre = precompiled
        precompiled = null
        if (pre != null && pre.first.movementId == id && pre.first.instrument == target && pre.second.generation > sentGeneration && startUs == 0L) {
            val r = Request(pre.first.gen, id, target, startUs, true, Kind.NEW)
            wanted = r
            deliver(r, pre.second)
            return
        }
        request(id, target, startUs, true, Kind.NEW)
    }

    private fun request(id: String, inst: InstrumentId, startUs: Long, autoPlay: Boolean, kind: Kind) {
        val r = Request(++generation, id, inst, startUs, autoPlay, kind)
        if (kind != Kind.PRECOMPILE) { wanted = r; pendingSend = null }
        val m = movementOf(id)
        val velocityPolicy = model?.works?.get(m.workId)?.velocityPolicy ?: "as-is"
        val profile = profileOf(inst)
        loader.execute {
            val result = compileOnLoader(m, r.gen, profile, velocityPolicy)
            post(Runnable { onCompiled(r, result) })
        }
    }

    /** HKLoader. */
    private fun compileOnLoader(m: Movement, gen: Int, profile: InstrumentProfile, velocityPolicy: String): CompileResult {
        val kind = if (m.id.startsWith("synth:")) SyntheticSpecs.kindOf(m.id) else null
        if (kind != null) return CompileResult.Ok(compiler.synthetic(kind, profile, gen))
        val cached = loaderBytes
        val bytes = if (cached != null && cached.first == m.id) cached.second
            else runCatching { library.readBytes(m) }.getOrElse { ByteArray(0) }.also { loaderBytes = m.id to it }
        val piano = profile.id != InstrumentId.HARPSICHORD
        val opts = CompileOptions(flatVelocity = if (velocityPolicy == "flat" && piano) FLAT_VELOCITY else 0)
        return runCatching { compiler.compile(bytes, m.id, gen, profile, opts) }
            .getOrElse { CompileResult.Failed(RejectReason.IO_ERROR, it.toString(), 0) }
    }

    private fun onCompiled(r: Request, result: CompileResult) {
        if (r.kind == Kind.PRECOMPILE) {
            if (result is CompileResult.Ok && r.instrument == instrument) precompiled = r to result.perf
            return
        }
        if (wanted !== r) return                                           // a newer request exists
        when (result) {
            is CompileResult.Ok -> deliver(r, result.perf)
            is CompileResult.Failed -> {
                wanted = null
                if (roomAfterSend) { roomAfterSend = false; updateRoom() }
                postStatus(StatusCode.IMPORT_FAILED, listOf(FactsAssembler.titleOf(model, r.movementId), result.reason.name, result.detail))
            }
        }
    }

    private fun deliver(r: Request, p: Performance) {
        if (bankInstrument != r.instrument) { pendingSend = r to p; return }
        send(r, p)
    }

    private fun send(r: Request, p: Performance) {
        wanted = null; pendingSend = null
        audio.setPerformance(p, r.startUs, r.autoPlay)
        render.setPerformance(p, profileOf(r.instrument))
        current = p
        sentGeneration = p.generation
        intendedPlaying = r.autoPlay
        if (roomAfterSend) { roomAfterSend = false; updateRoom() }
        if (r.kind == Kind.SYNC) { render.setTitle(null); refreshNowPlaying(); return }
        movementId = r.movementId
        render.setTitle(FactsAssembler.titleOf(model, r.movementId))
        if (r.kind == Kind.NEW) {
            if (!r.movementId.startsWith("synth:")) prefs.recent = (listOf(r.movementId) + prefs.recent.filter { it != r.movementId })
            if (r.autoPlay) kits.setPlaybackHint(true, instrument, quality, batteryTenths)
            onUiEvent?.invoke(UiEvent.MOVEMENT_STARTED)
            val next = playlist.peekNext()
            if (next != null) {
                val m = movementOf(next)
                val inst = prefs.workInstrument(m.workId) ?: model?.works?.get(m.workId)?.defaultInstrument ?: instrument
                if (inst == instrument) request(next, inst, 0L, true, Kind.PRECOMPILE)
            }
        }
        saveResume(songUs = if (r.startUs >= 0) r.startUs else null)
        refreshNowPlaying()
    }

    private fun seekSongUs(us: Long) {
        val p = current ?: return
        audio.seek(SeekUnits.clampSongUs(us, p.durationUs))
    }

    private fun buildPlaylist(id: String, shelf: String?) {
        val lib = model
        val ids: List<String> = when {
            lib == null -> listOf(id)
            shelf == SHELF_START_HERE -> lib.startHere
            shelf == SHELF_RECENT -> prefs.recent
            else -> {
                val m = lib.movements[id]
                val work = m?.let { lib.works[it.workId] }
                if (work == null) listOf(id) else {
                    val out = ArrayList<String>()
                    val ms = work.movementIds
                    val at = ms.indexOf(id).coerceAtLeast(0)
                    out.addAll(ms.subList(at, ms.size))
                    val s = shelf?.let { sid -> lib.shelves.firstOrNull { it.id == sid } }
                    if (s != null) {
                        val w = s.workIds.indexOf(work.id)
                        if (w >= 0) for (wid in s.workIds.subList(w + 1, s.workIds.size)) lib.works[wid]?.let { out.addAll(it.movementIds) }
                    }
                    out
                }
            }
        }
        val list = if (id in ids) ids else listOf(id) + ids
        setPlaylist(list, list.indexOf(id))
    }

    // ═══════════ instruments and kits ═══════════

    private fun switchInstrument(id: InstrumentId) {
        if (id == instrument) return
        val wasPlaying = intendedPlaying
        val mid = movementId
        if (syncOn) {                                    // the sync test keeps running with the new profile
            changeInstrument(id, pauseFirst = true)
            requestSync(wasPlaying || wanted?.kind == Kind.SYNC)
            return
        }
        mid?.let { prefs.setWorkInstrument(movementOf(it).workId, id) }
        val w0 = wanted
        changeInstrument(id, pauseFirst = true, perfFollows = (w0 != null && w0.kind != Kind.SYNC) || (mid != null && current != null))
        val w = wanted
        if (w != null && w.kind != Kind.SYNC) request(w.movementId, id, w.startUs, w.autoPlay, w.kind)
        else if (mid != null && current != null && !syncOn) request(mid, id, -1L, wasPlaying, Kind.SWITCH)
    }

    /** pause(30) → renderer → kit (its bank comes back through [kitCallback]). */
    private fun changeInstrument(id: InstrumentId, pauseFirst: Boolean, perfFollows: Boolean = true) {
        roomAfterSend = perfFollows
        if (pauseFirst) audio.pause(SWITCH_FADE_MS)
        instrument = id
        settings.putString(SessionKeys.INSTRUMENT, id.key)
        precompiled = null
        render.setInstrument(id, look(), lastDamper(id))
        openKit(id)
        // A cached kit may come back through onComplete alone (§2.6 step 5: "instant"); do not wait for it.
        val cached = banks[id]
        if (cached != null && bankInstrument != id && instrument == id) bankReady(id, cached)
    }

    private fun openKit(id: InstrumentId) = kits.open(id, KitCb(id))

    private inner class KitCb(val id: InstrumentId) : KitCallback {
        override fun onProgress(id: InstrumentId, fraction: Float) {}
        override fun onPlayable(bank: LoadedBank) = bankReady(id, bank)
        override fun onLayersChanged(bank: LoadedBank) {
            banks[id] = bank
            if (bankInstrument == id) rebuildKeyMap(id, bank)
        }
        override fun onComplete(bank: LoadedBank) = readyIfWaiting(bank)
        private fun readyIfWaiting(bank: LoadedBank) {
            if (banks[id] == null || (id == instrument && bankInstrument != id)) bankReady(id, bank) else banks[id] = bank
        }
        override fun onFallback(bank: LoadedBank, reason: FallbackReason) {
            postStatus(StatusCode.FALLBACK, listOf(reason.name))
            readyIfWaiting(bank)
        }
    }

    private fun bankReady(id: InstrumentId, bank: LoadedBank) {
        banks[id] = bank
        if (id != instrument) return
        val tuning = prefs.tuning(id)
        loader.execute {
            val km = kits.keyMap(bank, tuning)
            post(Runnable {
                if (id != instrument) return@Runnable
                if (bankInstrument == id) {                  // already sent by an earlier callback: idempotent
                    pendingSend?.let { (r, p) -> if (r.instrument == id && wanted === r) send(r, p) }
                    return@Runnable
                }
                audio.setBank(bank, km, profileOf(id).withLastDamper(bank.info.lastDamper))
                bankInstrument = id
                if (!kitPlayableSent) { kitPlayableSent = true; onUiEvent?.invoke(UiEvent.KIT_PLAYABLE) }
                // §2.6 step 5: the room design follows setPerformance; a compile still in flight sends it from send().
                val ps = pendingSend
                if (ps != null && ps.first.instrument == id && wanted === ps.first) { send(ps.first, ps.second); updateRoom() }
                else if (!roomAfterSend) updateRoom()
            })
        }
    }

    private fun rebuildKeyMap(id: InstrumentId, bank: LoadedBank) {
        val tuning = prefs.tuning(id)
        loader.execute {
            val km = kits.keyMap(bank, tuning)
            post(Runnable { if (bankInstrument == id && instrument == id) audio.setKeyMap(km) })
        }
    }

    private fun setTuning(id: InstrumentId, t: TuningSpec) {
        prefs.setTuning(id, t)
        val b = banks[id]
        if (b != null && bankInstrument == id) rebuildKeyMap(id, b)
    }

    // ═══════════ view, room, mix, sight ═══════════

    private fun setView(v: ViewId, f: Int) {
        view = v; framing = f.coerceIn(0, 1)
        settings.putString(SessionKeys.VIEW, v.name); settings.putInt(SessionKeys.FRAMING, framing)
        render.setView(view, framing)
        updateRoom()
        onUiEvent?.invoke(UiEvent.VIEW_CHANGED)
    }

    /** §2.6 step 4: one RoomDesign for (instrument, placement, listener, reverb mode) → audio.setRoom. */
    private fun updateRoom() {
        val id = instrument
        val g = venue ?: runCatching { scenes.venue().geometry }.getOrDefault(KonzertzimmerAcoustics.GEOMETRY).also { venue = it }
        val placement = g.placements[id] ?: KonzertzimmerAcoustics.PLACEMENTS.getValue(id)
        val a = anchorsOf(id)
        val listener = ListenerRooms.resolve(id, a, placement, view, framing).pose
        val source = a?.soundSource ?: ListenerRooms.SOURCE.getValue(id)
        val embedded = (banks[id]?.info ?: kits.info(id))?.embeddedRoomDb ?: 0f
        val d = designer.design(g, placement, source, listener, prefs.reverb, ListenerRooms.benchDistance(id, a), embedded)
        audio.setRoom(d, ROOM_GLIDE_MS)
    }

    private fun anchorsOf(id: InstrumentId): InstrumentAnchors? = anchors[id]
        ?: runCatching { scenes.instrument(id, look(), lastDamper(id)).anchors }.getOrNull()?.also { anchors[id] = it }

    private fun setMix(a: UiAction.SetMix) {
        val reverbChanged = a.reverb != null && a.reverb != prefs.reverb
        a.reverb?.let { prefs.reverb = it }
        a.resonance?.let { prefs.resonance = it }
        a.speakerBass?.let { prefs.speakerBass = it }
        a.releaseNoises?.let { prefs.releaseNoises = it }
        a.pedalNoises?.let { prefs.pedalNoises = it }
        audio.setMix(prefs.mix())
        if (reverbChanged) updateRoom()
    }

    private fun setSight(a: UiAction.SetSight) {
        if (a.autoRoom) prefs.roomOverride = null else a.room?.let { prefs.roomOverride = it }
        a.palette?.let { prefs.palette = it }
        a.stereoDepth?.let { prefs.stereoDepth = it }
        a.lookAround?.let { prefs.lookAround = it }
        a.reverseSwipe?.let { prefs.reverseSwipe = it }
        a.msaa?.let { prefs.msaa = it }
        val lookChanged = (a.finish != null && a.finish != prefs.finish) || (a.edgeOverlay != null && a.edgeOverlay != prefs.edgeOverlay) ||
            (a.autoEdgeOverlay && prefs.edgeOverlay != null)
        a.finish?.let { prefs.finish = it }
        if (a.autoEdgeOverlay) prefs.edgeOverlay = null else a.edgeOverlay?.let { prefs.edgeOverlay = it }
        pushRenderSettings()
        if (lookChanged) render.setInstrument(instrument, look(), lastDamper(instrument))
    }

    private fun pushRenderSettings() {
        render.setSettings(RenderSettings(stereoDepth = prefs.stereoDepth, lookAround = prefs.lookAround,
            roomOverride = prefs.roomOverride, palette = prefs.palette, presenceFloor = prefs.presenceFloor,
            displayLeadMs = prefs.avLead(route.key), lifeSizeVFov = prefs.lifeSizeVFov, look = look(), msaa = prefs.msaa))
    }

    private fun look() = InstrumentLook(prefs.finish, prefs.edgeOverlay ?: false)

    // ═══════════ sync test, library ═══════════

    private fun syncTest(on: Boolean) {
        if (on == syncOn) return
        if (on) {
            syncReturn = movementId?.let { ResumePoint(it, instrument, positionUs(), shelfId, emptyList(), 0) }
            syncOn = true
            audio.pause()
            render.setSyncFlash(true)
            requestSync(true)
        } else {
            syncOn = false
            render.setSyncFlash(false)
            audio.pause()
            val back = syncReturn
            syncReturn = null
            if (back != null) request(back.movementId, instrument, back.songUs, false, Kind.RESTORE)
            else { wanted = null; intendedPlaying = false; audio.setPerformance(null, 0L, false); current = null }
        }
    }

    private fun requestSync(autoPlay: Boolean) {
        val r = Request(++generation, SYNC_ID, instrument, if (current?.id == SYNC_ID) -1L else 0L, autoPlay, Kind.SYNC)
        wanted = r; pendingSend = null
        val profile = profileOf(instrument)
        loader.execute {
            val p = compiler.synthetic(SyntheticScore.SYNC_CLICK, profile, r.gen)
            post(Runnable { if (wanted === r) deliver(r, p) })
        }
    }

    private fun rescan() {
        loader.execute {
            val scan = runCatching { library.rescan() }.getOrNull()
            val m = runCatching { library.load() }.getOrNull()
            post(Runnable {
                if (m != null) model = m
                if (scan != null) {
                    if (scan.added > 0) {
                        // UiText IMPORTED args: name, notes, seconds (M6: was the bare count, which read as a file name)
                        postStatus(StatusCode.IMPORTED, listOf(if (scan.added == 1) "1 file" else "${scan.added} files"))
                        onUiEvent?.invoke(UiEvent.IMPORTED)
                    }
                    for (rj in scan.rejected) {
                        val code = if (rj.reason == RejectReason.PERMISSION_DENIED)
                            StatusCode.IMPORT_PERMISSION else StatusCode.IMPORT_FAILED
                        postStatus(code, listOf(rj.name, rj.reason?.name ?: "", rj.detail ?: ""))
                    }
                    if (scan.scoresDirForeign) postStatus(StatusCode.SCORES_DIR_FOREIGN)
                }
            })
        }
    }

    /** Companion upload results (M6): one status per file, like a rescan. */
    fun onUploadResults(results: List<ImportResult>, facts: (String) -> Pair<Int, Float>?) {
        for (r in results) {
            val mid = r.movementId
            if (r.ok && mid != null) {
                val f = facts(mid)
                postStatus(StatusCode.IMPORTED, listOfNotNull(r.name, f?.first?.toString(), f?.second?.toLong()?.toString()))
            } else if (r.reason != RejectReason.DUPLICATE) {
                val code = if (r.reason == RejectReason.PERMISSION_DENIED) StatusCode.IMPORT_PERMISSION else StatusCode.IMPORT_FAILED
                postStatus(code, listOf(r.name, r.reason?.name ?: "", r.detail ?: ""))
            }
        }
        onUiEvent?.invoke(UiEvent.IMPORTED)
    }

    private fun reloadLibrary() {
        loader.execute {
            val m = runCatching { library.load() }.getOrNull()
            post(Runnable { if (m != null) { model = m; onUiEvent?.invoke(UiEvent.IMPORTED) } })
        }
    }

    // ═══════════ facts, resume, now playing ═══════════

    fun facts(): UiFacts {
        audio.clock.sample(nanoTime(), sample)
        val p = current
        val pos = if (p != null && sample.generation == p.generation) sample.songUs else 0L
        return FactsAssembler.assemble(FactsInput(
            playing = sample.playing && p != null, positionUs = pos, durationUs = p?.durationUs ?: 0L,
            movementId = movementId, bar = p?.barAt(pos) ?: 1, instrument = instrument, view = view, framing = framing,
            kitStates = InstrumentId.entries.associateWith { runCatching { kits.state(it) }.getOrDefault(KitState.Missing) },
            library = model, settings = prefs.snapshot(listOf(route.key)), nextId = playlist.peekNext(), quality = qualityLevel,
            companionUrl = companionUrl, companionToken = companionToken, route = route, status = status.active(nowMs()),
            perfInfo = p?.info, sessions = sessions, resumeId = if (current == null) resume?.movementId else null,
            recent = prefs.recent, shelfId = shelfId, version = version, debug = debugLine))
    }

    val recent: List<String> get() = prefs.recent
    val playlistIds: List<String> get() = lastPlaylist
    val playlistIndex: Int get() = playlist.index

    /** Playlist keeps its ids private: a copy lives here for the resume point. */
    private var lastPlaylist: List<String> = emptyList()

    private fun setPlaylist(ids: List<String>, index: Int) { lastPlaylist = ids.toList(); playlist.set(lastPlaylist, index) }

    private fun saveResume(songUs: Long? = null) {
        val id = movementId ?: return
        if (syncOn || id.startsWith("synth:")) return
        val pos = songUs ?: positionUs()
        val r = ResumePoint(id, instrument, pos, shelfId, lastPlaylist.ifEmpty { listOf(id) }, playlist.index.coerceAtLeast(0))
        r.save(settings)
        resume = r
        lastResumeSaveMs = nowMs()
    }

    private fun refreshNowPlaying() {
        audio.clock.sample(nanoTime(), sample)
        val p = current
        val m = movementId?.let { model?.movements?.get(it) }
        val w = m?.let { model?.works?.get(it.workId) }
        val sb = StringBuilder(256).append('{')
        fun str(k: String, v: String?) { sb.append('"').append(k).append("\":"); if (v == null) sb.append("null") else jsonString(sb, v); sb.append(',') }
        fun raw(k: String, v: Any) { sb.append('"').append(k).append("\":").append(v).append(',') }
        str("movementId", movementId)
        str("title", movementId?.let { m?.title ?: FactsAssembler.titleOf(model, it) })
        str("composer", w?.composer)
        str("instrument", instrument.key)
        str("view", view.name.lowercase())
        raw("framing", framing)
        val pos = if (p != null && sample.generation == p.generation) sample.songUs else 0L
        raw("positionSec", fmt(SeekUnits.songUsToDisplaySec(pos)))
        raw("durationSec", fmt(if (p != null) SeekUnits.songUsToDisplaySec(p.durationUs) else 0f))
        raw("playing", sample.playing && p != null)
        raw("quality", qualityLevel)
        raw("underruns", stats.underruns)
        raw("batteryC", fmt(batteryTenths / 10f))
        sb.setLength(sb.length - 1)
        nowPlaying = sb.append('}').toString()
    }

    private fun positionUs(): Long {
        audio.clock.sample(nanoTime(), sample)
        val p = current ?: return 0L
        return if (sample.generation == p.generation) sample.songUs else 0L
    }

    fun isPlaying(): Boolean {
        audio.clock.sample(nanoTime(), sample)
        return sample.playing && current != null
    }

    private fun movementOf(id: String): Movement = model?.movements?.get(id) ?: Movement(id = id, workId = id,
        title = id.substringAfter(':'), asset = if (id.startsWith("asset:")) id.removePrefix("asset:") else null, file = null,
        sha1 = "", durationSec = 0f, lowKey = 21, highKey = 108, hasSustain = false, hasSoft = false, hasSostenuto = false,
        pedalMode = PedalMode.NONE)

    private fun profileOf(id: InstrumentId): InstrumentProfile {
        val base = InstrumentProfile.of(id)
        val ld = (banks[id]?.info ?: runCatching { kits.info(id) }.getOrNull())?.lastDamper ?: return base
        return base.withLastDamper(ld)
    }

    private fun lastDamper(id: InstrumentId): Int = profileOf(id).lastDamper

    private fun copyStatus(from: StatusBoard?, to: StatusBoard) {
        from ?: return
        val now = nowMs()
        for (s in from.active(now).asReversed()) to.post(s.code, s.args, now,
            if (s.untilMs == Long.MAX_VALUE) StatusBoard.PERSISTENT else s.untilMs - now)
    }

    companion object {
        /** Shelf ids of the two computed shelves (§1.5); WP9/WP10 use the same strings. */
        const val SHELF_START_HERE = "start-here"
        const val SHELF_RECENT = "recent"
        const val SYNC_ID = "synth:sync"
        const val SWITCH_FADE_MS = 30
        const val ROOM_GLIDE_MS = 500
        const val RESUME_EVERY_MS = 5_000L
        const val FLAT_VELOCITY = 72
        const val HK_DEFAULT_CAP = 96

        private fun fmt(v: Float): String = (Math.round(v * 10.0) / 10.0).toString()
        private fun jsonString(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) when {
                c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
            sb.append('"')
        }
    }
}
