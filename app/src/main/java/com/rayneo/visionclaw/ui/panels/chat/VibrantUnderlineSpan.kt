package com.rayneo.visionclaw.ui.panels.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

/**
 * Draws a high-contrast underline with configurable thickness.
 */
class VibrantUnderlineSpan(
    private val color: Int,
    private val thicknessPx: Float
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        return paint.measureText(text, start, end).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val originalColor = paint.color
        val width = paint.measureText(text, start, end)

        canvas.drawText(text, start, end, x, y.toFloat(), paint)

        val underlinePaint = Paint(paint).apply {
            color = this@VibrantUnderlineSpan.color
            style = Paint.Style.STROKE
            strokeWidth = thicknessPx
            isAntiAlias = true
        }
        val lineY = y + (thicknessPx * 1.5f)
        canvas.drawLine(x, lineY, x + width, lineY, underlinePaint)

        paint.color = originalColor
    }
}
