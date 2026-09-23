package com.tropicalstream.hammerklavier.contract.stub.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tropicalstream.hammerklavier.contract.OverlayState
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.android.OverlayHost
import com.tropicalstream.hammerklavier.contract.stub.StubOverlayState

/**
 * The overlay until WP10 merges (PLAN §2.2, M0): the title text and one status line, centred.
 * Its [view] is the one child of BinocularSbsLayout, so it shows in both eyes. Transparent
 * background (black is transparent on the waveguide); static between updates (hardware layer).
 */
class StubOverlay(ctx: Context) : OverlayHost {
    private val title = TextView(ctx).apply {
        text = "Hammerklavier"
        setTextColor(rgb(Pal.HUD_TEXT))
        textSize = 22f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        gravity = Gravity.CENTER
        setShadowLayer(10f, 0f, 0f, rgb(Pal.HUD_ACCENT))
    }
    private val line = TextView(ctx).apply {
        setTextColor(rgb(Pal.HUD_ACCENT))
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(0, 12, 0, 0)
    }

    override val view: View = FrameLayout(ctx).apply {
        setBackgroundColor(Color.TRANSPARENT)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title); addView(line)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    override fun show(state: OverlayState) {
        if (state is StubOverlayState) {
            if (title.text != state.title) title.text = state.title
            if (line.text != state.line) line.text = state.line
        }
    }

    private fun rgb(c: IntArray) = Color.rgb(c[0], c[1], c[2])
}
