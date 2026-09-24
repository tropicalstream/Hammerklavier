package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.MixSettings
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.SampleReader
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.contract.TuningSpec
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import com.tropicalstream.hammerklavier.contract.stub.PassThroughDsp
import com.tropicalstream.hammerklavier.dsp.DspFactory
import com.tropicalstream.hammerklavier.kit.KeyMapBuilder
import com.tropicalstream.hammerklavier.kit.KitIndex
import com.tropicalstream.hammerklavier.kit.KitMapCodec
import com.tropicalstream.hammerklavier.midi.ScoreCompilerImpl
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline A/B probe for the per-note "rubbing" noise (INTEGRATION.md, release-noise level): renders
 * real Krueger passages on the REAL grand kit with each stage toggled, writing WAVs and a note CSV
 * to `build/noise/` (repo root). Needs the Opus units pre-decoded to `build/noise/u<N>.s16`
 * (48 kHz stereo s16le, `ffmpeg -i u/N.opus -ac 2 -f s16le`) and the marker `build/noise/ENABLE`;
 * skipped otherwise, so CI never runs it.
 */
class NoiseProbeTest {
    private val root = File("../build/noise")
    // `kit=upright` / `kit=harpsichord` in ENABLE renders that kit (units pre-decoded to build/noise/<kit>/).
    private val tokens by lazy { File(root, "ENABLE").takeIf { it.isFile }?.readText()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList() }
    private val kit by lazy { tokens.firstOrNull { it.startsWith("kit=") }?.substringAfter('=') ?: "grand" }
    private val out by lazy { if (kit == "grand") root else File(root, kit) }
    private val units by lazy { when (kit) { "upright" -> intArrayOf(0, 1, 2, 62, 63); "harpsichord" -> intArrayOf(0, 1, 62); else -> intArrayOf(5, 8, 11, 62, 63) } }
    private val profile by lazy { when (kit) { "upright" -> InstrumentProfile.UPRIGHT; "harpsichord" -> InstrumentProfile.HARPSICHORD; else -> InstrumentProfile.GRAND } }

    private class PcmBank(val index: KitIndex, val pcm: Map<Int, ShortArray>) : LoadedBank {
        override val info: BankInfo = index.toBankInfo(id = index.instrumentId(), fallback = null)
        override val generation = 1
        override val regionCount get() = index.regions.size
        override fun frames(region: Int) = index.regions[region].frames
        override fun onsetFrame(region: Int) = index.regions[region].onsetFrame
        override fun thrFrame(region: Int) = index.regions[region].thrFrame
        override fun envByte(region: Int, tenMs: Int) = index.envByte(region, tenMs)
        override var readyMask: Long = -1L
        override fun newReader(): SampleReader = object : SampleReader {
            override val slowReads = 0
            override fun prefetch(region: Int, fromFrame: Int, frames: Int) {}
            override fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int): Int {
                java.util.Arrays.fill(dst, dstOff, dstOff + 2 * frames, 0)
                val r = index.regions[region]
                val src = pcm[r.unit] ?: return 0
                var n = 0
                for (i in 0 until frames) {
                    val f = fromFrame + i
                    if (f < 0 || f >= r.frames) continue
                    val s = 2 * (r.streamStart + f)
                    if (s + 1 >= src.size) break
                    dst[dstOff + 2 * i] = src[s]; dst[dstOff + 2 * i + 1] = src[s + 1]; n++
                }
                return n
            }
        }
    }

    private fun load(): Pair<KitIndex, PcmBank> {
        // `map=<dir>` (repo-relative) reads map.json + env.bin from another kit directory (an A/B of a map patch)
        val d = tokens.firstOrNull { it.startsWith("map=") }?.let { File("..", it.substringAfter('=')) }
            ?: File("../app/src/main/assets/instruments/$kit")
        val idx = (KitMapCodec.decode(File(d, "map.json").readText(), File(d, "env.bin").readBytes()) as KitMapCodec.Result.Ok).index
        val pcm = units.associateWith { u ->
            val b = File(out, "u$u.s16").readBytes()
            ShortArray(b.size / 2).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        }
        return idx to PcmBank(idx, pcm)
    }

    private fun perf(name: String): Performance {
        val b = File("../tools/cache/midi/krueger/$name.mid").readBytes()
        return (ScoreCompilerImpl().compile(b, name, 1, profile, CompileOptions()) as CompileResult.Ok).perf
    }

    private class V(val name: String, val mix: MixSettings, val passThrough: Boolean = false, val relTrimDb: Float = 0f, val reg: Int = HK.REG_8 or HK.REG_4)

    private fun mix(rev: ReverbMode = ReverbMode.ROOM, res: ResonanceMode = ResonanceMode.NATURAL, rel: Boolean = true, ped: Boolean = true) =
        MixSettings(reverb = rev, resonance = res, speakerBass = SpeakerBass.OFF, masterDb = -6f, releaseNoises = rel, pedalNoises = ped)

    @Test fun probe() {
        assumeTrue("noise probe not enabled", File(root, "ENABLE").isFile)
        val (idx, bank) = load()
        var mask = 0L; for (u in units) mask = mask or (1L shl u)
        val km0 = KeyMapBuilder.build(idx, TuningSpec.A440_EQUAL, mask)
        val variants = listOf(
            V("all", mix()), V("relOff", mix(rel = false)), V("pedOff", mix(ped = false)),
            V("combsOff", mix(res = ResonanceMode.OFF)), V("roomOff", mix(rev = ReverbMode.DRY)),
            V("rawPass", mix(), passThrough = true), V("rawPassRelOff", mix(rel = false), passThrough = true),
            V("rel-37", mix(), relTrimDb = -37f), V("rel-30", mix(), relTrimDb = -30f),
            V("reg8", mix(), reg = HK.REG_8), V("reg8RelOff", mix(rel = false), reg = HK.REG_8),
            V("rawPassReg8RelOff", mix(rel = false), passThrough = true, reg = HK.REG_8))
        val only = tokens.filter { !it.startsWith("kit=") && !it.startsWith("map=") && !it.startsWith("tag=") }.toSet()
        val tag = tokens.firstOrNull { it.startsWith("tag=") }?.substringAfter('=')?.let { "_$it" } ?: ""
        for (piece in listOf("mond_1", "mz_311_3")) {
            val p = perf(piece)
            val secs = if (piece == "mond_1") 40 else 25
            File(out, "$piece.notes.csv").printWriter().use { w ->
                w.println("on_s,off_s,key,vel")
                for (i in 0 until p.noteCount) if (p.onUs[i] < secs * 1_000_000L)
                    w.println("${p.onUs[i] / 1e6},${p.offUs[i] / 1e6},${p.key[i]},${p.vel[i]}")
            }
            for (v in variants) {
                if (only.isNotEmpty() && v.name !in only) continue
                val g = Math.pow(10.0, v.relTrimDb / 20.0).toFloat()
                val km: KeyMap = if (v.relTrimDb == 0f) km0 else km0.copy(releaseGain = FloatArray(km0.releaseGain.size) { km0.releaseGain[it] * g })
                val core = EngineCore(if (v.passThrough) PassThroughDsp.create() else DspFactory.create(HK.SR), VoiceCursorBoard(), HeadPose())
                core.on(Cmd.SET_BANK, 0L, 0f, core.prepareBank(bank, km, profile))
                if (kit == "harpsichord") core.on(Cmd.REGISTRATION, v.reg.toLong(), 0f, null)
                core.on(Cmd.QUALITY, 0L, 0f, QualityLadder.of(0, 96))
                core.on(Cmd.MIX, 0L, 0f, v.mix)
                core.on(Cmd.SET_PERF, 0L, 1f, p)
                OfflineRender.writeWavTo(File(out, "${piece}_${v.name}$tag.wav"), OfflineRender.render(core, secs * HK.SR))
            }
        }
    }
}
