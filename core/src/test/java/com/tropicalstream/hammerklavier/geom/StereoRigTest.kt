package com.tropicalstream.hammerklavier.geom

import com.tropicalstream.hammerklavier.contract.CameraPose
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.stub.StubInstrumentScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T6.1 (PLAN §7.2 WP6): zero parallax, crossed disparity, right = forward × up for every framing. */
class StereoRigTest {
    private fun ndcX(m: FloatArray, p: FloatArray): Float {
        val o = FloatArray(4); Mat4.transform(m, p[0], p[1], p[2], o); return o[0] / o[3]
    }

    private fun check(pose: CameraPose, stereoDepth: Float = 1f) {
        val rig = StereoRig()
        rig.update(pose.pos, pose.target, pose.vFovDeg, pose.ipdScale, pose.zeroParallaxM, stereoDepth, 320f / 480f, 0f, 0f, true)
        val f = rig.forward
        // right = forward × worldUp, then up = right × forward
        val r = floatArrayOf(-f[2], 0f, f[0]); Mat4.normalize(r)
        for (i in 0 until 3) assertEquals(r[i], rig.right[i], 1e-5f)
        val u = FloatArray(3); Mat4.cross(rig.right, f, u)
        for (i in 0 until 3) assertEquals(u[i], rig.up[i], 1e-5f)
        assertTrue("up points up", rig.up[1] > 0f)
        assertEquals(0f, Mat4.dot(rig.right, f), 1e-5f)
        // A point on the axis at the zero-parallax distance: same NDC x in both eyes.
        val zp = FloatArray(3) { pose.pos[it] + f[it] * pose.zeroParallaxM }
        val l0 = ndcX(rig.eye(0).viewProj, zp); val r0 = ndcX(rig.eye(1).viewProj, zp)
        assertEquals(l0, r0, 1e-4f)
        // Off axis too (zero parallax is a plane).
        val zp2 = FloatArray(3) { zp[it] + rig.right[it] * 0.1f + rig.up[it] * 0.05f }
        assertEquals(ndcX(rig.eye(0).viewProj, zp2), ndcX(rig.eye(1).viewProj, zp2), 1e-4f)
        // Nearer: crossed disparity (the left eye's image lies to the right of the right eye's).
        val near = FloatArray(3) { pose.pos[it] + f[it] * pose.zeroParallaxM * 0.5f }
        assertTrue("crossed", ndcX(rig.eye(0).viewProj, near) > ndcX(rig.eye(1).viewProj, near) + 1e-4f)
        // Farther: uncrossed.
        val far = FloatArray(3) { pose.pos[it] + f[it] * pose.zeroParallaxM * 2f }
        assertTrue("uncrossed", ndcX(rig.eye(0).viewProj, far) < ndcX(rig.eye(1).viewProj, far))
        // Eyes separated by 63 mm × IPD scale × depth along right.
        val d = FloatArray(3) { rig.eye(1).pos[it] - rig.eye(0).pos[it] }
        assertEquals(0.063f * pose.ipdScale * stereoDepth, Mat4.normalize(d), 1e-5f)
        for (i in 0 until 3) assertEquals(rig.right[i], d[i], 1e-4f)
    }

    @Test fun everyFramingOfEveryInstrument() {
        val director = CameraDirector()
        val pose = MechanismPose()
        val out = CameraPose()
        for (id in InstrumentId.entries) {
            val scene = StubInstrumentScene(id)
            val prof = InstrumentProfile.of(id)
            director.setScene(scene.anchors, KonzertzimmerAcoustics.PLACEMENTS.getValue(id), prof.lowKey, prof.highKey)
            for (v in ViewId.entries) for (fr in 0..1) {
                director.jump(v, fr)
                director.update(0.033f, pose, Gaze(), out)
                check(out); check(out, 0.5f)
            }
        }
    }

    @Test fun monoIsOneCentredEye() {
        val rig = StereoRig()
        val pos = floatArrayOf(0f, 1.3f, 1.5f); val t = floatArrayOf(0f, 0.5f, 0f)
        rig.update(pos, t, 34f, 0.6f, 1.75f, 1f, 640f / 480f, 0f, 0f, false)
        assertEquals(1, rig.eyeCount)
        for (i in 0 until 3) assertEquals(pos[i], rig.eye(0).pos[i], 0f)
        // Symmetric frustum: the look-at point projects to the centre.
        assertEquals(0f, ndcX(rig.eye(0).viewProj, t), 1e-5f)
    }

    @Test fun gazeTurnsTheForwardVector() {
        val rig = StereoRig()
        val pos = floatArrayOf(0f, 1.2f, 0f); val t = floatArrayOf(0f, 1.2f, -1f)
        rig.update(pos, t, 40f, 1f, 4.9f, 1f, 1f, 0.3f, 0f, true)
        assertTrue("look right = +x when facing −z", rig.forward[0] > 0.29f)
        assertEquals(0.2955f, rig.forward[0], 1e-3f)
        rig.update(pos, t, 40f, 1f, 4.9f, 1f, 1f, 0f, 0.2f, true)
        assertEquals(0.1987f, rig.forward[1], 1e-3f)
    }

    @Test fun sinTableAccuracy() {
        var maxErr = 0f
        var a = -20f
        while (a < 20f) { maxErr = maxOf(maxErr, abs(SinTable.sin(a) - kotlin.math.sin(a))); a += 0.0137f }
        assertTrue("err $maxErr", maxErr < 2e-6f)
        assertEquals(kotlin.math.tan(0.3f), SinTable.tan(0.3f), 1e-5f)
    }

    @Test fun rigidInverse() {
        val o = floatArrayOf(-1f, 0.2f, -1.9f); val yaw = -1.5707964f
        val m = FloatArray(16); val inv = FloatArray(16); val id = FloatArray(16)
        Mat4.rigidY(m, o, SinTable.cos(yaw), SinTable.sin(yaw)); Mat4.rigidYInverse(inv, o, SinTable.cos(yaw), SinTable.sin(yaw))
        Mat4.multiply(id, inv, m)
        for (i in 0 until 16) assertEquals(if (i % 5 == 0) 1f else 0f, id[i], 1e-5f)
        // matches Placement.toRoom
        val p = floatArrayOf(0.3f, 0.7f, 0.5f); val a = FloatArray(3); val b = FloatArray(3)
        KonzertzimmerAcoustics.PLACEMENTS.getValue(InstrumentId.GRAND).toRoom(p, a)
        Mat4.rigidY(m, floatArrayOf(-1f, 0f, -1.9f), SinTable.cos(yaw), SinTable.sin(yaw)); Mat4.transformPoint(m, p, b)
        for (i in 0 until 3) assertEquals(a[i], b[i], 1e-5f)
    }
}
