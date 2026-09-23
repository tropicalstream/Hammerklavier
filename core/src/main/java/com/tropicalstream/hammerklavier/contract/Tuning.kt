package com.tropicalstream.hammerklavier.contract

import kotlin.math.ln

/** Cents from 12-TET per pitch class, A = 0 (PLAN §3.18). */
enum class Temperament(val label: String, private val cents: FloatArray) {
    EQUAL("Equal", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    WERCKMEISTER_III("Werckmeister III", floatArrayOf(11.7f, 2.0f, 3.9f, 5.9f, 2.0f, 9.8f, 0.0f, 7.8f, 3.9f, 0f, 7.8f, 3.9f)),
    KELLNER("Kellner", floatArrayOf(8.2f, -1.6f, 2.7f, 2.3f, -2.7f, 6.3f, -3.5f, 5.5f, 0.4f, 0f, 4.3f, -0.8f)),
    VALLOTTI("Vallotti", floatArrayOf(5.9f, 0.0f, 2.0f, 3.9f, -2.0f, 7.8f, -2.0f, 3.9f, 2.0f, 0f, 5.9f, -3.9f)),
    YOUNG_II("Young II", floatArrayOf(5.9f, -3.9f, 2.0f, 0.0f, -2.0f, 3.9f, -5.9f, 3.9f, -2.0f, 0f, 2.0f, -3.9f)),
    KIRNBERGER_III("Kirnberger III", floatArrayOf(10.3f, 0.5f, 3.4f, 4.4f, -3.4f, 8.3f, 0.5f, 6.8f, 2.4f, 0f, 6.4f, -1.5f)),
    LEHMAN("Lehman 2005 (one proposal)", floatArrayOf(5.9f, 3.9f, 2.0f, 3.9f, -2.0f, 7.8f, 2.0f, 3.9f, 3.9f, 0f, 3.9f, 0.0f)),
    MEANTONE_QUARTER("¼-comma meantone", floatArrayOf(10.3f, -13.7f, 3.4f, 20.5f, -3.4f, 13.7f, -10.3f, 6.8f, -17.1f, 0f, 17.1f, -6.8f));

    /** 0 = C … 9 = A … 11 = B. */
    fun offsetCents(pitchClass: Int): Float = cents[pitchClass]
}

/** A pitch standard plus a temperament. Immutable; the maths here runs off the audio thread. */
class TuningSpec(val aHz: Float, val temperament: Temperament) {
    /** 1200 · log2(aHz / 440), computed once. */
    private val standardCents: Float = (1200.0 * ln(aHz.toDouble() / 440.0) / ln(2.0)).toFloat()

    /**
     * Target cents of key k relative to A440 equal temperament (pitch standard + temperament; the
     * kit's stretch shape is added by KeyMapBuilder).
     */
    fun keyCents(key: Int): Float = standardCents + temperament.offsetCents(((key % 12) + 12) % 12)

    override fun equals(other: Any?): Boolean =
        other is TuningSpec && other.aHz == aHz && other.temperament == temperament
    override fun hashCode(): Int = aHz.hashCode() * 31 + temperament.hashCode()
    override fun toString(): String = "TuningSpec(${aHz}Hz, ${temperament.name})"

    companion object {
        val A440_EQUAL: TuningSpec = TuningSpec(440f, Temperament.EQUAL)
        val A415_WERCKMEISTER: TuningSpec = TuningSpec(415f, Temperament.WERCKMEISTER_III)
    }
}
