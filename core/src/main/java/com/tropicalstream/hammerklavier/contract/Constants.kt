package com.tropicalstream.hammerklavier.contract

object HK {
    const val SR = 48_000
    const val BLOCK = 256                        // frames per render block = 5.333 ms
    const val TRACK_FRAMES = 4096                // AudioTrack buffer request = 85.3 ms
    const val PRE_ROLL_US = 400_000L             // every Performance starts with 400 ms of silence (>= 230 ms x 1.05 x 1.5)
    const val LOOK_FRAMES = 1024                 // sequencer lookahead in OUTPUT frames (onset lead + 4' stagger + steal-ahead)
    const val CLOCK_RECORDS = 256                // AudioClock ring: 1.37 s
    const val ENERGY_SLOTS = 256                 // EnergyRing: 1.37 s
    const val KEYS = 128
    const val LANES = 88                         // EnergyRing lane i = key 21 + i
    const val STOP_MAIN = 0; const val STOP_4FT = 1   // harpsichord: 0 = 8', 1 = 4'; pianos use 0
    const val REG_8 = 1; const val REG_4 = 2          // registration bit mask
    const val NOISE_SLOTS = 12                   // release + pedal-noise pool, outside the voice cap
    const val VOICE_CAP_MIN = 64; const val VOICE_CAP_MAX = 128
    const val IDLE_PARK_MS = 10_000              // paused, no voices, room tail below -90 dBFS -> track.pause() and park
    const val HEADROOM_MIN_FRAMES = 1536         // queued frames below this for 10 s -> voice cap -8
    const val USE_FG_SERVICE = false             // PLAN §8.4: flipped only if the display-off tests fail
    const val CONTROL_ACTION = "com.tropicalstream.hammerklavier.CONTROL"
    const val COMPANION_PORT = 19112
    const val TAG_AUDIO = "HKAudio"; const val TAG_CLOCK = "HKClock"; const val TAG_RENDER = "HKRender"
    const val TAG_THERMAL = "HKThermal"; const val TAG_INPUT = "HKInput"; const val TAG_LOADER = "HKLoader"
    const val TAG_KIT = "HKKit"; const val TAG_LIB = "HKLib"; const val TAG_WEB = "HKWeb"
    const val TAG_PERF = "HKPerf"; const val TAG_SELFTEST = "HKSelfTest"; const val TAG_UI = "HKUi"; const val TAG_SOAK = "HKSoak"
}
