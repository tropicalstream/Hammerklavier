package com.tropicalstream.hammerklavier.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.tropicalstream.hammerklavier.ui.model.FloorCard
import com.tropicalstream.hammerklavier.ui.model.SyncCard

/**
 * Display floor card (§1.4, [R:visual_design §3.6]): 16 warm swatches at levels 8…68 on
 * transparency plus a neutral row; the selected swatch is framed in gilt. The stage is hidden
 * meanwhile (UiOverlayState.stageHidden → RenderControl.setStageHidden, applied by WP0).
 */
class FloorCardView(ctx: Context) : LinearLayout(ctx) {
    private val title = Styles.text(ctx, Styles.TITLE_PX, Styles.TEXT, Gravity.CENTER)
    private val swatches = Swatches(ctx)
    private val value = Styles.text(ctx, Styles.BODY_PX, Styles.ACCENT, Gravity.CENTER)
    private val hint = Styles.text(ctx, Styles.SMALL_PX, Styles.TEXT, Gravity.CENTER)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(title); addView(swatches, LayoutParams(16 * 30, 80).apply { topMargin = 16; bottomMargin = 12 })
        addView(value); addView(hint)
        Styles.set(title, "Display floor")
    }

    fun update(c: FloorCard?) {
        Styles.show(this, c != null)
        if (c == null) return
        swatches.set(c.levels, c.selected)
        Styles.set(value, "level ${c.selected}")
        Styles.set(hint, c.hint)
    }

    private class Swatches(ctx: Context) : View(ctx) {
        private var levels: List<Int> = emptyList()
        private var selected = -1
        private val p = Paint()
        private val frame = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Styles.ACCENT }

        fun set(l: List<Int>, sel: Int) { if (l != levels || sel != selected) { levels = l; selected = sel; invalidate() } }

        override fun onDraw(c: Canvas) {
            if (levels.isEmpty()) return
            val w = width.toFloat() / levels.size
            val rowH = height / 2f - 4f
            for ((i, lv) in levels.withIndex()) {
                val x = i * w
                p.color = Color.rgb(lv, (lv * 0.86f).toInt(), (lv * 0.62f).toInt())      // warm
                c.drawRect(x + 3, 0f, x + w - 3, rowH, p)
                p.color = Color.rgb(lv, lv, lv)                                            // neutral
                c.drawRect(x + 3, rowH + 8, x + w - 3, height.toFloat(), p)
            }
            val i = levels.indexOfFirst { it >= selected }.let { if (it < 0) levels.size - 1 else it }
            val x = i * w
            c.drawRect(x + 1, 1f, x + w - 1, height - 1f, frame)
        }
    }
}

/** A/V sync card (§1.4): the lead in ms for the current route; the flash disc itself is drawn by the renderer. */
class SyncCardView(ctx: Context) : LinearLayout(ctx) {
    private val title = Styles.text(ctx, Styles.TITLE_PX, Styles.TEXT, Gravity.CENTER)
    private val value = Styles.text(ctx, Styles.TITLE_PX, Styles.ACCENT, Gravity.CENTER)
    private val hint = Styles.text(ctx, Styles.SMALL_PX, Styles.TEXT, Gravity.CENTER)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        addView(title); addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8; bottomMargin = 60 })
    }

    fun update(c: SyncCard?) {
        Styles.show(this, c != null)
        if (c == null) return
        Styles.set(title, "A/V sync · " + c.route)
        Styles.set(value, "display lead ${c.leadMs} ms")
        Styles.set(hint, c.hint)
    }
}
