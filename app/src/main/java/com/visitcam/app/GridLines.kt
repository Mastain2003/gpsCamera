package com.visitcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridLines(c: Context, a: AttributeSet?) : View(c, a) {
    private val p = Paint().apply { color = 0x88FFFFFF.toInt(); strokeWidth = 2f }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1..2) {
            cv.drawLine(w * i / 3, 0f, w * i / 3, h, p)
            cv.drawLine(0f, h * i / 3, w, h * i / 3, p)
        }
    }
}
