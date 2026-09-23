package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MixSettings
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.PassThroughDsp
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import com.tropicalstream.hammerklavier.contract.stub.StubScoreCompiler
import org.junit.Assert.assertTrue
import org.junit.Test

/** Listening renders of the synthetic scores on the stand-in bank (core/build/renders/<name>.wav), with the pass-through DSP. */
class OfflineRenderTest {
    private fun render(kind: SyntheticScore, id: InstrumentId, seconds: Int, name: String) {
        val profile = InstrumentProfile.of(id)
        val core = EngineCore(PassThroughDsp.create(), VoiceCursorBoard(), HeadPose())
        val stops = profile.stops
        val bank = SineBank(layers = 2, stops = stops, instrument = id)
        core.on(Cmd.SET_BANK, 0L, 0f, core.prepareBank(bank, KeyMapFixtures.forSineBank(2, KeyMapFixtures.Mode.XFADE, stops), profile))
        core.on(Cmd.QUALITY, 0L, 0f, QualityLadder.of(0, 96))
        core.on(Cmd.MIX, 0L, 0f, MixSettings(reverb = ReverbMode.DRY, resonance = ResonanceMode.NATURAL, speakerBass = SpeakerBass.OFF, masterDb = -14f))
        core.on(Cmd.SET_PERF, 0L, 1f, StubScoreCompiler().synthetic(kind, profile, 1))
        val out = OfflineRender.render(core, seconds * HK.SR)
        var peak = 0f; var bad = false
        for (x in out) { if (x.isNaN() || x.isInfinite()) bad = true; peak = maxOf(peak, kotlin.math.abs(x)) }
        assertTrue("$name: NaN or ∞", !bad)
        assertTrue("$name: silent", peak > 1e-3f)
        val f = OfflineRender.writeWav(name, out)
        assertTrue(f.length() > 44)
    }

    @Test fun scale() = render(SyntheticScore.SCALE, InstrumentId.GRAND, 23, "scale_grand")
    @Test fun pedalHalf() = render(SyntheticScore.PEDAL_HALF, InstrumentId.GRAND, 18, "pedalhalf_grand")
    @Test fun repeat15() = render(SyntheticScore.REPEAT_15, InstrumentId.UPRIGHT, 9, "repeat15_upright")
    @Test fun harpsichordScale() = render(SyntheticScore.SCALE, InstrumentId.HARPSICHORD, 23, "scale_harpsichord")
    @Test fun sostenuto() = render(SyntheticScore.SOSTENUTO, InstrumentId.GRAND, 12, "sostenuto_grand")
}
