package com.TapLinkX3.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A calm "night sky" audio visualizer, shown by swiping up on the right
 * temple pad while the screen is dimmed. Hosted inside DualWebViewGroup's
 * mask overlay, so BinocularSbsLayout mirrors one logical scene to both lenses.
 *
 * The whole scene breathes at a coherent-breathing pace (4s in, 1.5s hold,
 * 6s out): the sky brightens on the inhale and softens on the exhale, and a
 * central orb swells in time. Music drives the rest:
 *  - sub-bass glows up from the bottom of the frame
 *  - bass pulses the central orb
 *  - mids ripple through three slow ribbon waves, each following its own
 *    slice of the spectrum (hues drift indigo to soft violet, seafoam to
 *    teal, powder blue to gentle lavender; quiet passages desaturate —
 *    never neon)
 *  - highs shimmer as stars; "air" sets how fast they twinkle
 *
 * A "breathe in / hold / breathe out" hint accompanies the first two cycles.
 * If audio capture is unavailable (no RECORD_AUDIO permission), the view
 * degrades gracefully into a pure breathing exercise with gentle synthetic
 * motion. If the attached audio session turns out to be silent, a watchdog
 * fails over to the global output mix (session 0) after 2.5 seconds.
 */
class BreathingVisualizerView(context: Context) : View(context) {

    companion object {
        private const val TAG = "BreathingVisualizer"

        // Coherent-breathing pace: 4s in · 1.5s hold · 6s out.
        private const val BREATH_IN_MS = 4000f
        private const val BREATH_HOLD_MS = 1500f
        private const val BREATH_OUT_MS = 6000f
        private const val BREATH_CYCLE_MS = BREATH_IN_MS + BREATH_HOLD_MS + BREATH_OUT_MS

        private const val FRAME_MIN_INTERVAL_MS = 33L // ~30fps
        private const val STAR_COUNT = 70

        // Band edges in Hz: sub-bass / bass / mids / highs / air.
        private val BAND_EDGES = floatArrayOf(20f, 60f, 250f, 2000f, 6000f, 16000f)
        private val BAND_GAIN = floatArrayOf(1.0f, 1.0f, 1.5f, 2.0f, 2.6f)
    }

    private var visualizer: Visualizer? = null
    private var audioLive = false
    private var attachedSessionId = -1
    private var triedMixFallback = false
    private var startUptimeMs = 0L

    // Smoothed band levels (0..1) and the raw per-callback values feeding them.
    private val bands = FloatArray(5)
    private val bandsRaw = FloatArray(5)

    // Per-band decaying peak for auto-gain: each band normalizes against the
    // loudest it has recently been, so quiet sources still animate fully.
    private val bandPeak = FloatArray(5) { 0.08f }

    @Volatile private var fftCallbackCount = 0L
    @Volatile private var lastMeaningfulFftMs = 0L
    @Volatile private var lastFftRawTotal = 0f
    private var framesSinceStatLog = 0

    private var startedAtMs = 0L
    private var lastFrameMs = 0L
    private var running = false

    private val stars: List<Star> = List(STAR_COUNT) {
        Star(
            x = Random.nextFloat(),
            y = 0.42f * Random.nextFloat(),
            size = 0.8f + Random.nextFloat() * 1.8f,
            twinklePhase = 6.2831855f * Random.nextFloat(),
            twinkleSpeed = 0.4f + Random.nextFloat() * 1.2f
        )
    }

    private val bgPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint()
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f
        letterSpacing = 0.18f
    }
    private val exitHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f
        letterSpacing = 0.1f
    }
    private val ribbonPath = Path()

    private var bgShaderKey = -1
    private var orbShaderKey = -1
    private var glowShaderKey = -1
    private var cachedOrbShader: RadialGradient? = null
    private var cachedGlowShader: RadialGradient? = null
    private var loggedZeroSize = false

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            // Self-heal: the mask overlay's custom layout pass never measures
            // children added after layout, so we can find ourselves at 0x0
            // even while VISIBLE. Size ourselves to the parent's bounds.
            if (width == 0 || height == 0) {
                val p = parent as? View
                if (p == null || p.width <= 0 || p.height <= 0) {
                    if (!loggedZeroSize) {
                        loggedZeroSize = true
                        DebugLog.w(TAG, "view AND parent unsized (parent=${p?.width}x${p?.height}) — requesting layout")
                    }
                } else {
                    measure(
                        View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(p.height, View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, p.width, p.height)
                    DebugLog.d(TAG, "self-sized to parent ${p.width}x${p.height}")
                }
                requestLayout()
                p?.requestLayout()
            }
            maybeFailoverToOutputMix()
            framesSinceStatLog++
            if (framesSinceStatLog >= 300) { // ~every 10s at 30fps
                framesSinceStatLog = 0
                DebugLog.d(
                    TAG,
                    "stats: session=$attachedSessionId captures=$fftCallbackCount rawTotal=$lastFftRawTotal " +
                        "bands=[${bands.joinToString(",") { String.format("%.2f", it) }}]"
                )
            }
            invalidate()
            postDelayed(this, FRAME_MIN_INTERVAL_MS)
        }
    }

    init {
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.BLACK)
    }

    private val ribbonHsv = FloatArray(3)

    private data class Star(
        val x: Float,
        val y: Float,
        val size: Float,
        val twinklePhase: Float,
        val twinkleSpeed: Float
    )

    fun start(audioSessionId: Int) {
        if (running) return
        running = true
        startedAtMs = SystemClock.uptimeMillis()
        startUptimeMs = startedAtMs
        bands.fill(0f)
        bandsRaw.fill(0f)
        bandPeak.fill(0.08f)
        fftCallbackCount = 0L
        lastMeaningfulFftMs = 0L
        triedMixFallback = false
        framesSinceStatLog = 0
        attachVisualizer(audioSessionId)
        removeCallbacks(frameTick)
        post(frameTick)
    }

    fun stop() {
        running = false
        removeCallbacks(frameTick)
        releaseVisualizer()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun attachVisualizer(sessionId: Int) {
        releaseVisualizer()
        audioLive = false
        val hasRecordPermission = runCatching {
            context.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!hasRecordPermission) {
            DebugLog.w(TAG, "RECORD_AUDIO not granted — breathing-only mode")
            return
        }
        for (sid in listOf(sessionId, 0).distinct()) {
            try {
                val viz = Visualizer(sid)
                viz.setCaptureSize(Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024))
                val rate = Visualizer.getMaxCaptureRate().coerceAtMost(20000)
                viz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft != null) processFft(fft, samplingRate)
                    }
                }, rate, false, true)
                viz.setEnabled(true)
                visualizer = viz
                audioLive = true
                attachedSessionId = sid
                DebugLog.d(TAG, "Visualizer attached to session $sid (rate=$rate)")
                return
            } catch (t: Throwable) {
                DebugLog.w(TAG, "Visualizer attach failed for session $sid: ${t.message}")
            }
        }
    }

    private fun releaseVisualizer() {
        visualizer?.let { v ->
            runCatching { v.setEnabled(false) }
            runCatching { v.release() }
        }
        visualizer = null
        audioLive = false
    }

    private fun processFft(fft: ByteArray, samplingRateMilliHz: Int) {
        val n = fft.size / 2
        if (n < 8) return
        val srHz = samplingRateMilliHz / 1000f
        val binHz = srHz / fft.size
        val sums = FloatArray(5)
        val counts = IntArray(5)
        for (bin in 1 until n) {
            val re = fft[bin * 2].toFloat()
            val im = fft[bin * 2 + 1].toFloat()
            val mag = hypot(re, im) / 181f
            val freq = bin * binHz
            var b = -1
            for (k in 0 until 5) {
                if (freq >= BAND_EDGES[k] && freq < BAND_EDGES[k + 1]) {
                    b = k
                    break
                }
            }
            if (b >= 0) {
                sums[b] += mag
                counts[b] += 1
            }
        }
        var rawTotal = 0f
        for (k in 0 until 5) {
            val avg = if (counts[k] > 0) sums[k] / counts[k] else 0f
            val raw = (sqrt(avg) * BAND_GAIN[k]).coerceIn(0f, 1f)
            rawTotal += raw
            // Auto-gain: track a slowly decaying peak per band and normalize
            // against it, with a floor so silence doesn't divide by ~zero.
            bandPeak[k] = max(bandPeak[k] * 0.9977f, raw).coerceAtLeast(0.05f)
            bandsRaw[k] = (raw / bandPeak[k]).coerceIn(0f, 1f)
        }
        fftCallbackCount++
        lastFftRawTotal = rawTotal
        if (rawTotal > 0.02f) lastMeaningfulFftMs = SystemClock.uptimeMillis()
    }

    /**
     * Watchdog: some player sessions accept a Visualizer but never deliver
     * meaningful FFT energy. 2.5s after attach, if nothing audible has come
     * through, re-attach to session 0 (the global output mix). One shot only.
     */
    private fun maybeFailoverToOutputMix() {
        if (!running || !audioLive || triedMixFallback) return
        if (attachedSessionId == 0) {
            triedMixFallback = true
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - startUptimeMs < 2500) return
        if (lastMeaningfulFftMs == 0L || now - lastMeaningfulFftMs >= 2000) {
            triedMixFallback = true
            DebugLog.w(
                TAG,
                "session $attachedSessionId silent after $fftCallbackCount captures " +
                    "(rawTotal=$lastFftRawTotal) — failing over to output mix (session 0)"
            )
            attachVisualizer(0)
        }
    }

    private fun smoothBands() {
        for (k in 0 until 5) {
            var target = bandsRaw[k]
            if (!audioLive) {
                // No audio capture: gentle synthetic drift so the scene still lives.
                val t = (SystemClock.uptimeMillis() - startedAtMs) / 1000f
                target = (sin((k * 0.11f + 0.37f) * t + k * 1.7f) * 0.5f + 0.5f) * 0.06f + 0.1f
            }
            val cur = bands[k]
            bands[k] = if (target > cur) cur + (target - cur) * 0.5f else cur + (target - cur) * 0.1f
        }
    }

    /** 0 = fully exhaled, 1 = fully inhaled, eased with a half-cosine. */
    private fun breathLevel(cycleMs: Float): Float {
        if (cycleMs < BREATH_IN_MS) {
            val p = cycleMs / BREATH_IN_MS
            return 0.5f - cos(3.1415927f * p) * 0.5f
        }
        if (cycleMs < BREATH_IN_MS + BREATH_HOLD_MS) return 1f
        val p = (cycleMs - BREATH_IN_MS - BREATH_HOLD_MS) / BREATH_OUT_MS
        return 0.5f + cos(3.1415927f * p) * 0.5f
    }

    private fun breathPhaseLabel(cycleMs: Float): String =
        if (cycleMs < BREATH_IN_MS) "breathe in"
        else if (cycleMs < BREATH_IN_MS + BREATH_HOLD_MS) "hold"
        else "breathe out"

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        smoothBands()
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - startedAtMs).toFloat()
        val t = elapsed / 1000f
        val cycleMs = elapsed % BREATH_CYCLE_MS
        val breath = breathLevel(cycleMs)
        val energy = (bands[1] * 0.4f + bands[2] * 0.35f + bands[3] * 0.25f).coerceIn(0f, 1f)

        // Night-sky gradient: brightens with the inhale, warms with energy.
        val topColor = lerpColor(
            lerpColor(0xFF030B16.toInt(), 0xFF0A2238.toInt(), breath * 0.8f),
            0xFF1B1336.toInt(), energy * 0.45f
        )
        val bottomColor = lerpColor(0xFF02060C.toInt(), 0xFF06121F.toInt(), breath * 0.6f)
        val bgKey = (24 * energy).toInt() + (breath * 24).toInt() * 64
        if (bgKey != bgShaderKey) {
            bgShaderKey = bgKey
            bgPaint.shader = LinearGradient(0f, 0f, 0f, h, topColor, bottomColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Sub-bass: a soft glow rising from the bottom of the frame.
        val subLevel = bands[0]
        val glowRadius = h * (0.3f * subLevel + 0.2f + 0.06f * breath)
        val glowKey = (glowRadius / 6f).toInt()
        if (glowKey != glowShaderKey || cachedGlowShader == null) {
            glowShaderKey = glowKey
            cachedGlowShader = RadialGradient(
                w / 2f, h + glowRadius * 0.25f, glowRadius.coerceAtLeast(1f),
                intArrayOf(0x8C1E4FA8.toInt(), 0x3327588C, 0),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        glowPaint.shader = cachedGlowShader
        glowPaint.alpha = (120 + 135 * subLevel).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, h * 0.45f, w, h, glowPaint)

        // Highs: shimmering stars; "air" sets the twinkle rate.
        val starBoost = bands[3] * 0.65f + 0.35f
        val twinkleRate = bands[4] * 3.0f + 1f
        for (s in stars) {
            val tw = sin(s.twinklePhase + s.twinkleSpeed * t * twinkleRate) * 0.5f + 0.5f
            val a = (191.25f * tw * starBoost * (breath * 0.45f + 0.55f)).toInt()
            if (a > 4) {
                starPaint.color = Color.argb(a.coerceIn(0, 255), (30 * tw).toInt() + 205, 228, 255)
                canvas.drawCircle(s.x * w, s.y * h, s.size * (0.5f * tw + 0.8f), starPaint)
            }
        }

        // Mids: three ribbon waves, each following its own spectrum slice.
        // Hues: indigo->soft violet, seafoam->teal, powder blue->gentle lavender.
        drawRibbon(canvas, w, h, 0.55f, t * 0.65f, bands[1] * 0.6f + bands[2] * 0.4f, 232f, 268f, 2.6f)
        drawRibbon(canvas, w, h, 0.62f, t * 0.5f + 2.1f, bands[2], 162f, 184f, 2.2f)
        drawRibbon(canvas, w, h, 0.69f, t * 0.4f + 4.2f, bands[3], 205f, 252f, 1.8f)

        // Bass: the central orb, swelling with the breath.
        val cx = w / 2f
        val cy = h * 0.4f
        val orbR = 0.13f * h * (0.42f * breath + 0.78f + bands[1] * 0.16f)
        val coreColor = lerpColor(0xFF5AD7FF.toInt(), 0xFF9D8CFF.toInt(), energy * 0.5f)
        val orbKey = (orbR / 4f).toInt() * 64 + (16 * energy).toInt()
        if (orbKey != orbShaderKey || cachedOrbShader == null) {
            orbShaderKey = orbKey
            cachedOrbShader = RadialGradient(
                cx, cy, (orbR * 2.2f).coerceAtLeast(1f),
                intArrayOf(
                    withAlpha(coreColor, 230),
                    withAlpha(coreColor, 102),
                    withAlpha(coreColor, 20),
                    0
                ),
                floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
        }
        orbPaint.shader = cachedOrbShader
        canvas.drawCircle(cx, cy, 2.2f * orbR, orbPaint)
        orbRingPaint.color = withAlpha(coreColor, (90 + 100 * breath).toInt().coerceIn(0, 255))
        canvas.drawCircle(cx, cy, orbR, orbRingPaint)

        // Breathing hint for the first two cycles, fading out over the last quarter.
        val cyclesDone = elapsed / BREATH_CYCLE_MS
        if (cyclesDone < 2f) {
            val fade = if (cyclesDone > 1.75f) (2f - cyclesDone) / 0.25f else 1f
            hintPaint.color = withAlpha(0xFFBFE8F5.toInt(), (165 * fade).toInt())
            canvas.drawText(breathPhaseLabel(cycleMs), cx, cy + orbR + 30f, hintPaint)
        }

        // Exit hint for the first six seconds.
        if (elapsed < 6000f) {
            val fade = if (elapsed > 4500f) (6000f - elapsed) / 1500f else 1f
            exitHintPaint.color = withAlpha(0xFF9DC7D8.toInt(), (130 * fade).toInt())
            canvas.drawText("swipe down to return", cx, h - 14f, exitHintPaint)
        }

        // Exhale scrim: gently darken the whole scene as the breath releases.
        scrimPaint.color = Color.argb((70 * (1f - breath)).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, scrimPaint)
    }

    private fun drawRibbon(
        canvas: Canvas,
        w: Float,
        h: Float,
        yFrac: Float,
        phase: Float,
        level: Float,
        hueCalm: Float,
        hueLively: Float,
        baseWidth: Float
    ) {
        val lv = level.coerceIn(0f, 1f)
        val amp = (0.085f * lv + 0.012f) * h
        val baseY = h * yFrac
        ribbonPath.reset()
        for (i in 0..26) {
            val x = i * w / 26
            val k = i / 26f
            val y = sin(4.2f * k + phase) * amp * 0.85f + baseY +
                amp * 0.4f * sin(9.1f * k - 1.7f * phase)
            if (i == 0) ribbonPath.moveTo(x, y) else ribbonPath.lineTo(x, y)
        }
        // Hue drifts from its calm shade toward its lively shade with level;
        // saturation and value rise with it (quiet = desaturated, never neon).
        ribbonHsv[0] = hueCalm + (hueLively - hueCalm) * lv
        ribbonHsv[1] = 0.25f * lv + 0.4f
        ribbonHsv[2] = 0.3f * lv + 0.7f
        val alpha = (88 + 70 * lv).toInt().coerceIn(0, 255)
        ribbonPaint.color = (Color.HSVToColor(ribbonHsv) and 0x00FFFFFF) or (alpha shl 24)
        ribbonPaint.strokeWidth = baseWidth + 2.2f * lv
        canvas.drawPath(ribbonPath, ribbonPaint)
    }

    private fun lerpColor(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
