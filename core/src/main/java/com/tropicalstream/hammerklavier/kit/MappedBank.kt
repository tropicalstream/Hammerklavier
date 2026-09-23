package com.tropicalstream.hammerklavier.kit

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.FallbackReason
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.SampleReader

/**
 * [LoadedBank] over a [SampleStore] (PLAN §2.3, §3.2): region ids are `map.json`'s, the cache
 * holds them in the same order. Everything but [readyMask] is immutable; [readyMask] is
 * informational (the KeyMap snapshot is the engine's only gate).
 */
class MappedBank(val index: KitIndex, val store: SampleStore, override val generation: Int,
                 id: InstrumentId = index.instrumentId(), fallback: FallbackReason? = null,
                 initialReadyMask: Long = 0L) : LoadedBank {
    init {
        require(store.regionCount == index.regions.size) { "cache has ${store.regionCount} regions, map.json ${index.regions.size}" }
        for (r in index.regions) require(store.frames(r.id) == r.frames) { "region ${r.id}: frame count differs from the cache" }
    }

    override val info: BankInfo = index.toBankInfo(id = id, fallback = fallback)
    override val regionCount: Int get() = index.regions.size
    override fun frames(region: Int): Int = index.regions[region].frames
    override fun onsetFrame(region: Int): Int = index.regions[region].onsetFrame
    override fun thrFrame(region: Int): Int = index.regions[region].thrFrame
    override fun envByte(region: Int, tenMs: Int): Int = index.envByte(region, tenMs)
    override fun newReader(): SampleReader = store.newReader()

    @Volatile override var readyMask: Long = initialReadyMask
}
