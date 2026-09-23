package com.tropicalstream.hammerklavier.contract.android

import com.tropicalstream.hammerklavier.contract.OverlayState
import com.tropicalstream.hammerklavier.contract.RenderControl

/** The GL view (WP6 HkGlView; StubGlHost until then). Main thread. */
interface GlHost : RenderControl { val view: android.view.View }

/** The 2D overlay host (WP10 OverlayViews; StubOverlay until then). Its view goes inside BinocularSbsLayout. Main thread. */
interface OverlayHost { val view: android.view.View; fun show(state: OverlayState) }
