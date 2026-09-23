package com.tropicalstream.hammerklavier.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.tropicalstream.hammerklavier.ui.model.TitleCard

/** The title card (§1.4): gilt 22 px title, 18 px subtitle and state line, 14 px phone and route lines. */
class TitleCardView(ctx: Context) : LinearLayout(ctx) {
    private val title = Styles.text(ctx, Styles.TITLE_PX, Styles.ACCENT, Gravity.CENTER).apply { letterSpacing = 0.25f }
    private val subtitle = Styles.text(ctx, Styles.BODY_PX, Styles.TEXT, Gravity.CENTER)
    private val line = Styles.text(ctx, Styles.BODY_PX, Styles.TEXT, Gravity.CENTER)
    private val phone = Styles.text(ctx, Styles.SMALL_PX, Styles.TEXT, Gravity.CENTER)
    private val route = Styles.text(ctx, Styles.SMALL_PX, Styles.TEXT, Gravity.CENTER)
    private val pill = Styles.text(ctx, Styles.SMALL_PX, Styles.ACCENT, Gravity.CENTER)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        for ((v, gap) in listOf(title to 0, subtitle to 10, line to 28, phone to 24, route to 6, pill to 12)) {
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        }
    }

    fun update(t: TitleCard?) {
        Styles.show(this, t != null)
        if (t == null) return
        Styles.set(title, t.title); Styles.set(subtitle, t.subtitle); Styles.set(line, t.line)
        Styles.set(phone, t.phone); Styles.set(route, t.headphones); Styles.set(pill, t.pill)
    }
}
