# Requests from WP6 to WP0

1. **Wiring.glHost**: the note in Wiring.kt says `render.HkGlView(ctx, loader, msaa)`. HkGlView has a fourth,
   defaulted parameter `head: HeadPose? = null` so GazeCamera's sensor callback can write yaw and ω to the
   process's `HeadPose` (PLAN §2.1 traffic (f), §5.2). Please pass the Wiring `HeadPose` singleton when it exists.
2. **`--ez glreset true`** (T-GLRESET) is MainActivity's: recreate the GLSurfaceView (new context) and re-send the
   desired state. WP6 needs nothing else.
