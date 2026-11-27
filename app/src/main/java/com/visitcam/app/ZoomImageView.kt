package com.visitcam.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/** Image view with pinch-to-zoom, drag and double-tap zoom, like the phone's own gallery. */
class ZoomImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatImageView(c, a) {
    private val m = Matrix()
    private val vals = FloatArray(9)
    private var fitScale = 1f
    private val maxZoom = 6f

    private fun scale(): Float { m.getValues(vals); return vals[Matrix.MSCALE_X] }

    /** True while the picture is zoomed in (used to stop swipe-to-next in the gallery). */
    fun isZoomed(): Boolean = scale() > fitScale * 1.02f

    private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(d.scaleFactor, d.focusX, d.focusY); return true }
    })

    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed()) fit() else zoomBy(2.5f, e.x, e.y)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!isZoomed()) return false
            m.postTranslate(-dx, -dy); clamp(); imageMatrix = m
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        fitScale = minOf(width / dw, height / dh)
        m.reset()
        m.postScale(fitScale, fitScale)
        m.postTranslate((width - dw * fitScale) / 2f, (height - dh * fitScale) / 2f)
        imageMatrix = m
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val cur = scale()
        if (cur <= 0f) return
        val target = (cur * f).coerceIn(fitScale, fitScale * maxZoom)
        val k = target / cur
        m.postScale(k, k, fx, fy)
        clamp(); imageMatrix = m
    }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else { if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else { if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom }
        m.postTranslate(dx, dy)
    }

    override fun setImageDrawable(drawable: Drawable?) { super.setImageDrawable(drawable); fit() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        gd.onTouchEvent(e)
        parent?.requestDisallowInterceptTouchEvent(isZoomed() || e.pointerCount > 1)
        return true
    }
}
