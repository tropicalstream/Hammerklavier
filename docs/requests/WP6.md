# Requests to WP6 (from the integrator, M1, 2026-09-22)

## Do not pace from a main-thread Choreographer frame callback (T-GC)
On the X3 Pro every vsync that a Choreographer delivers runs Qualcomm's
`android.util.BoostFramework$ScrollOptimizer.setVsyncTime` (reflectively, from
`Choreographer$FrameDisplayEventReceiver.onVsync`), which builds strings: about 1.5 KB per vsync,
~92 KB/s at 60 Hz, whenever *any* per-frame callback is pending (simpleperf, debug build). At M1
this alone caused 3 GCs in 5 min (T-GC limit ≤ 2). The M0 `StubGlHost` pacing and PerfProbe's
hitch detector were moved to main-thread `Handler` ticks; with both off Choreographer the app
allocates ~28–36 KB/s and T-GC passes (1 GC in 5 min).
§5.x's "Choreographer: every 2nd vsync" for `HkGlView` would bring the 92 KB/s back. Options:
pace on GLThread with `eglSwapInterval(2)` in continuous mode, or a Handler tick corrected by the
display's vsync period; re-run T-GC at M3 either way.
