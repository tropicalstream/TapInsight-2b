package com.TapLink.app

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView

/**
 * Side-by-side binocular compositor.
 *
 * The first child is treated as a single logical viewport and measured to half
 * of the physical width, then drawn twice: left eye and right eye.
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var remapCurrentTouchSequence = false
    private var sdkMirrorView: View? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        require(childCount == 1) {
            "BinocularSbsLayout expects exactly one logical viewport child."
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val existing = sdkMirrorView
        if (existing != null) {
            try {
                startSdkMirror(existing)
            } catch (t: Throwable) {
                Log.w(TAG, "SDK mirror restart failed — reverting to drawChild path", t)
                removeView(existing)
                sdkMirrorView = null
                invalidate()
            }
            return
        }
        if (isSdkMirroringEnabled()) {
            try {
                attachSdkMirror()
            } catch (t: Throwable) {
                Log.w(TAG, "Mercury MirroringView unavailable — using drawChild mirroring", t)
                sdkMirrorView?.let { removeView(it) }
                sdkMirrorView = null
            }
        }
    }

    override fun onDetachedFromWindow() {
        sdkMirrorView?.let {
            try {
                stopSdkMirror(it)
            } catch (t: Throwable) {
                Log.w(TAG, "stopMirroring failed", t)
            }
        }
        super.onDetachedFromWindow()
    }

    private fun isSdkMirroringEnabled(): Boolean {
        return try {
            context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_SDK_MIRRORING, false)
        } catch (e: Exception) {
            false
        }
    }

    private fun attachSdkMirror() {
        val child = getChildAt(0) ?: return
        val mirror = MirroringView(context)
        mirror.setBackgroundColor(0)
        mirror.elevation = 1000f
        mirror.isClickable = false
        mirror.isFocusable = false
        addView(mirror)
        mirror.setSource(child)
        mirror.startMirroring()
        sdkMirrorView = mirror
        Log.d(TAG, "SDK MirroringView active — right eye handled by Mercury SDK")
    }

    private fun startSdkMirror(mirror: View) {
        val child = getChildAt(0) ?: return
        val m = mirror as MirroringView
        m.setSource(child)
        m.startMirroring()
    }

    private fun stopSdkMirror(mirror: View) {
        (mirror as MirroringView).stopMirroring()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val child = getChildAt(0) ?: return
        val logicalWidth = logicalViewportWidth(measuredWidth)
        val logicalHeight = measuredHeight.coerceAtLeast(0)

        val childWidthSpec = MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(logicalHeight, MeasureSpec.EXACTLY)
        child.measure(childWidthSpec, childHeightSpec)
        sdkMirrorView?.measure(childWidthSpec, childHeightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        sdkMirrorView?.let { mirror ->
            val lw = child.measuredWidth
            mirror.layout(lw, 0, lw + mirror.measuredWidth, mirror.measuredHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val child = getChildAt(0)
        if (child == null || child.visibility == GONE) {
            return
        }

        if (sdkMirrorView != null) {
            super.dispatchDraw(canvas)
            return
        }

        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return

        val drawTime = drawingTime

        canvas.save()
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawTime)
        canvas.restore()

        canvas.save()
        canvas.translate(logicalWidth.toFloat(), 0f)
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawTime)
        canvas.restore()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                remapCurrentTouchSequence = ev.getX(0) >= logicalWidth
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val shouldRemap = remapCurrentTouchSequence
                remapCurrentTouchSequence = false
                if (!shouldRemap) return super.dispatchTouchEvent(ev)
                val mapped = MotionEvent.obtain(ev)
                mapped.offsetLocation(-logicalWidth.toFloat(), 0f)
                val handled = super.dispatchTouchEvent(mapped)
                mapped.recycle()
                return handled
            }
        }

        if (!remapCurrentTouchSequence) return super.dispatchTouchEvent(ev)

        val mapped = MotionEvent.obtain(ev)
        mapped.offsetLocation(-logicalWidth.toFloat(), 0f)
        val handled = super.dispatchTouchEvent(mapped)
        mapped.recycle()
        return handled
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchGenericMotionEvent(event)

        val primaryPointerX = event.getX(0)
        if (primaryPointerX < logicalWidth) {
            return super.dispatchGenericMotionEvent(event)
        }

        val mapped = MotionEvent.obtain(event)
        mapped.offsetLocation(-logicalWidth.toFloat(), 0f)
        val handled = super.dispatchGenericMotionEvent(mapped)
        mapped.recycle()
        return handled
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        // Mirror rendering needs both halves redrawn whenever logical content changes.
        val mirror = sdkMirrorView
        if (mirror == null) {
            invalidate()
        } else if (child !== mirror) {
            mirror.invalidate()
        }
    }

    private fun logicalViewportWidth(totalWidth: Int): Int {
        return (totalWidth / 2).coerceAtLeast(0)
    }

    companion object {
        private const val TAG = "BinocularSbsLayout"
        private const val PREF_SDK_MIRRORING = "sdk_mirroring"
    }
}
