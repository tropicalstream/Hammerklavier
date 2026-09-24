package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.MixSettings
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.SampleReader
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.contract.TuningSpec
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import com.tropicalstream.hammerklavier.dsp.DspFactory
import com.tropicalstream.hammerklavier.dsp.Lufs
import com.tropicalstream.hammerklavier.dsp.MasterChain
import com.tropicalstream.hammerklavier.dsp.RoomAcoustics
import com.tropicalstream.hammerklavier.kit.KeyMapBuilder
import com.tropicalstream.hammerklavier.kit.KitIndex
import com.tropicalstream.hammerklavier.kit.KitMapCodec
import com.tropicalstream.hammerklavier.midi.ScoreCompilerImpl
import com.tropicalstream.hammerklavier.session.ListenerRooms
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

/**
 * Offline loudness table (INTEGRATION.md, loudness across instruments and views): renders three
 * pieces on each REAL kit through the full chain (combs, room design of each view, master bus at
 * the default −8 dB) and writes integrated LUFS (BS.1770) and limiter activity per instrument ×
 * view to `build/loudness/table<_tag>.csv`. Needs every unit pre-decoded to
 * `build/loudness/<kit>/u<N>.s16` (48 kHz stereo s16le) and the marker `build/loudness/ENABLE`
 * (tokens: `tag=<name>`, `kit=<kit>`, `secs=<n>`, `pieces=a,b,…`, `raw` = without the seat residual, `master=<dB>` (default −8),
 * `wav`); skipped otherwise, so CI never runs it. The first line is [LoudnessFingerprint]; copy
 * the `after` table to `core/src/test/resources/loudness/table_after.csv` for LoudnessTableTest.
 */
class LoudnessProbeTest {
    private val root = File("../build/loudness")
    private val tokens by lazy { File(root, "ENABLE").takeIf { it.isFile }?.readText()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList() }
    private fun tok(k: String) = tokens.firstOrNull { it.startsWith("$k=") }?.substringAfter('=')

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

    private class Kit(val id: InstrumentId, val name: String, val profile: InstrumentProfile)
    private val kits = listOf(Kit(InstrumentId.GRAND, "grand", InstrumentProfile.GRAND),
        Kit(InstrumentId.UPRIGHT, "upright", InstrumentProfile.UPRIGHT), Kit(InstrumentId.HARPSICHORD, "harpsichord", InstrumentProfile.HARPSICHORD))

    private class View(val label: String, val view: ViewId, val framing: Int)
    private val views = listOf(View("player", ViewId.PLAYER, 0), View("action0", ViewId.ACTION, 0),
        View("action1", ViewId.ACTION, 1), View("hall", ViewId.HALL, 0))

    private val pieces by lazy { tok("pieces")?.split(",") ?: listOf("mond_1", "mz_311_3", "bach_846") }

    @Test fun table() {
        assumeTrue("loudness probe not enabled", File(root, "ENABLE").isFile)
        val tag = tok("tag")?.let { "_$it" } ?: ""
        val secs = tok("secs")?.toInt() ?: 30
        val onlyKit = tok("kit")
        val rows = ArrayList<String>()
        val embedded = HashMap(LoudnessFingerprint.kitsFromAssets().mapValues { it.value.second })
        rows += "instrument,piece,view,lufs,peak_dbfs,lim_min_db,lim_active_pct,lim_mean_gr_db"
        for (k in kits) {
            if (onlyKit != null && k.name != onlyKit) continue
            val d = File("../app/src/main/assets/instruments/${k.name}")
            val idx = (KitMapCodec.decode(File(d, "map.json").readText(), File(d, "env.bin").readBytes()) as KitMapCodec.Result.Ok).index
            val units = idx.regions.map { it.unit }.toSet()
            val pcm = units.associateWith { u ->
                val b = File(root, "${k.name}/u$u.s16").readBytes()
                ShortArray(b.size / 2).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
            }
            val bank = PcmBank(idx, pcm)
            embedded[k.id] = bank.info.embeddedRoomDb
            var mask = 0L; for (u in units) mask = mask or (1L shl u)
            val km = KeyMapBuilder.build(idx, TuningSpec.A440_EQUAL, mask)
            val placement = KonzertzimmerAcoustics.PLACEMENTS.getValue(k.id)
            for (piece in pieces) {
                val b = File("../tools/cache/midi/krueger/$piece.mid").readBytes()
                val p: Performance = (ScoreCompilerImpl().compile(b, piece, 1, k.profile, CompileOptions()) as CompileResult.Ok).perf
                for (v in views) {
                    val listener = ListenerRooms.resolve(k.id, null, placement, v.view, v.framing).pose
                    val raw = RoomAcoustics.design(KonzertzimmerAcoustics.GEOMETRY, placement, ListenerRooms.SOURCE.getValue(k.id),
                        listener, ReverbMode.ROOM, ListenerRooms.benchDistance(k.id, null), bank.info.embeddedRoomDb)
                    // As SessionController.updateRoom; `raw` in ENABLE measures without the seat residual.
                    val design = if ("raw" in tokens) raw else ListenerRooms.leveled(raw, k.id, v.view, v.framing)
                    val dsp = DspFactory.create(HK.SR)
                    val master = dsp.master as MasterChain
                    val core = EngineCore(dsp, VoiceCursorBoard(), HeadPose())
                    core.on(Cmd.SET_BANK, 0L, 0f, core.prepareBank(bank, km, k.profile))
                    if (k.id == InstrumentId.HARPSICHORD) core.on(Cmd.REGISTRATION, (HK.REG_8 or HK.REG_4).toLong(), 0f, null)
                    core.on(Cmd.QUALITY, 0L, 0f, QualityLadder.of(0, 96))
                    core.on(Cmd.MIX, 0L, 0f, MixSettings(reverb = ReverbMode.ROOM, resonance = ResonanceMode.NATURAL,
                        speakerBass = SpeakerBass.OFF, masterDb = tok("master")?.toFloat() ?: -8f))
                    core.on(Cmd.ROOM, 0L, 0f, design)
                    core.on(Cmd.SET_PERF, 0L, 1f, p)
                    val frames = secs * HK.SR
                    val blocks = frames / HK.BLOCK
                    val out = FloatArray(2 * blocks * HK.BLOCK)
                    val buf = FloatArray(2 * HK.BLOCK)
                    master.limiter.takeMinGain()
                    var minG = 1f; var active = 0; var grSum = 0.0
                    for (i in 0 until blocks) {
                        core.render(buf, i.toLong() * HK.BLOCK)
                        System.arraycopy(buf, 0, out, 2 * i * HK.BLOCK, 2 * HK.BLOCK)
                        val g = master.limiter.takeMinGain()
                        if (g < minG) minG = g
                        if (g < 0.999f) active++
                        grSum += -20 * log10(g.toDouble())
                    }
                    var pk = 0f; for (x in out) { val a = kotlin.math.abs(x); if (a > pk) pk = a }
                    val l = Lufs.integrated(out)
                    rows += "%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.3f".format(k.name, piece, v.label, l, 20 * log10(pk.toDouble()),
                        20 * log10(minG.toDouble()), 100.0 * active / blocks, grSum / blocks)
                    if ("wav" in tokens) OfflineRender.writeWavTo(File(root, "wav/${k.name}_${piece}_${v.label}$tag.wav"), out)
                    println(rows.last())
                }
            }
        }
        File(root, "table$tag.csv").writeText(LoudnessFingerprint.of(embedded) + "\n" + rows.joinToString("\n") + "\n")
    }
}
