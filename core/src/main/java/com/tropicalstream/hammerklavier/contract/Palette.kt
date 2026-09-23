package com.tropicalstream.hammerklavier.contract

/**
 * The single source of colour (PLAN §5.9): sRGB lit-peak values 0..255, UN-lifted (shaders apply
 * pow(c, 0.85)). Each token is an IntArray(3); never mutate one. Where §5.9 gives a use multiplier
 * (FLEMISH_PAPER × 0.6, HARPSI_SOUNDBOARD × 0.7, SOUNDBOARD shadowed ≤ 0.25) the token holds the
 * base colour and the multiplier is applied where it is used.
 */
object Pal {
    @JvmField val FLAME_CORE = intArrayOf(255, 244, 214)
    @JvmField val FLAME_BODY = intArrayOf(255, 190, 90)
    @JvmField val FLAME_HALO = intArrayOf(255, 140, 50)          // halo alpha 0.18, r ≈ 12 cm
    @JvmField val GILT_HI = intArrayOf(255, 222, 150)
    @JvmField val GILT_LIT = intArrayOf(226, 168, 78)
    @JvmField val GILT_SHADE = intArrayOf(96, 64, 26)
    @JvmField val GILT_EMISSIVE = intArrayOf(48, 32, 13)
    @JvmField val BOISERIE_NEAR = intArrayOf(70, 58, 44)         // cap: wall glow near flames
    @JvmField val STADTSCHLOSS_GREEN = intArrayOf(46, 70, 48)
    @JvmField val PARQUET_POOL = intArrayOf(150, 100, 55)        // pool centre
    @JvmField val EBONY_FLOOR = intArrayOf(22, 18, 15)
    @JvmField val EBONY_RIM = intArrayOf(120, 78, 40)
    @JvmField val EBONY_SPEC = intArrayOf(255, 214, 160)
    @JvmField val IVORY = intArrayOf(232, 214, 178)
    @JvmField val IVORY_SIDE = intArrayOf(170, 150, 118)
    @JvmField val BONE = intArrayOf(226, 212, 182)
    @JvmField val BRASS_HI = intArrayOf(224, 172, 84)
    @JvmField val BRASS_MID = intArrayOf(140, 98, 40)
    @JvmField val PLATE_GOLD = intArrayOf(196, 150, 72)
    @JvmField val SOUNDBOARD = intArrayOf(176, 138, 84)          // shadowed ≤ 0.25
    @JvmField val STEEL_HI = intArrayOf(210, 210, 214)
    @JvmField val STEEL_BASE = intArrayOf(120, 120, 126)
    @JvmField val COPPER = intArrayOf(214, 136, 70)
    @JvmField val FELT = intArrayOf(236, 230, 212)
    @JvmField val LEATHER = intArrayOf(176, 128, 80)
    @JvmField val DAMPER_TOP = intArrayOf(150, 112, 70)
    @JvmField val KEYLEVER = intArrayOf(214, 186, 140)
    @JvmField val ACTION_WOOD = intArrayOf(196, 158, 104)
    @JvmField val CLOTH_RED = intArrayOf(180, 40, 40)
    @JvmField val SECTION_CAP = intArrayOf(200, 170, 120)        // + gilt outline
    @JvmField val FLEMISH_CASE = intArrayOf(156, 86, 52)
    @JvmField val FLEMISH_PAPER = intArrayOf(214, 190, 142)      // used × 0.6
    @JvmField val HARPSI_SOUNDBOARD = intArrayOf(214, 184, 128)  // used × 0.7; flowers 200,70,60 / 90,120,190 / 90,140,70; gilt rose 226,176,86
    @JvmField val OAK = intArrayOf(168, 116, 62)
    @JvmField val WALNUT = intArrayOf(150, 98, 56)
    @JvmField val MAHOGANY = intArrayOf(150, 72, 40)
    @JvmField val HUD_TEXT = intArrayOf(255, 236, 200)
    @JvmField val HUD_ACCENT = intArrayOf(240, 190, 100)
}
