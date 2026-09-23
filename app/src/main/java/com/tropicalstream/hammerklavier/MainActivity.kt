package com.tropicalstream.hammerklavier

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.tropicalstream.hammerklavier.contract.Gesture
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.android.GlHost
import com.tropicalstream.hammerklavier.contract.android.OverlayHost
import com.tropicalstream.hammerklavier.platform.BinocularSbsLayout
import com.tropicalstream.hammerklavier.platform.TrackpadGestureEngine

/**
 * Attaches the GL view, BinocularSbsLayout and the overlay host to the process singletons, and
 * feeds input to the AppController (PLAN §1.10, §2.2). Layers, bottom to top: the GL view (two eye
 * viewports), then BinocularSbsLayout whose one child (the overlay) is drawn in both eyes.
 * Black window (transparent on the waveguide), screen kept on, immersive, music volume stream.
 * `--ez mono true` (am start -S) draws one flat view for screenshots.
 *
 * Input: touch, key and generic-motion events go to the [TrackpadGestureEngine] first (the
 * right pad's light taps and swipes, firm clicks as DPAD_CENTER/BUTTON_A/ENTER with the touch/key
 * dedup, the left arm (cyttsp6) filtered); KEYCODE_BACK is the system back gesture. Extras of the
 * launching intent (`am start -S … --ez selftest true`) are handed to the CONTROL handler.
 */
class MainActivity : Activity() {
    private lateinit var controller: AppController
    private lateinit var gl: GlHost
    private lateinit var overlay: OverlayHost
    private lateinit var sbs: BinocularSbsLayout
    private val gestures = TrackpadGestureEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HammerklavierApp
        controller = app.controller
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        gl = app.wiring.glHost(this, msaa = app.wiring.settings.getBool("msaa", true))
        overlay = app.wiring.overlay(this)
        val glView = gl.view
        val overlayView = overlay.view                  // (inside apply {}, `overlay` would be View.getOverlay())
        sbs = BinocularSbsLayout(this)
        sbs.addView(overlayView)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(sbs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        applyMono(intent)
        gestures.onGesture = { g, src -> controller.onGesture(g, src) }
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> gestures.setScreenSize(r - l, b - t) }
        controller.onLeave = { finish() }
        controller.onBrightness = { b -> window.attributes = window.attributes.apply { screenBrightness = b } }
        controller.onDebug = { on -> gestures.debugSink = if (on) { m -> Log.d(HK.TAG_INPUT, "raw $m") } else null }
        controller.attach(gl, overlay)
        forwardExtras(intent)
        applyImmersive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyMono(intent)
        forwardExtras(intent)
    }

    private fun forwardExtras(i: Intent?) {
        val b = i?.extras ?: return
        val control = Bundle(b).apply { remove("mono") }
        if (!control.isEmpty) controller.onControl(control)
    }

    private fun applyMono(i: Intent?) {
        val mono = i?.getBooleanExtra("mono", false) ?: false
        sbs.sbsEnabled = !mono
        gl.setStereo(!mono)
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        controller.onResume()
    }

    override fun onPause() {
        controller.onPause()
        super.onPause()
    }

    override fun onStop() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        controller.onStop(displayInteractive = pm.isInteractive)
        super.onStop()
    }

    override fun onDestroy() {
        gestures.release()
        controller.onLeave = null; controller.onBrightness = null; controller.onDebug = null
        controller.detach()
        controller.onDestroy(finishing = isFinishing)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) controller.onGesture(Gesture.SYSTEM_BACK, "key")
            return true
        }
        if (gestures.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gestures.onTouchEvent(ev) || super.dispatchTouchEvent(ev)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean = gestures.onGenericMotion(ev) || super.dispatchGenericMotionEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
