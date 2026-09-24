package com.tropicalstream.hammerklavier.platform

import com.tropicalstream.hammerklavier.contract.Gesture
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gesture engine's swipe decision on realistic right-pad traces. cyttsp5_mt reports raw
 * X 0..638 and Y 0..196; InputReader scales them to the 1280×480 display (×2.005, ×2.444), so the
 * window coordinates the engine sees span 0..1279 × 0..479 and the vertical threshold is
 * 6 % of 480 = 28.8 px (≈ 12 raw pad units, a few millimetres). Each trace is fed MOVE by MOVE
 * with the engine's latch: the first decided gesture wins, the rest of the gesture is ignored.
 */
class SwipeClassifierTest {
    private val w = 1280; private val h = 480

    /** Raw pad samples (x, y) → window px, then the engine's accumulate-and-latch loop. */
    private fun run(raw: List<Pair<Int, Int>>, sw: Int = w, sh: Int = h): Gesture? {
        val px = raw.map { (x, y) -> Pair(x * 1279f / 638f, y * 479f / 196f) }
        val (x0, y0) = px.first()
        for ((x, y) in px.drop(1)) SwipeClassifier.classify(x - x0, y - y0, sw, sh)?.let { return it }
        return null
    }

    private fun trace(x0: Int, y0: Int, x1: Int, y1: Int, n: Int = 8) =
        (0..n).map { i -> Pair(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n) }

    @Test fun realisticVerticalSwipesAcrossTheTempleStrip() {
        // A finger crossing most of the 196-unit strip, with the forward drift a temple swipe has.
        assertEquals(Gesture.DOWN, run(trace(300, 40, 330, 160)))
        assertEquals(Gesture.UP, run(trace(330, 160, 300, 40)))
        // A short flick: 20 raw units (≈ 49 px) is enough.
        assertEquals(Gesture.DOWN, run(trace(400, 90, 405, 110, 4)))
        assertEquals(Gesture.UP, run(trace(400, 110, 395, 90, 4)))
        // A diagonal that is mostly vertical still counts as vertical, not forward.
        assertEquals(Gesture.DOWN, run(trace(200, 30, 250, 170)))
    }

    @Test fun adbInputSwipeTraces() {
        // `adb shell input swipe 320 150 320 350 150` arrives in window px directly.
        fun px(x0: Float, y0: Float, x1: Float, y1: Float): Gesture? {
            for (i in 1..10) SwipeClassifier.classify((x1 - x0) * i / 10, (y1 - y0) * i / 10, w, h)?.let { return it }
            return null
        }
        assertEquals(Gesture.DOWN, px(320f, 150f, 320f, 350f))
        assertEquals(Gesture.UP, px(320f, 350f, 320f, 150f))
        assertEquals(Gesture.FORWARD, px(200f, 240f, 500f, 240f))
        assertEquals(Gesture.BACK, px(500f, 240f, 200f, 240f))
    }

    @Test fun horizontalSwipesKeepTheirMeaningAndJitterIsNothing() {
        assertEquals(Gesture.FORWARD, run(trace(100, 100, 400, 115)))
        assertEquals(Gesture.BACK, run(trace(400, 100, 100, 90)))
        assertEquals(null, run(trace(300, 100, 305, 105)))          // a tap's wobble
        assertEquals(null, SwipeClassifier.classify(0f, 28f, w, h))  // just under 28.8 px
        assertEquals(Gesture.DOWN, SwipeClassifier.classify(0f, 29f, w, h))
    }

    @Test fun thresholdScalesWithTheRealDisplayNotTheDefault() {
        assertEquals(28.8f, SwipeClassifier.minSwipe(1280, 480), 0.01f)
        // A mono 640×480 window gives the same vertical threshold: the short side is still 480.
        assertEquals(Gesture.UP, run(trace(330, 160, 300, 40), 640, 480))
    }
}
