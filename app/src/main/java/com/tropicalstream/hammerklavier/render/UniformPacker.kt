package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.contract.InstrumentScene
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.SkinKind

/**
 * Pose → the `uniform vec4 uState[34]` blocks (PLAN §5.8), packed once per frame and shared by
 * both eyes. Per-key kinds put key k at flat float index k − lowKey (vec4 slot (k − lowKey) / 4,
 * lane (k − lowKey) % 4), which the shader reads back as dot(uState[int(slot)], lane). Pedals:
 * lane 0 soft / una corda, 1 sostenuto, 2 sustain. SOSTENUTO_ROT, HAMMER_RAIL and LID use
 * float 0. ACTION_SET is InstrumentScene.packActionSet's block. STRING carries
 * max(stringAmp, strike pulse) where the pulse falls linearly from 1 to 0 over [PULSE_SEC] after a
 * contact (strikeAge), so one float per key drives the spindle's swell and brightness.
 * GLThread; allocation-free.
 */
class UniformPacker {
    private val blocks = Array(SkinKind.entries.size) { FloatArray(FLOATS) }
    @JvmField var shiftXM = 0f
    @JvmField var railM = 0f
    /** Bit 0: the 8′ register engaged, bit 1: the 4′ (harpsichord). */
    @JvmField var registers = 3

    fun block(kind: SkinKind): FloatArray = blocks[kind.ordinal]

    fun pack(pose: MechanismPose, lowKey: Int, highKey: Int, lidLift: Float, scene: InstrumentScene?, xCutKey: Float) {
        packKeys(blocks[SkinKind.KEY_ROT.ordinal], pose.keyDip, lowKey, highKey)
        packKeys(blocks[SkinKind.HAMMER_ROT.ordinal], pose.hammer, lowKey, highKey)
        packKeys(blocks[SkinKind.JACK_LIFT.ordinal], pose.hammer, lowKey, highKey)
        packKeys(blocks[SkinKind.JACK4_LIFT.ordinal], pose.jack4, lowKey, highKey)
        packKeys(blocks[SkinKind.DAMPER_LIFT.ordinal], pose.damper, lowKey, highKey)
        packKeys(blocks[SkinKind.TONGUE_ROT.ordinal], pose.tongue, lowKey, highKey)
        packKeys(blocks[SkinKind.TONGUE4_ROT.ordinal], pose.tongue4, lowKey, highKey)
        val s = blocks[SkinKind.STRING.ordinal]
        java.util.Arrays.fill(s, 0f)
        for (k in lowKey..highKey) {
            val i = k - lowKey
            if (i >= FLOATS) break
            val age = pose.strikeAge[k]
            val pulse = if (age < PULSE_SEC && age >= 0f) 1f - age / PULSE_SEC else 0f
            val a = pose.stringAmp[k]
            s[i] = if (a > pulse) a else pulse
        }
        val p = blocks[SkinKind.PEDAL_ROT.ordinal]
        java.util.Arrays.fill(p, 0f); p[0] = pose.soft; p[1] = pose.sostenuto; p[2] = pose.sustain
        val so = blocks[SkinKind.SOSTENUTO_ROT.ordinal]; java.util.Arrays.fill(so, 0f); so[0] = pose.sostenuto
        val hr = blocks[SkinKind.HAMMER_RAIL.ordinal]; java.util.Arrays.fill(hr, 0f); hr[0] = pose.hammerRailMm * 0.001f
        val lid = blocks[SkinKind.LID.ordinal]; java.util.Arrays.fill(lid, 0f); lid[0] = lidLift
        val act = blocks[SkinKind.ACTION_SET.ordinal]
        if (scene != null) scene.packActionSet(pose, xCutKey, act) else java.util.Arrays.fill(act, 0f)
        shiftXM = pose.shiftMm * 0.001f
        railM = pose.hammerRailMm * 0.001f
        registers = pose.registers
    }

    private fun packKeys(out: FloatArray, src: FloatArray, lowKey: Int, highKey: Int) {
        java.util.Arrays.fill(out, 0f)
        for (k in lowKey..highKey) { val i = k - lowKey; if (i < FLOATS) out[i] = src[k] }
    }

    companion object {
        const val VEC4S = 34
        const val FLOATS = VEC4S * 4
        const val PULSE_SEC = 0.15f
        /** ACTION_SET: the first angle vec4 and the part types per slot (§5.8). */
        const val ACTION_ANGLES_VEC4 = 13
        const val ACTION_SLOTS = 13
        const val ACTION_PARTS = 6

        /** The shader's read: dot(uState[int(slot + 0.5)], lane). */
        fun readLane(state: FloatArray, slot: Float, lane: FloatArray): Float {
            val b = (slot + 0.5f).toInt() * 4
            return state[b] * lane[0] + state[b + 1] * lane[1] + state[b + 2] * lane[2] + state[b + 3] * lane[3]
        }

        /** The one-hot lane and slot a vertex of per-key part [partIndex] carries (MeshBuilder convention). */
        fun slotOf(partIndex: Int): Int = partIndex / 4
        fun laneOf(partIndex: Int): Int = partIndex % 4

        /** ACTION_SET, the shader's arithmetic: a vertex of (slot s, part p) addresses vec4 13 + (6s + p) / 4, lane (6s + p) % 4. */
        fun actionAngle(state: FloatArray, s: Int, p: Int): Float {
            val idx = ACTION_ANGLES_VEC4 * 4 + ACTION_PARTS * s + p
            val lane = FloatArray(4); lane[idx % 4] = 1f
            return readLane(state, (idx / 4).toFloat(), lane)
        }

        /** ACTION_SET header vec4 s = (xM, dim, key, 0), selected in the shader by uv.x = s. */
        fun actionHeader(state: FloatArray, s: Int, out: FloatArray) { for (i in 0 until 4) out[i] = state[s * 4 + i] }
    }
}
