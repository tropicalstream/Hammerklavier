package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.ScoreCompiler
import com.tropicalstream.hammerklavier.contract.ScoreFacts
import com.tropicalstream.hammerklavier.contract.SyntheticScore

/** The real [ScoreCompiler] (PLAN §4): pure, any thread, never throws. */
class ScoreCompilerImpl : ScoreCompiler {

    override fun sniff(head: ByteArray): Boolean = SmfParser.sniff(head)

    override fun inspect(bytes: ByteArray): ScoreFacts {
        val r = try { compileRaw(bytes, "inspect", 0, InstrumentProfile.GRAND, CompileOptions()) }
                catch (e: RuntimeException) { Outcome.Failed(SmfError.TRUNCATED, "internal: ${e.javaClass.simpleName}", 0) }
        return when (r) {
            is Outcome.Failed -> ScoreFacts(ok = false, error = r.error.reason, title = null, durationSec = 0f, lowKey = 0, highKey = 0,
                noteCount = 0, hasSustain = false, hasSoft = false, hasSostenuto = false, pedalMode = PedalMode.NONE,
                channels = 0, warnings = emptyList())
            is Outcome.Ok -> {
                val p = r.perf; val raw = r.raw
                ScoreFacts(ok = true, error = null, title = p.info.title, durationSec = Math.round((p.durationUs - HK.PRE_ROLL_US - PerformanceBuilder.TAIL_US) / 1000.0) / 1000f,
                    lowKey = raw.lowKey, highKey = raw.highKey, noteCount = raw.noteCount, hasSustain = raw.hasSustain,
                    hasSoft = raw.hasSoft, hasSostenuto = raw.hasSostenuto, pedalMode = raw.sustainMode,
                    channels = raw.channels, warnings = p.info.warnings)
            }
        }
    }

    override fun compile(bytes: ByteArray, id: String, generation: Int, profile: InstrumentProfile, opts: CompileOptions): CompileResult {
        val r = try { compileRaw(bytes, id, generation, profile, opts) }
                catch (e: RuntimeException) { Outcome.Failed(SmfError.TRUNCATED, "internal: ${e.javaClass.simpleName}", 0) }
        return when (r) {
            is Outcome.Failed -> CompileResult.Failed(r.error.reason, r.detail, r.byteOffset)
            is Outcome.Ok -> CompileResult.Ok(r.perf)
        }
    }

    override fun synthetic(kind: SyntheticScore, profile: InstrumentProfile, generation: Int): Performance =
        SyntheticScores.build(kind, profile, generation)

    internal sealed class Outcome {
        class Ok(val perf: Performance, val raw: PerformanceBuilder.RawFacts) : Outcome()
        class Failed(val error: SmfError, val detail: String, val byteOffset: Int) : Outcome()
    }

    internal fun compileRaw(bytes: ByteArray, id: String, generation: Int, profile: InstrumentProfile, opts: CompileOptions): Outcome =
        when (val s = SmfParser.parse(bytes)) {
            is SmfResult.Failed -> Outcome.Failed(s.error, s.detail, s.byteOffset)
            is SmfResult.Ok -> when (val b = build(s.smf, id, generation, profile, opts)) {
                is PerformanceBuilder.Result.Failed -> Outcome.Failed(b.error, b.detail, 0)
                is PerformanceBuilder.Result.Ok -> Outcome.Ok(b.perf, b.raw)
            }
        }

    companion object {
        /** Parsed file → builder result (used by tests that already hold a RawSmf). */
        fun build(smf: RawSmf, id: String, generation: Int, profile: InstrumentProfile,
                  opts: CompileOptions = CompileOptions()): PerformanceBuilder.Result {
            val tempo = TempoMap.of(smf)
            val ev = ChannelMerge.merge(smf, tempo)
            return PerformanceBuilder.build(PerformanceBuilder.Input(ev, { until -> tempo.barUs(until) }, smf.title, smf.warnings),
                id, generation, profile, opts)
        }
    }
}
