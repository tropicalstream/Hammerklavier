package com.tropicalstream.hammerklavier.platform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * One logical child, drawn twice side by side — left copy for the left eye,
 * right copy for the right — so 2D overlays (telemetry, captions, menus) land
 * in both lenses of the X3 Pro, matching the two-viewport GL scene beneath.
 * Transparent, so the GLSurfaceView under it shows through; touches on the
 * right half are re-mapped into the logical (left) half. With
 * `sbsEnabled = false` (phone testing) the child simply fills the window.
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var sbsEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    private var touchOffsetLatched = false

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val childW = if (sbsEnabled) w / 2 else w
        getChildAt(0)?.measure(
            MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val childW = if (sbsEnabled) (right - left) / 2 else right - left
        child.layout(0, 0, childW, bottom - top)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val child = getChildAt(0) ?: return
        if (!sbsEnabled) {
            drawChild(canvas, child, drawingTime)
            return
        }
        val logicalWidth = width / 2
        canvas.save()
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawingTime)
        canvas.restore()
        canvas.save()
        canvas.translate(logicalWidth.toFloat(), 0f)
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawingTime)
        canvas.restore()
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        invalidate()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!sbsEnabled) return super.dispatchTouchEvent(ev)
        val logicalWidth = width / 2f
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOffsetLatched = ev.x >= logicalWidth
        }
        if (!touchOffsetLatched) return super.dispatchTouchEvent(ev)
        // Remap into the logical half for our child, then restore: when nothing here consumes
        // the event it falls through to the GLSurfaceView beneath, which must see the real x.
        ev.offsetLocation(-logicalWidth, 0f)
        try {
            return super.dispatchTouchEvent(ev)
        } finally {
            ev.offsetLocation(logicalWidth, 0f)
        }
    }
}
