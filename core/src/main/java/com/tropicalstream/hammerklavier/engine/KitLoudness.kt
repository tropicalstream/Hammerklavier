package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.BankInfo

/**
 * Per-kit loudness trims (INTEGRATION.md, loudness across instruments and views): the gain the
 * engine applies to a kit's whole output (notes, releases, pedals, combs, soft bus) ahead of the
 * room, so that every instrument plays the same repertoire at the grand's loudness. Measured as
 * BS.1770 integrated loudness of offline renders through the full chain (three pieces × four
 * views, LoudnessProbeTest) and averaged; re-measure when a kit is rebuilt. Keyed by the map's
 * `kit` name; an unknown kit (stub, a synthetic test bank) plays untrimmed.
 */
object KitLoudness {
    val TRIM_DB: Map<String, Float> = mapOf(
        "grand-hd" to 0f,
        "upright" to 9.65f,
        "harpsichord" to 4.10f)

    fun gainDb(info: BankInfo): Float = if (info.isStub) 0f else TRIM_DB[info.kit] ?: 0f
}
