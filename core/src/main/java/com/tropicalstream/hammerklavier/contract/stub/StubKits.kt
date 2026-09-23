package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.KitCallback
import com.tropicalstream.hammerklavier.contract.KitService
import com.tropicalstream.hammerklavier.contract.KitState
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.TuningSpec

/**
 * Every instrument is playable at once with a [SineBank] (PLAN §2.3): grand and upright 2 layers,
 * harpsichord 1 layer × 2 stops. [open] reports playable and complete through [post] (main's
 * Handler.post on the device; direct in tests). The KeyMap is the ET fixture whatever the tuning.
 */
class StubKits(private val post: (Runnable) -> Unit = { it.run() }) : KitService {
    private val banks = HashMap<InstrumentId, SineBank>()
    private var generation = 0

    @Synchronized fun bank(id: InstrumentId): SineBank = banks.getOrPut(id) {
        generation++
        when (id) {
            InstrumentId.HARPSICHORD -> SineBank(layers = 1, stops = 2, instrument = id, generation = generation)
            else -> SineBank(layers = 2, stops = 1, instrument = id, generation = generation)
        }
    }

    override fun state(id: InstrumentId): KitState = KitState.Complete
    override fun info(id: InstrumentId): BankInfo = bank(id).info

    override fun open(id: InstrumentId, cb: KitCallback) {
        val b = bank(id)
        post(Runnable { cb.onProgress(id, 1f); cb.onPlayable(b); cb.onComplete(b) })
    }

    override fun keyMap(bank: LoadedBank, tuning: TuningSpec): KeyMap {
        val sb = bank as? SineBank
        return KeyMapFixtures.forSineBank(layers = sb?.layers ?: bank.info.layers, mode = KeyMapFixtures.Mode.HARD,
            stops = sb?.stops ?: bank.info.stops, readyMask = bank.readyMask)
    }

    override fun setPlaybackHint(playing: Boolean, activeId: InstrumentId?, q: QualityProfile, batteryTenths: Int) {}
    override fun decodeWhenIdle(ids: List<InstrumentId>) {}
    override fun release(id: InstrumentId) {}
    override fun diagnostics(): Map<String, String> = mapOf("kits" to "StubKits (SineBank)")
}
