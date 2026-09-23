package com.tropicalstream.hammerklavier.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.tropicalstream.hammerklavier.contract.OverlayState
import com.tropicalstream.hammerklavier.contract.android.OverlayHost
import com.tropicalstream.hammerklavier.ui.model.UiOverlayState

/**
 * WP10's OverlayHost (PLAN §2.2, §1.8): one transparent FrameLayout (the single child of
 * BinocularSbsLayout, so it is drawn in both eyes) holding the passive views. [show] ignores a
 * state equal to the last one and every view sets only changed text, so the 1 Hz HUD tick
 * invalidates at most once a second. Hardware layer; never Toasts or dialogs. Main thread.
 */
class OverlayViews(ctx: Context) : OverlayHost {
    private val title = TitleCardView(ctx)
    private val hud = HudView(ctx)
    private val menu = MenuCardView(ctx)
    private val floor = FloorCardView(ctx)
    private val sync = SyncCardView(ctx)
    private val panel = PanelView(ctx)
    private var last: UiOverlayState? = null

    /** Invalidations since creation (logged by WP0's 1 Hz tick to check the ≤ 1 Hz rule). */
    var updates: Int = 0
        private set

    override val view: View = FrameLayout(ctx).apply {
        setBackgroundColor(Color.TRANSPARENT)
        val full = FrameLayout.LayoutParams.MATCH_PARENT
        addView(hud, FrameLayout.LayoutParams(full, full))
        addView(title, FrameLayout.LayoutParams(full, full))
        addView(menu, FrameLayout.LayoutParams(Styles.MENU_W, Styles.MENU_H, Gravity.CENTER))
        addView(floor, FrameLayout.LayoutParams(full, full))
        addView(sync, FrameLayout.LayoutParams(full, full))
        addView(panel, FrameLayout.LayoutParams(600, 320, Gravity.CENTER))
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        for (i in 1 until childCount) getChildAt(i).visibility = View.GONE
    }

    override fun show(state: OverlayState) {
        val s = state as? UiOverlayState ?: return
        if (s == last) return
        last = s
        updates++
        title.update(s.title)
        hud.update(s)
        menu.update(s.menu, s.adjust)
        floor.update(s.floor)
        sync.update(s.sync)
        panel.update(s.panel)
    }

    /** Last state shown (WP0 applies `stageHidden` through RenderControl.setStageHidden). */
    val lastState: UiOverlayState? get() = last
}
