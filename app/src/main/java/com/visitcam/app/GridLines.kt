package com.visitcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** mode: 1=3x3, 2=4x4, 3=golden ratio, 4=diagonals, 5=center cross */
class GridLines(c: Context, a: AttributeSet?) : View(c, a) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE }
    var mode = 0
        set(v) { field = v; invalidate() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        fun v(x: Float) = cv.drawLine(w * x, 0f, w * x, h, p)
        fun hz(y: Float) = cv.drawLine(0f, h * y, w, h * y, p)
        when (mode) {
            1 -> { v(1f / 3); v(2f / 3); hz(1f / 3); hz(2f / 3) }
            2 -> { for (i in 1..3) { v(i / 4f); hz(i / 4f) } }
            3 -> { v(0.382f); v(0.618f); hz(0.382f); hz(0.618f) }
            4 -> { cv.drawLine(0f, 0f, w, h, p); cv.drawLine(w, 0f, 0f, h, p); v(0.5f); hz(0.5f) }
            5 -> { v(0.5f); hz(0.5f); cv.drawCircle(w / 2, h / 2, minOf(w, h) * 0.08f, p) }
        }
    }
}

/** Horizon level: a line that tilts with the phone and turns green when level. */
class LevelView(c: Context, a: AttributeSet?) : View(c, a) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; style = Paint.Style.STROKE }
    var angle = 0f
        set(v) { field = v; invalidate() }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val len = width * 0.22f
        p.color = if (Math.abs(angle) < 1.5f) 0xFF4CAF50.toInt() else 0xFFFFFFFF.toInt()
        cv.save(); cv.rotate(-angle, cx, cy)
        cv.drawLine(cx - len, cy, cx - len * 0.3f, cy, p); cv.drawLine(cx + len * 0.3f, cy, cx + len, cy, p)
        cv.restore()
        cv.drawCircle(cx, cy, len * 0.18f, p)
    }
}

/** Shows the real stamp on top of the camera preview, rotated to stay upright with the phone. */
class StampView(c: Context, a: AttributeSet?) : View(c, a) {
    var provider: (() -> StampData?)? = null
    var ratioIdx = 0
        set(v) { field = v; invalidate() }
    var rotDeg = 0
        set(v) { field = v; invalidate() }

    override fun onDraw(cv: Canvas) {
        val d = provider?.invoke() ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        var ar = when (ratioIdx) { 0 -> 3f / 4; 1 -> 9f / 16; 2 -> 1f; else -> 0f }
        if (ar > 0f && vw > vh && ar != 1f) ar = 1f / ar
        var cw = vw; var ch = vh
        if (ar > 0f) { if (vw / vh > ar) { cw = vh * ar } else { ch = vw / ar } }
        val left = (vw - cw) / 2; val top = (vh - ch) / 2
        cv.save()
        cv.clipRect(left, top, left + cw, top + ch)
        cv.translate(left + cw / 2, top + ch / 2)
        cv.rotate(-rotDeg.toFloat())
        val sideways = rotDeg == 90 || rotDeg == 270
        val uw = if (sideways) ch else cw; val uh = if (sideways) cw else ch
        cv.translate(-uw / 2, -uh / 2)
        drawStamp(cv, uw, uh, d)
        cv.restore()
    }
}
