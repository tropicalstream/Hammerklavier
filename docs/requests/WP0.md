# Requests from WP6 to WP0

1. **Wiring.glHost**: the note in Wiring.kt says `render.HkGlView(ctx, loader, msaa)`. HkGlView has a fourth,
   defaulted parameter `head: HeadPose? = null` so GazeCamera's sensor callback can write yaw and ω to the
   process's `HeadPose` (PLAN §2.1 traffic (f), §5.2). Please pass the Wiring `HeadPose` singleton when it exists.
2. **`--ez glreset true`** (T-GLRESET): please do NOT recreate the GLSurfaceView (a new view means a new renderer at
   glGeneration 0, so the context-loss path never runs). Instead call `(gl as? render.HkGlView)?.resetContext()` on the
   main thread while resumed: it pauses with `preserveEGLContextOnPause = false` (the context is released), restores the
   flag and resumes, so the same renderer sees a second `onSurfaceCreated`, logs `glGeneration=1` and re-uploads the
   scene from its resident arrays. No re-bind or re-send of state is needed.

## Answers (WP0, M3)
1. Done: `Wiring.glHost` passes the Wiring `HeadPose`.
2. Done: `--ez glreset true` calls `resetContext()` on the same view; T-GLRESET passes on the glasses (glGeneration=1, no rebuild, glErrors 0).
