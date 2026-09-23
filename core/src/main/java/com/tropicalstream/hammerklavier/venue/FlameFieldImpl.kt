package com.tropicalstream.hammerklavier.venue

import com.tropicalstream.hammerklavier.contract.FlameField
import com.tropicalstream.hammerklavier.contract.LightRig
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.RoomLevel

/**
 * The venue's candle sprites (PLAN §5.3 row 6, §5.5, §5.9), evaluated on the GL thread every frame.
 *
 * Per flame: a body sprite (FLAME_BODY, height flicker ±15%) and a halo (FLAME_HALO, alpha 0.18,
 * r 12 cm); at Q0 its images in the five glasses, each emitted only when the segment from the eye
 * to the virtual image crosses that glass's rectangle (CPU culling, no stencil), plus one N–S
 * bounce; its floor image at 25%; and in the Hall at Q0–Q1 up to `q.crystals` crystal sparkles.
 *
 * Flicker: a global ±4% term (6–10 Hz content) times a per-sprite hashed ±8% term; both come from
 * a prepared 256-entry noise table read with linear interpolation, so update() does no
 * transcendental maths and allocates nothing (§2.1 rules 1 and 5; T8.3, T8.7).
 *
 * Sprite record (8 floats): x y z size r g b alpha; size is the sprite's half-height in metres,
 * rgb 0..1 un-lifted sRGB, alpha the additive weight.
 */
class FlameFieldImpl : FlameField {
    private val n = FlameLayout.COUNT
    private val pos = FlameLayout.POS
    private val noise = FloatArray(TABLE)
    private val rate = FloatArray(n)          // per-sprite noise rate (table steps per second)
    private val phase = FloatArray(n)
    private val hRate = FloatArray(n)
    private val hPhase = FloatArray(n)
    private val cRate = FloatArray(FlameLayout.CRYSTALS)
    private val cPhase = FloatArray(FlameLayout.CRYSTALS)
    private val stageFade = FloatArray(n)     // 1 − smoothstep(3.5, 6, d) from the stage centre; 1 for stage flames
    private val img = FloatArray(3)
    private val img2 = FloatArray(3)
    private val hit = FloatArray(3)

    // lights(): chandelier aggregate, the two candelabra, the N sconce group nearest the instrument
    private val lightPos = FloatArray(12)
    private val sconcePos = FloatArray(9)     // the three N sconce group centroids, west to east
    private val lightWeight = floatArrayOf(18f / 5f, 1f, 1f, 4f / 5f)

    override val maxSprites: Int = MAX_SPRITES

    init {
        val rng = java.util.Random(0x1747L)
        // Band-limited noise: a sum of cosines with whole cycle counts per table (so it wraps), whose
        // frequencies at NOISE_RATE steps/s span 6..10 Hz. Built once here; reads are table lookups.
        // Normalised to exactly [−1, 1]; linear interpolation keeps every read inside.
        for (c in BAND_LO..BAND_HI) {
            val amp = 0.5f + rng.nextFloat()
            val ph = rng.nextDouble() * 2.0 * Math.PI
            for (i in 0 until TABLE) noise[i] += amp * Math.cos(2.0 * Math.PI * c * i / TABLE + ph).toFloat()
        }
        var mx = 0f
        for (v in noise) mx = maxOf(mx, kotlin.math.abs(v))
        for (i in 0 until TABLE) noise[i] /= mx
        for (i in 0 until n) {
            rate[i] = NOISE_RATE * (0.95f + 0.1f * rng.nextFloat()); phase[i] = rng.nextFloat() * TABLE
            hRate[i] = NOISE_RATE * (0.95f + 0.1f * rng.nextFloat()); hPhase[i] = rng.nextFloat() * TABLE
            val dx = pos[3 * i] - Konzertzimmer.STAGE_CENTRE[0]; val dy = pos[3 * i + 1] - Konzertzimmer.STAGE_CENTRE[1]
            val dz = pos[3 * i + 2] - Konzertzimmer.STAGE_CENTRE[2]
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            stageFade[i] = if (FlameLayout.STAGE[i]) 1f else 1f - smoothstep(Konzertzimmer.STAGE_FADE_NEAR, Konzertzimmer.STAGE_FADE_FAR, d)
        }
        for (i in 0 until FlameLayout.CRYSTALS) { cRate[i] = NOISE_RATE * (0.3f + 0.5f * rng.nextFloat()); cPhase[i] = rng.nextFloat() * TABLE }
        // aggregate light positions
        val groups = intArrayOf(FlameLayout.G_CHANDELIER, -1, -2, FlameLayout.G_SCONCE_N + 1)
        for (l in 0 until 4) {
            var sx = 0f; var sy = 0f; var sz = 0f; var c = 0
            for (i in 0 until n) {
                val g = FlameLayout.GROUP[i]
                val take = when (groups[l]) {
                    -1 -> g == FlameLayout.G_CANDELABRA && pos[3 * i] < 0f
                    -2 -> g == FlameLayout.G_CANDELABRA && pos[3 * i] >= 0f
                    else -> g == groups[l]
                }
                if (take) { sx += pos[3 * i]; sy += pos[3 * i + 1]; sz += pos[3 * i + 2]; c++ }
            }
            lightPos[3 * l] = sx / c; lightPos[3 * l + 1] = sy / c; lightPos[3 * l + 2] = sz / c
        }
        for (k in 0..2) {
            var sx = 0f; var sy = 0f; var sz = 0f; var c = 0
            for (i in 0 until n) if (FlameLayout.GROUP[i] == FlameLayout.G_SCONCE_N + k) { sx += pos[3 * i]; sy += pos[3 * i + 1]; sz += pos[3 * i + 2]; c++ }
            sconcePos[3 * k] = sx / c; sconcePos[3 * k + 1] = sy / c; sconcePos[3 * k + 2] = sz / c
        }
    }

    /** Index 0..2 (west to east) of the N sconce group currently used as light 3. */
    var sconceGroup: Int = 1
        private set

    /**
     * Picks light 3 as the N sconce group nearest the instrument's Placement origin (room frame,
     * metres). Call when the instrument changes; default is the centre bay (grand / harpsichord).
     */
    fun setInstrumentOrigin(x: Float, z: Float) {
        var best = 1; var bd = Float.MAX_VALUE
        for (k in 0..2) {
            val dx = sconcePos[3 * k] - x; val dz = sconcePos[3 * k + 2] - z
            val d = dx * dx + dz * dz
            if (d < bd) { bd = d; best = k }
        }
        sconceGroup = best
        for (c in 0..2) lightPos[9 + c] = sconcePos[3 * best + c]
    }

    /** Noise in [−1, 1] at table position x ≥ 0 (linear interpolation, wraps every 256 steps). */
    private fun noiseAt(x: Float): Float {
        val xx = if (x < 0f) 0f else x
        val k = xx.toInt()
        val f = xx - k
        val a = noise[k and (TABLE - 1)]; val b = noise[(k + 1) and (TABLE - 1)]
        return a + (b - a) * f
    }

    /** Global flicker factor, 1 ± 4%. */
    fun globalFlicker(tSec: Float): Float = 1f + GLOBAL_DEPTH * noiseAt(tSec * NOISE_RATE)

    /** Per-sprite brightness factor relative to the global flicker, 1 ± 8%. */
    fun spriteFlicker(tSec: Float, flame: Int): Float = 1f + SPRITE_DEPTH * noiseAt(tSec * rate[flame] + phase[flame])

    /** Per-sprite height factor, 1 ± 15%. */
    fun heightFlicker(tSec: Float, flame: Int): Float = 1f + HEIGHT_DEPTH * noiseAt(tSec * hRate[flame] + hPhase[flame])

    /**
     * The image of point [p] (offset [po]) in glass [mirror] as seen from [eye]: writes the virtual
     * image to [out] and returns true only if the point is on the room side of the glass, the eye
     * is too, and the segment from the eye to the image crosses the glass rectangle.
     */
    fun mirrorImage(p: FloatArray, po: Int, eye: FloatArray, mirror: Int, out: FloatArray): Boolean {
        val m = FlameLayout.MIRROR; val o = mirror * 6
        val pz = m[o]; val side = m[o + 5]
        val fz = p[po + 2]
        if ((fz - pz) * side <= 0.01f) return false          // behind (or in) the wall plane
        if ((eye[2] - pz) * side <= 0.01f) return false
        val iz = 2f * pz - fz
        out[0] = p[po]; out[1] = p[po + 1]; out[2] = iz
        val t = (pz - eye[2]) / (iz - eye[2])
        if (t <= 0f || t >= 1f) return false
        val cx = eye[0] + (out[0] - eye[0]) * t
        val cy = eye[1] + (out[1] - eye[1]) * t
        return inGlass(m, o, mirror, cx, cy)
    }

    /**
     * Inside glass [mirror]'s opening: the rectangle, and for the N pier mirrors also under the arched
     * head (springing 0.15 m below the top, parabolic rise of 0.15 m, as RoomShell draws the frame).
     */
    private fun inGlass(m: FloatArray, o: Int, mirror: Int, cx: Float, cy: Float): Boolean {
        if (cx < m[o + 1] || cx > m[o + 2] || cy < m[o + 3] || cy > m[o + 4]) return false
        if (!FlameLayout.isNorth(mirror)) return true
        val spring = m[o + 4] - ARCH_RISE
        if (cy <= spring) return true
        val u = 2f * (cx - m[o + 1]) / (m[o + 2] - m[o + 1]) - 1f
        return cy <= spring + ARCH_RISE * (1f - u * u)
    }

    private fun crossesRect(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, mirror: Int): Boolean {
        val m = FlameLayout.MIRROR; val o = mirror * 6
        val pz = m[o]
        val den = bz - az
        if (den == 0f) return false
        val t = (pz - az) / den
        if (t <= 0f || t >= 1f) return false
        val cx = ax + (bx - ax) * t; val cy = ay + (by - ay) * t
        hit[0] = cx; hit[1] = cy; hit[2] = pz
        return inGlass(m, o, mirror, cx, cy)
    }

    /**
     * One N–S bounce: flame → N mirror [nm] → S glass [sm] → eye. The eye sees the image of the N
     * image in the S plane; the ray must cross the S glass and, from there, the N glass toward the
     * first image.
     */
    fun bounceImage(p: FloatArray, po: Int, eye: FloatArray, nm: Int, sm: Int, out: FloatArray): Boolean {
        val m = FlameLayout.MIRROR
        val nz = m[nm * 6]; val sz = m[sm * 6]
        val fz = p[po + 2]
        if (fz <= nz + 0.01f || fz >= sz - 0.01f) return false
        if (eye[2] <= nz + 0.01f || eye[2] >= sz - 0.01f) return false
        val i1z = 2f * nz - fz                 // image in N
        val i2z = 2f * sz - i1z                // image of that in S
        out[0] = p[po]; out[1] = p[po + 1]; out[2] = i2z
        if (!crossesRect(eye[0], eye[1], eye[2], out[0], out[1], i2z, sm)) return false
        val hx = hit[0]; val hy = hit[1]; val hz = hit[2]
        return crossesRect(hx, hy, hz, p[po], p[po + 1], i1z, nm)
    }

    private fun effective(level: RoomLevel, q: QualityProfile): RoomLevel =
        if (level.ordinal < q.roomCap.ordinal) q.roomCap else level

    private var outN = 0

    private fun emit(out: FloatArray, x: Float, y: Float, z: Float, size: Float, rgb: IntArray, alpha: Float) {
        if (outN >= MAX_SPRITES || alpha <= 0.004f) return
        val o = outN * 8
        out[o] = x; out[o + 1] = y; out[o + 2] = z; out[o + 3] = size
        out[o + 4] = rgb[0] * INV255; out[o + 5] = rgb[1] * INV255; out[o + 6] = rgb[2] * INV255
        out[o + 7] = alpha
        outN++
    }

    override fun update(tSec: Float, eyeRoom: FloatArray, q: QualityProfile, level: RoomLevel, out: FloatArray): Int {
        outN = 0
        val lv = effective(level, q)
        if (lv == RoomLevel.INSTRUMENT || lv == RoomLevel.PASSTHROUGH || q.frameDivider == 0) return 0
        val stage = lv == RoomLevel.STAGE
        val g = globalFlicker(tSec)
        val mirrors = q.mirrorFlames
        for (i in 0 until n) {
            val fade = if (stage) stageFade[i] else 1f
            if (fade <= 0.004f) continue
            val b = g * spriteFlicker(tSec, i) * fade
            val h = FLAME_SIZE * heightFlicker(tSec, i)
            val x = pos[3 * i]; val y = pos[3 * i + 1]; val z = pos[3 * i + 2]
            emit(out, x, y + h * 0.5f, z, h, Pal.FLAME_BODY, b)
            emit(out, x, y + h * 0.5f, z, HALO_SIZE, Pal.FLAME_HALO, HALO_ALPHA * b)
            if (q.level <= 1) emit(out, x, -y - h * 0.5f, z, h, Pal.FLAME_BODY, FLOOR_ALPHA * b)
            if (mirrors) {
                for (mi in 0 until FlameLayout.MIRRORS) {
                    if (mirrorImage(pos, 3 * i, eyeRoom, mi, img)) emit(out, img[0], img[1] + h * 0.5f, img[2], h, Pal.FLAME_BODY, MIRROR_ALPHA * b)
                }
                if (q.level == 0) {
                    for (nm in 0 until 3) for (sm in 3 until 5) {
                        if (bounceImage(pos, 3 * i, eyeRoom, nm, sm, img2)) {
                            emit(out, img2[0], img2[1] + h * 0.5f, img2[2], h, Pal.FLAME_BODY, MIRROR_ALPHA * MIRROR_ALPHA * b)
                        }
                    }
                }
            }
        }
        if (lv == RoomLevel.SALON && q.crystals > 0) {
            val c = minOf(q.crystals, FlameLayout.CRYSTALS)
            val cp = FlameLayout.CRYSTAL_POS
            for (k in 0 until c) {
                val s = noiseAt(tSec * cRate[k] + cPhase[k])
                if (s < SPARKLE_THRESHOLD) continue
                val a = (s - SPARKLE_THRESHOLD) / (1f - SPARKLE_THRESHOLD) * g
                emit(out, cp[3 * k], cp[3 * k + 1], cp[3 * k + 2], CRYSTAL_SIZE, CRYSTAL_RGB, a)
            }
        }
        return outN
    }

    /**
     * Four dynamic point lights: the chandelier aggregate, the two candelabra, the sconce group of
     * the N mirror bay nearest the instrument ([setInstrumentOrigin]). rgb = FLAME_BODY (0..1) × (flames in the group / 5) ×
     * flicker; [LightRig.flicker] is the global factor.
     */
    override fun lights(tSec: Float, out: LightRig) {
        val g = globalFlicker(tSec)
        for (l in 0 until 4) {
            for (c in 0..2) {
                out.pos[3 * l + c] = lightPos[3 * l + c]
                out.rgb[3 * l + c] = Pal.FLAME_BODY[c] * INV255 * lightWeight[l] * g
            }
        }
        out.flicker = g
    }

    companion object {
        const val TABLE = 256
        /** Table steps per second: 256 steps = 8 s, so cycle count c is c/8 Hz. */
        const val NOISE_RATE = 32f
        const val BAND_LO = 48   // 6 Hz
        const val BAND_HI = 80   // 10 Hz
        const val ARCH_RISE = 0.15f
        const val GLOBAL_DEPTH = 0.04f
        const val SPRITE_DEPTH = 0.08f
        const val HEIGHT_DEPTH = 0.15f
        const val FLAME_SIZE = 0.030f
        const val HALO_SIZE = 0.12f
        const val HALO_ALPHA = 0.18f
        const val FLOOR_ALPHA = 0.25f
        const val MIRROR_ALPHA = 0.55f
        const val CRYSTAL_SIZE = 0.012f
        const val SPARKLE_THRESHOLD = 0.72f
        const val INV255 = 1f / 255f
        @JvmField val CRYSTAL_RGB = intArrayOf(255, 248, 230)
        /** 50 × (body + halo + floor + 5 glasses + 6 bounces) + 160 crystals. */
        val MAX_SPRITES: Int = FlameLayout.COUNT * 14 + FlameLayout.CRYSTALS

        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
