package com.tropicalstream.hammerklavier.ui

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.tropicalstream.hammerklavier.ui.model.UiOverlayState

/**
 * The stage HUD (§1.8), laid out in the 600 × 440 safe area of one eye: title lines top-left,
 * tuning top-right, time over a 200 px progress rule bottom-left, pills bottom-right (top-right
 * under the tuning line in Player follow), credit bottom-centre, toast top-centre, the status line
 * above the progress rule, the first-session hint centre-bottom and the debug line at the top.
 */
class HudView(ctx: Context) : FrameLayout(ctx) {
    private val work = Styles.text(ctx, Styles.BODY_PX)
    private val movement = Styles.text(ctx, Styles.SMALL_PX)
    private val tuning = Styles.text(ctx, Styles.SMALL_PX, gravity = Gravity.END)
    private val time = Styles.text(ctx, Styles.SMALL_PX)
    private val rule = RuleView(ctx)
    private val pills = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val credit = Styles.text(ctx, Styles.SMALL_PX, gravity = Gravity.CENTER)
    private val toast = Styles.text(ctx, Styles.BODY_PX, Styles.ACCENT, Gravity.CENTER)
    private val status = Styles.text(ctx, Styles.SMALL_PX, gravity = Gravity.CENTER)
    private val hint = Styles.text(ctx, Styles.SMALL_PX, gravity = Gravity.CENTER)
    private val debug = Styles.text(ctx, Styles.SMALL_PX, gravity = Gravity.CENTER).apply { setSingleLine(false) }
    private val stageBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val timeBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var pillsTop = false
    private var pillTexts: List<String> = emptyList()

    init {
        val sx = Styles.SAFE_X; val sy = Styles.SAFE_Y
        stageBlock.addView(work); stageBlock.addView(movement)
        addView(stageBlock, lp(Gravity.TOP or Gravity.START, sx, sy))
        addView(tuning, lp(Gravity.TOP or Gravity.END, sx, sy))
        timeBlock.addView(time)
        timeBlock.addView(rule, LinearLayout.LayoutParams(200, 1).apply { topMargin = 4 })
        addView(timeBlock, lp(Gravity.BOTTOM or Gravity.START, sx, sy))
        addView(pills, lp(Gravity.BOTTOM or Gravity.END, sx, sy))
        addView(credit, lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, sy))
        addView(status, lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, sy + 44))
        addView(hint, lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, sy + 22))
        addView(toast, lp(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, sy + 40))
        addView(debug, lp(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, sy + 70))
    }

    private fun lp(g: Int, x: Int, y: Int) = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, g).apply {
        leftMargin = x; rightMargin = x; topMargin = y; bottomMargin = y
    }

    fun update(s: UiOverlayState) {
        val h = s.hud
        Styles.show(stageBlock, h != null); Styles.show(tuning, h != null); Styles.show(timeBlock, h != null)
        Styles.show(pills, h != null && h.pills.isNotEmpty())
        if (h != null) {
            Styles.set(work, h.work); Styles.set(movement, h.movement); Styles.set(tuning, h.tuning); Styles.set(time, h.time)
            rule.fraction = h.progress
            if (h.pillsTopRight != pillsTop) {
                pillsTop = h.pillsTopRight
                val g = if (pillsTop) Gravity.TOP or Gravity.END else Gravity.BOTTOM or Gravity.END
                pills.layoutParams = lp(g, Styles.SAFE_X, if (pillsTop) Styles.SAFE_Y + 22 else Styles.SAFE_Y)
            }
            if (h.pills != pillTexts) {
                pillTexts = h.pills
                pills.removeAllViews()
                for (p in h.pills) pills.addView(Styles.text(context, Styles.SMALL_PX, Styles.ACCENT, Gravity.END))
                for (i in h.pills.indices) Styles.set(pills.getChildAt(i) as android.widget.TextView, h.pills[i])
            }
        }
        Styles.set(credit, s.credit); Styles.set(toast, s.toast); Styles.set(status, s.status)
        Styles.set(hint, s.hint); Styles.set(debug, s.debug)
    }
}
