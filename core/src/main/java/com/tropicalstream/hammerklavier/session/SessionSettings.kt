package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MixSettings
import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.contract.SettingsSnapshot
import com.tropicalstream.hammerklavier.contract.SettingsStore
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.contract.Temperament
import com.tropicalstream.hammerklavier.contract.TuningSpec
import com.tropicalstream.hammerklavier.contract.UprightFinish

/** The keys SessionController reads and writes through [SettingsStore] (WP0's typed Settings persists them). */
object SessionKeys {
    const val SEP = '\u001F'
    const val RESUME_MOVEMENT = "session.resume.movement"; const val RESUME_INSTRUMENT = "session.resume.instrument"
    const val RESUME_US = "session.resume.us"; const val RESUME_SHELF = "session.resume.shelf"
    const val RESUME_PLAYLIST = "session.resume.playlist"; const val RESUME_INDEX = "session.resume.index"
    const val RECENT = "session.recent"
    const val WORK_INSTRUMENT = "session.work.instrument."        // + workId
    const val INSTRUMENT = "session.instrument"; const val VIEW = "session.view"; const val FRAMING = "session.framing"
    const val SESSIONS = "session.count"; const val Q0_CAP = "session.q0cap"; const val BT_SEEN = "session.bt.seen"
    const val TUNING_A = "tuning.a."; const val TUNING_T = "tuning.t."   // + instrument key
    const val REGISTRATION = "harpsi.registration"; const val TEMPO = "tempo.pct"
    const val REVERB = "mix.reverb"; const val RESONANCE = "mix.resonance"; const val SPEAKER_BASS = "mix.speakerBass"
    const val MASTER_DB = "mix.masterDb"
    const val RELEASE_NOISES = "mix.releaseNoises"; const val PEDAL_NOISES = "mix.pedalNoises"
    const val ROOM_OVERRIDE = "sight.room"; const val PALETTE = "sight.palette"; const val FINISH = "sight.finish"
    const val STEREO_DEPTH = "sight.stereoDepth"; const val LOOK_AROUND = "sight.lookAround"; const val EDGE_OVERLAY = "sight.edge"
    const val REVERSE_SWIPE = "input.reverseSwipe"; const val PRESENCE_FLOOR = "sight.presenceFloor"; const val MSAA = "sight.msaa"
    const val AV_LEAD = "av.lead."                                 // + route key
    const val LIFE_FOV = "sight.lifeSizeVFov"

    /** §3.13 default until LevelCalibrationTest (T12.8) measures it. */
    const val DEFAULT_MASTER_DB = -8f
    const val DEFAULT_PRESENCE_FLOOR = 22
    const val DEFAULT_LIFE_FOV = 18.27f

    /** Default A/V lead per route key prefix (ms): the §2.5 latency allowances are the audio side; the display lead starts at 0. */
    fun defaultAvLead(routeKey: String): Int = 0

    fun joinIds(ids: List<String>): String = ids.joinToString(SEP.toString())
    fun splitIds(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(SEP)
}

/** The saved resume point (PLAN §4.9). [songUs] includes the pre-roll. */
class ResumePoint(val movementId: String, val instrument: InstrumentId, val songUs: Long, val shelfId: String?,
                  val playlist: List<String>, val index: Int) {

    fun save(s: SettingsStore) {
        s.putString(SessionKeys.RESUME_MOVEMENT, movementId)
        s.putString(SessionKeys.RESUME_INSTRUMENT, instrument.key)
        s.putString(SessionKeys.RESUME_US, songUs.toString())
        s.putString(SessionKeys.RESUME_SHELF, shelfId ?: "")
        s.putString(SessionKeys.RESUME_PLAYLIST, SessionKeys.joinIds(playlist))
        s.putInt(SessionKeys.RESUME_INDEX, index)
    }

    companion object {
        fun load(s: SettingsStore): ResumePoint? {
            val id = s.getString(SessionKeys.RESUME_MOVEMENT, "")
            if (id.isEmpty()) return null
            val inst = InstrumentId.of(s.getString(SessionKeys.RESUME_INSTRUMENT, "")) ?: InstrumentId.GRAND
            val us = s.getString(SessionKeys.RESUME_US, "0").toLongOrNull() ?: 0L
            val shelf = s.getString(SessionKeys.RESUME_SHELF, "").ifEmpty { null }
            val list = SessionKeys.splitIds(s.getString(SessionKeys.RESUME_PLAYLIST, ""))
            val index = s.getInt(SessionKeys.RESUME_INDEX, 0)
            return ResumePoint(id, inst, us.coerceAtLeast(0L), shelf, list.ifEmpty { listOf(id) },
                if (list.isEmpty()) 0 else index.coerceIn(0, list.size - 1))
        }

        fun clear(s: SettingsStore) { s.putString(SessionKeys.RESUME_MOVEMENT, "") }
    }
}

/** Typed reads and writes of the user settings SessionController applies. Main thread. */
class SessionSettings(private val s: SettingsStore) {

    fun tuning(id: InstrumentId): TuningSpec {
        val d = InstrumentProfile.of(id).defaultTuning
        val a = s.getFloat(SessionKeys.TUNING_A + id.key, d.aHz)
        val t = runCatching { Temperament.valueOf(s.getString(SessionKeys.TUNING_T + id.key, d.temperament.name)) }
            .getOrDefault(d.temperament)
        return if (a == d.aHz && t == d.temperament) d else TuningSpec(a, t)
    }
    fun setTuning(id: InstrumentId, t: TuningSpec) {
        s.putFloat(SessionKeys.TUNING_A + id.key, t.aHz); s.putString(SessionKeys.TUNING_T + id.key, t.temperament.name)
    }

    var registration: Int
        get() = s.getInt(SessionKeys.REGISTRATION, HK.REG_8 or HK.REG_4).coerceIn(1, 3)
        set(v) = s.putInt(SessionKeys.REGISTRATION, v.coerceIn(1, 3))
    var tempoPct: Int
        get() = s.getInt(SessionKeys.TEMPO, 100).coerceIn(50, 150)
        set(v) = s.putInt(SessionKeys.TEMPO, v.coerceIn(50, 150))
    var reverb: ReverbMode
        get() = enumOr(s.getString(SessionKeys.REVERB, ""), ReverbMode.ROOM)
        set(v) = s.putString(SessionKeys.REVERB, v.name)
    var resonance: ResonanceMode
        get() = enumOr(s.getString(SessionKeys.RESONANCE, ""), ResonanceMode.NATURAL)
        set(v) = s.putString(SessionKeys.RESONANCE, v.name)
    var speakerBass: SpeakerBass
        get() = enumOr(s.getString(SessionKeys.SPEAKER_BASS, ""), SpeakerBass.AUTO)
        set(v) = s.putString(SessionKeys.SPEAKER_BASS, v.name)
    var releaseNoises: Boolean
        get() = s.getBool(SessionKeys.RELEASE_NOISES, true)
        set(v) = s.putBool(SessionKeys.RELEASE_NOISES, v)
    var pedalNoises: Boolean
        get() = s.getBool(SessionKeys.PEDAL_NOISES, true)
        set(v) = s.putBool(SessionKeys.PEDAL_NOISES, v)
    var masterDb: Float
        get() = s.getFloat(SessionKeys.MASTER_DB, SessionKeys.DEFAULT_MASTER_DB)
        set(v) = s.putFloat(SessionKeys.MASTER_DB, v)
    /** null = automatic (the view decides). */
    var roomOverride: RoomLevel?
        get() = s.getString(SessionKeys.ROOM_OVERRIDE, "").let { n -> RoomLevel.entries.firstOrNull { it.name == n } }
        set(v) = s.putString(SessionKeys.ROOM_OVERRIDE, v?.name ?: "")
    var palette: Palette
        get() = enumOr(s.getString(SessionKeys.PALETTE, ""), Palette.SANSSOUCI_1747)
        set(v) = s.putString(SessionKeys.PALETTE, v.name)
    var finish: UprightFinish
        get() = enumOr(s.getString(SessionKeys.FINISH, ""), UprightFinish.WALNUT)
        set(v) = s.putString(SessionKeys.FINISH, v.name)
    var stereoDepth: Float
        get() = s.getFloat(SessionKeys.STEREO_DEPTH, 1f)
        set(v) = s.putFloat(SessionKeys.STEREO_DEPTH, v)
    var lookAround: Boolean
        get() = s.getBool(SessionKeys.LOOK_AROUND, true)
        set(v) = s.putBool(SessionKeys.LOOK_AROUND, v)
    /** null = automatic. */
    var edgeOverlay: Boolean?
        get() = when (s.getString(SessionKeys.EDGE_OVERLAY, "")) { "on" -> true; "off" -> false; else -> null }
        set(v) = s.putString(SessionKeys.EDGE_OVERLAY, when (v) { true -> "on"; false -> "off"; null -> "" })
    var reverseSwipe: Boolean
        get() = s.getBool(SessionKeys.REVERSE_SWIPE, false)
        set(v) = s.putBool(SessionKeys.REVERSE_SWIPE, v)
    var presenceFloor: Int
        get() = s.getInt(SessionKeys.PRESENCE_FLOOR, SessionKeys.DEFAULT_PRESENCE_FLOOR)
        set(v) = s.putInt(SessionKeys.PRESENCE_FLOOR, v)
    var msaa: Boolean
        get() = s.getBool(SessionKeys.MSAA, true)
        set(v) = s.putBool(SessionKeys.MSAA, v)
    val lifeSizeVFov: Float get() = s.getFloat(SessionKeys.LIFE_FOV, SessionKeys.DEFAULT_LIFE_FOV)

    fun avLead(routeKey: String): Int = s.getInt(SessionKeys.AV_LEAD + routeKey, SessionKeys.defaultAvLead(routeKey))
    fun setAvLead(routeKey: String, ms: Int) = s.putInt(SessionKeys.AV_LEAD + routeKey, ms.coerceIn(0, 400))

    fun workInstrument(workId: String): InstrumentId? = InstrumentId.of(s.getString(SessionKeys.WORK_INSTRUMENT + workId, ""))
    fun setWorkInstrument(workId: String, id: InstrumentId) = s.putString(SessionKeys.WORK_INSTRUMENT + workId, id.key)

    var recent: List<String>
        get() = SessionKeys.splitIds(s.getString(SessionKeys.RECENT, "")).take(RECENT_MAX)
        set(v) = s.putString(SessionKeys.RECENT, SessionKeys.joinIds(v.take(RECENT_MAX)))

    fun mix(): MixSettings = MixSettings(reverb = reverb, resonance = resonance, speakerBass = speakerBass, masterDb = masterDb,
        releaseNoises = releaseNoises, pedalNoises = pedalNoises)

    /** Route keys with a stored lead, for the snapshot. */
    fun snapshot(routeKeys: Collection<String>): SettingsSnapshot = SettingsSnapshot(
        tuning = InstrumentId.entries.associateWith { tuning(it) }, registration = registration, reverb = reverb,
        resonance = resonance, speakerBass = speakerBass, roomOverride = roomOverride, palette = palette, finish = finish,
        stereoDepth = stereoDepth, lookAround = lookAround, edgeOverlay = edgeOverlay, reverseSwipe = reverseSwipe,
        presenceFloor = presenceFloor, avLeadMs = routeKeys.associateWith { avLead(it) }, tempoPct = tempoPct, msaa = msaa,
        releaseNoises = releaseNoises, pedalNoises = pedalNoises)

    companion object {
        const val RECENT_MAX = 15
        private inline fun <reified E : Enum<E>> enumOr(name: String, d: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: d
    }
}
