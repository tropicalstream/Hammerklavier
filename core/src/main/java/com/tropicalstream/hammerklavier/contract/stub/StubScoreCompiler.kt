package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.ScoreCompiler
import com.tropicalstream.hammerklavier.contract.ScoreFacts
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.SyntheticSpecs

/**
 * ScoreCompiler without a MIDI parser (PLAN §2.3): [synthetic] = PerfFixtures.build of the
 * SyntheticSpecs (cc64 → sustain, cc67 → soft, cc66 → sostenuto) for all nine kinds. [compile]
 * returns Failed(NOT_MIDI) unless the bytes are a test twin: recognised by the movement id
 * (`test:<name>` or `synth:<name>`) or by bytes registered with [registerTwin]; a twin compiles to
 * the same Performance as its kind.
 */
class StubScoreCompiler : ScoreCompiler {
    private val twins = HashMap<Int, Pair<ByteArray, SyntheticScore>>()

    /** Makes [bytes] compile as [kind] (e.g. WP11's `assets/midi/test/<name>.mid`). */
    @Synchronized fun registerTwin(bytes: ByteArray, kind: SyntheticScore) { twins[bytes.contentHashCode()] = bytes to kind }

    @Synchronized private fun twinOf(bytes: ByteArray): SyntheticScore? =
        twins[bytes.contentHashCode()]?.takeIf { it.first.contentEquals(bytes) }?.second

    override fun sniff(head: ByteArray): Boolean {
        fun at(o: Int, s: String) = head.size >= o + 4 && (0..3).all { head[o + it] == s[it].code.toByte() }
        return at(0, "MThd") || (at(0, "RIFF") && at(8, "RMID"))
    }

    override fun inspect(bytes: ByteArray): ScoreFacts {
        val kind = twinOf(bytes) ?: return ScoreFacts(ok = false, error = RejectReason.NOT_MIDI, title = null, durationSec = 0f,
            lowKey = 0, highKey = 0, noteCount = 0, hasSustain = false, hasSoft = false, hasSostenuto = false,
            pedalMode = com.tropicalstream.hammerklavier.contract.PedalMode.NONE, channels = 0, warnings = emptyList())
        val spec = SyntheticSpecs.notes(kind)
        val p = synthetic(kind, InstrumentProfile.GRAND, 0)
        return ScoreFacts(ok = true, error = null, title = SyntheticSpecs.NAMES[kind],
            durationSec = (p.durationUs - HK.PRE_ROLL_US) / 1e6f,
            lowKey = spec.key.minOfOrNull { it.toInt() } ?: 0, highKey = spec.key.maxOfOrNull { it.toInt() } ?: 0,
            noteCount = spec.onUs.size, hasSustain = !spec.cc64.isEmpty, hasSoft = !spec.cc67.isEmpty,
            hasSostenuto = !spec.cc66.isEmpty, pedalMode = p.info.pedalMode, channels = 1, warnings = emptyList())
    }

    override fun compile(bytes: ByteArray, id: String, generation: Int, profile: InstrumentProfile,
                         opts: CompileOptions): CompileResult {
        val kind = (if (id.startsWith("test:") || id.startsWith("synth:")) SyntheticSpecs.kindOf(id) else null) ?: twinOf(bytes)
            ?: return CompileResult.Failed(RejectReason.NOT_MIDI, "StubScoreCompiler compiles only the test twins", 0)
        return CompileResult.Ok(build(kind, profile, generation, id))
    }

    override fun synthetic(kind: SyntheticScore, profile: InstrumentProfile, generation: Int): Performance =
        build(kind, profile, generation, "synth:" + SyntheticSpecs.NAMES.getValue(kind))

    private fun build(kind: SyntheticScore, profile: InstrumentProfile, generation: Int, id: String): Performance {
        val spec = SyntheticSpecs.notes(kind)
        return PerfFixtures.build(notes = spec, sustain = spec.cc64, soft = spec.cc67, sostenuto = spec.cc66,
            profile = profile, generation = generation, id = id, title = SyntheticSpecs.NAMES[kind])
    }
}
