package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.RoomDesign

/**
 * Literal RoomDesigns computed once from the §3.12 numbers (so WP2/WP3/WP4 can run a room before
 * WP3's RoomAcoustics exists). PLAYER: the grand in its §5.6 placement, the Player listener at
 * piano (0, 1.20, 0.55) facing the source (0, 0.90, −1.00): r = 1.579 m, Room mode, a dry kit.
 * Taps: the 6 first-order image sources (floor, ceiling, N, S, E, W) then the 6 second-order
 * (floor–ceiling, ceiling–floor, N–S, S–N, E–W, W–E); delay (d_img − d)·48000/343 frames; gain
 * (d/d_img)·Π√(1 − α500(plane)) with a constant-power pan from the azimuth re the listener's
 * forward (the `erGain` send is separate, 1.0 here); bright = every plane's α4k < 0.15.
 * Sabine T60 per band 1.151, 1.429, 1.692, 1.607, 1.589, 1.434, 0.928 s; r_c = 1.360 m;
 * reverbGain = r / r_c. The generator script is in docs/contracts-changelog.md (2026-09-22).
 */
object FixedRoom {
    val PLAYER: RoomDesign = RoomDesign(
        erDelay = intArrayOf(144, 988, 407, 1445, 1466, 1032, 1237, 1320, 2029, 2029, 2501, 2935),
        erGainL = floatArrayOf(0.4125f, 0.1253f, 0.3364f, 0.0008f, 0.0881f, 0.1186f, 0.1002f, 0.0948f, 0.0003f, 0.0822f, 0.0519f, 0.0448f),
        erGainR = floatArrayOf(0.4125f, 0.1253f, 0.0164f, 0.1160f, 0.0881f, 0.1186f, 0.1002f, 0.0948f, 0.0822f, 0.0003f, 0.0519f, 0.0448f),
        erBright = booleanArrayOf(true, true, true, false, true, true, true, true, false, false, true, true),
        brightLpHz = 12_000f, dullLpHz = 5_000f, preDelayFrames = 144,
        t60Low = 1.151f, t60Mid = 1.649f, t60High = 0.928f,
        reverbGain = 1.161f, erGain = 1.0f, directGain = 1.0f, airLpHz = 18_000f, width = 1.0f,
        worldLocked = false, sourceAzimuthRad = 1.5707964f)
}
