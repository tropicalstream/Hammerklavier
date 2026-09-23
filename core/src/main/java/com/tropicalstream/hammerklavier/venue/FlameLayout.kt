package com.tropicalstream.hammerklavier.venue

/**
 * The 50 candle flames of the Konzertzimmer (PLAN §5.5, room frame): chandelier 12 + 6, 2-arm
 * girandoles on each N mirror frame (12), on the S pier glasses (4), beside the doors (4), two
 * floor candelabra of 5 (10), two music-desk candles (2). Also the ≈ 160 chandelier crystals and
 * the five reflecting glasses (3 N pier mirrors, 2 S pier glasses).
 */
object FlameLayout {
    const val G_CHANDELIER = 0
    const val G_SCONCE_N = 1        // + mirror index 0..2 (west to east): groups 1, 2, 3
    const val G_SCONCE_S = 4
    const val G_DOOR = 5
    const val G_CANDELABRA = 6
    const val G_DESK = 7

    /** xyz per flame. */
    val POS: FloatArray
    /** Group per flame. */
    val GROUP: IntArray
    /** true: drawn at full strength at the Stage level (candelabra, desk candles, the mirror bay behind). */
    val STAGE: BooleanArray
    val COUNT: Int

    const val CHANDELIER_Y = 3.3f
    const val CHANDELIER_R = 0.55f
    const val CHANDELIER_UPPER_Y = 3.55f
    const val CHANDELIER_UPPER_R = 0.30f
    const val SCONCE_Y = 1.90f
    const val SCONCE_S_Y = 1.80f

    init {
        val p = ArrayList<Float>(); val g = ArrayList<Int>()
        fun add(x: Float, y: Float, z: Float, grp: Int) { p.add(x); p.add(y); p.add(z); g.add(grp) }
        for (i in 0 until 12) {
            val a = 2.0 * Math.PI * i / 12
            add((CHANDELIER_R * Math.sin(a)).toFloat(), CHANDELIER_Y, (CHANDELIER_R * Math.cos(a)).toFloat(), G_CHANDELIER)
        }
        for (i in 0 until 6) {
            val a = 2.0 * Math.PI * (i + 0.5) / 6
            add((CHANDELIER_UPPER_R * Math.sin(a)).toFloat(), CHANDELIER_UPPER_Y, (CHANDELIER_UPPER_R * Math.cos(a)).toFloat(), G_CHANDELIER)
        }
        val mz = -Konzertzimmer.HALF_D + 0.20f
        for ((m, cx) in floatArrayOf(-3f, 0f, 3f).withIndex()) for (side in intArrayOf(-1, 1)) for (arm in intArrayOf(-1, 1)) {
            add(cx + side * 0.80f + arm * 0.12f, SCONCE_Y, mz, G_SCONCE_N + m)
        }
        val sz = Konzertzimmer.HALF_D - 0.20f
        for (cx in floatArrayOf(-1.5f, 1.5f)) for (arm in intArrayOf(-1, 1)) add(cx + arm * 0.14f, SCONCE_S_Y, sz, G_SCONCE_S)
        for (wx in floatArrayOf(Konzertzimmer.HALF_W - 0.18f, -Konzertzimmer.HALF_W + 0.18f)) for (dz in floatArrayOf(-1.05f, 1.05f)) {
            add(wx, 2.0f, dz, G_DOOR)
        }
        for (c in 0 until 2) {
            val x = Konzertzimmer.CANDELABRA_XZ[2 * c]; val z = Konzertzimmer.CANDELABRA_XZ[2 * c + 1]
            add(x, Konzertzimmer.CANDELABRA_TOP + 0.12f, z, G_CANDELABRA)
            for (k in 0 until 4) {
                val a = 2.0 * Math.PI * (k + 0.5) / 4
                add(x + (0.18 * Math.sin(a)).toFloat(), Konzertzimmer.CANDELABRA_TOP, z + (0.18 * Math.cos(a)).toFloat(), G_CANDELABRA)
            }
        }
        add(-0.75f, 1.15f, -2.20f, G_DESK); add(-0.75f, 1.15f, -1.60f, G_DESK)
        POS = p.toFloatArray(); GROUP = g.toIntArray(); COUNT = GROUP.size
        STAGE = BooleanArray(COUNT) { GROUP[it] == G_CANDELABRA || GROUP[it] == G_DESK || GROUP[it] == G_SCONCE_N + 1 }
    }

    // ── Crystals: 160 drops on three hanging rings below and between the candles ──
    const val CRYSTALS = 160
    val CRYSTAL_POS: FloatArray = FloatArray(CRYSTALS * 3).also { c ->
        val rng = java.util.Random(1746)
        for (i in 0 until CRYSTALS) {
            val ring = i % 3
            val r = floatArrayOf(0.62f, 0.44f, 0.24f)[ring]
            val y = floatArrayOf(3.12f, 3.00f, 2.86f)[ring] - rng.nextFloat() * 0.08f
            val a = 2.0 * Math.PI * (i / 3 + 0.37 * ring) / (CRYSTALS / 3.0)
            c[3 * i] = (r * Math.sin(a)).toFloat(); c[3 * i + 1] = y; c[3 * i + 2] = (r * Math.cos(a)).toFloat()
        }
    }

    // ── The five reflecting glasses: z of the plane, x range, y range, and the side the room is on ──
    const val MIRRORS = 5
    /** Per mirror: planeZ, xMin, xMax, yMin, yMax, roomSide (+1: room at z > planeZ). */
    val MIRROR: FloatArray = FloatArray(MIRRORS * 6).also { m ->
        var o = 0
        for (f in Konzertzimmer.WALL_FEATURES) {
            val n = f.kind == Konzertzimmer.FeatureKind.MIRROR
            val s = f.kind == Konzertzimmer.FeatureKind.PIER_GLASS
            if (!n && !s) continue
            m[o] = if (n) -Konzertzimmer.HALF_D else Konzertzimmer.HALF_D
            m[o + 1] = f.along - f.w / 2; m[o + 2] = f.along + f.w / 2
            m[o + 3] = f.y0; m[o + 4] = f.y0 + f.h
            m[o + 5] = if (n) 1f else -1f
            o += 6
        }
    }
    /** Mirrors 0..2 are the N pier mirrors, 3..4 the S pier glasses. */
    fun isNorth(mirror: Int): Boolean = mirror < 3
}
