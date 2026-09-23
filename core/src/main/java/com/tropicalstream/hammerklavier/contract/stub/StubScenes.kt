package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.CameraPose
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentAnchors
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.InstrumentScene
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SceneFactory
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.SkinParams
import com.tropicalstream.hammerklavier.contract.TextureRecipe
import com.tropicalstream.hammerklavier.contract.VenueScene
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.mesh.MeshBuilder

/**
 * SceneFactory for M-GL before WP7/WP8 (PLAN §2.3): a box keyboard with skinned keys (correct
 * conventions: SKINNED layout, KEY_ROT, partIndex = key − lowKey → slot partIndex / 4, lane
 * partIndex % 4, drawSlot 10) with the grand's §5.6 cameras and listeners, and [StubVenue]
 * (a floor pool and six flames).
 */
class StubScenes : SceneFactory {
    override fun instrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int): InstrumentScene = StubInstrumentScene(id)
    override fun venue(): VenueScene = StubVenue()
}

class StubInstrumentScene(override val id: InstrumentId) : InstrumentScene {
    private val profile = InstrumentProfile.of(id)
    private val pitch = if (id == InstrumentId.HARPSICHORD) 0.01325f else 0.01371f

    override val anchors: InstrumentAnchors = StubAnchors(profile, pitch)
    override val skin: SkinParams = SkinParams(mapOf(SkinKind.KEY_ROT to floatArrayOf(0.70f, -0.45f, profile.keyDipMm / 1000f / 0.45f)))

    override fun meshes(): List<BakedMesh> {
        val white = MeshBuilder(com.tropicalstream.hammerklavier.contract.VertexLayout.SKINNED, 88 * 24)
        val black = MeshBuilder(com.tropicalstream.hammerklavier.contract.VertexLayout.SKINNED, 36 * 24)
        white.color(Pal.IVORY); black.color(Pal.EBONY_RIM)
        for (k in profile.lowKey..profile.highKey) {
            val part = k - profile.lowKey
            val x = anchors.keyX[k]
            if (isBlack(k)) {
                black.part(part / 4, part % 4)
                black.box(x - 0.0045f, 0.72f, -0.150f, x + 0.0045f, 0.735f, -0.050f)
            } else {
                white.part(part / 4, part % 4)
                white.box(x - 0.0060f, 0.70f, -0.150f, x + 0.0060f, 0.72f, 0f)
            }
        }
        val all = 0b1111; val views = 0b111111
        return white.build("stub.keys.white", MaterialId.IVORY, SkinKind.KEY_ROT, all, views, false, ProgramId.SKINNED, 10) +
            black.build("stub.keys.black", MaterialId.EBONY_KEY, SkinKind.KEY_ROT, all, views, false, ProgramId.SKINNED, 10)
    }

    override fun textures(): List<TextureRecipe> = emptyList()

    override fun packActionSet(pose: MechanismPose, xCutKey: Float, out: FloatArray) {
        java.util.Arrays.fill(out, 0, minOf(out.size, 136), 0f)
    }

    companion object {
        fun isBlack(k: Int): Boolean = when (k % 12) { 1, 3, 6, 8, 10 -> true; else -> false }
    }
}

/** The grand's §5.6 camera and listener tables (used for every instrument by the stub). */
class StubAnchors(private val profile: InstrumentProfile, pitch: Float) : InstrumentAnchors {
    private val centre = (profile.lowKey + profile.highKey) / 2f
    override val keyX: FloatArray = FloatArray(HK.KEYS) { k ->
        if (k < profile.lowKey || k > profile.highKey) Float.NaN else (k - centre) * pitch
    }
    override val soundSource: FloatArray = floatArrayOf(0f, 0.90f, -1.00f)
    override val benchEar: FloatArray = floatArrayOf(0f, 1.20f, 0.55f)

    private fun xOf(key: Float): Float = ((key.coerceIn(profile.lowKey.toFloat(), profile.highKey.toFloat())) - centre) *
        (keyX[profile.highKey] - keyX[profile.lowKey]) / (profile.highKey - profile.lowKey)

    override fun camera(view: ViewId, framing: Int, focusKey: Float, centroidKey: Float, out: CameraPose) {
        fun set(px: Float, py: Float, pz: Float, tx: Float, ty: Float, tz: Float, fov: Float, ipd: Float, zp: Float) {
            out.pos[0] = px; out.pos[1] = py; out.pos[2] = pz; out.target[0] = tx; out.target[1] = ty; out.target[2] = tz
            out.vFovDeg = fov; out.ipdScale = ipd; out.zeroParallaxM = zp
        }
        out.roomFrame = false; out.clipX = Float.NaN; out.lidLift = 0f
        when (view) {
            ViewId.PLAYER -> if (framing == 0) set(-0.10f, 1.30f, 1.55f, 0f, 0.50f, -0.12f, 34f, 0.6f, 1.75f)
                else { val xc = xOf(centroidKey); set(xc, 1.15f, 0.62f, xc, 0.70f, -0.08f, 30f, 0.5f, 0.95f) }
            ViewId.ACTION -> if (framing == 0) {
                val xc = xOf(focusKey); set(xc + 0.95f, 0.95f, 0.30f, xc, 0.76f, -0.24f, 22f, 0.35f, 1.1f); out.clipX = xc
            } else { set(0f, 1.95f, 0.55f, 0f, 0.84f, -0.90f, 44f, 0.5f, 1.8f); out.lidLift = 1f }
            ViewId.HALL -> {
                out.roomFrame = true
                if (framing == 0) set(0.4f, 1.20f, 3.0f, 0f, 1.95f, -1.9f, 40f, 1.0f, 4.9f)
                else set(0.4f, 1.20f, 3.0f, 0f, 1.05f, -1.9f, 18.27f, 1.0f, 4.9f)
            }
        }
    }

    override fun listener(view: ViewId, framing: Int, out: FloatArray): Boolean {
        fun set(x: Float, y: Float, z: Float) { out[0] = x; out[1] = y; out[2] = z }
        return when (view) {
            ViewId.PLAYER -> { set(0f, 1.20f, 0.55f); false }
            ViewId.ACTION -> { if (framing == 0) set(0.30f, 1.00f, -0.30f) else set(0f, 1.60f, 0.10f); false }
            ViewId.HALL -> { set(0.4f, 1.20f, 3.0f); true }
        }
    }
}
