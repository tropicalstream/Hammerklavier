package com.tropicalstream.hammerklavier.kit

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.FallbackReason
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId

/** Region kinds of `map.json` (PLAN §6.6). */
enum class RegionKind(val key: String) {
    SUSTAIN("sustain"), RELEASE("release"), PEDAL_DOWN("pedalDown"), PEDAL_UP("pedalUp");
    companion object { fun of(key: String): RegionKind? = entries.firstOrNull { it.key == key } }
}

class LayerDef(val index: Int, val velLo: Int, val velHi: Int, val velRef: Int, val unit: Int)

class StopDef(val index: Int, val name: String) {
    /** Sounding offset of the stop in semitones ("4'" sounds an octave up). */
    val octaveSemis: Int get() = if (name == "4'") 12 else 0
}

class LevelPoint(val vel: Int, val db: Float)

class UnitDef(val id: Int, val label: String, val order: Int, val file: String, val frames: Int, val sha1: String)

class RegionDef(
    val id: Int, val kind: RegionKind, val unit: Int, val streamStart: Int, val frames: Int,
    val stop: Int, val layer: Int, val root: Int, val lo: Int, val hi: Int, val rr: Int,
    val onsetFrame: Int, val thrFrame: Int, val pitchCents: Float, val gainDb: Float,
    val envOffset: Int, val envCount: Int,
    val borrowable: Boolean = false, val seamGainDb: Float = 0f, val seamLpHz: Float = 0f) {
    /** Measured sounding pitch re A440 ET in cents (§3.5 `nativeCents`). */
    val nativeCents: Float get() = 100f * root + pitchCents
}

class ReleaseRule(val relGainDb: Float, val velExp: Float, val ageTauS: Float, val floor: Float, val heldDb: Float)

/**
 * A validated `map.json` (schema 2) plus its `env.bin` (PLAN §6.6). Immutable; built by
 * [KitMapCodec] (or by [SynthBank] in code) on HKLoader.
 */
class KitIndex(
    val schema: Int, val instrument: String, val kit: String, val version: String, val sha1: String,
    val mode: String, val xfadeSteps: Int, val xfadeLaw: List<String>,
    val lastDamper: Int, val aOffsetCents: Float, val recordedAHz: Float, val pedalGainDb: Float,
    val releaseCarriesTail: Boolean, val embeddedRoomDb: Float, val embeddedEdtS: Float,
    val layers: List<LayerDef>, val stops: List<StopDef>, val levelCurve: List<List<LevelPoint>>,
    val units: List<UnitDef>, val regions: List<RegionDef>,
    val stretchCents: FloatArray, val inharmB: FloatArray, val damperT60: FloatArray,
    val freeT60: Array<FloatArray>, val releaseRule: ReleaseRule, val credit: String, val source: String,
    val env: ByteArray) {

    val isHard: Boolean get() = mode == "HARD"
    val layerCount: Int get() = layers.size
    val stopCount: Int get() = stops.size

    /** First 8 hex digits of [sha1]: names the PCM cache file. */
    val sha8: String get() = sha1.take(8)

    fun unit(id: Int): UnitDef? = units.firstOrNull { it.id == id }

    /** Unit ids in decode order (`units[].order`). */
    val decodeOrder: IntArray by lazy { units.sortedBy { it.order }.map { it.id }.toIntArray() }

    /** The unit holding the sustain regions of (stop, layer), or -1. */
    fun unitOf(stop: Int, layer: Int): Int {
        for (r in regions) if (r.kind == RegionKind.SUSTAIN && r.stop == stop && r.layer == layer) return r.unit
        return if (stop == 0) layers.firstOrNull { it.index == layer }?.unit ?: -1 else -1
    }

    /** 0..255 = −dBFS × 2; 255 past the end (LoadedBank.envByte). */
    fun envByte(region: Int, tenMs: Int): Int {
        val r = regions[region]
        if (tenMs < 0 || tenMs >= r.envCount) return 255
        return env[r.envOffset + tenMs].toInt() and 0xFF
    }

    /** The instrument this kit is for; "stub" kits stand in for [fallbackFor]. */
    fun instrumentId(fallbackFor: InstrumentId = InstrumentId.GRAND): InstrumentId =
        InstrumentId.of(instrument) ?: fallbackFor

    fun toBankInfo(id: InstrumentId = instrumentId(), isStub: Boolean = instrument == "stub",
                   fallback: FallbackReason? = null): BankInfo {
        val free = FloatArray(stopCount * HK.KEYS)
        for (s in 0 until stopCount) System.arraycopy(freeT60[s], 0, free, s * HK.KEYS, HK.KEYS)
        return BankInfo(instrument = id, kit = kit, version = version, sha1 = sha1,
            layers = layerCount, stops = stopCount, lastDamper = lastDamper, recordedAHz = recordedAHz,
            aOffsetCents = aOffsetCents, damperT60 = damperT60.copyOf(), freeT60 = free, inharmB = inharmB.copyOf(),
            releaseCarriesTail = releaseCarriesTail, embeddedRoomDb = embeddedRoomDb, decodeOrder = decodeOrder.copyOf(),
            isStub = isStub, fallback = fallback)
    }

    companion object {
        const val UNIT_RELEASES = 62
        const val UNIT_PEDALS = 63
    }
}
