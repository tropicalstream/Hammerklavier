package com.tropicalstream.hammerklavier.contract

/**
 * The single source of colour (PLAN §5.9): sRGB lit-peak values 0..255, UN-lifted (shaders apply
 * pow(c, 0.85)). Each token is an IntArray(3); never mutate one. Where §5.9 gives a use multiplier
 * (FLEMISH_PAPER × 0.6, HARPSI_SOUNDBOARD × 0.7, SOUNDBOARD shadowed ≤ 0.25) the token holds the
 * base colour and the multiplier is applied where it is used.
 *
 * M8 waveguide re-grade (user feedback): every brown desaturated ~40 % and shifted to hue 18-22 deg;
 * the ebony floor, key and rim are neutral / slightly cool; gilding stays the only saturated warm accent.
 */
object Pal {
    @JvmField val FLAME_CORE = intArrayOf(255, 244, 214)
    @JvmField val FLAME_BODY = intArrayOf(255, 190, 90)
    @JvmField val FLAME_HALO = intArrayOf(255, 140, 50)          // halo alpha 0.18, r ≈ 12 cm
    @JvmField val GILT_HI = intArrayOf(255, 222, 150)
    @JvmField val GILT_LIT = intArrayOf(226, 168, 78)
    @JvmField val GILT_SHADE = intArrayOf(96, 64, 26)
    @JvmField val GILT_EMISSIVE = intArrayOf(48, 32, 13)
    @JvmField val BOISERIE_NEAR = intArrayOf(63, 54, 49)         // cap: wall glow near flames
    @JvmField val STADTSCHLOSS_GREEN = intArrayOf(46, 70, 48)
    @JvmField val PARQUET_POOL = intArrayOf(123, 91, 76)        // pool centre
    /** M8: black keys / ebony lacquer base, neutral cool charcoal (waveguide read the warm dark as brown). */
    @JvmField val EBONY_KEY = intArrayOf(34, 34, 38)
    @JvmField val EBONY_FLOOR = intArrayOf(22, 22, 25)
    @JvmField val EBONY_RIM = intArrayOf(112, 118, 132)
    @JvmField val EBONY_SPEC = intArrayOf(228, 234, 246)
    @JvmField val IVORY = intArrayOf(228, 220, 200)
    @JvmField val IVORY_SIDE = intArrayOf(160, 152, 138)
    @JvmField val BONE = intArrayOf(222, 214, 194)
    @JvmField val BRASS_HI = intArrayOf(224, 172, 84)
    @JvmField val BRASS_MID = intArrayOf(140, 98, 40)
    @JvmField val PLATE_GOLD = intArrayOf(196, 150, 72)
    @JvmField val SOUNDBOARD = intArrayOf(144, 119, 99)          // shadowed ≤ 0.25
    @JvmField val STEEL_HI = intArrayOf(210, 210, 214)
    @JvmField val STEEL_BASE = intArrayOf(120, 120, 126)
    @JvmField val COPPER = intArrayOf(214, 136, 70)
    @JvmField val FELT = intArrayOf(236, 230, 212)
    @JvmField val LEATHER = intArrayOf(144, 114, 97)
    @JvmField val DAMPER_TOP = intArrayOf(123, 98, 84)
    @JvmField val KEYLEVER = intArrayOf(193, 171, 153)
    @JvmField val ACTION_WOOD = intArrayOf(176, 148, 127)
    @JvmField val CLOTH_RED = intArrayOf(180, 40, 40)
    @JvmField val SECTION_CAP = intArrayOf(180, 157, 137)        // + gilt outline
    @JvmField val FLEMISH_CASE = intArrayOf(128, 93, 77)
    @JvmField val FLEMISH_PAPER = intArrayOf(193, 174, 154)      // used × 0.6
    @JvmField val HARPSI_SOUNDBOARD = intArrayOf(193, 169, 146)  // used × 0.7; flowers 200,70,60 / 90,120,190 / 90,140,70; gilt rose 226,176,86
    @JvmField val OAK = intArrayOf(138, 104, 86)
    @JvmField val WALNUT = intArrayOf(123, 91, 77)
    @JvmField val MAHOGANY = intArrayOf(123, 85, 69)
    @JvmField val HUD_TEXT = intArrayOf(255, 236, 200)
    @JvmField val HUD_ACCENT = intArrayOf(240, 190, 100)
}
