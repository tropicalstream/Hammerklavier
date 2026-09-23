package com.tropicalstream.hammerklavier.contract

/** Invoked on main via post; SessionController implements it. */
interface CompanionCommands {
    fun play(movementId: String, instrument: InstrumentId?); fun toggle(); fun next(); fun previous()
    /** Song µs; CompanionServer converts the display ms. */
    fun seek(us: Long); fun instrument(id: InstrumentId)
    fun view(v: ViewId, framing: Int); fun importsChanged()
    /** Thread-safe: returns a @Volatile snapshot refreshed at 1 Hz. */
    fun nowPlayingJson(): String
}
