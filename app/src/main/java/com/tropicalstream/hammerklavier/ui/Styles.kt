package com.tropicalstream.hammerklavier.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.tropicalstream.hammerklavier.contract.Pal

/**
 * Overlay styling (PLAN §1.4, §1.8): warm white text, gilt accent, soft bloom shadow, no boxes
 * (black is transparent on the waveguide). Sizes are pixels of the 640 × 480 eye.
 */
object Styles {
    const val TITLE_PX = 22f
    const val BODY_PX = 18f
    const val SMALL_PX = 14f
    const val SAFE_X = 20
    const val SAFE_Y = 20
    const val MENU_W = 400
    const val MENU_H = 300

    val TEXT: Int = rgb(Pal.HUD_TEXT)
    val ACCENT: Int = rgb(Pal.HUD_ACCENT)
    val FLASH: Int = rgb(Pal.FLAME_CORE)
    private val BLOOM: Int = Color.argb(150, Pal.HUD_ACCENT[0], Pal.HUD_ACCENT[1], Pal.HUD_ACCENT[2])

    fun rgb(c: IntArray): Int = Color.rgb(c[0], c[1], c[2])

    val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

    fun text(ctx: Context, px: Float, color: Int = TEXT, gravity: Int = Gravity.START): TextView = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        setTextColor(color)
        typeface = serif
        this.gravity = gravity
        includeFontPadding = false
        setSingleLine(true)
        setShadowLayer(px * 0.45f, 0f, 0f, BLOOM)
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** Sets text only when it changed, so an unchanged 1 Hz tick invalidates nothing. */
    fun set(tv: TextView, s: CharSequence?) {
        val v = s ?: ""
        if (tv.text.toString() != v.toString()) tv.text = v
        val vis = if (v.isEmpty()) View.GONE else View.VISIBLE
        if (tv.visibility != vis) tv.visibility = vis
    }

    fun highlight(tv: TextView, on: Boolean) {
        val color = if (on) ACCENT else TEXT
        if (tv.currentTextColor != color) tv.setTextColor(color)
        val flags = if (on) tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG else tv.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        if (flags != tv.paintFlags) tv.paintFlags = flags
    }

    fun show(v: View, on: Boolean) {
        val vis = if (on) View.VISIBLE else View.GONE
        if (v.visibility != vis) v.visibility = vis
    }
}

/** A thin gilt rule; [fraction] of it drawn bright (the progress rule, or the menu's rule at 1). */
class RuleView(ctx: Context) : View(ctx) {
    private val dim = Paint().apply { color = Color.argb(255, 96, 64, 26) }
    private val lit = Paint().apply { color = Styles.ACCENT }
    var fraction: Float = 1f
        set(v) { if (v != field) { field = v; invalidate() } }

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, dim)
        c.drawRect(0f, 0f, w * fraction.coerceIn(0f, 1f), h, lit)
    }
}
