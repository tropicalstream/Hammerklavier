package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.DspSet

/** The real DSP set (PLAN §2.3 frozen constructor): combs, room, soft bus and master bus. */
object DspFactory {
    fun create(sampleRate: Int): DspSet = DspSet(
        resonance = ResonanceBank(sampleRate), room = RoomChain(sampleRate),
        soft = SoftBus(sampleRate), master = MasterChain(sampleRate))
}
