# Hammerklavier: what to reuse from the proven X3 apps

A codebase recon, done 2026-09-22 and read-only. It maps which code a new app at `/Users/me/Projects/Hammerklavier` (package `com.tropicalstream.hammerklavier`) should take from the user's own RayNeo X3 Pro apps. Every claim below has a `file:line` reference. Paths are shortened as follows:

| Short | Full path |
|---|---|
| `MC/` | `/Users/me/Projects/MathCosmos/app/src/main/java/com/rayneo/mathcosmos/` |
| `WQ/` | `/Users/me/Projects/WanderQuest/app/src/main/java/com/tropicalstream/wanderquest/` |
| `SH/` | `/Users/me/Projects/RayNeoSpyHunt/app/src/main/java/com/rayneo/spyhunt/` |
| `TV/` | `/Users/me/Projects/tapvibe/app/src/main/java/com/tropicalstream/tapvibe/` |
| `TR/` | `/Users/me/Projects/taprhythm/app/src/main/java/com/x3/taprhythm/` |
| `TM/` | `/Users/me/Projects/tapmetronome/app/src/main/java/com/tropicalstream/tapmetronome/` |
| `FL/` | `/Users/me/Projects/FrontLineAssist/app/src/main/java/com/frontlineassistant/` |
| `GUIDE` | `/Users/me/Documents/FABLE_X3_STARTER_GUIDE.md` |

## 0. What I checked on the glasses today (adb, read-only)

- `adb devices -l` lists `A06B4A96A733283 device usb:1-1 product:RayNeoX3Pro model:ARGF20 device:MercuryLiteXR`. The glasses run Android 12, manufacturer `RayNeo`.
- `wm size` reports 1280x480. `wm density` reports **160**, so 1 dp = 1 px with no density override needed. `ro.hardware.egl=adreno`, and `ro.opengles.version=196610` means GLES **3.2** is available.
- Audio from `dumpsys media.audio_flinger`: the primary output runs at **48000 Hz**, HAL frame count **192** (4 ms), normal frame count 960, and a **FastMixer is present** (mixPeriod 4 ms, latency 21 ms). A float, 48 kHz, low-latency track can therefore take the fast path. A 22.05 kHz track gets resampled by the normal mixer.
- `getevent -pl` shows these input devices: `cyttsp5_mt`, `cyttsp5_btn`, `cyttsp6_mt`, `cyttsp6_btn`, `gpio-keys`, `qpnp_pon`, CapSense Ch0-6, and `jbd4020_temp_protection`. The temple click key events come from the `*_btn` devices.
- Thermal status reads 0 and ThermalHAL 2.0 is connected. MathCosmos's own comment says status 0 is reported "even with the SoC near 60 °C" (`MC/MainActivity.kt:339-343`). **The battery temperature is the signal that actually works.**

## 1. Reuse verdict at a glance

| Piece | Source | Verdict |
|---|---|---|
| `BinocularSbsLayout` | `MC/BinocularSbsLayout.kt` (95 lines) | **Copy verbatim** (rename package) |
| GL surface + Choreographer 30 fps pacing + quality levels | `MC/MathCosmosView.kt:33-75` | **Copy the pattern.** Replace the MathCosmos gestures |
| Thermal governor (battery temperature + thermal status, hysteresis) | `MC/MainActivity.kt:332-366, 368-376, 393-394` | **Copy verbatim** |
| Stereo draw loop (two viewports, eye offset) | `MC/StereoMathRenderer.kt:445-464, 1654` | **Adapt.** Decide the FOV and projection first (§2.3) |
| VBO helper, `DynMesh`, shader compile helpers, `FRAG_PRECISION` | `MC/StereoMathRenderer.kt:1803-1810, 1912-1925, 2159-2165, 2431-2454` | **Copy verbatim** |
| `GlyphBoard` (text in 3D) | `MC/GlyphBoard.kt` (340 lines) | **Copy verbatim.** It serves as piece titles, the composer placard and bar numbers |
| `GazeCamera` (IMU look-around) | `MC/GazeCamera.kt` (82 lines) + application at `MC/StereoMathRenderer.kt:733-747` | **Copy verbatim.** Lets the viewer look around the hall |
| `Calibration` (one-number aim bias) | `MC/Calibration.kt` | Optional. **Adapt** it to seat the keyboard in the field of view |
| Gesture input | `WQ/input/TrackpadGestureEngine.kt` (329 lines) | **Copy verbatim.** It handles touch taps **and** KEY clicks, dedup, and the left pad |
| Debug adb CONTROL receiver | `MC/MainActivity.kt:260-298, 376-378, 507` | **Copy the pattern** |
| Title card, menu `TextView` | `MC/SplashScreen.kt`, `MC/TourMenu.kt` | **Adapt** the styling (bright on transparent). Rewire input through the gesture engine |
| Audio output thread | `SH/audio/AudioEngine.kt:705-760, 839-860` (float, 48 kHz, LOW_LATENCY) | **Copy the shape.** Do *not* copy MathCosmos's 22.05 kHz PCM16 loop for the piano |
| Limiter, soft clip, sine table | `SH/audio/Synth.kt:26-60, 374-390` | **Copy** |
| One-shot clips via MediaPlayer | `MC/SfxPlayer.kt` | Fine for UI cues only, never for notes |
| Companion web server | `WQ/companion/CompanionServer.kt` (routes, token, `deviceIp`) + `TV/net/CompanionServer.kt` (**multipart upload**) | **Merge the two** |
| Upload storage | `TV/music/MusicLibrary.kt:23-51` | **Copy** (sanitize, keep bytes verbatim, unique name) |
| MIDI file parsing | none anywhere | **Write new.** No sibling parses SMF (§5.5) |
| Build skeleton | MathCosmos root + `app/` | **Copy verbatim.** Rename; add the nanohttpd dependency; add the AR_APP category |

## 2. Stereo GL

### 2.1 Surface and EGL config

MathCosmos keeps GLSurfaceView's **default** config chooser. It sets only the ES version, and nothing calls `setEGLConfigChooser`, `setZOrder*` or `holder.setFormat`:

```kotlin
// MC/MathCosmosView.kt:48-53
init {
    setEGLContextClientVersion(2)
    setRenderer(renderer)
    renderMode = RENDERMODE_WHEN_DIRTY
    preserveEGLContextOnPause = true
}
```

GL state is set once in `onSurfaceCreated` (`MC/StereoMathRenderer.kt:367-376`):

- The clear colour is near-black, `glClearColor(0.01f, 0f, 0.012f, 1f)`, which is transparent on the waveguide.
- `GL_DEPTH_TEST`, `GL_CULL_FACE` and `GL_BLEND` are enabled, with `SRC_ALPHA, ONE_MINUS_SRC_ALPHA`.
- `glLineWidth` is clamped to `GL_ALIASED_LINE_WIDTH_RANGE` because "Some drivers only draw 1-px lines" (`:373-376`, `:1262`).

The alternative is SpyHunt's setup: `setEGLContextClientVersion(3)`, `setEGLConfigChooser(8, 8, 8, 0, 24, 0)` and `RENDERMODE_CONTINUOUSLY` (`SH/MainActivity.kt:101-105`). It proves ES3, a 24-bit depth buffer and an RGBA16F post chain all work on this Adreno. For Hammerklavier, **ES 2.0 with the default config** is the proven low-heat path. Ask for a 24-bit depth buffer (`setEGLConfigChooser(8,8,8,0,24,0)`) only if z-fighting shows up between hammers and strings, which sit close together in depth.

### 2.2 Frame pacing at 30 fps

The surface renders on demand. A Choreographer callback on the main thread requests a frame every `frameDivider`-th vsync:

```kotlin
// MC/MathCosmosView.kt:33-43, 71-75
@Volatile private var frameDivider = 2
private val frameCallback = object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
        if (!pacing) return
        if (++vsyncCount % frameDivider == 0) requestRender()
        Choreographer.getInstance().postFrameCallback(this)
    }
}
fun setQuality(level: Int) { val q = level.coerceIn(0, 2); frameDivider = 2 + q; renderer.quality = q }
```

Quality 0/1/2 therefore gives 30/20/15 fps. The comment at `:17-20` records why: "Rendering both eyes at full refresh cooked the glasses." Start the callback in `onResume` and remove it in `onPause` (`:55-65`). WanderQuest adds one refinement: call `removeFrameCallback` *before* `postFrameCallback` in `onResume`, so that a fast pause/resume cycle cannot stack two loops (`WQ/MainActivity.kt:330-333`).

In the renderer, `dt` is clamped to `[0, 0.05]` s and the fps counter updates each second (`MC/StereoMathRenderer.kt:413-425`). taprhythm logs `FRAME HITCH` whenever raw dt exceeds 120 ms (`TR/render/StereoRenderer.kt:52-55`). **Add that probe.** It is how the SoundPool stall in §4.3 was found.

### 2.3 Per-eye viewport, projection and eye separation

```kotlin
// MC/StereoMathRenderer.kt:445-455
// Fixed FOV: on a head-worn display the rendered field must stay matched to the optics.
if (stereo) {
    val halfWidth = width / 2
    Matrix.perspectiveM(projection, 0, 58f, halfWidth.toFloat() / height.toFloat(), 0.15f, 220f)
    drawEye(0, halfWidth, -EYE_OFFSET, seconds)
    drawEye(halfWidth, width - halfWidth, EYE_OFFSET, seconds)
} else { /* mono: full-width, 58°, offset 0 */ }

// :458-464
GLES20.glViewport(x, 0, viewportWidth, height)
val ex = camNowX + sideX * eyeOffset; ...                // eye moved along camera right
val lx = lookNowX + sideX * eyeOffset * 0.35f; ...       // look point moved 35% as far: mild toe-in
Matrix.setLookAtM(view, 0, ex, ey, ez, lx, ly, lz, 0f, 1f, 0f)

// :1654
private const val EYE_OFFSET = 0.035f   // ±, in scene units (total separation 0.07)
```

What these numbers are:

- **The vertical FOV is 58°** per eye, the aspect is 640/480, and the clip planes are near 0.15 and far 220.
- The eye offset is **±0.035 scene units**. MathCosmos scene units are arbitrary, not metres.
- The look point is shifted by 35% of the offset, which is a partial toe-in.
- The whole scene is drawn twice per frame. All simulation (`updateFlight`, `updateCamera` and so on) runs **once**, before either eye (`:426-443`).

**There is a conflict to settle before building the piano.** SpyHunt renders at the *measured optical* FOV, with an off-axis (parallel-axis) frustum and a real interpupillary distance:

```kotlin
// SH/render/StereoRig.kt:46-54
var ipd = 0.063f
/* X3 Pro ~50° diagonal → ~40.5° H / ~31.2° V per 640x480 eye */
var fovYDeg = 31.2f
// :215-225: shift = ∓ipd/2; view = T(-shift)·…; proj = M4.perspectiveOffAxis(fovY, aspect, near, far, shift, convergence)
// SH/core/Mathx.kt:78-90
val top = near * tan(fovYDeg * 0.5f * DEG2RAD); val halfW = top * aspect
val shift = eyeShift * near / converge
Matrix.frustumM(out, 0, -halfW - shift, halfW - shift, -top, top, near, far)
```

The SpyHunt README gives the reasons. "Toe-in introduces vertical disparity". Disparity falls off as 1/distance, so SpyHunt scales the scene until the content sits at about 3.4 m, where disparity is about 1° and reads clearly (`/Users/me/Projects/RayNeoSpyHunt/README.md` "The scale trick").

The official spec in `GUIDE:83` is **30° DFOV**. That is roughly 24° H by 18° V at 4:3.

So there are three proven or claimed vertical FOV values: MathCosmos 58°, SpyHunt 31.2°, and the spec at about 18°. MathCosmos's 58° makes everything look smaller and farther away than life. That is comfortable for a tour, but **it will not make a keyboard read as life-size**. Recommendation:

1. Use SpyHunt's off-axis frustum with a real 63 mm IPD in metre-scale world units. A key is 23.5 mm wide, so the keyboard is 1.23 m.
2. Put zero parallax at the key bed.
3. Make `fovYDeg` a CONTROL-tunable value, starting at 31.2°, and settle it on the glasses.

This is a hypothesis to test on the device, not a proven value.

### 2.4 Thermal governor (copy verbatim)

```kotlin
// MC/MainActivity.kt:353-366
val level = when {
    thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE   || batteryTenths >= 420 -> 2
    thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || batteryTenths >= 390 -> 1
    else -> 0
}
// Hysteresis: only relax one step when the battery has cooled 1.5 °C below the threshold.
val relaxed = if (level < qualityLevel && batteryTenths > (if (qualityLevel == 2) 405 else 375)) qualityLevel else level
if (relaxed != qualityLevel) { qualityLevel = relaxed; sceneView.setQuality(relaxed); Log.i("MCThermal", ...) }
```

Wiring:

- The inputs are `PowerManager.OnThermalStatusChangedListener` plus a sticky `ACTION_BATTERY_CHANGED` receiver reading `EXTRA_TEMPERATURE` in tenths of °C (`:333, :346-351`).
- Both are registered in `onResume` inside `runCatching` (`:371-375`) and removed in `onPause` (`:393-394`).
- The renderer then scales detail by `quality`, for example `drawBodies` draws `count`, `count/2` or `count/3` (`MC/StereoMathRenderer.kt:903`).

**Hammerklavier should also step the audio down.** At quality 2, lower the polyphony cap and drop sympathetic resonance or the reverb tail. The audio thread is the other heat source.

### 2.5 Meshes and shaders

- **Static geometry goes in VBOs**, uploaded once: `makeVbo(data)` with `GL_STATIC_DRAW` (`MC/StereoMathRenderer.kt:1803-1810`). This is the "VBO meshes" half of the thermal fix. The piano case, keys, hammers, strings and hall should all be static VBOs, animated per key through the model matrix.
- **Per-frame geometry goes through `DynMesh`**: a preallocated `FloatArray` copied into a direct `FloatBuffer`, stride 7 (position 3 + rgba 4) (`:1912-1925`). Scenes must not allocate per frame. See `SceneKit.kt:1-18`, and the comments at `SceneAlternatingWalk.kt:72` and `SceneTwins.kt:73`: "this device reboots when it gets hot."
- **Precision header** (`:2159-2165`). Use highp where available, because "World positions run to z = -194 and the shaders take sin() of multiples of them, which turns to static in fp16".
- **Waveguide colour lift** for textured surfaces (`:2345-2347`), which suits wood and gilt textures:

  ```glsl
  // The waveguides swallow dark tones … so the plate is lifted and warmed a little rather than shown flat.
  c = pow(c, vec3(0.85)) * (0.75 + 0.45 * uLift);
  ```

- Lit shader: a lamp point light with distance attenuation, an ambient floor of 0.24 and a rim term (`:2168-2227`). A floor of 0.24 keeps shadowed sides of black keys from vanishing. Black keys need a bright rim or specular highlight, or they turn transparent on the waveguide.
- `compileProgram` / `compileShader` use `require(...)` with the info log (`:2431-2454`).

### 2.6 GlyphBoard: text in 3D

`MC/GlyphBoard.kt` draws a label once through Android `Canvas` into an ARGB bitmap, uploads it as a texture, and caches it by `"style:text"`. Drawing it afterwards is one textured quad.

- **Cache:** an LRU `LinkedHashMap(32, 0.75f, true)` with `MAX_LABELS = 96`. The oldest texture is deleted on overflow (`:42, :58-74, :266`).
- **Rasterisation budget:** at most `MAX_NEW_PER_FRAME = 3` new labels per frame, with `beginFrame()` resetting the budget. When the budget is spent, `label()` returns null and the label simply appears a frame later (`:54-77, :268`).
- **Rendering:** white 72 px glyphs (96 px for TITLE, 44 px for SMALL), serif or italic by style, and two passes: a soft bloom via `setShadowLayer(basePx*0.18f)` at alpha 150, then the glyph at 255. "The waveguides lose thin strokes, so the bloom is what actually makes small notation readable" (`:198-241`). NPOT textures work with CLAMP and LINEAR filtering (`:246-251`).
- **Draw calls:** `drawBillboard(text, style, x, y, z, height, right, up, tint, alpha, glow, anchor, rise, view, projection)` faces the camera. `drawOriented(text, style, modelMatrix, …)` lies in a plane (`:84-141`); use it for a placard on the music desk.
- **Blend:** additive (`GL_SRC_ALPHA, GL_ONE`), depth-tested but not depth-written, with cull disabled for the quad. The state is restored afterwards (`:143-163`). The shader outputs `vec4(uTint.rgb*uGlow,1.0)*a*uAlpha` (`:285-296`).
- **Limits:** GL-thread only (`:33`). Call `glyphs.release()` when the content set changes (`MC/StereoMathRenderer.kt:410`).
- **Sizes that read on the hardware:** world glyph heights of about 0.2-0.26 in MathCosmos units, where 0.34 and up fills the screen. The telemetry HUD owns the top quarter of the eye and captions own the bottom fifth, so labels go *beside* the subject (from the MathCosmos project memory).

### 2.7 GazeCamera: IMU look-around (copy verbatim)

```kotlin
// MC/GazeCamera.kt:25-26  TYPE_GAME_ROTATION_VECTOR (gyro+accel, no compass → no magnetic jumps), fallback TYPE_ROTATION_VECTOR
// :43  registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
// :56-58 gaze = device −Z: gx=-rot[2], gy=-rot[5], gz=-rot[8]; heading=atan2(gx,gy); elevation=asin(gz)
// :72-76
yaw   += (dy.coerceIn(-1.75f, 1.75f) - yaw) * 0.25f       // smoothed, clamped ±100°
pitch += (dp.coerceIn(-1.0f, 1.0f) - pitch) * 0.25f       // ±57°
refYaw += dy * 0.0015f; refPitch += dp * 0.0015f          // soft re-centre over ~1 min (kills gyro drift)
```

- `recenter()` sets `refYaw = NaN`, so the next sample becomes straight ahead (`:50`). The app calls it on boarding and after calibration (`MC/MainActivity.kt:172, 183`).
- `enabled=false` keeps the reference glued to the head and eases the offsets back to 0 (`:65-70`).
- The yaw and pitch are `@Volatile` and are read on the GL thread.

The renderer rotates **only the look direction**: yaw about world up, pitch about camera right. The camera position is untouched (`MC/StereoMathRenderer.kt:733-747`). There is a load-bearing sign warning at `:748-750`: "right = forward x worldUp, up = right x forward — in that order. The reverse is the sign error that once mirrored the whole look-around on this hardware; do not swap them."

For a concert hall, the ±1.75 rad yaw clamp and the one-minute soft re-centre suit a seated listener looking around the room.

### 2.8 How overlays are layered

```kotlin
// MC/MainActivity.kt:211-240
val overlay = FrameLayout(this)             // ONE logical 640x480 child
overlay.addView(telemetryView, WRAP, TOP|START)
overlay.addView(bodyMap, 118x178, TOP|END)
overlay.addView(captionView, MATCH x WRAP, BOTTOM, margins 36,0,36,22)
overlay.addView(segmentMenu / tourMenu / calibration / splash, MATCH x MATCH)   // later = on top
val sbs = BinocularSbsLayout(this).apply { addView(overlay) }
val mono = intent?.getBooleanExtra("mono", false) == true || isEmulator()
sbs.sbsEnabled = !mono; sceneView.setStereo(!mono)
root.addView(sceneView, MATCH, MATCH); root.addView(sbs, MATCH, MATCH); setContentView(root)
```

- Z order runs bottom to top: the GLSurfaceView, then the transparent `BinocularSbsLayout` holding one `FrameLayout`, then the overlays in add order.
- Static text views use `setLayerType(LAYER_TYPE_HARDWARE)` so they are cached rather than re-recorded (`:123, :136`). The splash uses a SOFTWARE layer because `BlurMaskFilter` needs one (`MC/SplashScreen.kt:56`), and it redraws itself with `postInvalidateDelayed(33)` (`:89`).
- `BinocularSbsLayout` measures its child at `w/2` and draws it twice, with clip and translate (`MC/BinocularSbsLayout.kt:39-72`). It re-invalidates on any descendant invalidation (`:74-77`). It remaps touches on the right half into the left half, latched at DOWN, and **restores the offset** afterwards so events that fall through reach the GL view with the real x (`:79-94`).
- Styling that survives the waveguide:
  - Warm amber `rgb(255,196,107)` with a rose glow `setShadowLayer(8f,…,rgb(255,61,110))` for the HUD (`:117-118`).
  - Caption backgrounds of `argb(150, 8,3,10)`, dark but not black (`:129`).
  - Menus at `argb(218,8,3,10)` (`MC/TourMenu.kt:26`).
  - The title card has "No dark wash (… dark is transparent, so a wash only greys the picture)" (`MC/SplashScreen.kt:16-19`).

## 3. Input plumbing

### 3.1 MathCosmos: touch-only (proven for the tours, but incomplete)

**MathCosmos has no `dispatchKeyEvent`, `dispatchTouchEvent` or `dispatchGenericMotionEvent` at all.** A grep of `MC/*.kt` finds no `KEYCODE`, `onKey` or `InputDevice`, and it does no device-name filtering. Gestures are recognised in each View's `onTouchEvent`, from the light-tap DOWN/UP MotionEvents:

```kotlin
// MC/MathCosmosView.kt:108-127
if (elapsed < 280 && abs(dx) < 42f && abs(dy) < 42f) {           // tap
    if (event.eventTime - lastTapAt < 320) { lastTapAt = 0L; onDoubleTap?.invoke(); return true }
    lastTapAt = event.eventTime
    queueEvent { renderer.switchView() }; audioEngine.tap(); return true   // single tap fires INSTANTLY
}
if (abs(dx) > abs(dy) && abs(dx) > 80f) { onSwipe?.invoke(dx > 0f); ... }  // horizontal swipe only
```

Other thresholds in MathCosmos:

- Splash tap: under 400 ms and under 60 px (`MC/SplashScreen.kt:105`).
- Menus: tap under 300 ms and under 46 px; a move over 70 px on the dominant axis steps once, with +right and +down meaning "next" (`MC/TourMenu.kt:66-71`, `MC/SegmentMenu.kt:121-127`).
- Calibration: vertical nudge over 60 px (`MC/CalibrationScreen.kt:123-127`).

Two consequences: a double-tap also fires a single tap first, and a firm click (a KEY event) does nothing.

### 3.2 WanderQuest `TrackpadGestureEngine`: use this

It is self-contained (329 lines). It merges both physical paths into one tap stream and handles the left pad:

```kotlin
// WQ/input/TrackpadGestureEngine.kt:18-24
//  RIGHT pad = cyttsp5_mt. A LIGHT tap is a plain touch DOWN/UP MotionEvent. A FIRM physical click
//  arrives as a hardware KEY (KEYCODE_BUTTON_A / KEYCODE_DPAD_CENTER) — one firm click can be observed
//  on BOTH paths, so taps from the two sources are deduped. LEFT pad = cyttsp6_mt = volume pad. Ignored.
// :31-46 constants
SHORT_TAP_MAX_MS = 300; DOUBLE_TAP_WINDOW_MS = 300; LONG_TAP_MIN_MS = 600
KEY_TAP_MAX_MS = 400; ECHO_MIN_GAP_MS = 40; CROSS_SOURCE_DEDUP_MS = 250
GENERIC_SCROLL_SCALE = 22f; TAP_MOVE_TOLERANCE = max(18px, 4% of min(screenW,screenH)); SWIPE_MIN_PX = 26f
LEFT_ARM_DEVICE = "cyttsp6"
```

- **One tap stream** (`:89-111`). Each tap bumps `tapStreak` and re-arms a 300 ms resolver. The resolver fires `onTap` for 1, `onDoubleTap` for 2, and `onTripleTap` immediately for 3 or more. A tap from the other source within 250 ms is dropped as an echo, and a same-source repeat within 40 ms is dropped too. The cost is that **a single tap is delayed by 300 ms** so it can be told apart from a double-tap.
- **Touch path** (`:163-259`):
  - The left pad is matched by name (`InputDevice.getDevice(id)?.name.contains("cyttsp6", true)`) and swallowed. Only a left-pad tap of at most 300 ms and at most 30 px *peak* travel counts, which gives `onLeftTap`.
  - A swipe fires **once per gesture, during MOVE** (the `swipeFiredForGesture` latch, re-armed on DOWN).
  - A vertical swipe needs `|dy| ≥ max(26, 6% of min dim)` and `|dy| > 1.3|dx|`, which gives `onSwipeVertical(+1 down / −1 up)`.
  - A horizontal swipe needs `|dx| ≥ 1.6 × that` and `|dx| > 1.3|dy|`, which gives `onSwipeHorizontal(+1 fwd / −1 back)`.
  - A tap on UP requires no move past tolerance, no swipe, no long-press, and at most 300 ms held.
- **Key path** (`:265-295`). `BUTTON_A`, `DPAD_CENTER` and `ENTER` count. DOWN with `repeatCount==0` starts tracking and a 600 ms long-press timer. UP under 400 ms registers a key tap. **Every tap key is consumed.**
- **Generic motion** (`:298-324`). Only pointer, mouse or touchpad sources, and only `ACTION_SCROLL`. Events are ignored while a touch is tracking, because the pad emits both MOVE and SCROLL. Axis values are multiplied by 22, and the vertical scroll sign is inverted.
- **Activity wiring:** feed it first in all three dispatchers (`WQ/MainActivity.kt:367-382`):

  ```kotlin
  override fun dispatchKeyEvent(e: KeyEvent) = gestures.onKeyEvent(e) || super.dispatchKeyEvent(e)
  override fun dispatchTouchEvent(e: MotionEvent) = gestures.onTouchEvent(e) || super.dispatchTouchEvent(e)
  override fun dispatchGenericMotionEvent(e: MotionEvent) = gestures.onGenericMotion(e) || super.dispatchGenericMotionEvent(e)
  ```

  `onTouchEvent` returns true for everything, so child Views never see touches. Menus become state inside the activity or engine, driven by the callbacks. That is how WanderQuest, TapVibe (`TV/MainActivity.kt:64-68`) and tapmetronome work.
- Also call `gestures.release()` in `onDestroy`. Call `setScreenSize(640, 480)` if the pad coordinates need scaling ("Raw pad coords are not always screen-normalized", `:243-244`).
- **Gap to fix:** the key path does not filter by device. A firm click on the *left* arm arrives from `cyttsp6_btn`, which exists on this device (§0), and would register as a tap. The suggested fix is `event.device?.name?.contains("cyttsp6", true) == true → return false`. It has not been tested on the glasses.

The GUIDE's hardware-tuned constants agree: tap at most 400 ms; double-tap gap 40-320 ms; short at most 300 ms; double window 280 ms; long at least 600 ms; move tolerance max(4%, 18 px); triple-tap within 400 ms between taps and 800 ms total (`GUIDE:93-102`). Long-press is reserved by the X3 OS and never reaches apps (`MC/MathCosmosView.kt:15`). WanderQuest still wires `onLongTap`, so treat it as unreliable.

Two more rules:

- **Menus need a latched stepper**: one step per gesture, re-armed on UP or CANCEL. "Latching without an end-of-gesture reset deadlocked the menu" (`GUIDE:408`). The engine's `swipeFiredForGesture` already works this way.
- **Design for forward/backward, not left/right**: "slide direction semantics flip with the system's 'natural mode' setting" (`GUIDE:355`).

**Suggested Hammerklavier mapping**, following the brief:

| Gesture | Action |
|---|---|
| SWIPE forward/back | Switch views: Player view and Action view (hammers striking strings) |
| SWIPE up/down | Menu highlight, or volume and tempo in play |
| TAP | Play/pause or select |
| DOUBLE-TAP | Back or cancel |
| TRIPLE-TAP | Recenter the gaze |

### 3.3 Debug adb CONTROL receiver (copy the pattern)

```kotlin
// MC/MainActivity.kt:269-298 (excerpt), action constant :507
private val controlReceiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context?, intent: Intent?) {
        intent ?: return
        if (intent.hasExtra("view")) sceneView.setView(intent.getIntExtra("view", 1))
        if (intent.getBooleanExtra("menu", false)) sceneView.post { openMenuFromTour() }
        if (intent.hasExtra("quality")) sceneView.setQuality(intent.getIntExtra("quality", 0))
        if (intent.getBooleanExtra("recenter", false)) gaze.recenter()
        if (intent.hasExtra("gaze")) gaze.enabled = intent.getBooleanExtra("gaze", true)
        ...
    }
}
// onResume :376-378
val filter = IntentFilter(CONTROL_ACTION)
if (Build.VERSION.SDK_INT >= 33) registerReceiver(controlReceiver, filter, RECEIVER_EXPORTED)
else registerReceiver(controlReceiver, filter)
// onPause :395   runCatching { unregisterReceiver(controlReceiver) }
```

The receiver is registered only while the activity is resumed, so it exists only in the foreground. Launch with `--ez mono true` for a flat single view in screenshots (`:233`); `isEmulator()` does the same automatically (`:499-504`).

For Hammerklavier, use the action `com.tropicalstream.hammerklavier.CONTROL` with these extras:

| Extra | Meaning |
|---|---|
| `--ei view N` | 0 = player view, 1 = hammer action |
| `--ei piano N` | 0 = grand, 1 = upright, 2 = early fortepiano or harpsichord |
| `--es play "<piece id or file name>"` | Start a piece |
| `--ez pause true` | Pause |
| `--ei seek ms` | Seek |
| `--ei quality N` | Force the quality level |
| `--ez recenter true` | Recenter the gaze |
| `--ef fov 31.2` | Set the FOV |
| `--ez debug true` | Debug overlay |

A **tap, swipe or double-tap extra** that calls the same callbacks as the gesture engine would let demos script input without touching the pad.

### 3.4 Testing hatches (from the SpyHunt README, 200-230)

- `adb shell settings put global device_wearing 1`. Off the head, the glasses doze and the launcher's `BackgroundAppManager` force-stops the app about 2 s after launch, which looks exactly like a crash. **Set it back to 0 afterwards.**
- `adb shell wm dismiss-keyguard` clears the lockscreen.
- `adb shell input tap/swipe` events come from a virtual device. MathCosmos and WanderQuest accept them because neither filters the right pad by name. SpyHunt rejects them and needs `settings put system spyhunt_any_input 1`. If Hammerklavier stays name-agnostic on the right pad, adb input works as is.

## 4. Audio

### 4.1 MathCosmos `MathAudioEngine` (proven for 30-40 min tours alongside GL)

```kotlin
// MC/MathAudioEngine.kt:69, 275-320
RATE = 22_050
minBuffer = AudioTrack.getMinBufferSize(RATE, CHANNEL_OUT_STEREO, ENCODING_PCM_16BIT).coerceAtLeast(4096)
AudioTrack.Builder()
  .setAudioAttributes(USAGE_GAME, CONTENT_TYPE_SONIFICATION)
  .setAudioFormat(rate 22050, ENCODING_PCM_16BIT, CHANNEL_OUT_STEREO)
  .setBufferSizeInBytes(minBuffer * 2)
  .setTransferMode(MODE_STREAM).build(); track.play()
val buffer = ShortArray(1024)          // 512 stereo frames ≈ 23 ms
while (running && gen == generation) { synth.applyTriggers(pending.getAndSet(0)); synth.render(buffer, …); track.write(buffer, 0, buffer.size) }   // blocking write paces the loop
```

It uses **PCM16 shorts, not float**, sets **no performance mode** and **no thread priority** (a plain `thread(name = "ic-body-audio")`, `:95`). The thread is stopped with `running=false` and `join(350)` (`:98-102`). A generation counter stops a stale loop from clearing a newer loop's flag (`:94, :316`). Triggers go through an `AtomicInteger` bitmask (`:104`).

The lesson in its comment (`:35-37`) is the thermal fix itself: "the whole voice runs on float phase accumulators and a 4096-entry sine table at 22.05 kHz (a few percent of one core), never on per-sample double-precision trig."

Its hot loop does still call `exp()` per sample in two places (`expf`, `:150, :210-213`). Avoid that in the piano voice.

### 4.2 SpyHunt `AudioEngine`: the template for the piano output

This is the one sibling using **float PCM at 48 kHz, low latency and urgent priority**:

```kotlin
// SH/audio/AudioEngine.kt:705-760
val minBytes = AudioTrack.getMinBufferSize(SR, CHANNEL_OUT_STEREO, ENCODING_PCM_FLOAT)
val burst = halBurstFrames()                                   // AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER, sane 16..2048
val frames = (BLOCK * BUFFERED_BLOCKS + burst - 1) / burst * burst   // 256×4 rounded to whole HAL bursts
AudioTrack.Builder()
  .setAudioAttributes(USAGE_GAME, CONTENT_TYPE_SONIFICATION)
  .setAudioFormat(ENCODING_PCM_FLOAT, SR = 48000, CHANNEL_OUT_STEREO)
  .setTransferMode(MODE_STREAM).setBufferSizeInBytes(max(minBytes, frames*2*4))
  .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
th.priority = Thread.MAX_PRIORITY
// :839-860
Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
// play() FIRST, then prime with silence. A blocking write to a stopped track only returns once the data
// fits, and LOW_LATENCY is free to hand back a smaller buffer — priming before play() can block forever,
// stranding this thread at MAX_PRIORITY where stop()'s join() cannot reclaim it.
t.play(); t.write(outBuf, 0, outBuf.size, WRITE_BLOCKING)
while (running) { renderBlock(); if (t.write(outBuf, 0, outBuf.size, WRITE_BLOCKING) < 0) break }
// :1038-1041  BLOCK = 256, BUFFERED_BLOCKS = 4, RING = 128 (SPSC lock-free event ring)
```

House rules from `SH/audio/Synth.kt:16-23`:

1. Nothing allocates after construction.
2. Every DSP object is owned by the mixer thread.
3. The only cross-thread traffic is the lock-free ring of ints and floats, which "drops events rather than stalling the game thread" (`AudioEngine.kt:690-705`).

`onPause` stops the mixer mid-block, so every voice must be killed on `start()`, or resuming replays a stale tail (`:747-751`).

Output chain: `hp.setCutoff(95f)` then `Limiter(0.92f)`, which is a peak envelope with 0.4 ms attack and 150 ms release into a Padé `softClip` (`Synth.kt:374-390, :51-56`). There is also a 2048-point interpolated sine table (`:30-49`).

A speaker lesson matters for a piano: "The X3 Pro drives small open-ear speakers with essentially no output below ~150 Hz … weight in the 150-400 Hz band and a master high-pass" (`AudioEngine.kt:30-34`, README "Music"). The bass octaves of a grand will be inaudible on the built-in speakers. Consider a gentle low-shelf or harmonic enhancement for the speaker route, and leave the output flat when a headset or Bluetooth device is connected.

The measurements in §0 confirm 48 kHz native, a 192-frame burst and a FastMixer. **Recommended Hammerklavier output:**

- Float, 48 kHz, stereo, `PERFORMANCE_MODE_LOW_LATENCY`, `THREAD_PRIORITY_URGENT_AUDIO`.
- 256-frame blocks and about 4 blocks buffered, which is about 21 ms. That is fine for sequenced playback, which needs stable timing more than low latency.
- A sample-clocked MIDI scheduler **inside** `renderBlock` (see §4.6).
- Sample voices streamed from preloaded float or short arrays (or `MappedByteBuffer`s) with linear-interpolated pitch and table-driven envelopes. No per-sample `exp` or `pow`.

This recommendation is not yet proven on the device. Measure core usage with `top -H -p <pid>` against a target of under 50% of one core at a polyphony cap of about 32-48 voices.

### 4.3 taprhythm: never use SoundPool, and keep no looping MediaPlayer open

```
// TR/audio/GameAudio.kt:16-19
DEVICE HISTORY (verified with FRAME HITCH instrumentation): keeping a loaded SoundPool or an always-on
looping MediaPlayer open made the X3 audio stack stall the GL thread 500-630 ms every ~5-6 s.
One-shot playback tested clean.
```

taprhythm's fix is `SfxMixer`, which mixes PCM in software into **one** AudioTrack that is opened only while sound is audible (`TR/audio/SfxMixer.kt:10-27, 95-146`).

The persistent `AudioTrack` streams in MathCosmos and SpyHunt did *not* show the stall, so one app-owned AudioTrack stream is fine. **SoundPool and looping MediaPlayer are banned.** A piano sampler has to be its own mixer anyway.

### 4.4 tapmetronome: a timing anti-pattern for a sequencer

`TM/MainActivity.kt:89-94` drives `metro.update(System.nanoTime())` from a Choreographer frame callback. On each beat crossing, `ClickEngine.play()` builds a **new `MODE_STATIC` AudioTrack per click** on an executor, then sleeps and releases it (`TM/sound/ClickEngine.kt:28-66`, 44.1 kHz PCM16 mono). The onset therefore jitters by up to a frame (16-33 ms), plus the time to create the track.

What is reusable is the **phase-accumulator clock with a clamped `dt`**: `phaseBeats += dt*bpm/60`, where `dt` is clamped to `[0, 0.1]` so a resume cannot fast-forward (`TM/metro/Metronome.kt:51-60`). Use it on the **visual** side to animate key and pedal positions between audio-thread events. **Note-on timing itself must come from the audio thread's sample counter.**

### 4.5 One-shot clips and voices (MathCosmos)

`MC/SfxPlayer.kt:20-51` copies `assets/sfx/<name>.wav` atomically to `cacheDir`, via `.tmp` then `renameTo`. It plays each clip with a fresh `MediaPlayer` and `prepareAsync`, caps overlap at 4, and releases each player on completion. This is fine for UI cues such as a page turn or an applause sting. It is not usable for notes.

`app/build.gradle.kts:60-62` sets `androidResources { noCompress += listOf("wav", "ogg") }`. **Hammerklavier needs this for its sample bank**, for example `noCompress += listOf("wav", "ogg", "flac", "mid", "bin")`. Uncompressed assets can be `openFd`- or mmap-mapped instead of inflated.

Two other audio notes:

- "No TTS engine bound" on the X3 (MathCosmos README "Voices"), so any spoken programme notes must be pre-rendered clips.
- GUIDE: "Sleep button fires `onPause` while the user still wears the glasses — don't stop audio in `onPause`." MathCosmos and WanderQuest *do* stop audio in `onPause`, on purpose (no crew talking to a dark display). For a recital, **decide deliberately**. One option is to keep playing through a display-off pause, but that needs the audio engine to outlive the GL view's `onPause` and stay thermally safe.

### 4.6 Drawing the sounding piano from the audio clock

MathCosmos publishes `beatPhaseSec` and `breathPhase01` from the audio thread each buffer, and the renderer reads them to stay phase-locked (`MC/MathAudioEngine.kt:60-63, 310-311`; `MC/StereoMathRenderer.kt:430`). **Do the same.** The audio thread publishes a `@Volatile` sample position (or reads `AudioTrack.getTimestamp`). The renderer then derives each key's and hammer's pose from the MIDI event list at `(samplePos − outputLatency)`. Pictures and sound then cannot drift apart.

## 5. Companion server and file import

### 5.1 WanderQuest `CompanionServer` (NanoHTTPD 2.3.1)

- **Transport:** plain HTTP on **port 19111**: "TLS handshakes cost audio on this CPU; reads are public game stats. WRITES require the companion token" (`WQ/companion/CompanionServer.kt:24-26, :48`). TapInsight uses HTTPS on 19110 with an EC certificate, because RSA handshakes stutter audio (`GUIDE:115, :347`).
- **Routes** (`:78-111`): `/` and `/index.html` serve the page from assets through `htmlProvider("companion.html").replace("%%WQ_TOKEN%%", token)`. Injecting the token means "whoever can load the page is already on the same Wi-Fi … the page auths itself". Also `/board` (HTML) and `/api/state`, `/api/leaderboard`, `/api/sites`, `/api/position`, `/api/quests`, which answer GET with JSON. POST routes run through `requirePost`. Unknown paths get 404. `serve()` wraps `route()` in `runCatching` and returns a JSON 500 on failure (`:72-76`).
- **Bodies:** `readBody` calls `session.parseBody(files)` and reads `files["postData"]` as JSON (`:124-128`). **There is no multipart handling.**
- **Token check** (`:130-137`): the `x-wq-token` header, then a JSON `token` field, then the `?token=` query.
- **Main thread:** writes are marshalled onto it with `mainHandler.post` (`:139`).
- **IP address:** `deviceIp()` returns the first IPv4 address on an up, non-loopback interface (`:51-58`).
- **Start and display** (`WQ/MainActivity.kt:203-240`): the server is created in `onCreate`, the html provider is `assets.open(name).bufferedReader().use { it.readText() }`, and the call is `srv.start(5000, true)`, meaning a 5 s socket read timeout and a daemon thread. The URL is stored as `engine.leaderboardUrl = "http://$ip:19111"`. It is **drawn on the Journal screen** as `Companion (phone browser): <url>` in cyan 11 px, with `write-key <token>` below it (`WQ/render/GameView.kt:1681-1687`). `server?.stop()` runs in `onDestroy` (`WQ/MainActivity.kt:355`).

### 5.2 TapVibe `CompanionServer`: the multipart upload to copy

```kotlin
// TV/net/CompanionServer.kt:25-52
when {
  session.method == Method.POST && session.uri == "/upload" -> upload(session)
  session.method == Method.POST && session.uri == "/delete" -> delete(session)   // ?name=
  session.uri == "/list" -> json(listJson())
  session.uri == "/" || session.uri == "/index.html" -> newFixedLengthResponse(OK, "text/html", PAGE)
}
private fun upload(session: IHTTPSession): Response {
    val files = HashMap<String, String>()
    session.parseBody(files)                                // multipart → temp files
    for ((field, tmpPath) in files) {
        val original = session.parameters[field]?.firstOrNull() ?: continue   // original filename
        library.save(File(tmpPath), original)               // binary copy, keep extension
    }
    onChanged(); return json("""{"ok":true,"saved":$saved}""")
}
```

- **How NanoHTTPD 2.3.1 behaves here:** `files[field]` is a temp-file path, and `session.parameters[field]` holds the client's filename. NanoHTTPD deletes its temp files when the request finishes, so **copy them inside the handler**, as this code does.
- **Port and start:** port **8080**, `start(5000, false)`. The URL `http://<ip>:8080` comes from `companionUrl()` on the stage view (`TV/MainActivity.kt:77-90`). `deviceIp()` here prefers `isSiteLocalAddress` IPv4 (`:76-88`), which is better than WanderQuest's first-IPv4 rule.
- **The page** is inline HTML inside the Kotlin file. It has drag-and-drop, including whole folders via `webkitGetAsEntry`, plus a file picker. Each file goes as its own `FormData` POST with `XMLHttpRequest` upload progress (`:136-197`, key lines `:192-193`). There is a list with delete, and "No '$' or backticks (Kotlin string safety)" (`:90`).
- **Storage** (`TV/music/MusicLibrary.kt:13-51`) lives in `getExternalFilesDir(null)/Music`. `save` is a byte-for-byte `copyTo`. "Never re-encode or touch the bytes as text", which fixed the TapInsight bug where uploaded bytes were handled as text. `sanitize` keeps the base name and maps `[^A-Za-z0-9._ ()\-]` to `_`, truncated to 120 characters. `uniqueDest` appends ` (n)`.
- **Manifest:** INTERNET and ACCESS_WIFI_STATE. The dependency is `org.nanohttpd:nanohttpd:2.3.1` (`/Users/me/Projects/tapvibe/app/build.gradle.kts:39`).

### 5.3 NanoHTTPD gotchas (FrontLineAssist)

- "NanoHTTPD's parseBody() decodes with a non-UTF-8 default, which corrupts characters like the em-dash". For JSON bodies, read `content-length` bytes from `session.inputStream` and decode UTF-8 yourself (`FL/web/WebSyncServer.kt:171-189`). This matters because piece titles contain names like Für Elise and Händel.
- Serve assets as decoded Strings through `newFixedLengthResponse`, which "frames the Content-Length itself, … more robust across browsers/keep-alive". Add `Cache-Control: no-store` (`:191-198`).

### 5.4 Recommended Hammerklavier companion

Merge the two servers: WanderQuest's structure (token injected into an asset page, `runCatching` routing, `deviceIp`) plus TapVibe's `/upload` multipart handling and `MusicLibrary`-style storage.

- **Storage:** save to `getExternalFilesDir(null)/Scores`, accepting only `.mid`, `.midi` and `.kar`. Validate the `MThd` header before accepting a file.
- **Routes:** `GET /api/library` returns built-in and imported pieces; `POST /api/play?id=` and `POST /api/piano?kind=` let the phone act as a remote.
- **Token:** gate writes with the injected token.
- **Port:** 19112, so it cannot clash with WanderQuest's 19111 or the 8080 used by TapVibe and temporalace. Plain HTTP.
- **Showing the URL:** put it on the title card and in the library menu through the SBS overlay, like WanderQuest's Journal line. `deviceIp()` returns null when Wi-Fi is off, so show "no Wi-Fi" in that case, as TapVibe does.
- **Size limit:** cap uploads at a few MB, since MIDI files are small.

### 5.5 MIDI

No sibling parses Standard MIDI Files. The only MIDI in the tree is note-number-to-Hz conversion in SpyHunt (`SH/audio/Synth.kt:91-92`, `Music.kt`). `android.media.midi` is for live device I/O, not files. `MediaPlayer` can play `.mid` through the built-in Sonivox synth, but that defeats the purpose.

**Write a small SMF parser**:

- Formats 0 and 1.
- VLQ delta times and running status.
- Tempo meta-events (0x51) merged into a global tempo map, plus time signature (0x58).
- Note on and off (note-on with velocity 0 counts as note-off).
- CC64 sustain, CC66 sostenuto and CC67 soft, which are the three pedals the brief wants to see moving.

Parse off the audio thread into a flat, time-sorted, sample-indexed event array. The audio thread then only walks an index.

## 6. Build files

### 6.1 Copy verbatim (then rename)

| File | Change |
|---|---|
| `MathCosmos/gradlew` | none |
| `MathCosmos/gradle/wrapper/gradle-wrapper.jar` | none |
| `MathCosmos/gradle/wrapper/gradle-wrapper.properties` | none (`gradle-8.9-all.zip`, `networkTimeout=10000`, `validateDistributionUrl=true`) |
| `MathCosmos/build.gradle.kts` | none (plugins `com.android.application` 8.7.3 and `org.jetbrains.kotlin.android` 2.0.21, `apply false`) |
| `MathCosmos/settings.gradle.kts` | `rootProject.name = "Hammerklavier"`. Repositories are google, mavenCentral and gradlePluginPortal, with `FAIL_ON_PROJECT_REPOS` |
| `MathCosmos/gradle.properties` | none (`-Xmx2048m`, `useAndroidX`, `nonTransitiveRClass`, `kotlin.code.style=official`) |
| `MathCosmos/local.properties` | none (`sdk.dir=/opt/homebrew/share/android-commandlinetools`). Keep it untracked |
| `app/src/main/res/xml/backup_rules.xml` (`<full-backup-content />`) and `data_extraction_rules.xml` (empty `<cloud-backup/>` and `<device-transfer/>`) | none |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` (adaptive, background + foreground drawables) | none. minSdk 29 means no PNG mipmaps are needed. **Draw new** `drawable/ic_launcher_{background,foreground}.xml` vectors (108 dp, full-bleed background) |
| `app/proguard-rules.pro` | none |
| `.gitignore` | Keep the build, credentials and `*.apk` sections |
| `.gitattributes` | Only if the sample bank goes in LFS: `*.ogg filter=lfs …`. Add `*.wav`/`*.flac` if used |

There is no `gradlew.bat` in MathCosmos.

### 6.2 `app/build.gradle.kts` shape (MathCosmos, adapted)

```kotlin
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.tropicalstream.hammerklavier"; compileSdk = 35
    defaultConfig { applicationId = "com.tropicalstream.hammerklavier"; minSdk = 29; targetSdk = 35
                    versionCode = 1; versionName = "0.1-alpha" }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
                 debug { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("wav", "ogg", "flac", "mid", "bin") }   // MC :60-62 pattern
}
base { archivesName.set("Hammerklavier") }        // → Hammerklavier-debug.apk (MC :66-68)
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")   // WQ/app/build.gradle.kts:41, TV :39
}
```

The Fish `buildConfigField` block (MathCosmos `:8-32`) is only needed for voiced programme notes. The API-key config file must never be copied or committed; hand the user the `cp` command instead (per the memory note on blocked secret copies). **No vendor AARs are needed**: "the only thing it needs from the platform is the `com.rayneo.mercury.app` meta-data flag … Verified on the glasses: both lenses still render" (`MC/MathCosmosApp.kt:5-16`, `app/build.gradle.kts:71-75`).

### 6.3 Manifest: MathCosmos plus WanderQuest's AR_APP category

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
    <uses-feature android:glEsVersion="0x00020000" android:required="true" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.INTERNET" />            <!-- companion server sockets -->
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <application android:name=".HammerklavierApp" android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules" android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name" android:supportsRtl="true" android:hardwareAccelerated="true"
        android:theme="@style/Theme.Hammerklavier" tools:targetApi="31">
        <meta-data android:name="com.rayneo.mercury.app" android:value="true" />
        <!-- NO ar_mode meta-data: it restricts rendering to the left lens. -->
        <activity android:name=".MainActivity"
            android:configChanges="density|orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|keyboard|navigation"
            android:exported="true" android:launchMode="singleTask" android:screenOrientation="landscape"
            android:theme="@style/Theme.Hammerklavier" android:windowSoftInputMode="adjustNothing">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="com.rayneo.intent.category.AR_APP" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Sources: `MathCosmos/app/src/main/AndroidManifest.xml:1-44`, and `WanderQuest/app/src/main/AndroidManifest.xml` for the AR_APP category, `hardwareAccelerated` and the no-`ar_mode` note. `GUIDE:410` says the launch-proven TapBubbles form uses **two separate** intent-filters (MAIN+LAUNCHER, and MAIN+AR_APP) plus `resizeableActivity="false"`. WanderQuest's combined filter also works. Pick one.

Serving over NanoHTTPD needs no cleartext config; that restriction only applies to outbound client connections.

### 6.4 Theme (copy verbatim, rename)

```xml
<!-- MathCosmos/app/src/main/res/values/themes.xml -->
<style name="Theme.MathCosmos" parent="android:style/Theme.Material.NoActionBar">
    <item name="android:windowBackground">@android:color/black</item>
    <item name="android:statusBarColor">@android:color/black</item>
    <item name="android:navigationBarColor">@android:color/black</item>
    <item name="android:windowFullscreen">true</item>
    <item name="android:forceDarkAllowed" tools:targetApi="q">false</item>
    <item name="android:fontFamily">sans</item>
</style>
```

Also set `FLAG_KEEP_SCREEN_ON` (`MC/MainActivity.kt:103`), and apply the immersive `systemUiVisibility` flags in both `onCreate` and `onResume` (`:512-520`).

### 6.5 adb commands in use

```bash
cd /Users/me/Projects/Hammerklavier && ./gradlew :app:assembleDebug && \
adb -s A06B4A96A733283 install -r app/build/outputs/apk/debug/Hammerklavier-debug.apk && \
adb -s A06B4A96A733283 shell am start -n com.tropicalstream.hammerklavier/.MainActivity
# flat single view for captures:   … am start -n …/.MainActivity --ez mono true
adb -s A06B4A96A733283 shell am broadcast -a com.tropicalstream.hammerklavier.CONTROL --ei view 1
adb -s A06B4A96A733283 exec-out screencap -p > shot.png          # full 1280x480 SBS pair
adb -s A06B4A96A733283 logcat -s HKThermal HKAudio HKGaze AndroidRuntime:E
adb -s A06B4A96A733283 shell settings put global device_wearing 1  # keep alive off-head; reset to 0 after
adb -s A06B4A96A733283 shell wm dismiss-keyguard
```

These follow `MathCosmos/README.md:166-190` and the SpyHunt README and `tools/run.sh`.

- **Always pass `-s`.** "The X3 Pro is usually not the only device attached", and `:app:installDebug` "fans out to every attached device". SpyHunt's `tools/run.sh` picks the device whose `ro.product.manufacturer` is `RayNeo`.
- **Chain with `&&`** so a failed build never installs a stale APK (`GUIDE:272`).

## 7. Gotchas a new app must respect

1. **Black is transparent. Dark tones vanish.** Use no dark washes, near-black backgrounds (`argb(150-218, 8,3,10)`), a lifted gamma on textures, an ambient floor in lit shaders, and blooms under thin strokes (§2.5, §2.6, §2.8). Walnut, ebony and black keys will disappear unless they are rim-lit or given a warm specular highlight.
2. **Heat reboots the glasses.**
   - Render at 30 fps on demand, not continuously, stepped down by the governor.
   - Upload static geometry to VBOs once.
   - Allocate nothing per frame: no string formatting in `draw()`, no `buildList`.
   - Rasterise at most 3 new labels per frame.
   - Use table-driven float DSP with no per-sample transcendental functions.
   - Run the simulation once per frame and render it twice.
3. **The thermal status lies (it reads 0).** Drive the governor from battery temperature: 39.0 °C gives quality 1, 42.0 °C gives quality 2, with hysteresis at 37.5 and 40.5 °C.
4. **Every 2D view goes inside the one child of `BinocularSbsLayout`.** A sibling of the SBS layout shows in one eye only. System toasts and dialogs show in one eye too. Never derive the half width by mutating `DisplayMetrics` ("1280→640→320→240" collapse, `GUIDE:411-420`). Video or any SurfaceView must be a TextureView to be duplicated.
5. **The click is a KEY, and one firm click can arrive as both touch and key.** Dedup them (250 ms cross-source, 40 ms echo). Filter the left pad by the *name* `cyttsp6`, never by device id, because ids shuffle across reboots. Long-press never reaches apps. Swipe direction can flip with the system's natural-mode setting.
6. **A single tap costs 300 ms** under a double-tap scheme. MathCosmos fires the single tap instantly and accepts that a double-tap also triggers it. Choose per action: play/pause can tolerate 300 ms of latency; a view switch should not fire twice.
7. **No SoundPool, and no always-open looping MediaPlayer** (a 500-630 ms GL stall every 5-6 s). One app-owned AudioTrack stream is fine.
8. **Call `play()` before priming a LOW_LATENCY track**, or the thread can hang at MAX_PRIORITY. On restart, kill every voice so no stale tail replays.
9. **Big blocking writes deadlock.** The assistant apps slice writes into 16 KB `WRITE_NON_BLOCKING` chunks with a volatile cancel generation (`GUIDE:110`). For a synth loop, small blocking block writes (256 frames) are the proven form.
10. **The speakers have essentially no output below about 150 Hz.** Voice the mix mid-forward, and leave it flat on headsets.
11. **There is no TTS engine.** Pre-render any speech.
12. **The GL sign conventions:** right = forward × up, and up = right × forward. Swapping them mirrors the IMU look-around.
13. **Fast UI refresh starves the audio decoder** (`GUIDE:113, :380`). Keep overlays static between updates, using a HARDWARE layer and `postInvalidateDelayed(33)` at most.
14. **Kotlin build traps.**
    - Kotlin block comments nest, so never write `x/*` inside a KDoc (`GUIDE:387`).
    - After copying a tree, `rm -rf .gradle app/build */build` before the first build (`GUIDE:391`).
    - Inline HTML in a Kotlin raw string must avoid `$` and backticks (`TV/net/CompanionServer.kt:90`), or the page can live in `assets/` as WanderQuest's does.
15. **NanoHTTPD details.** `parseBody` is not UTF-8-safe for text bodies. Multipart temp files vanish after the request, so copy them inside the handler. `start(5000, …)` sets the socket timeout. Stop the server in `onDestroy`.
16. **The glasses force-stop apps when off the head**, unless `device_wearing` is 1 during bench tests.
17. **Git identity.** Commit as `tropicalstream <tropicalstream@users.noreply.github.com>`, and never write the Gmail address into any file. Keep the MathCosmos `.gitignore` credentials block, and never commit a Fish config.

## 8. Open decisions this recon cannot settle

- **FOV and projection** (§2.3): MathCosmos uses 58° toe-in, SpyHunt 31.2° off-axis, and the spec says 30° diagonal. For a life-size keyboard, test off-axis at 24-31° on the glasses with a CONTROL `fov` extra.
- **Audio across a display-off `onPause`:** stop, as MathCosmos does, or keep playing, as the GUIDE advises for media.
- **Where the sample bank lives:** APK assets, or a first-run download or companion upload into `getExternalFilesDir`. A multi-sampled grand at 48 kHz is large. MathCosmos's 115 MB APK is already past GitHub's 100 MB per-file limit, which is why `*.apk` is in `.gitignore`.
- **A left-arm firm-click filter on the key path:** the suggested name check has not been tested on the glasses.
