package com.tropicalstream.hammerklavier.geom

import com.tropicalstream.hammerklavier.contract.CameraPose
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.stub.StubInstrumentScene
import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T6.2 (PLAN §7.2 WP6): dip 250/250 ms with the cut at the midpoint, queue depth 1, springs, dead band, rest. */
class CameraDirectorTest {
    private val pose = MechanismPose()
    private val gaze = Gaze()
    private val out = CameraPose()

    private fun director(id: InstrumentId = InstrumentId.GRAND): CameraDirector {
        val d = CameraDirector()
        val prof = InstrumentProfile.of(id)
        d.setScene(StubInstrumentScene(id).anchors, KonzertzimmerAcoustics.PLACEMENTS.getValue(id), prof.lowKey, prof.highKey)
        d.jump(ViewId.PLAYER, 0)
        d.update(0.001f, pose, gaze, out)
        return d
    }

    @Test fun dipTimingAndCutAtMidpoint() {
        val d = director()
        d.request(ViewId.ACTION, 0)
        val dt = 0.001f
        var t = 0f; var cutAt = -1f; var endAt = -1f; var maxFade = 0f
        while (t < 0.7f) {
            d.update(dt, pose, gaze, out); t += dt
            maxFade = maxOf(maxFade, d.fade)
            if (d.cutThisFrame) { assertTrue(cutAt < 0f); cutAt = t; assertEquals(1f, d.fade, 0f); assertEquals(ViewId.ACTION, d.view) }
            if (cutAt < 0f) assertEquals(ViewId.PLAYER, d.view)
            if (endAt < 0f && cutAt > 0f && !d.dipping) endAt = t
            if (abs(t - 0.125f) < dt / 2) assertEquals(0.5f, d.fade, 0.01f)
            if (abs(t - 0.375f) < dt / 2) assertEquals(0.5f, d.fade, 0.01f)
        }
        assertEquals(0.25f, cutAt, 0.0015f)
        assertEquals(0.50f, endAt, 0.0015f)
        assertEquals(1f, maxFade, 0f)
        assertEquals(0f, d.fade, 0f)
    }

    @Test fun queueDepthOne() {
        val d = director()
        d.request(ViewId.ACTION, 0)
        d.update(0.1f, pose, gaze, out)
        d.request(ViewId.ACTION, 1)                          // during OUT: retargets the same dip
        d.update(0.2f, pose, gaze, out)                      // cut
        assertEquals(ViewId.ACTION, d.view); assertEquals(1, d.framing)
        d.request(ViewId.HALL, 0); d.request(ViewId.HALL, 1); d.request(ViewId.PLAYER, 1)   // during IN: only the last is kept
        var cuts = 0
        repeat(1000) { d.update(0.002f, pose, gaze, out); if (d.cutThisFrame) cuts++ }
        assertEquals(1, cuts)
        assertEquals(ViewId.PLAYER, d.view); assertEquals(1, d.framing)
        assertFalse(d.dipping)
    }

    @Test fun sameViewIsNoDipButSceneSwapDips() {
        val d = director()
        d.request(ViewId.PLAYER, 0)
        assertFalse(d.dipping)
        d.requestDip()
        var cuts = 0
        repeat(300) { d.update(0.002f, pose, gaze, out); if (d.cutThisFrame) cuts++ }
        assertEquals(1, cuts); assertEquals(ViewId.PLAYER, d.view)
    }

    @Test fun noDipAfterDisplayRest() {
        val d = director()
        d.request(ViewId.HALL, 0)                            // arrives during rest
        d.cutImmediately()                                   // first frame after wake
        d.update(0.033f, pose, gaze, out)
        assertTrue(d.cutThisFrame); assertEquals(ViewId.HALL, d.view)
        assertEquals(0f, d.fade, 0f); assertFalse(d.dipping)
        // A scene swap requested during rest also cuts at once.
        d.requestDip(); d.cutImmediately(); d.update(0.033f, pose, gaze, out)
        assertTrue(d.cutThisFrame); assertEquals(0f, d.fade, 0f)
    }

    @Test fun followSpringCappedAt06() {
        val d = director()
        d.request(ViewId.PLAYER, 1)
        repeat(400) { d.update(0.002f, pose, gaze, out) }
        pose.centroidKey = 21f; repeat(100) { d.update(0.033f, pose, gaze, out) }
        pose.centroidKey = 108f
        var prev = d.followSpring.x; var maxV = 0f
        repeat(200) {
            d.update(0.033f, pose, gaze, out)
            maxV = maxOf(maxV, abs(d.followSpring.x - prev) / 0.033f); prev = d.followSpring.x
        }
        assertTrue("max speed $maxV", maxV <= 0.6f + 1e-4f)
        assertTrue(maxV > 0.55f)
        assertEquals(d.keyToX(108f), d.followSpring.x, 1e-3f)
        // The camera x (piano frame) is the spring: with the grand placement (yaw −90°) piano +x maps to room +z (south).
        assertEquals(-1.90f + d.followSpring.x, out.pos[2], 1e-3f)
    }

    @Test fun cutDeadBand() {
        val d = director()
        pose.focusKey = 60f
        d.request(ViewId.ACTION, 0); repeat(300) { d.update(0.002f, pose, gaze, out) }
        repeat(100) { d.update(0.033f, pose, gaze, out) }
        val x60 = d.cutSpring.x
        assertEquals(d.keyToX(60f), x60, 1e-4f)
        pose.focusKey = 61.9f; repeat(100) { d.update(0.033f, pose, gaze, out) }
        assertEquals(x60, d.cutSpring.x, 1e-5f)               // inside ±2 semitones: held
        pose.focusKey = 58.1f; repeat(100) { d.update(0.033f, pose, gaze, out) }
        assertEquals(x60, d.cutSpring.x, 1e-5f)
        pose.focusKey = 63f; repeat(200) { d.update(0.033f, pose, gaze, out) }
        assertEquals(d.keyToX(63f), d.cutSpring.x, 1e-3f)     // beyond the band: follows
        // clipX from the anchors, piano frame, at the springed key
        assertEquals(d.cutSpring.x, out.clipX, 2e-3f)
    }

    @Test fun cutSpringIsCriticallyDamped() {
        val s = CritSpring(omega = 4f)
        s.snap(0f)
        var over = 0f; var t = 0f
        while (t < 5f) { s.update(1f, 0.033f); over = maxOf(over, s.x - 1f); t += 0.033f }
        assertTrue("no overshoot $over", over < 1e-3f)
        assertEquals(1f, s.x, 1e-3f)
    }

    @Test fun gazeScaling() {
        val d = director()
        gaze.yaw = 1.0f; gaze.pitch = 0.2f
        d.update(0.03f, pose, gaze, out)
        assertEquals(CameraDirector.PARALLAX_MAX, d.gazeYaw, 1e-6f)
        assertEquals(0.03f, d.gazePitch, 1e-6f)
        d.jump(ViewId.HALL, 0); d.update(0.03f, pose, gaze, out)
        assertEquals(1.0f, d.gazeYaw, 0f)
        gaze.yaw = 0f; gaze.pitch = 0f
    }

    @Test fun updateAllocatesNothing() {
        val d = director()
        d.request(ViewId.ACTION, 0)
        repeat(100) { d.update(0.033f, pose, gaze, out) }
        val bytes = AllocProbe.measure { repeat(1000) { d.update(0.033f, pose, gaze, out); d.request(ViewId.PLAYER, it % 2) } }
        assertEquals(0L, bytes)
    }
}
