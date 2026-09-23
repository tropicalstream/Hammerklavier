package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.ViewId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Small §5.8–§5.10 rules: room level precedence, the una corda shift set, the label anchors. */
class RenderRulesTest {
    @Test fun userOverrideBeatsThermalCapWhichBeatsAuto() {
        // Auto alone
        assertEquals(RoomLevel.STAGE.ordinal, StereoRenderer.levelFor(null, ViewId.PLAYER, RoomLevel.SALON))
        assertEquals(RoomLevel.SALON.ordinal, StereoRenderer.levelFor(null, ViewId.HALL, RoomLevel.SALON))
        // the cap overrides Auto
        assertEquals(RoomLevel.INSTRUMENT.ordinal, StereoRenderer.levelFor(null, ViewId.HALL, RoomLevel.INSTRUMENT))
        // the user overrides both
        assertEquals(RoomLevel.SALON.ordinal, StereoRenderer.levelFor(RoomLevel.SALON, ViewId.PLAYER, RoomLevel.PASSTHROUGH))
        assertEquals(RoomLevel.PASSTHROUGH.ordinal, StereoRenderer.levelFor(RoomLevel.PASSTHROUGH, ViewId.HALL, RoomLevel.SALON))
    }

    @Test fun unaCordaMovesOnlyTheAction() {
        val moving = setOf(SkinKind.KEY_ROT, SkinKind.HAMMER_ROT, SkinKind.JACK_LIFT, SkinKind.JACK4_LIFT,
            SkinKind.TONGUE_ROT, SkinKind.TONGUE4_ROT, SkinKind.ACTION_SET)
        for (k in SkinKind.entries) assertEquals(k.name, if (k in moving) 0.0025f else 0f, ItemDrawer.shiftFor(k, 0.0025f), 0f)
        for (k in listOf(SkinKind.DAMPER_LIFT, SkinKind.PEDAL_ROT, SkinKind.SOSTENUTO_ROT, SkinKind.HAMMER_RAIL, SkinKind.LID))
            assertEquals(0f, ItemDrawer.shiftFor(k, 0.0025f), 0f)
    }

    @Test fun labelAnchorFollowsTheInstrument() {
        val o = FloatArray(2)
        StereoRenderer.labelAnchor(InstrumentId.GRAND, o); assertEquals(0.93f, o[0]); assertEquals(-0.24f, o[1])
        StereoRenderer.labelAnchor(InstrumentId.UPRIGHT, o); assertEquals(0.93f, o[0]); assertEquals(-0.20f, o[1])
        StereoRenderer.labelAnchor(InstrumentId.HARPSICHORD, o); assertEquals(0.86f, o[0]); assertEquals(-0.42f, o[1])
    }
}
