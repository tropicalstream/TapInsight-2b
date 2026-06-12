package com.rayneo.visionclaw.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wireframe oscilloscope tuned for HUD use:
 * - low-luminance rendering
 * - 2px stroke
 * - vsync animation via Choreographer
 * - waveform emanates outward from a circular camera cutout
 */
class VoiceOscilloscopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokePx = context.resources.displayMetrics.density * 2f

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = 0xFF1F1F1F.toInt()
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = 0xFFFF4D4D.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val leftPath = Path()
    private val rightPath = Path()
    private var targetLevel = 0f
    private var displayedLevel = 0f
    private var phase = 0f
    private var active = false
    private var framePosted = false
    private var centerCutoutRadiusPx = 0f
    private var cutoutCenterFraction = 0.5f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            val delta = targetLevel - displayedLevel
            displayedLevel += delta * 0.28f
            targetLevel *= 0.86f
            if (!active && displayedLevel < 0.01f) {
                displayedLevel = 0f
                invalidate()
                return
            }
            phase += 0.24f
            invalidate()
            postFrame()
        }
    }

    fun pushLevel(amplitude: Float, color: Int) {
        targetLevel = amplitude.coerceIn(0f, 1f)
        wavePaint.color = color
        active = true
        postFrame()
    }

    fun stop() {
        active = false
        targetLevel = 0f
        postFrame()
    }

    fun setCenterCutoutRadiusDp(radiusDp: Float) {
        centerCutoutRadiusPx = (radiusDp * context.resources.displayMetrics.density).coerceAtLeast(0f)
        invalidate()
    }

    /** Set the horizontal position of the cutout center as a fraction of this view's width. */
    fun setCutoutCenterFraction(fraction: Float) {
        cutoutCenterFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postFrame()
    }

    override fun onDetachedFromWindow() {
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            framePosted = false
        }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * cutoutCenterFraction
        val cy = h / 2f
        val cutR = centerCutoutRadiusPx + strokePx

        // ── Axis line with gap around the circle ──
        if (cutR > 0f) {
            val leftEnd = (cx - cutR).coerceAtLeast(0f)
            val rightStart = (cx + cutR).coerceAtMost(w)
            canvas.drawLine(0f, cy, leftEnd, cy, axisPaint)
            canvas.drawLine(rightStart, cy, w, cy, axisPaint)
        } else {
            canvas.drawLine(0f, cy, w, cy, axisPaint)
        }

        // ── Wave amplitude ──
        val amplitude = (h * 0.10f) + (h * 0.34f * displayedLevel)
        val steps = 64

        if (cutR <= 0f) {
            // No cutout — single continuous wave
            leftPath.reset()
            for (i in 0..steps) {
                val ratio = i / steps.toFloat()
                val x = w * ratio
                val y = cy + (sin(phase + ratio * Math.PI * 2.2).toFloat() * amplitude)
                if (i == 0) leftPath.moveTo(x, y) else leftPath.lineTo(x, y)
            }
            canvas.drawPath(leftPath, wavePaint)
            return
        }

        // ── Two wave segments that emanate FROM the circle edge ──

        // LEFT segment: draw left-to-right so the path renders correctly,
        // but only include points LEFT of the cutout circle.
        leftPath.reset()
        var started = false
        for (i in 0..steps) {
            val ratio = i / steps.toFloat()
            val x = w * ratio
            if (x >= cx - cutR) break  // reached the cutout edge — stop
            // Fade amplitude near the circle edge so wave "emerges" smoothly
            val distFromEdge = (cx - cutR) - x
            val edgeFade = (distFromEdge / (cutR * 0.8f)).coerceIn(0f, 1f)
            val y = cy + (sin(phase + ratio * Math.PI * 2.2).toFloat() * amplitude * edgeFade)
            if (!started) {
                leftPath.moveTo(x, y)
                started = true
            } else {
                leftPath.lineTo(x, y)
            }
        }
        canvas.drawPath(leftPath, wavePaint)

        // RIGHT segment: starts at the circle boundary, flows right to edge
        rightPath.reset()
        started = false
        for (i in 0..steps) {
            val ratio = i / steps.toFloat()
            val x = w * ratio
            if (x <= cx + cutR) continue  // still inside or left of cutout
            // Fade amplitude near the circle edge so wave "emerges" smoothly
            val distFromEdge = x - (cx + cutR)
            val edgeFade = (distFromEdge / (cutR * 0.8f)).coerceIn(0f, 1f)
            val y = cy + (sin(phase + ratio * Math.PI * 2.2).toFloat() * amplitude * edgeFade)
            if (!started) {
                rightPath.moveTo(x, y)
                started = true
            } else {
                rightPath.lineTo(x, y)
            }
        }
        canvas.drawPath(rightPath, wavePaint)
    }

    private fun postFrame() {
        if (framePosted || !isAttachedToWindow) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}
