package com.tropicalstream.hammerklavier

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.tropicalstream.hammerklavier.contract.Gesture
import com.tropicalstream.hammerklavier.contract.android.GlHost
import com.tropicalstream.hammerklavier.contract.android.OverlayHost
import com.tropicalstream.hammerklavier.platform.BinocularSbsLayout

/**
 * Attaches the GL view, BinocularSbsLayout and the overlay host to the process singletons, and
 * feeds input to the AppController (PLAN §1.10, §2.2). Layers, bottom to top: the GL view (two eye
 * viewports), then BinocularSbsLayout whose one child (the overlay) is drawn in both eyes.
 * Black window (transparent on the waveguide), screen kept on, immersive, music volume stream.
 * `--ez mono true` (am start -S) draws one flat view for screenshots.
 *
 * contracts-v1: keys only (DPAD_CENTER/ENTER = tap, BACK = system back); the trackpad gesture
 * engine, the §1.10 display-asleep rules and the CONTROL receiver arrive in contracts-v1.1.
 */
class MainActivity : Activity() {
    private lateinit var controller: AppController
    private lateinit var gl: GlHost
    private lateinit var overlay: OverlayHost
    private lateinit var sbs: BinocularSbsLayout

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
        controller.onLeave = { finish() }
        controller.attach(gl, overlay)
        applyImmersive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyMono(intent)
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
        controller.detach()
        controller.onDestroy(finishing = isFinishing)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val g = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Gesture.TAP
            KeyEvent.KEYCODE_BACK -> Gesture.SYSTEM_BACK
            else -> null
        } ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) controller.onGesture(g)
        return true
    }

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
