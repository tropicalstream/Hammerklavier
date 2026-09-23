package com.tropicalstream.hammerklavier.venue

import com.tropicalstream.hammerklavier.contract.AcousticMaterial
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.VenueGeometry

/**
 * The drawn Konzertzimmer (PLAN §3.12, §5.5, §5.6). Room frame: x east, y up, z south, metres,
 * origin at the floor centre.
 *
 * [ACOUSTICS] is the shared contract constant itself (the plane × material table WP3 uses).
 * [GEOMETRY] is the venue's own [VenueGeometry]: it shares the constant's surface list, ER planes,
 * audience and air data and placements by reference and adds the fixture footprints (candelabra,
 * music stand, chairs, door swings) that T8.9 checks the placements against. The constant's
 * `fixtureFootprints` is empty in contracts-v1 (docs/requests/WP8.md asks WP0 to copy
 * [FOOTPRINTS] into it; until then the renderer and T8.9 use this object).
 *
 * Every wall opening the renderer draws is listed in [WALL_FEATURES]; the boiserie is what is left
 * of each wall up to the cornice. T8.1 recomputes the §3.12 areas from these rectangles.
 */
object Konzertzimmer {
    const val WIDTH = 10.5f          // x
    const val DEPTH = 8.0f           // z
    const val HALF_W = WIDTH / 2f
    const val HALF_D = DEPTH / 2f
    const val CORNICE = 4.6f
    const val CORNICE_DEPTH = 0.30f
    const val COVE_R = 1.1f
    const val CEILING = 5.7f
    const val DADO = 0.90f
    const val PILASTER_W = 0.35f
    const val BAY_PITCH = 1.8f

    /** Wall planes as in [com.tropicalstream.hammerklavier.contract.Surface.plane]. */
    const val PLANE_FLOOR = 0; const val PLANE_CEILING = 1
    const val PLANE_N = 2; const val PLANE_S = 3; const val PLANE_E = 4; const val PLANE_W = 5

    enum class FeatureKind { MIRROR, PESNE, WINDOW, PIER_GLASS, DOOR, SUPRAPORTE }

    /**
     * One rectangular wall opening. [along] is the centre along the wall (x on N/S, z on E/W),
     * [y0] its bottom, [w] × [h] its size.
     */
    class WallFeature(val kind: FeatureKind, val plane: Int, val along: Float, val y0: Float, val w: Float, val h: Float) {
        val area: Float get() = w * h
        val material: AcousticMaterial get() = when (kind) {
            FeatureKind.MIRROR, FeatureKind.PIER_GLASS -> KonzertzimmerAcoustics.MIRROR_GLASS
            FeatureKind.PESNE, FeatureKind.SUPRAPORTE -> KonzertzimmerAcoustics.CANVAS
            FeatureKind.WINDOW -> KonzertzimmerAcoustics.SILK_DRAPES
            FeatureKind.DOOR -> KonzertzimmerAcoustics.PANELLED_DOOR
        }
    }

    val WALL_FEATURES: List<WallFeature> = buildList {
        for (x in floatArrayOf(-3f, 0f, 3f)) add(WallFeature(FeatureKind.MIRROR, PLANE_N, x, 0.9f, 1.3f, 3.4f))
        for (x in floatArrayOf(-1.5f, 1.5f)) add(WallFeature(FeatureKind.PESNE, PLANE_N, x, 1.2f, 1.1f, 2.4f))
        for (x in floatArrayOf(-3f, 0f, 3f)) add(WallFeature(FeatureKind.WINDOW, PLANE_S, x, 0.45f, 1.5f, 3.9f))
        for (x in floatArrayOf(-1.5f, 1.5f)) add(WallFeature(FeatureKind.PIER_GLASS, PLANE_S, x, 0.9f, 0.9f, 3.0f))
        for (p in intArrayOf(PLANE_E, PLANE_W)) {
            add(WallFeature(FeatureKind.DOOR, p, 0f, 0f, 1.5f, 3.2f))
            add(WallFeature(FeatureKind.SUPRAPORTE, p, 0f, 3.3f, 1.5f, 1.0f))
            for (z in floatArrayOf(-2.3f, 2.3f)) add(WallFeature(FeatureKind.PESNE, p, z, 1.2f, 1.1f, 2.4f))
        }
    }

    /** Wall length along its plane. */
    fun wallLength(plane: Int): Float = if (plane == PLANE_N || plane == PLANE_S) WIDTH else DEPTH

    /** The drawn wall area up to the cornice. */
    fun wallArea(plane: Int): Float = wallLength(plane) * CORNICE

    /** Boiserie = wall minus its openings. */
    fun boiserieArea(plane: Int): Float = wallArea(plane) - WALL_FEATURES.filter { it.plane == plane }.sumOf { it.area.toDouble() }.toFloat()

    /**
     * Room volume: box to the cornice + box from cornice to ceiling − what the quarter-round cove
     * cuts off (r²(1 − π/4) per metre along the straight runs, r³/3 in each of the four mitred
     * corners where two cove cylinders meet).
     */
    fun volume(): Float {
        val r = COVE_R.toDouble()
        val runs = 2.0 * ((WIDTH - 2 * COVE_R) + (DEPTH - 2 * COVE_R))
        val removed = runs * r * r * (1.0 - Math.PI / 4.0) + 4.0 * r * r * r / 3.0
        return (WIDTH * DEPTH * CEILING - removed).toFloat()
    }

    /** Drawn ceiling surface: flat field + cove runs (πr/2 per metre) + 4 mitred corners (2r² each). */
    fun ceilingArea(): Float {
        val r = COVE_R.toDouble()
        val flat = (WIDTH - 2 * COVE_R).toDouble() * (DEPTH - 2 * COVE_R)
        val runs = 2.0 * ((WIDTH - 2 * COVE_R) + (DEPTH - 2 * COVE_R))
        return (flat + runs * Math.PI * r / 2.0 + 4.0 * 2.0 * r * r).toFloat()
    }

    // ── Fixtures on the floor (footprints for T8.9 and the renderer) ──

    /** The two floor candelabra (room x, z) flanking the instrument on the audience side. */
    val CANDELABRA_XZ = floatArrayOf(-1.70f, -0.85f, 1.30f, -0.85f)
    const val CANDELABRA_HALF = 0.20f
    const val CANDELABRA_TOP = 1.60f

    /** Frederick's music stand (Kambly), beside the keyboard. */
    val MUSIC_STAND_XZ = floatArrayOf(-2.70f, -1.30f)
    const val MUSIC_STAND_HALF = 0.25f

    /** Audience: 3 rows × 6 chairs, pitch 0.62 m, the Hall listener's chair at x = 0.4 in row 3. */
    val CHAIR_ROWS_Z = floatArrayOf(1.40f, 2.35f, 3.30f)
    const val CHAIR_PITCH = 0.62f
    const val CHAIR_X0 = 0.40f - 2 * CHAIR_PITCH
    const val CHAIR_HALF = 0.24f

    fun chairX(i: Int): Float = CHAIR_X0 + i * CHAIR_PITCH

    /** A door leaf swinging into the room sweeps this depth. */
    const val DOOR_SWING = 0.80f

    val FOOTPRINTS: FloatArray = run {
        val l = ArrayList<Float>()
        fun add(x0: Float, z0: Float, x1: Float, z1: Float) { l.add(x0); l.add(z0); l.add(x1); l.add(z1) }
        for (i in 0 until 2) {
            val x = CANDELABRA_XZ[2 * i]; val z = CANDELABRA_XZ[2 * i + 1]
            add(x - CANDELABRA_HALF, z - CANDELABRA_HALF, x + CANDELABRA_HALF, z + CANDELABRA_HALF)
        }
        add(MUSIC_STAND_XZ[0] - MUSIC_STAND_HALF, MUSIC_STAND_XZ[1] - MUSIC_STAND_HALF,
            MUSIC_STAND_XZ[0] + MUSIC_STAND_HALF, MUSIC_STAND_XZ[1] + MUSIC_STAND_HALF)
        for (z in CHAIR_ROWS_Z) for (i in 0 until 6) {
            val x = chairX(i)
            add(x - CHAIR_HALF, z - CHAIR_HALF, x + CHAIR_HALF, z + CHAIR_HALF + 0.06f)
        }
        add(HALF_W - DOOR_SWING, -0.75f, HALF_W, 0.75f)
        add(-HALF_W, -0.75f, -HALF_W + DOOR_SWING, 0.75f)
        l.toFloatArray()
    }

    /**
     * Case outlines in the piano frame as (xMin, zMin, xMax, zMax), from the §5.4 dimensions (x
     * toward the treble, z toward the player, origin under the key-front centre): the C5 grand's
     * rim 1.49 × 2.00 m, the U3 upright 1.53 × 0.65 m, the Flemish single 0.93 × 2.28 m.
     */
    val CASE_OUTLINES: Map<InstrumentId, FloatArray> = mapOf(
        InstrumentId.GRAND to floatArrayOf(-0.745f, -1.95f, 0.745f, 0.05f),
        InstrumentId.UPRIGHT to floatArrayOf(-0.765f, -0.65f, 0.765f, 0.0f),
        InstrumentId.HARPSICHORD to floatArrayOf(-0.465f, -2.26f, 0.465f, 0.02f))

    val ACOUSTICS: VenueGeometry = KonzertzimmerAcoustics.GEOMETRY

    val GEOMETRY: VenueGeometry = ACOUSTICS.let { a ->
        VenueGeometry(widthM = a.widthM, depthM = a.depthM, corniceM = a.corniceM, ceilingM = a.ceilingM, volumeM3 = a.volumeM3,
            surfaces = a.surfaces, erPlanes = a.erPlanes, people = a.people, personSabins = a.personSabins, airM = a.airM,
            placements = a.placements,
            fixtureFootprints = if (a.fixtureFootprints.isNotEmpty()) a.fixtureFootprints else FOOTPRINTS)
    }

    /** Where the Stage level is centred (the grand's case centre in the room frame) and the pool radius. */
    val STAGE_CENTRE = floatArrayOf(0f, 1.0f, -1.9f)
    const val POOL_R = 2.6f
    const val STAGE_FADE_NEAR = 3.5f
    const val STAGE_FADE_FAR = 6.0f
}
