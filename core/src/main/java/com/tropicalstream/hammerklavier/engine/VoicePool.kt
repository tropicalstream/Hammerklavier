package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard

/**
 * The preallocated voices (PLAN §3.6, §3.9, §3.14): VOICE_CAP_MAX (128) main voices + 64 kill
 * slots + 12 noise slots = 204 objects whose role moves between MAIN (counted in the cap), KILL
 * (a stolen voice fading over 5 ms inside one block, free again the next) and NOISE (releases and
 * pedal noises, outside the cap). Renders every voice of a block into the dry and soft buses,
 * accumulates the per-key self rows for the combs and the per-key mean-square energy.
 * Allocation-free.
 */
internal class VoicePool(private val sampleRate: Int, private val cursors: VoiceCursorBoard) {
    @JvmField val voices = Array(TOTAL) { Voice(it) }
    private val free = IntArray(TOTAL) { TOTAL - 1 - it }
    private var freeN = TOTAL

    @JvmField var mainActive = 0
    @JvmField var killActive = 0
    @JvmField var noiseActive = 0
    @JvmField var stolen = 0
    @JvmField var peak = 0

    // Scratch for one voice's block.
    private val tL = FloatArray(HK.BLOCK)
    private val tR = FloatArray(HK.BLOCK)

    /** A fresh voice in [role], or null when every object is busy (cannot happen within the role limits). */
    fun alloc(role: Int): Voice? {
        if (freeN == 0) return null
        val v = voices[free[--freeN]]
        v.clear()
        v.role = role
        when (role) { Voice.ROLE_MAIN -> mainActive++; Voice.ROLE_KILL -> killActive++; else -> noiseActive++ }
        return v
    }

    /** Returns [v] to the free list (callers set a freshly allocated voice's state before anything can idle it). */
    fun toIdle(v: Voice) {
        if (v.state == Voice.IDLE) return
        when (v.role) { Voice.ROLE_MAIN -> mainActive--; Voice.ROLE_KILL -> killActive--; else -> noiseActive-- }
        v.state = Voice.IDLE
        v.reader = null; v.bank = null; v.region = -1; v.note = -1; v.evIndex = -1
        cursors.clear(v.slot)
        free[freeN++] = v.slot
    }

    /** Moves a sounding voice (main or noise) into a kill slot: a 5 ms fade in the next rendered block. */
    fun moveToKill(v: Voice) {
        when (v.role) { Voice.ROLE_MAIN -> mainActive--; Voice.ROLE_NOISE -> noiseActive--; else -> return }
        v.role = Voice.ROLE_KILL; killActive++
        v.state = Voice.KILL
        stolen++
    }

    /** Silences every voice at once (reset). */
    fun clearAll() {
        for (v in voices) if (v.state != Voice.IDLE) toIdle(v)
        cursors.clearAll()
    }

    /** Main voices (any state but IDLE) of [key]. */
    fun countKey(key: Int): Int {
        var n = 0
        for (i in 0 until TOTAL) { val v = voices[i]; if (v.state != Voice.IDLE && v.role == Voice.ROLE_MAIN && v.key == key) n++ }
        return n
    }

    /** The oldest main voice of [key] (pending voices count as newest), or null. */
    fun oldestOfKey(key: Int): Voice? {
        var best: Voice? = null
        for (i in 0 until TOTAL) {
            val v = voices[i]
            if (v.state == Voice.IDLE || v.role != Voice.ROLE_MAIN || v.key != key) continue
            if (best == null || older(v, best)) best = v
        }
        return best
    }

    private fun older(a: Voice, b: Voice): Boolean {
        val ap = a.state == Voice.PENDING; val bp = b.state == Voice.PENDING
        if (ap != bp) return bp
        return a.startAt < b.startAt
    }

    /** The oldest noise voice, or null. */
    fun oldestNoise(): Voice? {
        var best: Voice? = null
        for (i in 0 until TOTAL) {
            val v = voices[i]
            if (v.state == Voice.IDLE || v.role != Voice.ROLE_NOISE) continue
            if (best == null || v.startAt < best.startAt) best = v
        }
        return best
    }

    /** Re-strike: the sounding main voices of [key] that belong to other notes start FADING with [mul] per block. */
    fun restrike(key: Int, note: Int, mul: Float) {
        for (i in 0 until TOTAL) {
            val v = voices[i]
            if (v.role != Voice.ROLE_MAIN || v.key != key || v.note == note) continue
            val st = v.state
            if (st != Voice.PLAYING && st != Voice.FADING) continue
            if (v.fadeMode == Voice.FADE_LIN || v.fadeMode == Voice.FADE_HANDOFF) continue
            v.state = Voice.FADING
            v.fadeMode = Voice.FADE_EXP
            if (v.fadeMul == 1f || mul < v.fadeMul) v.fadeMul = mul
        }
    }

    /** Starts a linear fade to silence over [frames] on every sounding voice (seek, new performance, bank). */
    fun fadeAll(frames: Int) {
        val step = 1f / frames
        for (i in 0 until TOTAL) {
            val v = voices[i]
            val st = v.state
            if (st == Voice.IDLE || st == Voice.PENDING || st == Voice.KILL) continue
            if (v.tFade == 0f && v.tTarget == 0f) { toIdle(v); continue }   // frozen by a pause: already silent
            if (v.role == Voice.ROLE_MAIN) v.state = Voice.FADING
            if (v.fadeMode == Voice.FADE_HANDOFF) { v.fade = v.fade0 * DecayTables.cosQ(v.handX) }
            v.fadeMode = Voice.FADE_LIN
            if (v.fadeStep <= 0f || step > v.fadeStep) v.fadeStep = step
        }
    }

    /** Transport fade of every sounding voice toward [target] over [frames] (pause 0, resume 1). */
    fun transportFade(target: Float, frames: Int) {
        val step = 1f / frames
        for (i in 0 until TOTAL) {
            val v = voices[i]
            val st = v.state
            if (st == Voice.IDLE || st == Voice.PENDING) continue
            v.tTarget = target; v.tStep = step
        }
    }

    /**
     * Renders one block. Voices whose start frame falls in [pf, pf + BLOCK) begin at their offset;
     * voices frozen by a pause are skipped (their positions kept). [dm] may be null (no bank).
     */
    fun render(pf: Long, outFrame: Long, playing: Boolean, ks: KeyState, dm: DamperModel?, hermite: Boolean,
               spectralAllowed: Boolean, dryL: FloatArray, dryR: FloatArray, softL: FloatArray, softR: FloatArray,
               self: FloatArray, selfRows: BooleanArray, laneMs: FloatArray) {
        val hasVoice = ks.hasVoice
        java.util.Arrays.fill(hasVoice, false)
        val block = HK.BLOCK
        var active = 0
        for (vi in 0 until TOTAL) {
            val v = voices[vi]
            var st = v.state
            if (st == Voice.IDLE) continue
            if (st == Voice.PENDING) {
                if (!playing || v.startAt >= pf + block) continue
                val d = v.startAt - pf
                v.startDelay = if (d < 0) 0 else d.toInt()
                v.winStart = 0; v.pos = v.skipFixed
                v.refill()
                v.startedOut = outFrame + v.startDelay
                v.state = v.runState
                v.fresh = true
                st = v.state
            }
            if (v.tFade == 0f && v.tTarget == 0f) { active++; continue }     // frozen by a pause

            val key = v.key
            val i0 = if (v.fresh) v.startDelay else 0
            // ── per-block multipliers ──
            var engagedNow = false
            val lpBefore = v.lpIdx
            // Damping applies once the note's key has gone down (its key-down is applied in the block of its event frame).
            if (v.role == Voice.ROLE_MAIN && (st == Voice.PLAYING || st == Voice.FADING) && dm != null && playing && v.eventAt < pf + block) {
                val d = ks.damping[key]
                if (d > 0f) {
                    v.damp *= dm.dampOf(key, d)
                    if (spectralAllowed && d > 0.05f) {
                        if (!v.spectral) { v.spectral = true; v.lpIdx = DecayTables.LP_IDX_18K; engagedNow = true }
                        val target = dm.fcTargetOf(key, d)
                        val next = v.lpIdx + (target - v.lpIdx) * DecayTables.LP_GLIDE
                        if (next < v.lpIdx) v.lpIdx = next                       // never re-opens
                    }
                }
            }
            when (v.fadeMode) {
                Voice.FADE_EXP -> v.fade *= v.fadeMul
                Voice.FADE_LIN -> { v.fade -= v.fadeStep * block; if (v.fade < 0f) v.fade = 0f }
                Voice.FADE_HANDOFF -> {
                    v.handX += v.fadeStep * block; if (v.handX > 1f) v.handX = 1f
                    v.fade = v.fade0 * DecayTables.cosQ(v.handX)
                }
                else -> {}
            }
            var fin = 1f
            val fadingIn = v.fadeIn < 1f
            if (fadingIn) {                                   // in step with the sustain's handoff (same block, same x)
                v.fadeIn += v.fadeInStep * block; if (v.fadeIn > 1f) v.fadeIn = 1f
                fin = DecayTables.sinQ(v.fadeIn)
            }
            if (v.tFade != v.tTarget) {
                val dt = v.tStep * block
                v.tFade = if (v.tTarget > v.tFade) minOf(v.tTarget, v.tFade + dt) else maxOf(v.tTarget, v.tFade - dt)
            }
            val held = v.base * v.damp * v.fade * fin          // the voice's own level; a pause fade only hides it
            val level = held * v.tFade
            v.level = level
            var gEnd = level * Voice.SHORT_SCALE
            var i1 = block
            if (st == Voice.KILL) { gEnd = 0f; i1 = minOf(block, i0 + KILL_FRAMES) }
            if (v.fresh) { v.g = if (st == Voice.KILL) v.g else if (fadingIn) 0f else gEnd }   // a fade-in ramps from silence
            // ── window, synthesis, filter, mix ──
            if (v.needsRefill()) v.refill()
            val lo = if (v.role == Voice.ROLE_MAIN) ks.landOffset[key] else -1
            val rampFrom = if (lo >= 0) lo else i0
            v.synth(i0, rampFrom, i1, gEnd, hermite, tL, tR)
            if (v.spectral) {
                // A low-pass engaged by this block's landing starts at the landing frame, seeded with the sample before it.
                // A cutoff change caused by a landing inside the block starts at the landing frame too.
                if (engagedNow) {
                    if (rampFrom > i0) { v.lpL = tL[rampFrom - 1]; v.lpR = tR[rampFrom - 1] } else { v.lpL = v.lastL; v.lpR = v.lastR }
                } else if (rampFrom > i0) v.lowPass(i0, rampFrom, tL, tR, lpBefore)
                v.lowPass(if (rampFrom > i0) rampFrom else i0, i1, tL, tR)
            }
            val outL: FloatArray; val outR: FloatArray
            if (v.bus == Voice.SOFT) { outL = softL; outR = softR } else { outL = dryL; outR = dryR }
            for (i in i0 until i1) { outL[i] += tL[i]; outR[i] += tR[i] }
            val row = key - 21
            if (row in 0 until HK.LANES && ks.selfWanted[key]) {
                val b = row * block
                for (i in i0 until i1) self[b + i] += 0.5f * (tL[i] + tR[i])
                selfRows[row] = true
            }
            if (i1 > i0) { v.lastL = tL[i1 - 1]; v.lastR = tR[i1 - 1] }
            v.produced = i1 - i0
            v.fresh = false
            // ── level, energy, cursor, cull ──
            val abs = v.absFrame()
            val bank = v.bank
            val env = if (bank != null) bank.envByte(v.region, if (abs < 0) 0 else abs / 480) else 255
            v.levelDb = DecayTables.ENV_DB[env] + DecayTables.lin2db(held)
            val ms = DecayTables.ENV_POW[env] * level * level
            v.envPow = ms
            if (row in 0 until HK.LANES) {
                laneMs[row] += if (st == Voice.KILL) ms * (i1 - i0) / block else ms
                hasVoice[key] = true
            }
            if (!v.attackSet && v.onsetAt < pf + block) { v.attackSet = true; v.attackDb = v.levelDb }
            cursors.set(v.slot, v.region, abs)
            val dead = st == Voice.KILL ||
                (v.fadeMode == Voice.FADE_LIN && v.fade <= 0f) ||
                (v.fadeMode == Voice.FADE_HANDOFF && v.handX >= 1f) ||
                abs >= v.regionFrames ||
                (v.attackSet && v.onsetAt < pf && v.levelDb < CULL_DB)
            if (dead) toIdle(v) else active++
        }
        if (mainActive > peak) peak = mainActive
    }

    /** Voices that are neither idle nor pending. */
    fun sounding(): Int {
        var n = 0
        for (i in 0 until TOTAL) { val s = voices[i].state; if (s != Voice.IDLE && s != Voice.PENDING) n++ }
        return n
    }

    /** True when every non-idle voice is quieter than [db] (pending voices count as loud). */
    fun allBelow(db: Float): Boolean {
        for (i in 0 until TOTAL) {
            val v = voices[i]
            if (v.state == Voice.IDLE) continue
            if (v.state == Voice.PENDING || v.levelDb >= db) return false
        }
        return true
    }

    fun anyActive(): Boolean = mainActive + killActive + noiseActive > 0

    companion object {
        const val TOTAL = HK.VOICE_CAP_MAX + 64 + HK.NOISE_SLOTS      // 204
        const val KILL_FRAMES = 240                                     // 5 ms
        const val CULL_DB = -80f
    }
}
