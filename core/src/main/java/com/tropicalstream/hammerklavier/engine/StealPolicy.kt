package com.tropicalstream.hammerklavier.engine

/**
 * Victim choice (PLAN §3.14), a pure function of the voices' state: the lowest
 * `score = levelDb + classBonus − 2·age_s`, with `classBonus` FADING −40, damping (D > 0, key up)
 * −20, pedal-held (D = 0, key up) −10, key down +20. A voice younger than 50 ms is taken only if
 * nothing else exists. Pending voices (no output yet), kill slots and noise voices are never
 * victims: noises never steal music and music never steals noises.
 */
object StealPolicy {
    const val FADING_BONUS = -40f
    const val DAMPING_BONUS = -20f
    const val PEDAL_HELD_BONUS = -10f
    const val KEY_DOWN_BONUS = 20f
    const val YOUNG_FRAMES = 2400L            // 50 ms at 48 kHz

    const val CLASS_FADING = 0
    const val CLASS_DAMPING = 1
    const val CLASS_PEDAL_HELD = 2
    const val CLASS_KEY_DOWN = 3

    fun bonus(cls: Int): Float = when (cls) {
        CLASS_FADING -> FADING_BONUS
        CLASS_DAMPING -> DAMPING_BONUS
        CLASS_PEDAL_HELD -> PEDAL_HELD_BONUS
        else -> KEY_DOWN_BONUS
    }

    /** The steal class of a sounding main voice. */
    fun classOf(fading: Boolean, keyDown: Boolean, damping: Float): Int = when {
        fading -> CLASS_FADING
        keyDown -> CLASS_KEY_DOWN
        damping > 0f -> CLASS_DAMPING
        else -> CLASS_PEDAL_HELD
    }

    fun score(levelDb: Float, cls: Int, ageFrames: Long, sampleRate: Int): Float =
        levelDb + bonus(cls) - 2f * ageFrames / sampleRate

    /**
     * The slot index of the victim among [voices] (0 until [count]), or −1 when there is none.
     * [down] and [damping] are per key (KeyState's `held` and `D`); [nowOut] is the output frame, [nowPf] the play frame (voices before their onset are skipped).
     */
    internal fun pick(voices: Array<Voice>, count: Int, nowOut: Long, nowPf: Long, down: BooleanArray, damping: FloatArray,
                      sampleRate: Int): Int {
        var best = -1; var bestScore = Float.MAX_VALUE
        var young = -1; var youngScore = Float.MAX_VALUE
        for (i in 0 until count) {
            val v = voices[i]
            if (v.role != Voice.ROLE_MAIN) continue
            val st = v.state
            if (st != Voice.PLAYING && st != Voice.FADING && st != Voice.HANDOFF) continue
            if (v.onsetAt > nowPf) continue                  // before its sampled onset: treated like pending
            val k = v.key
            val cls = classOf(st != Voice.PLAYING, down[k], damping[k])
            val age = nowOut - v.startedOut
            val s = score(v.levelDb, cls, age, sampleRate)
            if (age < YOUNG_FRAMES) { if (s < youngScore) { youngScore = s; young = i } }
            else if (s < bestScore) { bestScore = s; best = i }
        }
        return if (best >= 0) best else young
    }
}
