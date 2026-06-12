package com.TapLinkX3.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * TapInsight boot/security splash (the "revamp", restored from the June-10
 * APK decompile after the rebuild reverted to the old TapLink intro).
 *
 * Binocular-safe: hosted inside DualWebViewGroup's lock overlay, so
 * BinocularSbsLayout mirrors one logical scene to both lenses.
 *
 * Scene: night-sky starfield with a faint constellation, an orbiting
 * three-ring "insight" system around the TAPINSIGHT wordmark (per-character
 * reveal) + "X3 · SEE DEEPER" tagline, HERMES / OPENCLAW status chips, a
 * typewriter "curiosity line" drawn from [CURIOSITY_LINES], a progress bar,
 * and the GLASSES ONLINE caption. Boot chime via SoundPool (load-then-play
 * with a 300ms fallback poke so a slow load can't swallow the chime).
 */
class BootIntroView(context: Context) : View(context) {

    companion object {
        private const val INTRO_MS = 3600L
        private const val BG_TOP = 0xFF05070D.toInt()
        private const val BG_BOTTOM = 0xFF000000.toInt()
        private const val CYAN = 0xFF00E5FF.toInt()
        private const val GREEN = 0xFF69F0AE.toInt()
        private const val AMBER = 0xFFFFD166.toInt()
        private const val BLUE = 0xFF4C7DFF.toInt()
        private const val WORDMARK = "TAPINSIGHT"
        private const val TAGLINE = "X3 · SEE DEEPER"

        /**
         * The quote library: one line is picked at random per boot and
         * typed out under the status chips. Short, true, wonder-first —
         * no attributions to keep them honest and the line compact.
         */
        private val CURIOSITY_LINES = listOf(
            "Some starlight reaching you tonight left before Rome fell.",
            "Your brain runs on about 20 watts — dimmer than a bulb.",
            "There are more chess games than atoms in the universe.",
            "A day on Venus lasts longer than its year.",
            "Sharks are older than trees.",
            "You are made of atoms forged inside dying stars.",
            "Honey from pharaohs' tombs is still edible.",
            "Octopuses taste the world through their arms.",
            "The Eiffel Tower grows taller every summer.",
            "Sound travels four times faster underwater.",
            "A teaspoon of neutron star outweighs a mountain.",
            "Half the oxygen you breathe comes from the ocean.",
            // ── library expansion (user, June 2026) ────────────────────
            "Jupiter's Great Red Spot has raged for centuries.",
            "Sunlight takes eight minutes to reach your eyes.",
            "Earth has more trees than the Milky Way has stars.",
            "Bananas are very slightly radioactive.",
            "The Moon drifts four centimeters farther away every year.",
            "Cleopatra lived closer to the Moon landing than to the Great Pyramid.",
            "Lightning is five times hotter than the surface of the Sun.",
            "Tardigrades have survived the open vacuum of space.",
            "Oxford University is older than the Aztec Empire.",
            "Every second the Sun fuses 600 million tons of hydrogen.",
            "On a clear night your eye can respond to a single photon.",
            "Eight thousand years ago the Sahara was green.",
            "An octopus has three hearts and blue blood.",
            "Saturn is so light it would float in water.",
            "Less time separates you from T. rex than T. rex from Stegosaurus.",
            "Trillions of solar neutrinos pass through you every second.",
            "A single cloud can weigh more than a million pounds.",
            "Your heart will beat about three billion times.",
            "Mushrooms are closer kin to animals than to plants.",
            "Andromeda is rushing toward us at 110 kilometers a second.",
            "Mercury, the planet, is slowly shrinking.",
            "Antarctica is the largest desert on Earth.",
            "Some turtles can breathe through their backsides.",
            "Scaled down, Earth is smoother than a billiard ball.",
            "A photon takes thousands of years to escape the Sun's core.",
            "Your body glows — just too faintly to see.",
            "The Atlantic widens about as fast as your fingernails grow.",
            "Venus spins backwards.",
            "Petrichor: the smell of rain landing on dry earth.",
            "Hummingbirds can fly backwards.",
            "Time runs faster at your head than at your feet.",
            "There are more stars than grains of sand on every beach on Earth.",
            "Uncoiled, your DNA would stretch to the Sun and back many times.",
            "A whale's song can cross an entire ocean.",
            "Saturn's rings may be younger than sharks.",
            "The Moon has quakes — moonquakes.",
            "Butterflies taste with their feet.",
            "Wombats produce cube-shaped droppings."
        )
    }

    var onComplete: (() -> Unit)? = null

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val bootSoundPool: SoundPool =
        SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    private var bootSoundId = 0
    private var bootSoundLoaded = false
    private var bootSoundRequested = false
    private var bootSoundPlayed = false
    private var completed = false
    private val finishRunnable = Runnable { fadeOutAndFinish() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    /** Normalized star: x/y in 0..1, radius in px, twinkle phase 0..2π. */
    private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float)

    private val stars: List<Star>
    private val constellation: List<Star>
    private val curiosityLine: String = CURIOSITY_LINES.random()

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        val rng = Random(System.currentTimeMillis())
        stars = List(70) {
            Star(
                x = rng.nextFloat(),
                y = rng.nextFloat() * 0.92f,
                r = rng.nextFloat() * 1.6f + 0.6f,
                phase = rng.nextFloat() * 6.2831855f
            )
        }
        // A faint 6-star constellation in the upper sky, linked left→right.
        constellation = stars.filter { it.y < 0.34f }
            .shuffled(rng)
            .take(6)
            .sortedBy { it.x }
    }

    fun start() {
        if (animator != null) return
        playBootChime()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INTRO_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        removeCallbacks(finishRunnable)
        postDelayed(finishRunnable, INTRO_MS)
    }

    private fun fadeOutAndFinish() {
        animate()
            .alpha(0f)
            .setDuration(360L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { finish() }
            .start()
    }

    private fun finish() {
        if (completed) return
        completed = true
        animator?.cancel()
        animator = null
        onComplete?.invoke()
    }

    fun release() {
        animate().cancel()
        removeCallbacks(finishRunnable)
        animator?.cancel()
        animator = null
        runCatching { bootSoundPool.release() }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun playBootChime() {
        if (bootSoundPlayed) return
        bootSoundRequested = true
        if (bootSoundId == 0) {
            bootSoundPool.setOnLoadCompleteListener { _, sampleId, status ->
                if (sampleId != bootSoundId) return@setOnLoadCompleteListener
                bootSoundLoaded = status == 0
                if (!bootSoundLoaded) {
                    Log.w("BootIntro", "boot_chime load failed status=$status")
                } else if (bootSoundRequested) {
                    playLoadedBootChime()
                }
            }
            bootSoundId = bootSoundPool.load(context, R.raw.boot_chime, 1)
        }
        if (bootSoundLoaded) {
            playLoadedBootChime()
        } else {
            // Fallback poke: if the load-complete callback raced or was
            // dropped, try again shortly — playLoadedBootChime() guards
            // against double-plays.
            postDelayed({
                if (bootSoundRequested && !bootSoundPlayed) playLoadedBootChime()
            }, 300L)
        }
    }

    private fun playLoadedBootChime() {
        if (bootSoundPlayed || bootSoundId == 0) return
        val streamId = runCatching {
            bootSoundPool.play(bootSoundId, 1f, 1f, 1, 0, 1f)
        }.onFailure {
            Log.w("BootIntro", "boot_chime play failed", it)
        }.getOrDefault(0)
        if (streamId != 0) {
            bootSoundPlayed = true
            Log.d("BootIntro", "boot_chime played")
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drawBackground(canvas, w, h)
        drawStarfield(canvas, w, h)
        val cx = w / 2f
        val cy = h * 0.44f
        val appear = ease(segment(progress, 0f, 0.26f))
        val sweep = ease(segment(progress, 0.14f, 0.8f))
        val settle = ease(segment(progress, 0.7f, 1f))
        val pulse = sin(progress * 3.1415927f * 5f) * 0.5f + 0.5f
        drawCenterSystem(canvas, cx, cy, min(w, h), appear, sweep, pulse)
        drawStatus(canvas, cx, h, appear, settle)
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        fill.shader = LinearGradient(0f, 0f, 0f, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        // Soft cyan glow behind the center system.
        fill.shader = RadialGradient(
            w * 0.5f, h * 0.42f, 0.52f * min(w, h),
            0x2E00E5FF, 0x00000000, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    private fun drawStarfield(canvas: Canvas, w: Float, h: Float) {
        val appear = ease(segment(progress, 0f, 0.3f))
        if (appear <= 0f) return
        for (s in stars) {
            val tw = (sin(progress * 6.2832f * 1.6f + s.phase) * 0.5f + 0.5f) * 0.55f + 0.45f
            fill.color = withAlpha(Color.WHITE, (appear * tw * 170).toInt())
            canvas.drawCircle(s.x * w, s.y * h, s.r, fill)
        }
        if (constellation.size >= 2) {
            val linkAlpha = (70 * appear).toInt()
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 1f
            stroke.color = withAlpha(CYAN, linkAlpha)
            for (i in 0 until constellation.size - 1) {
                val a = constellation[i]
                val b = constellation[i + 1]
                canvas.drawLine(a.x * w, a.y * h, b.x * w, b.y * h, stroke)
            }
            for (s in constellation) {
                fill.color = withAlpha(CYAN, (150 * appear).toInt())
                canvas.drawCircle(s.x * w, s.y * h, s.r + 0.8f, fill)
            }
        }
    }

    private fun drawCenterSystem(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        appear: Float,
        sweep: Float,
        pulse: Float
    ) {
        val radius = size * 0.165f
        val ringAlpha = (210 * appear).toInt()

        // Three tilted electron-style orbits with riders.
        val orbitTilts = floatArrayOf(-28f, 28f, 90f)
        val orbitColors = intArrayOf(CYAN, AMBER, GREEN)
        val rx = radius * 1.62f
        val ry = radius * 0.52f
        for (k in orbitTilts.indices) {
            canvas.save()
            canvas.rotate(orbitTilts[k], cx, cy)
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 1.4f
            stroke.color = withAlpha(orbitColors[k], (95 * appear).toInt())
            canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), stroke)
            val theta = Math.toRadians(
                progress.toDouble() * 360.0 * (k * 0.35 + 1.15) + k * 120.0
            )
            val ex = cos(theta).toFloat() * rx + cx
            val ey = sin(theta).toFloat() * ry + cy
            fill.color = withAlpha(orbitColors[k], (235 * appear).toInt())
            canvas.drawCircle(ex, ey, 4.5f, fill)
            canvas.restore()
        }

        // Pulsing cyan ring + green sweep arc.
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 2.5f
        stroke.color = withAlpha(CYAN, ringAlpha)
        canvas.drawCircle(cx, cy, (0.95f + 0.04f * pulse) * radius, stroke)
        stroke.strokeWidth = 7f
        stroke.color = withAlpha(GREEN, ringAlpha)
        val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, -90f, sweep * 360f, false, stroke)

        // Dark core disc behind the wordmark.
        fill.color = withAlpha(0xFF06131B.toInt(), (235 * appear).toInt())
        canvas.drawCircle(cx, cy, radius * 0.62f, fill)

        // TAPINSIGHT — per-character reveal, left-aligned manual advance so
        // the reveal doesn't shift the centered block.
        text.textSize = 0.052f * size
        text.letterSpacing = 0.1f
        val total = text.measureText(WORDMARK)
        var x = cx - total / 2f
        val reveal = 10 * ease(segment(progress, 0.18f, 0.62f))
        val oldAlign = text.textAlign
        text.textAlign = Paint.Align.LEFT
        for (i in WORDMARK.indices) {
            val charProgress = (reveal - i).coerceIn(0f, 1f)
            text.color = withAlpha(Color.WHITE, (appear * charProgress * 255).toInt())
            val s = WORDMARK[i].toString()
            canvas.drawText(s, x, cy - 0.012f * size, text)
            x += text.measureText(s)
        }
        text.textAlign = oldAlign
        text.letterSpacing = 0f

        text.color = withAlpha(CYAN, (225 * appear).toInt())
        text.textSize = 0.03f * size
        canvas.drawText(TAGLINE, cx, cy + 0.05f * size, text)
    }

    private fun drawStatus(canvas: Canvas, cx: Float, h: Float, appear: Float, settle: Float) {
        val baseY = h * 0.7f
        drawChip(canvas, cx - 72f, baseY, "HERMES", GREEN, appear)
        drawChip(canvas, cx + 72f, baseY, "OPENCLAW", BLUE, appear)

        // Typewriter curiosity line with blinking block cursor.
        val typed = ease(segment(progress, 0.3f, 0.86f))
        val chars = (curiosityLine.length * typed).toInt().coerceIn(0, curiosityLine.length)
        if (chars > 0) {
            text.color = withAlpha(0xFFFFE8B8.toInt(), (235 * appear).toInt())
            text.textSize = 14.5f
            val cursor =
                if (chars < curiosityLine.length && (progress * 6f).toInt() % 2 == 0) "▌" else ""
            canvas.drawText(curiosityLine.take(chars) + cursor, cx, h * 0.795f, text)
        }

        // Progress bar.
        val barW = min(width * 0.62f, 340f)
        val left = cx - barW / 2f
        val top = h * 0.862f
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.5f
        stroke.color = withAlpha(CYAN, (160 * appear).toInt())
        canvas.drawRoundRect(RectF(left, top, left + barW, top + 8f), 4f, 4f, stroke)
        fill.color = withAlpha(GREEN, (235 * appear).toInt())
        canvas.drawRoundRect(
            RectF(left + 2f, top + 2f, left + 2f + (barW - 4f) * settle, top + 8f - 2f),
            3f, 3f, fill
        )

        text.color = withAlpha(0xFFB9D4E0.toInt(), (190 * appear).toInt())
        text.textSize = 12f
        canvas.drawText("GLASSES ONLINE", cx, h * 0.925f, text)
    }

    private fun drawChip(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        label: String,
        color: Int,
        appear: Float
    ) {
        val rect = RectF(cx - 108f / 2f, cy - 30f / 2f, cx + 108f / 2f, cy + 30f / 2f)
        fill.color = withAlpha(0xFF071013.toInt(), (220 * appear).toInt())
        canvas.drawRoundRect(rect, 9f, 9f, fill)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.5f
        stroke.color = withAlpha(color, (220 * appear).toInt())
        canvas.drawRoundRect(rect, 9f, 9f, stroke)
        text.color = withAlpha(Color.WHITE, (240 * appear).toInt())
        text.textSize = 13f
        canvas.drawText(label, cx, cy + 5f, text)
    }

    private fun segment(p: Float, start: Float, end: Float): Float =
        ((p - start) / (end - start)).coerceIn(0f, 1f)

    private fun ease(t: Float): Float = t * t * (3f - 2f * t)

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0xFFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
