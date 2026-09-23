package com.tropicalstream.hammerklavier.contract

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Absorption coefficients at 125, 250, 500, 1k, 2k, 4k, 8k Hz. */
class AcousticMaterial(val name: String, val alpha: FloatArray /*125, 250, 500, 1k, 2k, 4k, 8k Hz*/)

/** One plane per entry. plane: 0 floor, 1 ceiling, 2 N, 3 S, 4 E, 5 W. */
class Surface(val name: String, val plane: Int /*0 floor 1 ceiling 2 N 3 S 4 E 5 W*/, val areaM2: Float, val material: AcousticMaterial)

/** Piano frame → room frame. */
class Placement(val originRoom: FloatArray /*3*/, val yawRad: Float) {
    private val c = cos(yawRad)
    private val s = sin(yawRad)

    /** out = origin + R_y(yaw) · p; R_y(θ): (x,y,z) → (x cosθ + z sinθ, y, −x sinθ + z cosθ). out may alias p. */
    fun toRoom(p: FloatArray, out: FloatArray) {
        val x = p[0]; val y = p[1]; val z = p[2]
        out[0] = originRoom[0] + x * c + z * s
        out[1] = originRoom[1] + y
        out[2] = originRoom[2] - x * s + z * c
    }
}

/** The room as both the renderer and the acoustics see it (PLAN §3.12). */
class VenueGeometry(val widthM: Float, val depthM: Float, val corniceM: Float, val ceilingM: Float, val volumeM3: Float,
    val surfaces: List<Surface>, val erPlanes: FloatArray /*6: floor y, ceiling y (effective), N z, S z, E x, W x*/,
    val people: Int, val personSabins: FloatArray /*7*/, val airM: FloatArray /*7, 1/m*/,
    val placements: Map<InstrumentId, Placement>, val fixtureFootprints: FloatArray /*n × 4: xMin zMin xMax zMax*/)

/**
 * The §3.12 plane × material table and the §5.6 placements, as one shared constant: WP8's
 * Konzertzimmer returns it for acoustics, WP3's tests use it. `fixtureFootprints` is empty here:
 * the fixtures are WP8's (its drawn geometry carries them).
 */
object KonzertzimmerAcoustics {
    val OAK_PARQUET = AcousticMaterial("oak parquet on joists", floatArrayOf(.15f, .11f, .07f, .06f, .06f, .07f, .07f))
    val PLASTER_ON_LATH = AcousticMaterial("plaster on lath", floatArrayOf(.14f, .10f, .06f, .05f, .04f, .03f, .03f))
    val WOOD_PANELLING = AcousticMaterial("wood panelling on battens", floatArrayOf(.25f, .15f, .10f, .08f, .07f, .07f, .07f))
    val MIRROR_GLASS = AcousticMaterial("mirror glass on wall", floatArrayOf(.08f, .06f, .04f, .03f, .02f, .02f, .02f))
    val CANVAS = AcousticMaterial("canvas", floatArrayOf(.10f, .10f, .10f, .10f, .10f, .10f, .10f))
    val SILK_DRAPES = AcousticMaterial("silk drapes, drawn", floatArrayOf(.07f, .31f, .49f, .75f, .70f, .60f, .60f))
    val PANELLED_DOOR = AcousticMaterial("panelled wood", floatArrayOf(.14f, .10f, .06f, .08f, .10f, .10f, .10f))

    /** Grand, harpsichord and upright placements of §5.6 (piano frame → room frame). */
    val PLACEMENTS: Map<InstrumentId, Placement> = mapOf(
        InstrumentId.GRAND to Placement(floatArrayOf(-1.00f, 0f, -1.90f), Math.toRadians(-90.0).toFloat()),
        InstrumentId.HARPSICHORD to Placement(floatArrayOf(-1.10f, 0f, -1.90f), Math.toRadians(-90.0).toFloat()),
        InstrumentId.UPRIGHT to Placement(floatArrayOf(-2.20f, 0f, -2.95f), Math.toRadians(25.0).toFloat()))

    val GEOMETRY: VenueGeometry = VenueGeometry(
        widthM = 10.5f, depthM = 8.0f, corniceM = 4.6f, ceilingM = 5.7f, volumeM3 = 469.7f,
        surfaces = listOf(
            Surface("floor", 0, 84.00f, OAK_PARQUET),
            Surface("ceiling and cove", 1, 108.60f, PLASTER_ON_LATH),
            Surface("N boiserie", 2, 29.76f, WOOD_PANELLING),
            Surface("N 3 pier mirrors (1.3 × 3.4)", 2, 13.26f, MIRROR_GLASS),
            Surface("N 2 Pesne panels (1.1 × 2.4)", 2, 5.28f, CANVAS),
            Surface("S boiserie", 3, 25.35f, WOOD_PANELLING),
            Surface("S 3 windows behind drawn drapes (1.5 × 3.9)", 3, 17.55f, SILK_DRAPES),
            Surface("S 2 pier glasses (0.9 × 3.0)", 3, 5.40f, MIRROR_GLASS),
            Surface("E boiserie", 4, 25.22f, WOOD_PANELLING),
            Surface("E double door (1.5 × 3.2)", 4, 4.80f, PANELLED_DOOR),
            Surface("E 2 Pesne panels + supraporte (1.5 × 1.0)", 4, 6.78f, CANVAS),
            Surface("W boiserie", 5, 25.22f, WOOD_PANELLING),
            Surface("W double door (1.5 × 3.2)", 5, 4.80f, PANELLED_DOOR),
            Surface("W 2 Pesne panels + supraporte (1.5 × 1.0)", 5, 6.78f, CANVAS)),
        erPlanes = floatArrayOf(0f, 5.3f, -4.0f, 4.0f, 5.25f, -5.25f),
        people = 18,
        personSabins = floatArrayOf(.30f, .40f, .50f, .55f, .60f, .60f, .60f),
        airM = floatArrayOf(.0001f, .0002f, .0005f, .0010f, .0024f, .0062f, .0215f),
        placements = PLACEMENTS,
        fixtureFootprints = FloatArray(0))
}

/** The only places the yaw convention of §2.3 is computed. Off the audio and GL threads. */
object Conventions {
    /** atan2(dx, −dz): 0 = room −z (north), positive toward +x (east, clockwise seen from above). */
    fun yawOf(dirRoom: FloatArray): Float = atan2(dirRoom[0], -dirRoom[2])

    /** The listener faces the source: yaw of (source − ear), both given in the piano frame. */
    fun forwardYaw(placement: Placement, earPiano: FloatArray, sourcePiano: FloatArray): Float {
        val e = FloatArray(3); val s = FloatArray(3)
        placement.toRoom(earPiano, e); placement.toRoom(sourcePiano, s)
        return yawOf(floatArrayOf(s[0] - e[0], s[1] - e[1], s[2] - e[2]))
    }
}
