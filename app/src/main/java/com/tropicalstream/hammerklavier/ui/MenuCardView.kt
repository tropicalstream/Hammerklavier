package com.tropicalstream.hammerklavier.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.tropicalstream.hammerklavier.ui.model.AdjustCard
import com.tropicalstream.hammerklavier.ui.model.MenuCard
import com.tropicalstream.hammerklavier.ui.model.MenuTree

/**
 * The 400 × 300 menu card (§1.4): 22 px title over one thin gilt rule, ≤ 7 rows at 18 px, the
 * highlighted row gilt and underlined, a 14 px footer. No box. Also shows an Adjust (title, value, hint).
 */
class MenuCardView(ctx: Context) : LinearLayout(ctx) {
    private val title = Styles.text(ctx, Styles.TITLE_PX, Styles.TEXT, Gravity.CENTER)
    private val rule = RuleView(ctx)
    private val rows = Array(MenuTree.PAGE) { Styles.text(ctx, Styles.BODY_PX).apply { ellipsize = android.text.TextUtils.TruncateAt.END } }   // M6: no clipped glyphs
    private val value = Styles.text(ctx, Styles.TITLE_PX, Styles.ACCENT, Gravity.CENTER)
    private val page = Styles.text(ctx, Styles.SMALL_PX, Styles.TEXT, Gravity.END)
    private val footer = Styles.text(ctx, Styles.SMALL_PX, Styles.TEXT, Gravity.CENTER)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(rule, LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { topMargin = 6; bottomMargin = 8 })
        for (r in rows) addView(r, LayoutParams(LayoutParams.MATCH_PARENT, 26))
        addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 30; bottomMargin = 30 })
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4 })
    }

    fun update(m: MenuCard?, a: AdjustCard?) {
        Styles.show(this, m != null || a != null)
        if (a != null) {
            Styles.set(title, a.title); Styles.set(value, a.value); Styles.set(footer, a.hint); Styles.set(page, null)
            for (r in rows) Styles.show(r, false)
            return
        }
        if (m == null) return
        Styles.set(value, null)
        Styles.set(title, m.title); Styles.set(footer, m.footer)
        Styles.set(page, if (m.pages > 1) "${m.page + 1} / ${m.pages}" else null)
        for (i in rows.indices) {
            val r: TextView = rows[i]
            val text = m.rows.getOrNull(i)
            Styles.set(r, text)
            // keep empty rows' height so the card does not jump between pages
            if (text == null) r.visibility = INVISIBLE
            Styles.highlight(r, i == m.highlight)
        }
    }
}
