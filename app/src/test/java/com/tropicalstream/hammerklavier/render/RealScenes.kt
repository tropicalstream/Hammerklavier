package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentScene
import com.tropicalstream.hammerklavier.contract.SceneFactory
import com.tropicalstream.hammerklavier.contract.VenueScene
import com.tropicalstream.hammerklavier.contract.stub.StubScenes

/**
 * Test helper: WP7's `instrument.Instruments` and WP8's `venue.VenueSceneImpl` (PLAN §2.3) found by name, so WP6's
 * pairing tests compile before those WPs merge and exercise the real scenes once they do. Each falls back to the stub.
 */
object RealScenes {
    private const val PKG = "com.tropicalstream.hammerklavier"
    private val stub = StubScenes()
    var label = "stub"; private set

    fun factory(): SceneFactory {
        val instObj = runCatching { Class.forName("$PKG.instrument.Instruments") }.getOrNull()
        val venueCls = runCatching { Class.forName("$PKG.venue.VenueSceneImpl") }.getOrNull()
        label = (if (instObj != null) "WP7" else "stubInstrument") + "+" + (if (venueCls != null) "WP8" else "stubVenue")
        return object : SceneFactory {
            override fun instrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int): InstrumentScene {
                if (instObj == null) return stub.instrument(id, look, lastDamper)
                val inst = instObj.getField("INSTANCE").get(null)
                val m = instObj.getMethod("create", InstrumentId::class.java, InstrumentLook::class.java, Int::class.javaPrimitiveType)
                return m.invoke(inst, id, look, lastDamper) as InstrumentScene
            }
            override fun venue(): VenueScene =
                if (venueCls == null) stub.venue() else venueCls.getDeclaredConstructor().newInstance() as VenueScene
        }
    }
}
