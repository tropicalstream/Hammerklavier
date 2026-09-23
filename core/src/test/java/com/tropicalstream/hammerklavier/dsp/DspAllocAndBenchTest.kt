package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.SoftKind
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.contract.stub.FixedRoom
import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** T3.5: zero allocation in every process (and every audio-thread setter); ns per frame per processor. */
class DspAllocAndBenchTest {
    private val B = HK.BLOCK

    private class Buffers {
        val mix = DspTestUtil.noise(HK.BLOCK, 0.2, 1)
        val self = FloatArray(88 * HK.BLOCK)
        val rows = BooleanArray(88)
        val gate = FloatArray(HK.KEYS) { if (it in 21..108) 1f else 0f }
        val soft = BooleanArray(HK.KEYS).also { it[60] = true }
        val damp = FloatArray(HK.KEYS)
        val l = DspTestUtil.noise(HK.BLOCK, 0.2, 2); val r = DspTestUtil.noise(HK.BLOCK, 0.2, 3)
        val ol = FloatArray(HK.BLOCK); val or = FloatArray(HK.BLOCK)
        val inter = FloatArray(2 * HK.BLOCK)
    }

    private fun comb(): ResonanceBank {
        val b = FloatArray(HK.KEYS) { if (it < 60) 2e-4f else 1e-4f }
        val bank = ResonanceBank()
        bank.apply(bank.design(CombRig.et(440.0), b, null, InstrumentProfile.GRAND), 0)
        bank.setMode(ResonanceMode.RICH, InstrumentId.GRAND, 88, true)
        return bank
    }

    @Test fun noAllocationInProcessOrSetters() {
        val x = Buffers()
        val bank = comb()
        val prepared = bank.design(CombRig.et(415.0), FloatArray(HK.KEYS), null, InstrumentProfile.GRAND)
        val out88 = FloatArray(88)
        AllocProbe.assertNoAllocation("ResonanceBank.process") {
            repeat(200) { bank.process(x.mix, x.self, x.rows, x.gate, x.soft, x.damp, x.ol, x.or, B); bank.energy(out88) }
        }
        AllocProbe.assertNoAllocation("ResonanceBank.apply/setMode") {
            bank.apply(prepared, 200); bank.setMode(ResonanceMode.NATURAL, InstrumentId.GRAND, 44, false)
            repeat(50) { bank.process(x.mix, x.self, x.rows, x.gate, x.soft, x.damp, x.ol, x.or, B) }
            bank.setMode(ResonanceMode.RICH, InstrumentId.GRAND, 88, true)
        }
        val room = RoomChain(); room.setDesign(FixedRoom.PLAYER, 0)
        AllocProbe.assertNoAllocation("RoomChain.process + setters") {
            room.setDesign(FixedRoom.PLAYER, 500); room.setInputGain(0.5f, 60f); room.setLines(4); room.setLines(8)
            repeat(200) { room.process(x.l, x.r, x.ol, x.or, B, 0.3f) }
        }
        val soft = SoftBus()
        AllocProbe.assertNoAllocation("SoftBus") {
            soft.configure(SoftKind.UNA_CORDA); repeat(200) { soft.process(x.l, x.r, x.ol, x.or, B) }; soft.configure(SoftKind.HAMMER_RAIL)
        }
        val master = MasterChain()
        AllocProbe.assertNoAllocation("MasterChain") {
            master.setRoute(OutputRoute.SPEAKER); master.setSpeakerBass(SpeakerBass.ON); master.setGain(0.4f)
            repeat(200) { master.process(x.l, x.r, B, x.inter) }
            master.setRoute(OutputRoute.WIRED)
        }
        AllocProbe.assertNoAllocation("DspTables lookups") {
            var s = 0f; repeat(1000) { s += DspTables.dbToLin(-it * 0.1f) + DspTables.linToDb(it + 1f) + DspTables.sinT(it * 0.01f) }
            assertEquals(true, s.isFinite())
        }
    }

    @Test fun nanosecondsPerFramePerProcessor() {
        val x = Buffers()
        val bank = comb(); val room = RoomChain(); room.setDesign(FixedRoom.PLAYER, 0)
        val soft = SoftBus(); soft.configure(SoftKind.UNA_CORDA)
        val master = MasterChain(); master.setSpeakerBass(SpeakerBass.ON)
        val blocks = 2000
        fun bench(name: String, f: () -> Unit): String {
            repeat(blocks / 2) { f() }
            val t0 = System.nanoTime(); repeat(blocks) { f() }
            val ns = (System.nanoTime() - t0).toDouble() / blocks / B
            return "%-34s %8.1f ns/frame  (%.2f%% of a 48 kHz frame, JVM host)".format(name, ns, ns / 20833.0 * 100)
        }
        val lines = listOf(
            bench("ResonanceBank, 88 combs (4-way)") { bank.process(x.mix, x.self, x.rows, x.gate, x.soft, x.damp, x.ol, x.or, B) },
            bench("ResonanceBank, 88 combs (scalar)") { bank.scalarReference = true; bank.process(x.mix, x.self, x.rows, x.gate, x.soft, x.damp, x.ol, x.or, B); bank.scalarReference = false },
            bench("RoomChain (direct + 12 ER + FDN 8)") { room.process(x.l, x.r, x.ol, x.or, B, 0f) },
            bench("SoftBus (una corda)") { soft.process(x.l, x.r, x.ol, x.or, B) },
            bench("MasterChain (speaker, VB on)") { master.process(x.l, x.r, B, x.inter) })
        val text = "T3.5 DSP bench (feeds PLAN §3.16; the device numbers come from EngineBench)\n" + lines.joinToString("\n") + "\n"
        print(text)
        File("build").mkdirs(); File("build/dsp_bench.txt").writeText(text)
    }
}
