package com.tropicalstream.hammerklavier.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.tropicalstream.hammerklavier.ui.model.MenuTree
import com.tropicalstream.hammerklavier.ui.model.PanelCard

/** Text pager (§1.4): Credits, About, Import; 18 px, 7 lines per page. */
class PanelView(ctx: Context) : LinearLayout(ctx) {
    private val title = Styles.text(ctx, Styles.TITLE_PX, Styles.TEXT, Gravity.CENTER)
    private val rule = RuleView(ctx)
    private val lines = Array(MenuTree.PAGE) { Styles.text(ctx, Styles.BODY_PX) }
    private val page = Styles.text(ctx, Styles.SMALL_PX, Styles.TEXT, Gravity.END)
    private val footer = Styles.text(ctx, Styles.SMALL_PX, Styles.TEXT, Gravity.CENTER)

    init {
        orientation = VERTICAL
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(rule, LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { topMargin = 6; bottomMargin = 8 })
        for (l in lines) addView(l, LayoutParams(LayoutParams.MATCH_PARENT, 26))
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4 })
    }

    fun update(p: PanelCard?) {
        Styles.show(this, p != null)
        if (p == null) return
        Styles.set(title, p.title); Styles.set(footer, p.footer)
        Styles.set(page, if (p.pages > 1) "${p.page + 1} / ${p.pages}" else null)
        for (i in lines.indices) {
            val t = p.lines.getOrNull(i)
            Styles.set(lines[i], t)
            if (t == null || t.isEmpty()) lines[i].visibility = INVISIBLE
        }
    }
}
