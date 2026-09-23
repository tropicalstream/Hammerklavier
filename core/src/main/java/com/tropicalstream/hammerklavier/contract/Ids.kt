package com.tropicalstream.hammerklavier.contract

/** The three sampled instruments; [key] is the id used in map.json, catalog.json and CONTROL. */
enum class InstrumentId(val key: String) {
    GRAND("grand"), UPRIGHT("upright"), HARPSICHORD("harpsichord");
    companion object { fun of(key: String): InstrumentId? = entries.firstOrNull { it.key == key } }
}

/** The three stops of the swipe ring. Framing 0 = primary, 1 = second (vertical swipe). */
enum class ViewId { PLAYER, ACTION, HALL }

/** How much of the Konzertzimmer is drawn (PLAN §5.9). */
enum class RoomLevel { SALON, STAGE, INSTRUMENT, PASSTHROUGH }

/** Route class of the routed output device. WIRED includes USB. */
enum class OutputRoute { SPEAKER, WIRED, BLUETOOTH }

/**
 * The routed output device.
 * @param key "speaker" | "wired" | "bt:<address>" (the per-route settings key)
 * @param outputFlags from dumpsys-free API: "deep" | "fast" | "normal" | ""
 */
class RouteInfo(val route: OutputRoute, val key: String,
                val deviceType: Int, val name: String, val outputFlags: String)

enum class Gesture { TAP, DOUBLE, TRIPLE, FORWARD, BACK, UP, DOWN, SYSTEM_BACK }
enum class PedalMode { NONE, SWITCH, CONTINUOUS }
enum class ResonanceMode { OFF, NATURAL, RICH }
enum class ReverbMode { DRY, ROOM, RESONANT }
enum class SpeakerBass { AUTO, ON, OFF }
enum class Palette { SANSSOUCI_1747, STADTSCHLOSS_1747 }
enum class UprightFinish { WALNUT, MAHOGANY, EBONY }
enum class SoftKind { NONE, UNA_CORDA, HAMMER_RAIL }
