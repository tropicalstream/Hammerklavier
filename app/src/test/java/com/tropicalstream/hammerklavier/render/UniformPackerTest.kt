package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.contract.stub.StubInstrumentScene
import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

/** T6.4 (PLAN §7.2 WP6): key k → lane k − lowKey; the ACTION_SET decode agrees with the §5.8 layout. */
class UniformPackerTest {
    private fun oneHot(l: Int) = FloatArray(4).also { it[l] = 1f }

    @Test fun keyMapsToLaneKMinusLowKey() {
        for (id in InstrumentId.entries) {
            val prof = InstrumentProfile.of(id)
            val pose = MechanismPose()
            for (k in 0 until 128) { pose.keyDip[k] = k / 1000f; pose.hammer[k] = k / 2000f; pose.damper[k] = k / 3000f; pose.jack4[k] = k / 4000f }
            val p = UniformPacker()
            p.pack(pose, prof.lowKey, prof.highKey, 0f, null, 60f)
            for (k in prof.lowKey..prof.highKey) {
                val part = k - prof.lowKey
                val slot = UniformPacker.slotOf(part).toFloat(); val lane = oneHot(UniformPacker.laneOf(part))
                assertEquals(pose.keyDip[k], UniformPacker.readLane(p.block(SkinKind.KEY_ROT), slot, lane), 0f)
                assertEquals(pose.hammer[k], UniformPacker.readLane(p.block(SkinKind.HAMMER_ROT), slot, lane), 0f)
                assertEquals(pose.hammer[k], UniformPacker.readLane(p.block(SkinKind.JACK_LIFT), slot, lane), 0f)
                assertEquals(pose.jack4[k], UniformPacker.readLane(p.block(SkinKind.JACK4_LIFT), slot, lane), 0f)
                assertEquals(pose.damper[k], UniformPacker.readLane(p.block(SkinKind.DAMPER_LIFT), slot, lane), 0f)
            }
            // beyond the compass the lanes are zero
            val n = prof.highKey - prof.lowKey + 1
            for (i in n until UniformPacker.FLOATS) assertEquals(0f, p.block(SkinKind.KEY_ROT)[i], 0f)
        }
    }

    @Test fun stubKeyboardVerticesReadTheirOwnKey() {
        val scene = StubInstrumentScene(InstrumentId.GRAND)
        val prof = InstrumentProfile.of(InstrumentId.GRAND)
        val pose = MechanismPose()
        for (k in 0 until 128) pose.keyDip[k] = k.toFloat()
        val p = UniformPacker(); p.pack(pose, prof.lowKey, prof.highKey, 0f, scene, 60f)
        val keyX = scene.anchors.keyX
        for (m in scene.meshes()) {
            assertEquals(VertexLayout.SKINNED, m.layout)
            val f = m.layout.floats
            for (v in 0 until m.vertexCount) {
                val o = v * f
                val lane = floatArrayOf(m.vertices[o + 13], m.vertices[o + 14], m.vertices[o + 15], m.vertices[o + 16])
                val key = UniformPacker.readLane(p.block(SkinKind.KEY_ROT), m.vertices[o + 12], lane).toInt()
                // the vertex lies on the key it reads
                assertEquals(keyX[key], m.vertices[o], 0.0071f)
            }
        }
    }

    @Test fun pedalsAndScalars() {
        val pose = MechanismPose()
        pose.soft = 0.25f; pose.sostenuto = 0.5f; pose.sustain = 0.75f; pose.shiftMm = 2.5f; pose.hammerRailMm = 22f
        pose.strikeAge[60] = 0.075f; pose.stringAmp[61] = 0.3f
        val p = UniformPacker(); p.pack(pose, 21, 108, 1f, null, 60f)
        val b = p.block(SkinKind.PEDAL_ROT)
        assertEquals(0.25f, UniformPacker.readLane(b, 0f, oneHot(0)), 0f)
        assertEquals(0.5f, UniformPacker.readLane(b, 0f, oneHot(1)), 0f)
        assertEquals(0.75f, UniformPacker.readLane(b, 0f, oneHot(2)), 0f)
        assertEquals(0.5f, p.block(SkinKind.SOSTENUTO_ROT)[0], 0f)
        assertEquals(0.022f, p.block(SkinKind.HAMMER_RAIL)[0], 1e-6f)
        assertEquals(1f, p.block(SkinKind.LID)[0], 0f)
        assertEquals(0.0025f, p.shiftXM, 1e-7f); assertEquals(0.022f, p.railM, 1e-7f)
        assertEquals(0.5f, p.block(SkinKind.STRING)[60 - 21], 1e-6f)      // pulse half-way down
        assertEquals(0.3f, p.block(SkinKind.STRING)[61 - 21], 1e-6f)
    }

    /** The §5.8 layout, written out independently of the decoder (a private reference packer). */
    private fun referencePack(out: FloatArray, xM: FloatArray, dim: FloatArray, key: IntArray, angle: Array<FloatArray>) {
        java.util.Arrays.fill(out, 0f)
        for (s in 0 until 13) { out[4 * s] = xM[s]; out[4 * s + 1] = dim[s]; out[4 * s + 2] = key[s].toFloat() }
        for (s in 0 until 13) for (p in 0 until 6) {
            val vec4 = 13 + (6 * s + p) / 4; val lane = (6 * s + p) % 4
            out[vec4 * 4 + lane] = angle[s][p]
        }
    }

    @Test fun actionSetDecodeAgreesWithTheLayout() {
        val xM = FloatArray(13) { -0.1f + it * 0.0137f }
        val dim = FloatArray(13) { if (it == 6) 1f else 0.4f }
        val key = IntArray(13) { 54 + it }
        val angle = Array(13) { s -> FloatArray(6) { p -> 0.01f * s + 0.001f * p + 0.0001f } }
        val block = FloatArray(136)
        referencePack(block, xM, dim, key, angle)
        val h = FloatArray(4)
        for (s in 0 until 13) {
            UniformPacker.actionHeader(block, s, h)
            assertEquals(xM[s], h[0], 0f); assertEquals(dim[s], h[1], 0f); assertEquals(key[s].toFloat(), h[2], 0f)
            for (p in 0 until 6) assertEquals(angle[s][p], UniformPacker.actionAngle(block, s, p), 0f)
        }
        // vec4 33 is spare, and 13 + (6·12 + 5)/4 = 32 is the last angle vec4
        assertEquals(32, 13 + (6 * 12 + 5) / 4)
        for (i in 132 until 136) assertEquals(0f, block[i], 0f)
    }

    @Ignore("needs WP7 InstrumentScene.packActionSet: decode WP7's packed known pose with actionAngle/actionHeader (pair of T7.7)")
    @Test fun actionSetDecodeAgreesWithWp7Pack() {}

    @Test fun packAllocatesNothing() {
        val scene = StubInstrumentScene(InstrumentId.GRAND)
        val pose = MechanismPose(); val p = UniformPacker()
        AllocProbe.assertNoAllocation("UniformPacker.pack") { repeat(100) { p.pack(pose, 21, 108, 0f, scene, 60f) } }
    }
}
