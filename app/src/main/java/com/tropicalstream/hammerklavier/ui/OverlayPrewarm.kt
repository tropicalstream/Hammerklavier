package com.tropicalstream.hammerklavier.ui

import android.content.Context
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.UiContext
import com.tropicalstream.hammerklavier.ui.model.Hud
import com.tropicalstream.hammerklavier.ui.model.MenuCard
import com.tropicalstream.hammerklavier.ui.model.PanelCard
import com.tropicalstream.hammerklavier.ui.model.TitleCard
import com.tropicalstream.hammerklavier.ui.model.UiOverlayState

/**
 * M8: HWUI compiles its shaders for the overlay (bloom-shadowed serif text, the underlined gilt row, the
 * hardware layer) the first time each is drawn after an install, and that stalled the GL thread ~130 ms on
 * the first menu open. Before the GL view exists, draw a throw-away overlay with every card kind into an
 * offscreen HardwareRenderer (the process's one RenderThread, so the same shader cache) and wait for it.
 * Main thread, onCreate. Costs a few ms once shaders are cached.
 */
object OverlayPrewarm {
    fun run(ctx: Context, w: Int = 640, h: Int = 480) {
        val t0 = SystemClock.uptimeMillis()
        try {
            val ov = OverlayViews(ctx)
            val v = ov.view
            val rows = listOf("Resume", "Instrument", "Library", "View", "Display", "Credits", "About")
            fun state(c: UiContext, menu: MenuCard?, panel: PanelCard?, title: TitleCard?) = UiOverlayState(
                c, title, Hud("Sonata", "I. Allegro", "A=415", "0:00", 0.5f, listOf("Q1"), false),
                menu, null, null, null, panel, "toast", "credit", "status", "hint", null, false, false)
            val states = listOf(
                state(UiContext.TITLE, null, null, TitleCard("Hammerklavier", "Sonata", "line", "phone", "headphones", "pill")),
                state(UiContext.MENU, MenuCard("Menu", rows, 2, 0, 2, "footer"), null, null),
                state(UiContext.PANEL, null, PanelCard("Credits", rows, 0, 3, "footer"), null))
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val node = RenderNode("hkPrewarm").apply { setPosition(0, 0, w, h) }
            val r = HardwareRenderer().apply { setContentRoot(node); setSurface(reader.surface) }
            for (s in states) {
                ov.show(s)
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, h)
                val c = node.beginRecording(w, h)
                try { v.draw(c) } finally { node.endRecording() }
                r.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                reader.acquireLatestImage()?.close()
            }
            r.destroy(); reader.close()
            Log.i(HK.TAG_UI, "overlay prewarm ${SystemClock.uptimeMillis() - t0} ms")
        } catch (t: Throwable) {
            Log.w(HK.TAG_UI, "overlay prewarm skipped: $t")
        }
    }
}
