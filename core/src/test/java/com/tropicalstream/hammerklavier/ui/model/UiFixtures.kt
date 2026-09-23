package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.*

/** Private WP10 test helpers: a small library and UiFacts with named overrides. */
object UiFixtures {
    fun mv(id: String, work: String, title: String, sec: Float) = Movement(id = id, workId = work, title = title, asset = null,
        file = null, sha1 = "", durationSec = sec, lowKey = 21, highKey = 108, hasSustain = true, hasSoft = false,
        hasSostenuto = false, pedalMode = PedalMode.SWITCH)

    fun work(id: String, composer: String, short: String, mids: List<String>, def: InstrumentId = InstrumentId.GRAND,
             source: String = "krueger", imported: Boolean = false) = Work(id = id, composer = composer, composerShort = composer,
        title = short, shortTitle = short, catalogue = null, year = null, era = "x", defaultInstrument = def,
        altInstruments = emptyList(), sourceId = source, tier = "A", velocityPolicy = "as-is", tuning = null,
        movementIds = mids, imported = imported)

    val library: LibraryModel by lazy {
        val ms = listOf(
            mv("beethoven.op106.1", "beethoven.op106", "I. Allegro", 593.1f),
            mv("beethoven.op106.2", "beethoven.op106", "II. Scherzo", 160f),
            mv("beethoven.op106.3", "beethoven.op106", "III. Adagio", 1100f),
            mv("beethoven.op106.4", "beethoven.op106", "IV. Largo – Allegro", 282f),
            mv("bach.bwv846.1", "bach.bwv846", "Prelude and Fugue in C", 250f),
            mv("scarlatti.k141.1", "scarlatti.k141", "Sonata", 200f))
        val works = listOf(
            work("beethoven.op106", "Beethoven", "Sonata op. 106 \"Hammerklavier\"", ms.take(4).map { it.id }),
            work("bach.bwv846", "Bach", "Prelude and Fugue BWV 846", listOf("bach.bwv846.1")),
            work("scarlatti.k141", "Scarlatti", "Sonata K. 141", listOf("scarlatti.k141.1"), InstrumentId.HARPSICHORD, "sankey"))
        val sources = mapOf(
            "krueger" to Source("krueger", "Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE", "CC-BY-SA-3.0-DE",
                "", "http://www.piano-midi.de", null, "step-sequenced", "A", false),
            "sankey" to Source("sankey", "Harpsichord: John Sankey", "SANKEY", "", "https://www.johnsankey.ca", null, "performed", "A", false))
        val shelves = listOf(Shelf("imported", "Imported", emptyList()), Shelf("bach", "Bach · teaching the keyboard", listOf("bach.bwv846")),
            Shelf("scarlatti", "Scarlatti", listOf("scarlatti.k141")), Shelf("beethoven", "Beethoven", listOf("beethoven.op106")))
        LibraryModel(shelves = shelves, works = works.associateBy { it.id }, movements = ms.associateBy { it.id },
            sources = sources, startHere = listOf("bach.bwv846.1", "scarlatti.k141.1", "beethoven.op106.1"))
    }

    fun settings(reverseSwipe: Boolean = false, tempoPct: Int = 100, presenceFloor: Int = 22,
                 avLeadMs: Map<String, Int> = emptyMap(), tuning: Map<InstrumentId, TuningSpec> = emptyMap(),
                 registration: Int = 3, edgeOverlay: Boolean? = null) = SettingsSnapshot(tuning = tuning,
        registration = registration, reverb = ReverbMode.ROOM, resonance = ResonanceMode.NATURAL, speakerBass = SpeakerBass.AUTO,
        roomOverride = null, palette = Palette.SANSSOUCI_1747, finish = UprightFinish.WALNUT, stereoDepth = 1f,
        lookAround = true, edgeOverlay = edgeOverlay, reverseSwipe = reverseSwipe, presenceFloor = presenceFloor,
        avLeadMs = avLeadMs, tempoPct = tempoPct, msaa = true)

    val SPEAKER = RouteInfo(OutputRoute.SPEAKER, "speaker", 2, "Speaker", "deep")
    val BT = RouteInfo(OutputRoute.BLUETOOTH, "bt:AA:BB", 8, "Sony WH", "normal")

    fun facts(playing: Boolean = true, positionUs: Long = HK.PRE_ROLL_US + 192_000_000L,
              durationUs: Long = HK.PRE_ROLL_US + 593_000_000L, movementId: String? = "beethoven.op106.1", bar: Int = 112,
              instrument: InstrumentId = InstrumentId.GRAND, view: ViewId = ViewId.PLAYER, framing: Int = 0,
              kitStates: Map<InstrumentId, KitState> = mapOf(InstrumentId.GRAND to KitState.Complete),
              library: LibraryModel? = UiFixtures.library, settings: SettingsSnapshot = settings(), nextTitle: String? = "II. Scherzo",
              quality: Int = 0, companionUrl: String? = "http://192.168.1.5:19112", token: String = "K7QM4TZP",
              route: RouteInfo = SPEAKER, status: List<StatusItem> = emptyList(), perfInfo: PerfInfo? = null,
              sessions: Int = 5, resumeTitle: String? = null, recent: List<String> = emptyList()) = UiFacts(
        playing = playing, positionUs = positionUs, durationUs = durationUs, movementId = movementId, bar = bar,
        instrument = instrument, view = view, framing = framing, kitStates = kitStates, library = library, settings = settings,
        nextTitle = nextTitle, quality = quality, companionUrl = companionUrl, companionToken = token, route = route,
        status = status, perfInfo = perfInfo, firstRun = sessions <= 1, sessions = sessions, resumeTitle = resumeTitle,
        recent = recent, shelfId = null, version = "1.0 main abc123", debug = null)

    fun perf(folded: Int = 0, merged: Int = 0, finger: Boolean = false) = PerfInfo(title = null, lowKey = 21, highKey = 108,
        noteCount = 100, maxPolyphony = 10, pedalMode = PedalMode.SWITCH, sustainEvents = 0, softEvents = 0, sostenutoEvents = 0,
        folded = folded, mergedChannels = merged, droppedDrumNotes = 0, fingerPedalled = finger, voiceDemandP99 = 10,
        voiceDemandMax = 12, warnings = emptyList())

    /** A machine already past the title card. */
    fun entered(f: UiFacts = facts()): UiStateMachineImpl = UiStateMachineImpl().also { it.onEvent(UiEvent.ENTERED, f, 0) }

    fun state(ui: UiStateMachineImpl, f: UiFacts, now: Long = 0) = ui.render(f, now)
}
