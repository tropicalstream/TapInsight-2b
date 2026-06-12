package com.TapLinkX3.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.PowerManager
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.TapLink.app.media.MediaFileInterceptor
import com.TapLink.app.media.MediaLibraryBridge
import java.util.concurrent.atomic.AtomicReference
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.math.pow
import org.json.JSONObject

@SuppressLint("ClickableViewAccessibility")
class DualWebViewGroup
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        ViewGroup(context, attrs, defStyleAttr) {

    // Custom WebView to expose protected scroll methods
    private inner class InternalWebView(context: Context) : WebView(context) {
        fun getHorizontalScrollRange() = super.computeHorizontalScrollRange()
        fun getHorizontalScrollExtent() = super.computeHorizontalScrollExtent()
        fun getHorizontalScrollOffset() = super.computeHorizontalScrollOffset()
        fun getVerticalScrollRange() = super.computeVerticalScrollRange()
        fun getVerticalScrollExtent() = super.computeVerticalScrollExtent()
        fun getVerticalScrollOffset() = super.computeVerticalScrollOffset()
    }

    private val PREFS_NAME = "TapLinkPrefs"
    private val KEY_WINDOWS_STATE = "saved_windows_state"
    private val KEY_BROWSER_SHOW_SYSTEM_INFO = "browser_show_system_info"
    private val KEY_OUTDOOR_BRIGHTNESS_ACTIVE = "outdoorBrightnessActive"
    private val KEY_PRE_OUTDOOR_BRIGHTNESS_PROGRESS = "preOutdoorBrightnessProgress"
    private val sharedConfigPrefs =
            context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    private val sharedConfigListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_BROWSER_SHOW_SYSTEM_INFO) {
                    post {
                        updateSystemInfoBarVisibility()
                        requestLayout()
                        invalidate()
                    }
                }
            }

    private data class BrowserWindow(
            val id: String = java.util.UUID.randomUUID().toString(),
            val webView: InternalWebView,
            var thumbnail: Bitmap? = null,
            var title: String = "New Tab"
    )

    private data class ScrollMetrics(
            val rangeX: Int,
            val extentX: Int,
            val offsetX: Int,
            val rangeY: Int,
            val extentY: Int,
            val offsetY: Int
    )

    private data class ExternalScrollMetrics(
            val rangeX: Int,
            val extentX: Int,
            val offsetX: Int,
            val rangeY: Int,
            val extentY: Int,
            val offsetY: Int,
            val timestamp: Long
    )

    private val windows = java.util.concurrent.CopyOnWriteArrayList<BrowserWindow>()
    private var activeWindowId: String? = null

    interface WindowCallback {
        fun onWindowCreated(webView: WebView)
        fun onWindowSwitched(webView: WebView)
    }

    var windowCallback: WindowCallback? = null

    private var webView: InternalWebView

    val webViewsContainer: FrameLayout =
            FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

    // REFACTORED: rightEyeView no longer needed - single viewport mode
    // BinocularSbsLayout now handles the binocular SBS rendering
    private val rightEyeView: SurfaceView = SurfaceView(context)

    val dialogContainer: FrameLayout =
            FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(640, 480) // Full left eye size
                setBackgroundColor(Color.parseColor("#CC000000")) // Semi-transparent black
                visibility = View.GONE
                isClickable = true
                isFocusable = true
                elevation = 2000f
            }
    private var customKeyboard: CustomKeyboardView? = null
    private var bitmap: Bitmap? = null

    private var velocityTracker: android.view.VelocityTracker? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshInterval = 16L // ~60fps for smooth mirroring
    // While the screen is masked the projector is off, so the refresh
    // runnable has nothing to draw — its only remaining job is the
    // periodic scrollbar-visibility check inside captureLeftEyeContent,
    // and that check self-rate-limits to once per second. Running the
    // outer loop any faster than that just churns the main thread and
    // measurably starves the audio-decoder thread on the X3 Pro
    // (manifesting as ~15-second skips during local-file playback in
    // dim mode). 1 Hz is the slowest rate that still keeps the
    // scrollbar self-check eligible to run every cycle.
    private val maskedRefreshIntervalMs = 1000L
    // When media is playing AND the screen is masked we don't even
    // need the scrollbar check (no scrollbars are visible behind the
    // mask), so we drop further still. This is the single most
    // important knob for clean dim-mode playback.
    private val maskedMediaRefreshIntervalMs = 2000L
    private var lastCaptureTime = 0L
    private var lastScrollBarCheckTime = 0L
    private val scrollBarVisibilityThrottleMs = 50L
    private val MIN_CAPTURE_INTERVAL = 16L // Cap at ~60fps
    private var lastCursorUpdateTime = 0L
    private val CURSOR_UPDATE_INTERVAL = 16L // 60fps cap for cursor updates
    private var lastScrollBarInteractionTime = 0L
    private val scrollBarHoldMs = 1200L
    private var lastHorzScrollableAt = 0L
    private var lastVertScrollableAt = 0L
    private var scrollBarMemoryUrl: String? = null
    private var stickyHorzScrollable = false
    private var stickyVertScrollable = false
    private var externalScrollMetrics: ExternalScrollMetrics? = null
    private val externalScrollMetricsStaleMs = 600000L // 10 minutes
    private var isMediaPlaying = false
    private var lastMediaPlayingAt = 0L
    private var lastMediaInteractionTime = 0L
    private val mediaScrollFreezeMs = 1500L
    private var youtubeCssFullModeActive = false
    private val mediaStateByWindowId = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val mediaLastPlayedAtByWindowId = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var nativeTapRadioPlaying = false
    private var nativeTapRadioLastActiveAt = 0L
    private val youtubeMediaTeardownScript =
            """
            (function() {
                try {
                    document.querySelectorAll('video, audio').forEach(function(el) {
                        try {
                            el.pause();
                            el.autoplay = false;
                            el.muted = true;
                            el.currentTime = 0;
                            el.removeAttribute('src');
                            el.load();
                        } catch (inner) {}
                    });
                } catch (outer) {}
            })();
            """.trimIndent()

    // Idle detection for power saving
    private var lastUserInteractionTime = 0L
    private val idleThresholdMs = 5000L // 5 seconds before considered idle
    private val idleRefreshIntervalMs = 100L // ~10fps when idle

    private lateinit var leftSystemInfoView: SystemInfoView

    lateinit var leftNavigationBar: View
    private val navBarHeightPx = 32.dp()
    private val toggleBarWidthPx = 32.dp()
    private val toggleButtonSizePx = toggleBarWidthPx
    // HUD/chat lane reserved at the top of the browser. Dynamic so the
    // double-tap "roll up" can drop it to 0 and let the WebView fill the
    // screen from the very top; restored when the HUD rolls back down.
    private val hudLaneReservePx = 136.dp()
    @Volatile
    private var hudLaneReserved = true
    private val unipanelTopReservePx: Int
        get() = if (hudLaneReserved) hudLaneReservePx else 0

    val keyboardContainer: FrameLayout =
            FrameLayout(context).apply {
                val containerWidth = 640 - toggleBarWidthPx
                layoutParams =
                        FrameLayout.LayoutParams(
                                        containerWidth,
                                        FrameLayout.LayoutParams.WRAP_CONTENT
                                )
                                .apply {
                                    leftMargin = toggleBarWidthPx
                                    gravity = Gravity.TOP or Gravity.START
                                }
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
            }
    private val buttonFeedbackDuration = 200L
    var lastCursorX = 0f
    var lastCursorY = 0f

    private var anchoredGestureActive = false
    private var anchoredTarget = 0 // 0: None, 1: Keyboard, 2: Bookmarks, 3: Menu
    private var anchoredTouchStartX = 0f
    private var anchoredTouchStartY = 0f
    private var lastAnchoredY = 0f
    private var isAnchoredDrag = false
    private val ANCHORED_TOUCH_SLOP = 10f

    lateinit var leftToggleBar: View
    var progressBar: android.widget.ProgressBar =
            android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
                    .apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 4)
                        progressDrawable.setTint(Color.BLUE)
                        max = 100
                        visibility = View.GONE
                        elevation = 200f // Ensure it's above other views
                    }
    private var btnShowNavBars: ImageButton =
            ImageButton(context).apply {
                layoutParams =
                        FrameLayout.LayoutParams(toggleButtonSizePx, toggleButtonSizePx).apply {
                            gravity = Gravity.BOTTOM or Gravity.END
                            rightMargin = 8
                            bottomMargin = 8
                        }
                setImageResource(R.drawable.ic_visibility_on)
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(8, 8, 8, 8)
                alpha = 1.0f
                visibility = View.GONE
                elevation = 2000f
                setOnClickListener {
                    setScrollMode(false)
                    setNavBarsHidden(false)
                }
            }

    @Volatile private var isRefreshing = false
    private val refreshLock = Any()

    private var isDesktopMode = false
    private var currentWebZoom = 1.0f
    private var isHoveringModeToggle = false
    private var isHoveringDashboardToggle = false
    private var isHoveringBookmarksMenu = false

    private lateinit var leftBookmarksView: BookmarksView
    private lateinit var chatView: ChatView

    var navigationListener: NavigationListener? = null
    var linkEditingListener: LinkEditingListener? = null

    private var isBookmarkEditing = false

    private var mobileUserAgent: String
    private var desktopUserAgent: String = ""

    private val verticalScrollFraction = 0.25f // Scroll vertically by 25% of the viewport per tap

    private var isHoveringZoomIn = false
    private var isHoveringZoomOut = false
    private var isHoveringWindowsToggle = false
    private var windowsButton: FontIconView? = null

    private var fullScreenTapDetector: GestureDetector =
            GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent): Boolean {
                            // Always accept the initial down event so we can track the full gesture
                            return fullScreenOverlayContainer.visibility == View.VISIBLE
                        }

                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            // Controls visibility is now managed by dispatchFullScreenOverlayTouch
                            // in MainActivity, which handles button hit-testing first.
                            // Just consume the event here to prevent propagation.
                            return fullScreenOverlayContainer.visibility == View.VISIBLE
                        }
                    }
            )

    var isAnchored = false
        set(value) {
            field = value
            updateRefreshRate()
        }
    private var isHoveringAnchorToggle = false

    private val bitmapLock = Any()
    private var settingsMenu: View? = null
    private var isSettingsVisible = false

    interface DualWebViewGroupListener {
        fun onCursorPositionChanged(x: Float, y: Float, isVisible: Boolean)
    }

    interface MaskToggleListener {
        fun onMaskTogglePressed()
    }

    interface AnchorToggleListener {
        fun onAnchorTogglePressed()
    }

    interface FullscreenListener {
        fun onEnterFullscreen()
        fun onExitFullscreen()
    }

    var fullscreenListener: FullscreenListener? = null

    private var hideProgressBarRunnable: Runnable? = null

    fun updateLoadingProgress(progress: Int) {

        post {
            // Cancel any pending hide action whenever we get an update
            hideProgressBarRunnable?.let { removeCallbacks(it) }
            hideProgressBarRunnable = null

            if (progress < 100) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = progress
                progressBar.bringToFront()
                requestLayout() // Force layout update to position progress bar correctly
            } else {
                progressBar.progress = 100
                // Delay hiding to ensure user sees 100%
                hideProgressBarRunnable = Runnable { progressBar.visibility = View.GONE }
                postDelayed(hideProgressBarRunnable!!, 500)
            }
        }
    }

    private data class NavButton(
            val left: FontIconView,
            val right: FontIconView,
            var isHovered: Boolean = false
    )

    private fun FontIconView.configureToggleButton(iconRes: Int) {
        visibility = View.VISIBLE
        setText(iconRes)
        setBackgroundResource(R.drawable.nav_button_background)
        gravity = android.view.Gravity.CENTER
        setPadding(8, 8, 8, 8)
        alpha = 1.0f
        elevation = 2f
        stateListAnimator = null
    }

    private fun clearNavigationButtonStates() {
        navButtons.values.forEach { navButton ->
            navButton.isHovered = false
            navButton.left.isHovered = false
            navButton.right.isHovered = false
        }
    }

    // Properties for link editing
    lateinit var urlEditText: EditText
    private val urlFieldMinHeight = 56.dp()

    private var leftEditField: EditText
    private var rightEditField: EditText
    private var _isUrlEditing = false

    // Keyboard listener interface
    interface KeyboardListener {
        fun onShowKeyboard()
        fun onHideKeyboard()
    }

    var keyboardListener: KeyboardListener? = null
        set(value) {
            field = value
            if (::chatView.isInitialized) {
                chatView.keyboardListener = value
            }
        }

    var micListener: ChatView.MicListener? = null
        set(value) {
            field = value
            if (::chatView.isInitialized) {
                chatView.micListener = value
            }
        }

    private var navButtons: Map<String, NavButton>

    var listener: DualWebViewGroupListener? = null
    var maskToggleListener: MaskToggleListener? = null

    val leftEyeUIContainer =
            FrameLayout(context).apply {
                clipChildren = true
                clipToOutline = true

                setBackgroundColor(Color.TRANSPARENT) // Make sure background is transparent
            }

    fun isActiveWebView(webView: WebView): Boolean {
        return this.webView == webView
    }

    fun setChatMicActive(active: Boolean) {
        if (::chatView.isInitialized) {
            chatView.setMicActive(active)
        }
    }

    fun insertVoiceToChatInput(text: String) {
        if (::chatView.isInitialized) {
            chatView.insertVoiceText(text)
        }
    }

    fun pauseBackgroundMedia(sourceWebView: WebView) {
        windows.forEach { win ->
            if (win.webView != sourceWebView) {
                // Pause all media elements
                win.webView.evaluateJavascript(
                        "document.querySelectorAll('video, audio').forEach(function(e) { e.pause(); });",
                        null
                )
            }
        }
    }

    /**
     * Pause HTML5 <video> and <audio> elements across EVERY window, regardless
     * of the current URL. Used on explicit user navigation away from TapBrowser
     * (e.g. double-tap-return-to-chat) so background media can't keep playing
     * after the user has clearly switched away. YouTube-specific teardown lives
     * in [pauseYouTubeMediaAcrossAllWindows]; this is the non-YouTube sweep.
     */
    fun pauseAllWindowsMedia() {
        windows.forEach { win ->
            try {
                win.webView.post {
                    win.webView.evaluateJavascript(
                        "try { document.querySelectorAll('video, audio').forEach(function(e) { " +
                            "try { e.pause(); e.muted = true; } catch (err) {} " +
                            "}); } catch (err) {}",
                        null
                    )
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Silence every WebView's media while the boot intro / swipe-login is on
     * screen. The previous version of this method also flipped
     * `mediaPlaybackRequiresUserGesture` and called `WebView.onPause()` per
     * window — both of those caused regressions in bridge-driven audio
     * (text-reader TTS plays synthesized chunks via `<audio>.play()` from a
     * JavaScriptInterface callback, which has no user gesture and depends on
     * the WebView being fully alive). The minimal-impact version is just a
     * JS sweep that pauses + mutes every `<video>` / `<audio>` element
     * already in the DOM, plus a +1500ms re-sweep for pages whose media
     * elements weren't created yet at suspend time. This stops boot-time
     * autoplay without touching settings or WebView lifecycle state — so
     * post-boot TTS, bridge audio, and normal interactive playback all
     * resume cleanly. The Service-owned Gemini Live audio is unaffected.
     */
    fun suspendMediaForBoot() {
        bootMediaSuspended = true
        val sweep = "try { document.querySelectorAll('video, audio').forEach(function(e) { " +
            "try { e.pause(); e.autoplay = false; e.removeAttribute('autoplay'); } catch (err) {} " +
            "}); } catch (err) {}"
        fun runSweep() {
            if (!bootMediaSuspended) return
            windows.forEach { win ->
                try {
                    win.webView.post {
                        try { win.webView.evaluateJavascript(sweep, null) } catch (_: Exception) {}
                    }
                    mediaStateByWindowId[win.id] = false
                } catch (_: Exception) {}
            }
        }
        runSweep()
        // Re-sweep while the lock/intro is up. Restored YouTube/Spotify pages
        // can create their media element after the first pass, and a single
        // delayed sweep was not enough to stop audio leaking through the login
        // screen on cold restart.
        repeat(20) { attempt ->
            postDelayed({ runSweep() }, 500L * (attempt + 1))
        }
        nativeTapRadioPlaying = false
        updateMediaState(false)
    }

    /**
     * Counterpart to [suspendMediaForBoot]. With the minimal-impact version
     * above this is essentially a flag clear — nothing was actually paused at
     * the WebView level, so there's nothing to un-pause. Kept as a paired
     * method for symmetry and in case future suspend logic needs to undo
     * something.
     */
    fun resumeMediaAfterBoot() {
        bootMediaSuspended = false
    }

    private var bootMediaSuspended = false

    fun clearTrackedMediaPlayback() {
        mediaStateByWindowId.keys.forEach { id ->
            mediaStateByWindowId[id] = false
        }
        mediaLastPlayedAtByWindowId.clear()
        nativeTapRadioPlaying = false
        lastMediaPlayingAt = 0L
        lastMaskedDomTitle = null
        lastMaskedDomTitleUrl = null
        lastMaskedDomTitleAt = 0L
        updateMediaState(false)
        refreshMaskedNowPlaying()
        hideMediaControls()
    }

    fun pauseYouTubeMediaAcrossAllWindows(resetTracking: Boolean = true) {
        windows.forEach { win ->
            val url = win.webView.url.orEmpty()
            if (!url.contains("youtube.com", ignoreCase = true) &&
                            !url.contains("youtu.be", ignoreCase = true)
            ) {
                return@forEach
            }

            try {
                win.webView.stopLoading()
            } catch (_: Exception) {}

            win.webView.post { win.webView.evaluateJavascript(youtubeMediaTeardownScript, null) }
            mediaStateByWindowId[win.id] = false
        }

        if (resetTracking) {
            updateMediaState(anyTrackedMediaPlaying())
        }
    }

    private val fullScreenOverlayContainer =
            FrameLayout(context).apply {
                clipChildren = true
                clipToOutline = true // Ensure clipping to bounds
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                isClickable = true
                isFocusable = true
            }

    // Swipe-unlock boot screen overlay. Lives in the same clip parent as the
    // fullscreen-video overlay so BinocularSbsLayout mirrors it to both eyes,
    // and sits above everything (including fullscreen video) when visible.
    private val lockOverlayContainer =
            FrameLayout(context).apply {
                clipChildren = true
                clipToOutline = true
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                isClickable = true
                isFocusable = true
            }

    // UI scale factor (0.5 to 1.0) - controlled by screen size slider
    var uiScale = 1.0f

    private val fullScreenHiddenViews: List<View> by lazy {
        listOf(
                webViewsContainer,
                leftToggleBar,
                leftNavigationBar,
                keyboardContainer,
                leftSystemInfoView,
                urlEditText
        )
    }

    private val previousFullScreenVisibility = mutableMapOf<View, Int>()

    val leftEyeClipParent =
            FrameLayout(context).apply {
                // Force it to be exactly 640px wide and match height (or some fixed height).
                // Using MATCH_PARENT for height is common if you want the full vertical space.
                layoutParams = FrameLayout.LayoutParams(640, FrameLayout.LayoutParams.MATCH_PARENT)

                // Ensure that children are clipped to our bounds
                clipToPadding = true
                clipChildren = true
                setBackgroundColor(Color.BLACK) // Set background to ensure proper rendering
            }

    fun updateUiScale(scale: Float) {
        uiScale = scale

        // Set pivot point to center (320, 240) so scaling happens around the center
        leftEyeUIContainer.pivotX = 320f
        leftEyeUIContainer.pivotY = 240f
        leftEyeUIContainer.scaleX = scale
        leftEyeUIContainer.scaleY = scale

        fullScreenOverlayContainer.pivotX = 320f
        fullScreenOverlayContainer.pivotY = 240f
        fullScreenOverlayContainer.scaleX = scale
        fullScreenOverlayContainer.scaleY = scale

        // Ensure parent is not scaled so it acts as a fixed window
        leftEyeClipParent.scaleX = 1f
        leftEyeClipParent.scaleY = 1f

        updateUiTranslation()

        // Update scroll bar visibility based on scale and anchor mode
        updateScrollBarsVisibility()

        // Notify listener to refresh cursor scale visually
        listener?.onCursorPositionChanged(lastCursorX, lastCursorY, true)

        requestLayout()
        invalidate()
    }

    private fun updateUiTranslation() {
        if (isAnchored) {
            leftEyeUIContainer.translationX = 0f
            leftEyeUIContainer.translationY = 0f
            fullScreenOverlayContainer.translationX = 0f
            fullScreenOverlayContainer.translationY = 0f
            return
        }

        // Calculate max allowed translation based on current scale
        val maxTransX = 320f * (1f - uiScale)
        val maxTransY = 240f * (1f - uiScale)

        // Get saved progress (default 50)
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val xProgress = prefs.getInt("uiTransXProgress", 50)
        val yProgress = prefs.getInt("uiTransYProgress", 50)

        // Calculate translation
        val transX = ((xProgress - 50) / 50f) * maxTransX
        val transY = ((yProgress - 50) / 50f) * maxTransY

        leftEyeUIContainer.translationX = transX
        leftEyeUIContainer.translationY = transY

        fullScreenOverlayContainer.translationX = transX
        fullScreenOverlayContainer.translationY = transY

        // Update scroll bar thumb positions
        updateScrollBarThumbs(xProgress, yProgress)
        applyScrollbarTransform()
    }

    private fun normalizeWebViewsContainerAnchor() {
        (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
            val targetGravity = Gravity.TOP or Gravity.START
            if (p.gravity != targetGravity) {
                p.gravity = targetGravity
                webViewsContainer.layoutParams = p
                webViewsContainer.requestLayout()
            }
        }
    }

    /**
     * When the unipanel HUD/chat rolls up (double-tap), drop the reserved
     * top lane so the WebView grows up to the very top edge (y=0) and the
     * browser fills the whole screen. Rolling the HUD back down restores the
     * reserve. [unipanelTopReservePx] is read live by onMeasure/onLayout, so
     * we just flip the flag, re-apply the container's static topMargin, and
     * force a re-layout.
     */
    fun setHudLaneReserved(reserved: Boolean) {
        if (hudLaneReserved == reserved) return
        hudLaneReserved = reserved
        runCatching {
            (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
                p.topMargin = unipanelTopReservePx
                webViewsContainer.layoutParams = p
            }
        }
        requestLayout()
        invalidate()
    }

    fun stabilizeWebViewViewportAfterNavigation(
            targetWebView: WebView? = webView,
            resetVerticalScroll: Boolean = false
    ) {
        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("uiTransXProgress", 50)
                .putInt("uiTransYProgress", 50)
                .apply()
        updateUiTranslation()
        normalizeWebViewsContainerAnchor()

        targetWebView?.post {
            try {
                val nextY = if (resetVerticalScroll) 0 else targetWebView.scrollY
                targetWebView.scrollTo(0, nextY)
            } catch (_: Exception) {}
            try {
                targetWebView.evaluateJavascript(
                        """
                        (function() {
                            try {
                                var resetVertical = $resetVerticalScroll;
                                window.scrollTo(0, resetVertical ? 0 : window.scrollY);
                                if (document.documentElement) {
                                    document.documentElement.scrollLeft = 0;
                                    if (resetVertical) document.documentElement.scrollTop = 0;
                                }
                                if (document.body) {
                                    document.body.scrollLeft = 0;
                                    if (resetVertical) document.body.scrollTop = 0;
                                }
                                if (window.__taplinkScrollTarget) {
                                    window.__taplinkScrollTarget.scrollLeft = 0;
                                    if (resetVertical) window.__taplinkScrollTarget.scrollTop = 0;
                                }
                                if (window.__taplinkReportScroll) window.__taplinkReportScroll();
                                if (window.__taplinkWarmupScroll) window.__taplinkWarmupScroll();
                            } catch (e) {}
                        })();
                        """.trimIndent(),
                        null
                )
            } catch (_: Exception) {}
        }

        val delays = longArrayOf(0L, 120L, 350L, 900L, 1600L)
        delays.forEach { delayMs ->
            postDelayed({
                normalizeWebViewsContainerAnchor()
                targetWebView?.let { injectPageObservers(it) }
                updateScrollBarsVisibility()
            }, delayMs)
        }
    }

    fun recenterViewportForDashboard(targetWebView: WebView? = webView) {
        stabilizeWebViewViewportAfterNavigation(
                targetWebView = targetWebView,
                resetVerticalScroll = true
        )
    }

    private fun isWebViewScrollEnabled(): Boolean {
        // Always return true to ensure scrollbars ONLY scroll the WebView content
        // and never move the screen position (viewport panning).
        return true
    }

    private fun scrollPageHorizontal(delta: Int) {
        if (isWebViewScrollEnabled()) {
            // Scroll the WebView content
            val scrollAmount = delta * 15 // Increase sensitivity
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            if (shouldUseJsScroll(metrics)) {
                scrollWebViewByJs(
                        left = scrollAmount,
                        top = null,
                        smooth = false,
                        useScrollTo = false
                )
            } else {
                webView.scrollBy(scrollAmount, 0)
            }
            updateScrollBarThumbs(0, 0) // Update thumbs immediately
        } else {
            // Pan the viewport
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            val currentProgress = prefs.getInt("uiTransXProgress", 50)
            val newProgress = (currentProgress + delta).coerceIn(0, 100)

            prefs.edit().putInt("uiTransXProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun scrollPageVertical(delta: Int) {
        if (isWebViewScrollEnabled()) {
            // Scroll the WebView content
            val scrollAmount = delta * 15 // Increase sensitivity
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            if (shouldUseJsScroll(metrics)) {
                scrollWebViewByJs(
                        left = null,
                        top = scrollAmount,
                        smooth = false,
                        useScrollTo = false
                )
            } else {
                webView.scrollBy(0, scrollAmount)
            }
            updateScrollBarThumbs(0, 0) // Update thumbs immediately
        } else {
            // Pan the viewport
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            val currentProgress = prefs.getInt("uiTransYProgress", 50)
            val newProgress = (currentProgress + delta).coerceIn(0, 100)

            prefs.edit().putInt("uiTransYProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun shouldFreezeScrollBars(): Boolean {
        val now = SystemClock.uptimeMillis()
        return isMediaPlaying || (now - lastMediaPlayingAt < mediaScrollFreezeMs)
    }

    private fun updateScrollBarThumbs(xProgress: Int, yProgress: Int) {
        val now = SystemClock.uptimeMillis()
        // Guard against updates during or shortly after scrollbar interaction to prevent bouncing
        if (now - lastScrollBarInteractionTime < 250L) return

        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(now)
            // Update Horizontal Thumb based on WebView scroll
            val hTrackContainer = horizontalScrollBar.getChildAt(1) as? FrameLayout
            val hTrackWidth =
                    when {
                        hTrackContainer != null && hTrackContainer.width > 0 ->
                                hTrackContainer.width
                        hTrackContainer != null && hTrackContainer.measuredWidth > 0 ->
                                hTrackContainer.measuredWidth
                        horizontalScrollBar.width > 0 -> {
                            val leftBtnWidth = horizontalScrollBar.getChildAt(0)?.width ?: 0
                            val rightBtnWidth = horizontalScrollBar.getChildAt(2)?.width ?: 0
                            (horizontalScrollBar.width - leftBtnWidth - rightBtnWidth)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (hTrackWidth > 0) {
                val thumbWidth = 60
                val maxMargin = hTrackWidth - thumbWidth
                // Calculate ratio: scrollX / (contentWidth - viewportWidth)
                // Since we can't easily get full content width without computeHorizontalScrollRange
                // (protected),
                // we'll rely on an approximation or need to subclass WebView.
                // For now, let's try using the standard range approximation if possible, or just
                // skip if we can't get it.
                // Actually, we can use computeHorizontalScrollRange via reflection or just use
                // scrollX/ArbitraryLargeNumber if needed,
                // but simpler is to use `webView.scrollX` relative to estimated width.
                // Let's defer exact horizontal proportion calculation or use a safe fallback.

                // Using standard view methods available on WebView (which is a View)
                val range = metrics.rangeX
                val extent = metrics.extentX
                val offset = metrics.offsetX

                if (range > extent) {
                    val maxScroll = range - extent
                    val ratio = offset.coerceIn(0, maxScroll).toFloat() / maxScroll
                    val hMargin = (ratio * maxMargin).toInt().coerceIn(0, maxMargin)
                    hScrollThumb.translationX = hMargin.toFloat()
                    hScrollThumb.invalidate()
                }
            }

            // Update Vertical Thumb based on WebView scroll
            val vTrackContainer = verticalScrollBar.getChildAt(1) as? FrameLayout
            val vTrackHeight =
                    when {
                        vTrackContainer != null && vTrackContainer.height > 0 ->
                                vTrackContainer.height
                        vTrackContainer != null && vTrackContainer.measuredHeight > 0 ->
                                vTrackContainer.measuredHeight
                        verticalScrollBar.height > 0 -> {
                            val topBtnHeight = verticalScrollBar.getChildAt(0)?.height ?: 0
                            val bottomBtnHeight = verticalScrollBar.getChildAt(2)?.height ?: 0
                            (verticalScrollBar.height - topBtnHeight - bottomBtnHeight)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (vTrackHeight > 0) {
                val thumbHeight = 60
                val maxMargin = vTrackHeight - thumbHeight

                val range = metrics.rangeY
                val extent = metrics.extentY
                val offset = metrics.offsetY

                if (range > extent) {
                    val maxScroll = range - extent
                    val ratio = offset.coerceIn(0, maxScroll).toFloat() / maxScroll
                    val vMargin = (ratio * maxMargin).toInt().coerceIn(0, maxMargin)
                    vScrollThumb.translationY = vMargin.toFloat()
                    vScrollThumb.invalidate()
                }
            }
        } else {
            // Existing logic for non-anchored (viewport pan)
            // Update horizontal thumb position
            val hTrackContainer = horizontalScrollBar.getChildAt(1) as? FrameLayout
            val hTrackWidth =
                    when {
                        hTrackContainer != null && hTrackContainer.width > 0 ->
                                hTrackContainer.width
                        hTrackContainer != null && hTrackContainer.measuredWidth > 0 ->
                                hTrackContainer.measuredWidth
                        horizontalScrollBar.width > 0 -> {
                            val leftBtnWidth = horizontalScrollBar.getChildAt(0)?.width ?: 0
                            val rightBtnWidth = horizontalScrollBar.getChildAt(2)?.width ?: 0
                            (horizontalScrollBar.width - leftBtnWidth - rightBtnWidth)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (hTrackWidth > 0) {
                val thumbWidth = 60
                val maxMargin = hTrackWidth - thumbWidth
                val hMargin = (xProgress / 100f * maxMargin).toInt()
                hScrollThumb.translationX = hMargin.toFloat()
                hScrollThumb.invalidate()
            }

            // Update vertical thumb position
            val vTrackContainer = verticalScrollBar.getChildAt(1) as? FrameLayout
            val vTrackHeight =
                    when {
                        vTrackContainer != null && vTrackContainer.height > 0 ->
                                vTrackContainer.height
                        vTrackContainer != null && vTrackContainer.measuredHeight > 0 ->
                                vTrackContainer.measuredHeight
                        verticalScrollBar.height > 0 -> {
                            val topBtnHeight = verticalScrollBar.getChildAt(0)?.height ?: 0
                            val bottomBtnHeight = verticalScrollBar.getChildAt(2)?.height ?: 0
                            (verticalScrollBar.height - topBtnHeight - bottomBtnHeight)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (vTrackHeight > 0) {
                val thumbHeight = 60
                val maxMargin = vTrackHeight - thumbHeight
                val vMargin = (yProgress / 100f * maxMargin).toInt()
                vScrollThumb.translationY = vMargin.toFloat()
                vScrollThumb.invalidate()
            }
        }
    }

    fun updateScrollBarsVisibility(force: Boolean = false) {
        // DebugLog.d("ScrollDebug", "updateScrollBarsVisibility called. isAnchored=$isAnchored,
        // isInScrollMode=$isInScrollMode, uiScale=$uiScale")
        val now = SystemClock.uptimeMillis()
        if (!force &&
            (isInteractingWithScrollBar || now - lastScrollBarInteractionTime < scrollBarRelayoutSuppressMs)
        ) {
            // During scrollbar clicks/drags, JS scroll metrics can arrive in
            // a burst and briefly disagree with WebView metrics. Avoid
            // relaying those into WebView layout changes until the gesture
            // settles; otherwise the track extent changes under the cursor
            // and the thumb appears to jump up/down by itself.
            return
        }
        // Check freeze state but don't return early - we need to update layout
        val isFrozen = !force && shouldFreezeScrollBars() && !isInteractingWithScrollBar

        // Determine mode-specific base constraints
        val isScrollModeActive = isInScrollMode || isNavBarsHidden

        // Base dimensions
        val containerWidth = 640
        val topReserve = unipanelTopReservePx
        val baseLeftMargin = if (isScrollModeActive) 0 else toggleBarWidthPx
        val rawBottomMargin = if (isScrollModeActive) 0 else navBarHeightPx
        val keyboardVisible = keyboardContainer.visibility == View.VISIBLE
        if (keyboardVisible) {
            val keyboardWidth = 640 - toggleBarWidthPx
            keyboardContainer.measure(
                    MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        val keyboardHeight =
                if (keyboardVisible) {
                    val measured = keyboardContainer.measuredHeight
                    if (measured > 0) measured else 160
                } else {
                    0
                }
        val baseBottomMargin = if (keyboardVisible) 0 else rawBottomMargin

        val currentUrl = webView.url ?: ""
        if (scrollBarMemoryUrl != currentUrl) {
            resetScrollBarVisibilityMemory(currentUrl)
        }

        // If anchored, scrollbars are always hidden
        if (isAnchored) {
            horizontalScrollBar.visibility = View.GONE
            verticalScrollBar.visibility = View.GONE

            (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
                var targetWidth: Int
                var targetHeight: Int
                if (isScrollModeActive) {
                    targetWidth = containerWidth
                    targetHeight = (480 - topReserve - keyboardHeight).coerceAtLeast(0)
                } else {
                    targetWidth = containerWidth - baseLeftMargin
                    targetHeight = (480 - topReserve - baseBottomMargin - keyboardHeight).coerceAtLeast(0)
                }

                var changed = false
                if (p.width != targetWidth) changed = true
                if (p.height != targetHeight) changed = true
                val targetGravity = Gravity.TOP or Gravity.START
                if (p.leftMargin != baseLeftMargin) changed = true
                if (p.topMargin != topReserve) changed = true
                if (p.rightMargin != 0) changed = true
                if (p.bottomMargin != baseBottomMargin) changed = true
                if (p.gravity != targetGravity) changed = true

                if (changed) {
                    p.width = targetWidth
                    p.height = targetHeight
                    p.leftMargin = baseLeftMargin
                    p.topMargin = topReserve
                    p.rightMargin = 0
                    p.bottomMargin = baseBottomMargin
                    p.gravity = targetGravity
                    webViewsContainer.layoutParams = p
                    webViewsContainer.requestLayout()
                    webViewsContainer.invalidate()
                }
            }
            return
        }

        // YouTube "Full" mode: hide the custom scrollbars so they don't draw
        // over the fullscreen video — REGARDLESS of whether the browser nav
        // bars are rolled up. (The old gate also required isNavBarsHidden, so
        // a scrollbar could linger over the video in Full mode when the bars
        // were shown.) Scoped to an actual YouTube watch page so a stale
        // youtubeCssFullModeActive flag can never hide scrollbars on
        // search/home/non-YouTube pages; Theater & Mini keep their scrollbars.
        val onYoutubeWatchForFull =
            currentUrl.contains("youtube.com/watch", ignoreCase = true) ||
                currentUrl.contains("youtu.be/", ignoreCase = true) ||
                (currentUrl.contains("youtube.com", ignoreCase = true) &&
                    currentUrl.contains("v=", ignoreCase = true))
        if (youtubeCssFullModeActive && onYoutubeWatchForFull) {
            horizontalScrollBar.apply {
                visibility = View.GONE
                isClickable = false
                isFocusable = false
            }
            verticalScrollBar.apply {
                visibility = View.GONE
                isClickable = false
                isFocusable = false
            }
            (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
                val targetWidth = containerWidth
                val targetHeight = (480 - topReserve - keyboardHeight).coerceAtLeast(0)
                val targetGravity = Gravity.TOP or Gravity.START
                if (p.width != targetWidth ||
                    p.height != targetHeight ||
                    p.leftMargin != 0 ||
                    p.topMargin != topReserve ||
                    p.rightMargin != 0 ||
                    p.bottomMargin != 0 ||
                    p.gravity != targetGravity
                ) {
                    p.width = targetWidth
                    p.height = targetHeight
                    p.leftMargin = 0
                    p.topMargin = topReserve
                    p.rightMargin = 0
                    p.bottomMargin = 0
                    p.gravity = targetGravity
                    webViewsContainer.layoutParams = p
                    webView.requestLayout()
                    webViewsContainer.requestLayout()
                    webViewsContainer.invalidate()
                }
            }
            return
        }

        // Hide scrollbars entirely on AR nav map pages (full-viewport 3D map)
        if (currentUrl.contains("ar_nav.html")) {
            horizontalScrollBar.visibility = View.GONE
            verticalScrollBar.visibility = View.GONE
            (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
                val targetWidth = if (isScrollModeActive) containerWidth else containerWidth - baseLeftMargin
                val targetHeight = (480 - topReserve - baseBottomMargin - keyboardHeight).coerceAtLeast(0)
                val targetGravity = Gravity.TOP or Gravity.START
                if (p.width != targetWidth ||
                    p.height != targetHeight ||
                    p.leftMargin != baseLeftMargin ||
                    p.topMargin != topReserve ||
                    p.rightMargin != 0 ||
                    p.bottomMargin != baseBottomMargin ||
                    p.gravity != targetGravity
                ) {
                    p.width = targetWidth
                    p.height = targetHeight
                    p.leftMargin = baseLeftMargin
                    p.topMargin = topReserve
                    p.rightMargin = 0
                    p.bottomMargin = baseBottomMargin
                    p.gravity = targetGravity
                    webViewsContainer.layoutParams = p
                    webViewsContainer.requestLayout()
                }
            }
            return
        }

        // Always check WebView scrollability since we disabled viewport panning
        val metrics = resolveScrollMetrics(now)
        val webHRange = metrics.rangeX
        val webHExtent = metrics.extentX
        val webVRange = metrics.rangeY
        val webVExtent = metrics.extentY
        val scrollDeltaThreshold = 1
        val webHDelta = webHRange - webHExtent
        val webVDelta = webVRange - webVExtent
        val showHorzRaw = webHDelta > scrollDeltaThreshold
        val showVertRaw = webVDelta > scrollDeltaThreshold
        if (showHorzRaw) {
            lastHorzScrollableAt = now
            stickyHorzScrollable = true
        }
        if (showVertRaw) {
            lastVertScrollableAt = now
            stickyVertScrollable = true
        }
        // On a YouTube WATCH page, decide from the LIVE scroll range instead of
        // the sticky latch + hold. YouTube is a single-page app: Full mode's
        // overflow:hidden makes the page unscrollable WITHOUT a URL change, and
        // the latch only resets on navigation — so it would otherwise keep a
        // scrollbar drawn over the video for the whole session even after the
        // page stops being scrollable. Watch pages are decisively scrollable
        // (Theater/Mini) or decisively not (Full), so there's no marginal zone
        // for the latch to debounce here. Every other page — including YouTube
        // search/home — keeps the original anti-flicker/anti-oscillation latch.
        // A YouTube watch page (Full/Theater/Mini) should NEVER show a
        // horizontal scrollbar — the video/page is constrained to 100vw, and a
        // horizontal bar was appearing (notably over Mini). Suppress it there;
        // every other page keeps the normal horizontal-scroll behaviour.
        val showHorz =
                if (onYoutubeWatchForFull) false
                else stickyHorzScrollable || showHorzRaw || (now - lastHorzScrollableAt < scrollBarHoldMs)
        // On a YouTube watch page the vertical bar is deterministic, not
        // measured: in Full the page is overflow:hidden (no bar); in Theater/
        // Mini the page always scrolls, so show the bar IMMEDIATELY rather than
        // waiting for a live re-measure (which only fired after the user
        // interacted near it). youtubeCssFullModeActive is true only in Full.
        val showVert =
                if (onYoutubeWatchForFull) !youtubeCssFullModeActive
                else stickyVertScrollable || showVertRaw || (now - lastVertScrollableAt < scrollBarHoldMs)

        if (!isFrozen) {
            horizontalScrollBar.apply {
                visibility = if (showHorz) View.VISIBLE else View.INVISIBLE
                isClickable = showHorz
                isFocusable = false
            }

            verticalScrollBar.apply {
                visibility = if (showVert) View.VISIBLE else View.INVISIBLE
                isClickable = showVert
                isFocusable = false
            }
        }

        // Apply layout adjustments
        (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
            // Keep WebView sizing stable to avoid layout churn (prevents media pauses/flicker).
            val rightMarginShift = if (verticalScrollBar.visibility == View.VISIBLE) 20 else 0
            val bottomMarginShift = if (horizontalScrollBar.visibility == View.VISIBLE) 20 else 0

            var targetWidth: Int
            var targetHeight: Int
            var targetLeftMargin: Int
            var targetTopMargin: Int
            var targetBottomMargin: Int
            var targetRightMargin: Int

            if (isScrollModeActive) {
                // Scroll Mode: 640 total width
                targetWidth = 640 - rightMarginShift
                targetHeight = (480 - topReserve - bottomMarginShift - keyboardHeight).coerceAtLeast(0)
                targetLeftMargin = 0
                targetTopMargin = topReserve
                targetRightMargin = rightMarginShift
                targetBottomMargin = bottomMarginShift
            } else {
                // Normal Mode:
                // Width: 640 total - toggle bar - margin
                targetWidth = (640 - baseLeftMargin) - rightMarginShift

                // Height: 480 total - nav bar - margin
                // We must be explicit here so onMeasure picks it up
                targetHeight =
                        (480 - topReserve - baseBottomMargin - bottomMarginShift - keyboardHeight).coerceAtLeast(
                                0
                        )

                targetLeftMargin = baseLeftMargin
                targetTopMargin = topReserve
                targetRightMargin = rightMarginShift
                targetBottomMargin = baseBottomMargin + bottomMarginShift
            }

            var changed = false
            if (p.width != targetWidth) changed = true
            if (p.height != targetHeight) changed = true
            val targetGravity = Gravity.TOP or Gravity.START
            if (p.leftMargin != targetLeftMargin) changed = true
            if (p.topMargin != targetTopMargin) changed = true
            if (p.rightMargin != targetRightMargin) changed = true
            if (p.bottomMargin != targetBottomMargin) changed = true
            if (p.gravity != targetGravity) changed = true

            if (changed) {
                p.width = targetWidth
                p.height = targetHeight
                p.leftMargin = targetLeftMargin
                p.topMargin = targetTopMargin
                p.rightMargin = targetRightMargin
                p.bottomMargin = targetBottomMargin
                p.gravity = targetGravity

                webViewsContainer.layoutParams = p
                // Force layout update on WebView itself to ensure it resizes
                webView.requestLayout()
                webViewsContainer.requestLayout()
                webViewsContainer.invalidate()
            }
        }

        // Remove unconditional requestLayout/invalidate here
        // webView.requestLayout()
        // webView.invalidate()

        if (horizontalScrollBar.visibility == View.VISIBLE ||
                        verticalScrollBar.visibility == View.VISIBLE
        ) {
            updateScrollBarThumbs(0, 0)
        }
    }

    fun updateExternalScrollMetrics(
            rangeX: Int,
            extentX: Int,
            offsetX: Int,
            rangeY: Int,
            extentY: Int,
            offsetY: Int
    ) {
        val now = SystemClock.uptimeMillis()
        externalScrollMetrics =
                ExternalScrollMetrics(
                        rangeX = rangeX.coerceAtLeast(0),
                        extentX = extentX.coerceAtLeast(0),
                        offsetX = offsetX.coerceAtLeast(0),
                        rangeY = rangeY.coerceAtLeast(0),
                        extentY = extentY.coerceAtLeast(0),
                        offsetY = offsetY.coerceAtLeast(0),
                        timestamp = now
                )

        if (isInteractingWithScrollBar || now - lastScrollBarInteractionTime < scrollBarRelayoutSuppressMs) {
            finishScrollBarInteraction()
            return
        }

        if (!isAnchored && now - lastScrollBarCheckTime > scrollBarVisibilityThrottleMs) {
            updateScrollBarsVisibility()
            lastScrollBarCheckTime = now
        } else if (now - lastScrollBarInteractionTime >= 250L) {
            // Only update thumb position if not recently interacting with scrollbar
            updateScrollBarThumbs(0, 0)
        }
    }

    fun clearExternalScrollMetrics() {
        externalScrollMetrics = null
    }

    private fun resetScrollBarVisibilityMemory(url: String? = webView.url) {
        scrollBarMemoryUrl = url ?: ""
        stickyHorzScrollable = false
        stickyVertScrollable = false
        lastHorzScrollableAt = 0L
        lastVertScrollableAt = 0L
    }

    private fun resolveScrollMetrics(now: Long): ScrollMetrics {
        val webRangeX = webView.getHorizontalScrollRange()
        val webExtentX = webView.getHorizontalScrollExtent()
        val webOffsetX = webView.getHorizontalScrollOffset()
        val webRangeY = webView.getVerticalScrollRange()
        val webExtentY = webView.getVerticalScrollExtent()
        val webOffsetY = webView.getVerticalScrollOffset()

        val external =
                externalScrollMetrics?.takeIf { now - it.timestamp <= externalScrollMetricsStaleMs }
        if (external == null) {
            return ScrollMetrics(
                    rangeX = webRangeX,
                    extentX = webExtentX,
                    offsetX = webOffsetX,
                    rangeY = webRangeY,
                    extentY = webExtentY,
                    offsetY = webOffsetY
            )
        }

        // Use whichever source reports the larger scrollable delta. Some
        // pages temporarily report stale/zero JS metrics during SPA updates
        // while the WebView still has a scroll range; using external metrics
        // unconditionally made the custom bar disappear until the next full
        // observer refresh. Nested scrollers still win when they actually
        // have more scrollable content than the root WebView.
        val externalDeltaX = (external.rangeX - external.extentX).coerceAtLeast(0)
        val externalDeltaY = (external.rangeY - external.extentY).coerceAtLeast(0)
        val webDeltaX = (webRangeX - webExtentX).coerceAtLeast(0)
        val webDeltaY = (webRangeY - webExtentY).coerceAtLeast(0)
        val useExternalH = external.extentX > 0 && externalDeltaX >= webDeltaX
        val useExternalV = external.extentY > 0 && externalDeltaY >= webDeltaY
        return ScrollMetrics(
                rangeX = if (useExternalH) external.rangeX else webRangeX,
                extentX = if (useExternalH) external.extentX else webExtentX,
                offsetX = if (useExternalH) external.offsetX else webOffsetX,
                rangeY = if (useExternalV) external.rangeY else webRangeY,
                extentY = if (useExternalV) external.extentY else webExtentY,
                offsetY = if (useExternalV) external.offsetY else webOffsetY
        )
    }

    private fun shouldUseJsScroll(metrics: ScrollMetrics): Boolean {
        val now = SystemClock.uptimeMillis()
        val external =
                externalScrollMetrics?.takeIf { now - it.timestamp <= externalScrollMetricsStaleMs }
                        ?: return false

        val externalDeltaX = (external.rangeX - external.extentX).coerceAtLeast(0)
        val externalDeltaY = (external.rangeY - external.extentY).coerceAtLeast(0)
        // If resolveScrollMetrics selected the page-reported scroller, use the
        // JS path. The dashboard keeps the WebView/body stationary and scrolls
        // an inner .content element, so native WebView.scrollTo() can move the
        // thumb while leaving the visible page fixed.
        val metricsUseExternalX =
                external.extentX > 0 &&
                        externalDeltaX > 0 &&
                        metrics.rangeX == external.rangeX &&
                        metrics.extentX == external.extentX
        val metricsUseExternalY =
                external.extentY > 0 &&
                        externalDeltaY > 0 &&
                        metrics.rangeY == external.rangeY &&
                        metrics.extentY == external.extentY
        return metricsUseExternalX || metricsUseExternalY
    }

    private fun scrollWebViewByJs(left: Int?, top: Int?, smooth: Boolean, useScrollTo: Boolean) {
        val leftValue = left?.toString() ?: "undefined"
        val topValue = top?.toString() ?: "undefined"
        val behavior = if (smooth) "'smooth'" else "'auto'"
        val useScrollToJs = if (useScrollTo) "true" else "false"
        webView.evaluateJavascript(
                """
            (function() {
                var leftVal = $leftValue;
                var topVal = $topValue;
                var behavior = $behavior;
                var useScrollTo = $useScrollToJs;

                function isNumber(v) {
                    return typeof v === 'number' && !isNaN(v);
                }

                function scrollWindow() {
                    if (useScrollTo && typeof window.scrollTo === 'function') {
                        window.scrollTo({
                            left: isNumber(leftVal) ? leftVal : window.scrollX,
                            top: isNumber(topVal) ? topVal : window.scrollY,
                            behavior: behavior
                        });
                    } else if (!useScrollTo && typeof window.scrollBy === 'function') {
                        window.scrollBy({
                            left: isNumber(leftVal) ? leftVal : 0,
                            top: isNumber(topVal) ? topVal : 0,
                            behavior: behavior
                        });
                    } else if (typeof window.scrollTo === 'function') {
                        window.scrollTo({
                            left: isNumber(leftVal) ? leftVal : window.scrollX,
                            top: isNumber(topVal) ? topVal : window.scrollY,
                            behavior: behavior
                        });
                    }
                }

                function scrollElement(el) {
                    if (!el) {
                        scrollWindow();
                        return;
                    }
                    var hasScrollTo = typeof el.scrollTo === 'function';
                    var hasScrollBy = typeof el.scrollBy === 'function';
                    if (useScrollTo && hasScrollTo) {
                        el.scrollTo({
                            left: isNumber(leftVal) ? leftVal : el.scrollLeft,
                            top: isNumber(topVal) ? topVal : el.scrollTop,
                            behavior: behavior
                        });
                        return;
                    }
                    if (!useScrollTo && hasScrollBy) {
                        el.scrollBy({
                            left: isNumber(leftVal) ? leftVal : 0,
                            top: isNumber(topVal) ? topVal : 0,
                            behavior: behavior
                        });
                        return;
                    }

                    var targetLeft = isNumber(leftVal) ? leftVal : el.scrollLeft;
                    var targetTop = isNumber(topVal) ? topVal : el.scrollTop;
                    if (!useScrollTo) {
                        targetLeft = el.scrollLeft + (isNumber(leftVal) ? leftVal : 0);
                        targetTop = el.scrollTop + (isNumber(topVal) ? topVal : 0);
                    }
                    el.scrollLeft = targetLeft;
                    el.scrollTop = targetTop;
                }

                var target = window.__taplinkScrollTarget;
                var root = document.scrollingElement || document.documentElement || document.body;
                var isRoot = !target || target === root || target === document.documentElement || target === document.body;
                if (!isRoot && target && target.isConnected !== false) {
                    scrollElement(target);
                } else {
                    scrollWindow();
                }
            })();
        """,
                null
        )
    }

    private fun applyScrollbarTransform() {
        val scale = if (uiScale <= 0f) 1f else 1f / uiScale
        val transX = -leftEyeUIContainer.translationX
        val transY = -leftEyeUIContainer.translationY
        horizontalScrollBar.apply {
            // The horizontal rail sits on the bottom edge. When the UI is
            // scaled down, this rail is inversely scaled so it remains
            // tappable; pivot from the bottom so that extra height grows
            // upward inside the viewport instead of being clipped below it.
            pivotX = 0f
            pivotY = height.takeIf { it > 0 }?.toFloat() ?: 20f
            scaleX = scale
            scaleY = scale
            translationX = transX
            translationY = transY
        }
        verticalScrollBar.apply {
            // The vertical rail is laid out flush to the right edge. With
            // pivotX=0, inverse scaling expanded it to the right, which is
            // why the scrollbar could vanish off-screen. Pivot from the
            // rail's right edge so it grows inward and never leaves the
            // logical 640px browser viewport.
            pivotX = width.takeIf { it > 0 }?.toFloat() ?: 20f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
            translationX = transX
            translationY = transY
        }
    }

    private fun updateHorizontalScroll(percent: Float) {
        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            var range = metrics.rangeX
            var extent = metrics.extentX
            // Native fallback (see updateVerticalScroll for the full rationale):
            // when nav-bars-hidden full-screen resizes the viewport the cached
            // JS metrics can go stale and report range<=extent, so the drag
            // moved nothing. Trust the live native range if it disagrees.
            var forceNative = false
            if (range - extent <= 0) {
                val nr = webView.getHorizontalScrollRange()
                val ne = webView.getHorizontalScrollExtent()
                if (nr - ne > 0) { range = nr; extent = ne; forceNative = true }
            }
            if (range > extent) {
                val targetX = percent * (range - extent)
                if (!forceNative && shouldUseJsScroll(metrics)) {
                    scrollWebViewByJs(
                            left = targetX.toInt(),
                            top = null,
                            smooth = false,
                            useScrollTo = true
                    )
                } else {
                    webView.scrollTo(targetX.toInt(), webView.scrollY)
                }
            }
        } else {
            val newProgress = (percent * 100).toInt()
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("uiTransXProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun updateVerticalScroll(percent: Float) {
        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            var range = metrics.rangeY
            var extent = metrics.extentY
            // Native fallback. Dragging the scroll bar while nav bars are hidden
            // ("full screen") could scroll nothing: hiding the bars resizes the
            // WebView, and the cached external JS scroll metrics can lag a frame
            // and report range<=extent — so this method computed no movement.
            // If the live native WebView range says the page IS scrollable,
            // trust it and scroll natively (the JS path was keyed off the stale
            // metrics). Purely additive: only runs when the old code did nothing.
            var forceNative = false
            if (range - extent <= 0) {
                val nr = webView.getVerticalScrollRange()
                val ne = webView.getVerticalScrollExtent()
                if (nr - ne > 0) {
                    range = nr; extent = ne; forceNative = true
                    DebugLog.d("ScrollDebug", "updateVerticalScroll native fallback: range=$nr extent=$ne")
                }
            }
            if (range > extent) {
                val targetY = percent * (range - extent)
                if (!forceNative && shouldUseJsScroll(metrics)) {
                    scrollWebViewByJs(
                            left = null,
                            top = targetY.toInt(),
                            smooth = false,
                            useScrollTo = true
                    )
                } else {
                    webView.scrollTo(webView.scrollX, targetY.toInt())
                }
            }
        } else {
            val newProgress = (percent * 100).toInt()
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("uiTransYProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    // Function to update the cursor positions and visibility
    fun updateCursorPosition(x: Float, y: Float, isVisible: Boolean) {
        val currentTime = System.currentTimeMillis()
        lastCursorX = x
        lastCursorY = y

        if (!isAttachedToWindow) {
            return
        }

        if (currentTime - lastCursorUpdateTime >= CURSOR_UPDATE_INTERVAL) {
            if (isVisible) {
                // Convert cursor from container-local to screen coordinates
                val containerLocation = IntArray(2)
                getLocationOnScreen(containerLocation)

                // Account for UI scale and translation when calculating screen position
                // Visual cursor is scaled around (320, 240) and then translated (only in
                // non-anchored mode)
                val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
                val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

                val visualX = 320f + (x - 320f) * uiScale + transX
                val visualY = 240f + (y - 240f) * uiScale + transY

                val screenX = visualX + containerLocation[0]
                val screenY = visualY + containerLocation[1]

                // Pass screen coordinates - buttons also use screen coordinates
                updateButtonHoverStates(screenX, screenY)
            }
            listener?.onCursorPositionChanged(x, y, isVisible)
            lastCursorUpdateTime = currentTime
        }
    }

    private fun refreshHoverAtCurrentCursor() {
        if (!isAttachedToWindow) return

        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)

        val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
        val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

        val visualX = 320f + (lastCursorX - 320f) * uiScale + transX
        val visualY = 240f + (lastCursorY - 240f) * uiScale + transY

        val screenX = visualX + containerLocation[0]
        val screenY = visualY + containerLocation[1]

        updateButtonHoverStates(screenX, screenY)
    }

    fun updatePointerHover(screenX: Float, screenY: Float) {
        if (!isAttachedToWindow) return
        updateButtonHoverStates(screenX, screenY)
    }

    fun clearPointerHover() {
        if (!isAttachedToWindow) return
        clearAllHoverStates()
    }

    private var isScreenMasked = false
    private var isHostPaused = false
    private var isHoveringMaskToggle = false
    // WakeLock keeps CPU awake while screen is masked (projector off) so audio doesn't skip.
    // Without this, Android Doze will periodically sleep the CPU causing ~10s audio stutters.
    private val maskWakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapInsight:MaskAudioPlayback")
    private val pausedMediaWakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapInsight:PausedMediaPlayback")
    private val mediaWifiLock: WifiManager.WifiLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.run {
            @Suppress("DEPRECATION")
            createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TapInsight:StreamingAudio")
        }
    private var maskOverlay: FrameLayout =
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT) // Left eye width only
                elevation = 1000f // Put it above everything except cursors
                isClickable = true
                isFocusable = true

                // Dim-mode touch handling lives at the ACTIVITY level
                // (MainActivity.maskedGestureDetector), so we only need
                // to consume any stray events that reach this view to
                // stop them from propagating to webviews/navbar
                // underneath. The activity-level detector handles the
                // documented tap gestures and consumes horizontal flings
                // without treating them as media skip commands.
                setOnTouchListener { _, _ -> true }
            }

    // Mask mode UI elements
    private lateinit var maskMediaControlsContainer: LinearLayout
    private lateinit var btnMaskPrevTrack: FontIconView // Skip to previous song
    private lateinit var btnMaskPrev: FontIconView // 10s back
    private lateinit var btnMaskPlay: FontIconView
    private lateinit var btnMaskPause: FontIconView
    private lateinit var btnMaskNext: FontIconView // 10s forward
    private lateinit var btnMaskNextTrack: FontIconView // Skip to next song
    private lateinit var btnMaskUnmask: ImageButton
    private lateinit var maskNowPlayingText: TextView
    private lateinit var maskCaptionText: TextView
    private lateinit var maskSpotifyInfoContainer: LinearLayout
    private lateinit var maskSpotifyTitleText: TextView
    private lateinit var maskSpotifyArtistText: TextView
    private lateinit var maskSpotifyAlbumText: TextView
    private lateinit var maskSpotifyProgressTrack: FrameLayout
    private lateinit var maskSpotifyProgressFill: View
    private lateinit var maskSpotifyLyricsText: TextView
    private data class MaskSpotifyInfo(
        val title: String,
        val artist: String,
        val album: String,
        val progressMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
        val lyricsLoaded: Boolean,
        /** True when the loaded lyrics include per-line timestamps so the
         *  dim-mode overlay can show one line at a time in sync with playback. */
        val hasSyncedLyrics: Boolean = false,
        /** The currently-singing lyric line for karaoke-style display in dim
         *  mode. Empty between lines (instrumental gaps) or when only plain
         *  (non-timed) lyrics are loaded. */
        val currentLyricLine: String = ""
    )
    private var lastMaskedSpotifyInfo: MaskSpotifyInfo? = null
    // Minimal dim-mode metadata: battery (with charging indicator) + time.
    // Only these three pieces of text appear in dim mode now — no
    // toolbar, no visualizer, no exit button. Gestures handle media
    // control and exit.
    private lateinit var maskBatteryText: TextView
    private lateinit var maskTimeText: TextView
    private val maskClockBatteryRefresh: Runnable = object : Runnable {
        override fun run() {
            if (!isScreenMasked) return
            updateMaskClockAndBattery()
            postDelayed(this, 30_000L) // 30s — enough resolution for the
            // minute clock + battery percent, doesn't churn the GPU.
        }
    }
    private var lastMaskedDomTitle: String? = null
    private var lastMaskedDomTitleUrl: String? = null
    private var lastMaskedDomTitleAt: Long = 0L
    private val maskedDomTitleFreshMs = 15000L
    private val maskNowPlayingPeriodicRefresh: Runnable = object : Runnable {
        override fun run() {
            if (!isScreenMasked) return
            refreshMaskedNowPlayingFromJs()
            refreshMaskedNowPlaying()
            postDelayed(this, maskedNowPlayingRefreshDelayMs())
        }
    }

    // ── Dim-mode caption ticker (DimCaptionEngine feed) ────────────
    /**
     * Pull-source for the dim caption line, independent of the current
     * page. MainActivity points this at DimCaptionEngine (TapRadio synced
     * lyrics) so the SAME mask caption slot YouTube dim-captions use can
     * show radio lyrics while dimmed. The ticker polls every 500ms while
     * masked; ownership semantics (`dimCaptionOwnsSlot`) make sure it only
     * clears the slot when it was the one writing to it, so it never
     * fights the YouTube push channel or the Spotify karaoke line.
     */
    @Volatile var dimCaptionProvider: (() -> String?)? = null
    private var dimCaptionOwnsSlot = false
    private var lastDimCaptionLine: String? = null
    private val dimCaptionTick: Runnable = object : Runnable {
        override fun run() {
            if (!isScreenMasked) return
            val line = runCatching { dimCaptionProvider?.invoke() }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (line != lastDimCaptionLine) {
                lastDimCaptionLine = line
                if (line != null) {
                    dimCaptionOwnsSlot = true
                    updateMaskCaption(line)
                } else if (dimCaptionOwnsSlot) {
                    dimCaptionOwnsSlot = false
                    updateMaskCaption(null)
                }
            }
            postDelayed(this, 500L)
        }
    }

    // ── Breathing audio visualizer (dim mode) ──────────────────────
    /** Lazily created on first show; lives on the mask overlay. */
    private var breathingVisualizer: BreathingVisualizerView? = null
    /**
     * Supplies the audio session id the visualizer should attach to
     * (e.g. the native TapRadio ExoPlayer session). Set by MainActivity
     * right before [showAudioVisualizer]; 0 / null falls back to the
     * global output mix (the view also self-heals to session 0 via its
     * own silent-session watchdog).
     */
    @Volatile var audioSessionIdProvider: (() -> Int)? = null
    private var maskOverlayTouchDownX = 0f
    private var maskOverlayTouchDownY = 0f
    private var maskOverlayTouchDownTime = 0L
    // Tap slop — generous enough that small cursor drift during a press
    // does not disqualify the tap on AR glasses input.
    private val maskOverlayTapSlopPx = 60f
    // Lightweight dedup for mask overlay touch dispatch — prevents the same physical tap
    // from being processed twice when multiple code paths fire within the same input cycle.
    // Short enough that intentional rapid taps (e.g. theme cycling, toggle on/off)
    // still register but long enough to swallow the immediate double-fire from
    // a paired ACTION_DOWN / ACTION_UP hitting the same button.
    private var lastMaskOverlayDispatchTime = 0L
    private val MASK_OVERLAY_DISPATCH_DEBOUNCE_MS = 140L
    // Allow slower taps — up to ~700ms — since the AR glasses' input pipeline
    // can add noticeable latency between ACTION_DOWN and ACTION_UP.
    private val maskOverlayTapMaxDurationMs = 700L

    // ── Dim-mode (mask) gesture thresholds ─────────────────────────
    // Minimum horizontal travel for a swipe to qualify as next/prev.
    // Set generously so a wobbly tap never fires the wrong action.
    private val maskOverlaySwipeThresholdPx = 140f
    // Maximum duration for a swipe — keeps a slow drag from firing.
    private val maskOverlaySwipeMaxDurationMs = 600L
    // Time window for the second tap of a double-tap (exits dim mode).
    // Slightly longer than the system default to forgive the AR glasses'
    // input latency between two physical taps.
    private val MASK_OVERLAY_DOUBLE_TAP_WINDOW_MS = 380L
    // Timestamp of the last single tap that's "armed" for double-tap
    // detection. 0L means no pending tap.
    private var lastMaskOverlayTapTime = 0L

    // Fullscreen Mode UI elements
    private lateinit var fullScreenControlsContainer: FrameLayout
    private lateinit var fullScreenMediaControls: LinearLayout
    private var suppressFullscreenMediaControls = false
    private lateinit var btnFsPrevTrack: FontIconView
    private lateinit var btnFsPrev: FontIconView
    private lateinit var btnFsPlayPause: FontIconView // Single toggle button
    private var isFsPlaying: Boolean = false // Track play state
    private lateinit var btnFsNext: FontIconView
    private lateinit var btnFsNextTrack: FontIconView
    private lateinit var btnFsExit: FontIconView

    var anchorToggleListener: AnchorToggleListener? = null

    // Add properties to track translations
    private var _translationX = 0f
    private var _translationY = 0f
    private var _rotationZ = 0f

    private var isInScrollMode = false
    private var isNavBarsHidden = false // Tracks nav bar visibility independent of scroll mode
    private var settingsScrim: View? = null

    // Scroll bar containers for non-anchored mode
    private var horizontalScrollBar: LinearLayout
    private var verticalScrollBar: LinearLayout
    private var hScrollThumb: View
    private var vScrollThumb: View
    private var isInteractingWithScrollBar = false
    private val scrollBarRelayoutSuppressMs = 450L
    private val scrollBarSettleRunnable = Runnable {
        isInteractingWithScrollBar = false
        updateScrollBarsVisibility(force = true)
        updateScrollBarThumbs(0, 0)
    }

    private fun beginScrollBarInteraction() {
        isInteractingWithScrollBar = true
        lastScrollBarInteractionTime = SystemClock.uptimeMillis()
        removeCallbacks(scrollBarSettleRunnable)
    }

    private fun finishScrollBarInteraction() {
        lastScrollBarInteractionTime = SystemClock.uptimeMillis()
        removeCallbacks(scrollBarSettleRunnable)
        postDelayed(scrollBarSettleRunnable, scrollBarRelayoutSuppressMs)
    }

    private fun clickScrollBarArrow(action: () -> Unit) {
        beginScrollBarInteraction()
        action()
        finishScrollBarInteraction()
    }

    private var windowsOverviewContainer: android.widget.ScrollView? = null
    private var hoveredWindowsOverviewItem: View? = null

    fun showWindowsOverview() {
        DebugLog.d(
                "WindowsOverview",
                "showWindowsOverview called, windowsOverviewContainer=${windowsOverviewContainer != null}"
        )
        if (windowsOverviewContainer == null) {
            createWindowsOverviewUI()
            DebugLog.d("WindowsOverview", "Created windows overview UI")
        }

        // Populate container with current windows
        val container = windowsOverviewContainer?.getChildAt(0) as? LinearLayout
        if (container == null) {
            Log.e(
                    "WindowsOverview",
                    "Container is null! windowsOverviewContainer has ${windowsOverviewContainer?.childCount ?: 0} children"
            )
            return
        }
        DebugLog.d(
                "WindowsOverview",
                "Container found, clearing views. Windows count: ${windows.size}"
        )
        container.removeAllViews()
        hoveredWindowsOverviewItem = null

        // Add "Add Window" button at the top - shorter with label
        val addButton =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50)
                                    .apply { bottomMargin = 12 }
                    // Create StateListDrawable for hover feedback
                    val normalBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#2A5298"))
                                cornerRadius = 12f
                            }
                    val hoveredBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#3A72C8"))
                                cornerRadius = 12f
                                setStroke(2, Color.parseColor("#6BAAFF"))
                            }
                    val pressedBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#4A82D8"))
                                cornerRadius = 12f
                            }
                    background =
                            android.graphics.drawable.StateListDrawable().apply {
                                addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                                addState(intArrayOf(android.R.attr.state_hovered), hoveredBg)
                                addState(intArrayOf(), normalBg)
                            }
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { createNewWindow() }
                }

        val addIcon =
                FontIconView(context).apply {
                    setText(R.string.fa_plus)
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { rightMargin = 12 }
                }
        val addLabel =
                TextView(context).apply {
                    text = "Open New Tab"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                }
        addButton.addView(addIcon)
        addButton.addView(addLabel)
        container.addView(addButton)
        DebugLog.d("WindowsOverview", "Added 'Add Window' button")

        // Calculate item dimensions for 3-column grid with stretching
        // Container width = 608 - 32 (padding) = 576
        val itemMargin = 8
        val columnsPerRow = 3
        val itemHeight = 120 // Fixed height for items

        // Create rows for 3-column grid
        var currentRow: LinearLayout? = null
        windows.forEachIndexed { index, win ->
            DebugLog.d("WindowsOverview", "Adding window item: ${win.id}, title: ${win.title}")

            // Create new row every 3 items
            if (index % columnsPerRow == 0) {
                currentRow =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams =
                                    LinearLayout.LayoutParams(
                                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                            )
                                            .apply { bottomMargin = itemMargin }
                        }
                container.addView(currentRow)
            }

            // Calculate position in current row
            val positionInRow = index % columnsPerRow

            val item =
                    FrameLayout(context).apply {
                        // Use weight=1 so items stretch to fill available width
                        layoutParams =
                                LinearLayout.LayoutParams(0, itemHeight, 1f).apply {
                                    marginStart = if (positionInRow == 0) 0 else itemMargin / 2
                                    marginEnd =
                                            if (positionInRow == columnsPerRow - 1) 0
                                            else itemMargin / 2
                                }
                        background =
                                android.graphics.drawable.StateListDrawable().apply {
                                    val isActive = win.id == activeWindowId

                                    // Colors
                                    val normalBgColor =
                                            if (isActive) Color.parseColor("#444444")
                                            else Color.parseColor("#252525")
                                    val hoverBgColor =
                                            if (isActive) Color.parseColor("#555555")
                                            else Color.parseColor("#353535")
                                    val normalStrokeColor =
                                            if (isActive) Color.parseColor("#4488FF")
                                            else Color.parseColor("#404040")
                                    val hoverStrokeColor =
                                            if (isActive) Color.parseColor("#4488FF")
                                            else
                                                    Color.parseColor(
                                                            "#505050"
                                                    ) // Lighter stroke on hover for inactive

                                    val hoveredDrawable =
                                            GradientDrawable().apply {
                                                setColor(hoverBgColor)
                                                setStroke(2, hoverStrokeColor)
                                                cornerRadius = 12f
                                            }

                                    val normalDrawable =
                                            GradientDrawable().apply {
                                                setColor(normalBgColor)
                                                setStroke(2, normalStrokeColor)
                                                cornerRadius = 12f
                                            }

                                    addState(
                                            intArrayOf(android.R.attr.state_hovered),
                                            hoveredDrawable
                                    )
                                    addState(intArrayOf(), normalDrawable)
                                }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { switchToWindow(win.id) }
                    }

            // Thumbnail (Placeholder or actual bitmap)
            val thumbView =
                    ImageView(context).apply {
                        if (win.thumbnail != null) {
                            setImageBitmap(win.thumbnail)
                        } else {
                            setBackgroundColor(Color.parseColor("#1A1A1A"))
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.MATCH_PARENT
                                        )
                                        .apply { setMargins(3, 3, 3, 3) }
                        alpha = 0.6f
                    }
            item.addView(thumbView)

            // Title - smaller text, truncated
            val titleView =
                    TextView(context).apply {
                        text = win.title.take(20) + if (win.title.length > 20) "..." else ""
                        textSize = 10f
                        setTextColor(Color.WHITE)
                        maxLines = 2
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply {
                                            gravity = Gravity.BOTTOM
                                            setMargins(6, 0, 6, 6)
                                        }
                        setShadowLayer(3f, 0f, 0f, Color.BLACK)
                    }
            item.addView(titleView)

            // Delete button - smaller
            val deleteBtn =
                    FontIconView(context).apply {
                        setText(R.string.fa_xmark)
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#CC333333"))
                                    shape = GradientDrawable.OVAL
                                }
                        layoutParams =
                                FrameLayout.LayoutParams(28, 28).apply {
                                    gravity = Gravity.TOP or Gravity.END
                                    topMargin = 4
                                    rightMargin = 4
                                }
                        setOnClickListener { closeWindow(win.id) }
                    }
            item.addView(deleteBtn)

            currentRow?.addView(item)
        }

        DebugLog.d(
                "WindowsOverview",
                "Setting container visible, total items: ${container.childCount}"
        )
        windowsOverviewContainer?.visibility = View.VISIBLE
        webView.visibility = View.GONE

        // Force the container to the front by removing and re-adding at the end
        // Preserve the layout params
        // Force the container to the front using bringToFront() instead of remove/add
        // which can cause layout state loss
        val params =
                windowsOverviewContainer?.layoutParams as? FrameLayout.LayoutParams
                        ?: FrameLayout.LayoutParams(640 - toggleBarWidthPx, 480 - navBarHeightPx)
                                .apply {
                                    leftMargin = toggleBarWidthPx
                                    // Explicitly set Gravity to avoid any ambiguity
                                    gravity = Gravity.TOP or Gravity.START
                                }

        // Ensure params are applied
        windowsOverviewContainer?.layoutParams = params

        windowsOverviewContainer?.bringToFront()

        requestLayout()
        invalidate()

        // Log layout info after layout pass
        windowsOverviewContainer?.post {
            val woc = windowsOverviewContainer
            DebugLog.d(
                    "WindowsOverview",
                    "Post-layout: width=${woc?.width}, height=${woc?.height}, " +
                            "x=${woc?.x}, y=${woc?.y}, visibility=${woc?.visibility}, " +
                            "layoutParams=${woc?.layoutParams?.width}x${woc?.layoutParams?.height}"
            )
            refreshHoverAtCurrentCursor()
        }
    }

    fun hideWindowsOverview() {
        windowsOverviewContainer?.visibility = View.GONE
        webView.visibility = View.VISIBLE
        requestLayout()
        invalidate()
    }

    private fun createWindowsOverviewUI() {
        DebugLog.d("WindowsOverview", "createWindowsOverviewUI called")
        // Use explicit dimensions since MATCH_PARENT wasn't resolving
        val containerWidth = 640 - toggleBarWidthPx // 608
        val containerHeight = 480 - navBarHeightPx // 448

        windowsOverviewContainer =
                android.widget.ScrollView(context).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(containerWidth, containerHeight).apply {
                                leftMargin = toggleBarWidthPx
                                gravity = Gravity.TOP or Gravity.START
                            }
                    setBackgroundColor(Color.parseColor("#101010"))
                    visibility = View.GONE
                    elevation = 1500f
                    isFillViewport = true // Ensure content fills the viewport
                }
        DebugLog.d("WindowsOverview", "Container created: ${containerWidth}x${containerHeight}")

        val content =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    // Use ViewGroup.LayoutParams for ScrollView children
                    layoutParams =
                            ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                    setPadding(16, 16, 16, 16)
                    setBackgroundColor(Color.parseColor("#101010")) // Match parent background
                }

        windowsOverviewContainer?.addView(content)
        leftEyeUIContainer.addView(windowsOverviewContainer)
        DebugLog.d(
                "WindowsOverview",
                "UI created: ScrollView has ${windowsOverviewContainer?.childCount} children, added to leftEyeUIContainer (${leftEyeUIContainer.childCount} children)"
        )
    }

    fun toggleWindowMode() {
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            hideWindowsOverview()
        } else {
            // Capture thumbnail of current window before showing overview
            val currentWin = windows.find { it.id == activeWindowId }
            if (currentWin != null) {
                try {
                    // Simple capture of the webview drawing cache or similar
                    // Using drawing cache is deprecated but works for simple needs, or
                    // PixelCopy/draw
                    // Here we'll use a simple draw to canvas if possible
                    val w = webView.width
                    val h = webView.height
                    if (w > 0 && h > 0) {
                        val bmp = Bitmap.createBitmap(w / 4, h / 4, Bitmap.Config.RGB_565)
                        val c = Canvas(bmp)
                        c.scale(0.25f, 0.25f)
                        webView.draw(c)
                        currentWin.thumbnail = bmp
                    }
                } catch (e: Exception) {
                    Log.e("Windows", "Failed to capture thumbnail", e)
                }
                currentWin.title = webView.title ?: "Tab"
            }
            showWindowsOverview()
        }
    }

    fun createNewWindow(loadDefaultUrl: Boolean = true): WebView {
        val newWebView = InternalWebView(context)
        configureWebView(newWebView)
        applyBrowsingModeToWebView(newWebView, isDesktopMode)
        // Popup windows supplied via WebViewTransport must be pristine (not pre-navigated).
        if (loadDefaultUrl) {
            newWebView.loadUrl(Constants.DEFAULT_URL)
        }
        val newWindow = BrowserWindow(webView = newWebView, title = "New Tab")

        // Add to container but invisible
        newWebView.visibility = View.INVISIBLE
        webViewsContainer.addView(
                newWebView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Notify MainActivity to configure the new WebView (clients, settings, etc.)
        windowCallback?.onWindowCreated(newWebView)

        windows.add(newWindow)
        switchToWindow(newWindow.id)
        saveAllWindowsState()
        return newWebView
    }

    fun resetToSingleWindow(loadDefaultUrl: Boolean = false): WebView {
        windows.toList().forEach { win ->
            try {
                win.webView.stopLoading()
            } catch (_: Exception) {}
            try {
                webViewsContainer.removeView(win.webView)
            } catch (_: Exception) {}
            try {
                win.webView.destroy()
            } catch (_: Exception) {}
            win.thumbnail?.recycle()
        }

        windows.clear()
        mediaStateByWindowId.clear()
        mediaLastPlayedAtByWindowId.clear()
        activeWindowId = null
        isMediaPlaying = false
        hideMediaControls()
        webViewsContainer.removeAllViews()

        val freshWebView = InternalWebView(context)
        configureWebView(freshWebView)
        applyBrowsingModeToWebView(freshWebView, isDesktopMode)
        if (loadDefaultUrl) {
            freshWebView.loadUrl(Constants.DEFAULT_URL)
        }

        val freshWindow = BrowserWindow(webView = freshWebView, title = "New Tab")
        freshWebView.visibility = View.VISIBLE
        webViewsContainer.addView(
                freshWebView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        )
        windowCallback?.onWindowCreated(freshWebView)

        windows.add(freshWindow)
        activeWindowId = freshWindow.id
        webView = freshWebView
        updateScrollBarsVisibility()
        windowCallback?.onWindowSwitched(freshWebView)
        freshWebView.post { injectPageObservers(freshWebView) }
        startRefreshing()
        hideWindowsOverview()
        saveAllWindowsState()
        return freshWebView
    }

    fun switchToWindow(id: String) {
        val targetWindow = windows.find { it.id == id } ?: return

        if (activeWindowId == id) {
            // Already active, just hide overview if visible
            hideWindowsOverview()
            return
        }

        // Pause the old WebView to free CPU/network resources while inactive
        val oldWebView = webView
        oldWebView.visibility = View.INVISIBLE
        oldWebView.onPause()
        oldWebView.pauseTimers()

        // Switch active window
        activeWindowId = id
        webView = targetWindow.webView

        // Resume the new WebView and bring to front
        webView.resumeTimers()
        webView.onResume()
        webView.visibility = View.VISIBLE
        webView.bringToFront()

        // Ensure settings are applied (zoom, font size, etc.) which might be instance specific if
        // not global
        // MainActivity's setup should handle most, but we might need to re-apply UI specific things
        updateScrollBarsVisibility()

        // Notify callback
        windowCallback?.onWindowSwitched(webView)

        // Ensure observers exist for restored pages where onPageFinished may not fire.
        webView.post { injectPageObservers(webView) }
        // Ensure refresh loop is running (it might have died if previous webview was detached)
        startRefreshing()

        hideWindowsOverview()
        saveAllWindowsState()
    }

    fun closeWindow(id: String) {
        val windowToRemove = windows.find { it.id == id } ?: return

        // Don't close the last window, or create a new one if we do
        val wasActive = activeWindowId == id

        windows.remove(windowToRemove)
        mediaStateByWindowId.remove(id)
        mediaLastPlayedAtByWindowId.remove(id)
        webViewsContainer.removeView(windowToRemove.webView)
        windowToRemove.webView.destroy()
        windowToRemove.thumbnail?.recycle()

        if (windows.isEmpty()) {
            createNewWindow()
        } else if (wasActive) {
            // Switch to the last window in the list
            switchToWindow(windows.last().id)
            // If overview was open, refresh it
            if (windowsOverviewContainer?.visibility == View.VISIBLE) {
                showWindowsOverview()
            }
        } else {
            // If overview was open, refresh it
            if (windowsOverviewContainer?.visibility == View.VISIBLE) {
                showWindowsOverview()
            }
        }
        saveAllWindowsState()
        if (mediaStateByWindowId.isNotEmpty() || nativeTapRadioPlaying) {
            updateMediaState(anyTrackedMediaPlaying())
        } else {
            isMediaPlaying = false
            hideMediaControls()
        }
    }

    fun saveWindowMetadataState(forceSync: Boolean = false) {
        saveAllWindowsState(forceSync = forceSync, includeWebViewState = false)
    }

    fun saveAllWindowsState(forceSync: Boolean = false, includeWebViewState: Boolean = true) {
        try {
            val root = org.json.JSONObject()
            root.put("activeId", activeWindowId)
            root.put("isDesktopMode", isDesktopMode)

            val windowsArray = org.json.JSONArray()
            val maxStateSize = 500_000 // 500KB per window max

            windows.forEach { win ->
                // Update title from WebView if available
                if (!win.webView.title.isNullOrEmpty()) {
                    win.title = win.webView.title!!
                }

                val winObj = org.json.JSONObject()
                winObj.put("id", win.id)
                winObj.put("title", win.title)
                winObj.put("url", win.webView.url ?: "")

                if (includeWebViewState) {
                    // Save full WebView state (history, etc) - with size limit
                    try {
                        val state = Bundle()
                        win.webView.saveState(state)
                        val parcel = Parcel.obtain()
                        state.writeToParcel(parcel, 0)
                        val bytes = parcel.marshall()
                        parcel.recycle()

                        // Only save state if under size limit
                        if (bytes.size < maxStateSize) {
                            val stateString = Base64.encodeToString(bytes, Base64.DEFAULT)
                            winObj.put("state", stateString)
                        } else {
                            Log.w(
                                    "Persistence",
                                    "Window ${win.id} state too large (${bytes.size} bytes), skipping state save"
                            )
                            // Don't save state, just URL - will reload on restore
                        }
                    } catch (e: Exception) {
                        Log.e("Persistence", "Error saving state for window ${win.id}", e)
                        // Continue without state for this window
                    }
                }

                windowsArray.put(winObj)
            }
            root.put("windows", windowsArray)

            // Final size check before saving
            val jsonString = root.toString()
            if (jsonString.length > 5_000_000) { // 5MB total limit
                Log.e(
                        "Persistence",
                        "Total state size too large (${jsonString.length} chars), clearing old state"
                )
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(KEY_WINDOWS_STATE)
                        .apply()
                return
            }

            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_WINDOWS_STATE, jsonString)
            if (forceSync) {
                editor.commit()
            } else {
                editor.apply()
            }

            DebugLog.d(
                    "Persistence",
                    "Saved ${windows.size} windows with${if (includeWebViewState) "" else "out"} bundles (${jsonString.length} chars)"
            )
        } catch (e: Exception) {
            Log.e("Persistence", "Error saving window state", e)
        }
    }

    fun restoreState() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_WINDOWS_STATE, null)

            if (jsonString.isNullOrEmpty()) {
                if (windows.isEmpty()) {
                    createNewWindow()
                }
                return
            }

            val root = org.json.JSONObject(jsonString)
            val savedActiveId = if (root.has("activeId")) root.getString("activeId") else null
            val restoredDesktopMode = root.optBoolean("isDesktopMode", isDesktopMode)
            isDesktopMode = restoredDesktopMode
            prefs.edit().putBoolean("isDesktopMode", isDesktopMode).apply()
            val windowsArray = root.optJSONArray("windows")

            if (windowsArray != null && windowsArray.length() > 0) {
                // Clear existing windows (default one)
                windows.toList().forEach {
                    it.webView.destroy()
                    it.thumbnail?.recycle()
                }
                windows.clear()

                webViewsContainer.removeAllViews()

                for (i in 0 until windowsArray.length()) {
                    val winObj = windowsArray.getJSONObject(i)
                    val id = winObj.getString("id")
                    val title = winObj.getString("title")
                    val rawUrl = winObj.getString("url")
                    val rawStateString = winObj.optString("state", "")

                    // Belt-and-suspenders: if this window was persisted while
                    // on a TapRadio auto-play URL (radio.html / podcasts.html /
                    // spotify.html with playurl=/autoplay=1/spotifyqueue=
                    // query params), the saved state + URL would resurrect
                    // playback the moment this WebView is restored. Redirect
                    // it to the default dashboard instead and discard the
                    // Parcelable state bundle (which holds the nav history
                    // including that same auto-play URL).
                    val isAutoplay = try {
                        MainActivity.isRadioAutoplayUrl(rawUrl)
                    } catch (_: Throwable) { false }
                    val url = if (isAutoplay) Constants.DEFAULT_URL else rawUrl
                    val stateString = if (isAutoplay) "" else rawStateString
                    if (isAutoplay) {
                        Log.w(
                            "Persistence",
                            "Dropping restored window with radio auto-play URL: $rawUrl"
                        )
                    }

                    val newWebView = InternalWebView(context)
                    configureWebView(newWebView)
                    applyBrowsingModeToWebView(newWebView, isDesktopMode)
                    // Important: notify MainActivity to attach its logic
                    windowCallback?.onWindowCreated(newWebView)

                    var restored = false
                    if (stateString.isNotEmpty()) {
                        try {
                            val bytes = Base64.decode(stateString, Base64.DEFAULT)
                            val parcel = Parcel.obtain()
                            parcel.unmarshall(bytes, 0, bytes.size)
                            parcel.setDataPosition(0)
                            val state = Bundle()
                            state.readFromParcel(parcel)
                            parcel.recycle()
                            // Restore state returns the WebBackForwardList but we don't need it
                            // explicitly
                            newWebView.restoreState(state)
                            restored = true
                        } catch (e: Exception) {
                            Log.e("Persistence", "Failed to restore webview bundle", e)
                        }
                    }

                    if (!restored) {
                        if (url.isNotEmpty()) {
                            newWebView.loadUrl(url)
                        } else {
                            newWebView.loadUrl(Constants.DEFAULT_URL)
                        }
                    }

                    val win = BrowserWindow(id = id, webView = newWebView, title = title)
                    windows.add(win)

                    // Add to container
                    newWebView.visibility = View.INVISIBLE
                    webViewsContainer.addView(
                            newWebView,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }

                if (windows.isNotEmpty()) {
                    val targetId =
                            if (savedActiveId != null && windows.any { it.id == savedActiveId }) {
                                savedActiveId
                            } else {
                                windows.last().id
                            }
                    switchToWindow(targetId)
                    syncBrowsingModeUi()
                } else {
                    // Fallback if parsing failed
                    createNewWindow()
                }
            } else if (windows.isEmpty()) {
                createNewWindow()
            }
        } catch (e: Exception) {
            Log.e("Persistence", "Error restoring window state", e)
            // Restore default if failed
            if (windows.isEmpty()) createNewWindow()
        }
    }

    private fun configureWebView(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION") // Suppress for extensive database usage
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false

        // ── Scrollbar visibility (global, applies to every page) ──
        //
        // RayNeo's WebView ships with the Android default of
        // `scrollbarFadingEnabled = true`, which fades the scrollbar
        // out within a few hundred ms of the user releasing a scroll.
        // On these glasses that fade animation is also flaky — it
        // sometimes never completes its fade-IN on a fresh page load,
        // leaving the user with no scroll affordance at all. The
        // long-running symptom users report as "the scrollbar
        // disappears" is this.
        //
        // Disabling fading + explicitly enabling the vertical scrollbar
        // + INSIDE_OVERLAY style means the bar is *always* drawn over
        // content whenever there's something to scroll, and never
        // animated. We deliberately do NOT override scrollBarSize —
        // that earlier override sized it down to 8dp which the user
        // (correctly) reported as too thin to find. Letting Android
        // use its platform default gives the standard wider rail
        // every other browser ships with.
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        webView.isScrollbarFadingEnabled = false
        webView.scrollBarStyle = WebView.SCROLLBARS_INSIDE_OVERLAY
        // Fade duration is irrelevant when fading is disabled, but we
        // belt-and-suspender this in case a future Android version
        // re-enables fading on us through its compatibility layer.
        webView.scrollBarFadeDuration = 0
        webView.scrollBarDefaultDelayBeforeFade = Int.MAX_VALUE

        webView.addJavascriptInterface(MediaInterface(this, webView), "MediaInterface")

        // On-glasses Media Library JS bridge. Per-WebView urlRef so each
        // tab's trust gate tracks that tab's current URL — the bridge is
        // called from JS on a background thread and can't touch WebView
        // state, so we stash the last-known URL here from the lifecycle
        // callbacks below. MediaFileInterceptor shares the bridge's
        // MediaLibraryService instance so /media/… URL resolution goes
        // through the exact same safe-path logic as the JS bridge.
        val mediaBridgeUrlRef = AtomicReference("")
        // Gemini 3.1 TTS client for on-glasses text-to-speech. Reads the API
        // key out of "visionclaw_prefs" / "gemini_api_key" so it matches
        // whatever the companion app has saved. Passed into the bridge so
        // MediaLibraryBridge.speakText() has a synth to delegate to.
        val ttsClient = com.TapLink.app.media.GlassesTtsClient(
            apiKeyProvider = { com.TapLink.app.media.resolveGlassesGeminiKey(context) }
        )
        // Fish.audio TTS client (cloud TTS engine #2). Pulls config out of
        // SharedPreferences on every synth call, so flipping the engine in
        // the companion app takes effect on the very next chunk. The bridge
        // routes between Fish and Gemini based on `readout_engine` plus
        // whether Fish has an API key + active voice configured.
        val fishTtsClient = com.TapLink.app.media.FishTtsClient(
            configProvider = { com.TapLink.app.media.resolveGlassesFishConfig(context) }
        )
        val mediaLibraryBridge = MediaLibraryBridge(context, mediaBridgeUrlRef, ttsClient, fishTtsClient)
        val mediaFileInterceptor = MediaFileInterceptor(context, mediaLibraryBridge.service)
        webView.addJavascriptInterface(mediaLibraryBridge, MediaLibraryBridge.JS_NAME)
        // Async TTS back-channel: wraps evaluateJavascript in webView.post so
        // the worker-thread synth can safely post completion events back into
        // JS without touching the WebView from the wrong thread.
        mediaLibraryBridge.jsEvaluator = { js ->
            webView.post { webView.evaluateJavascript(js, null) }
        }
        // photos_gallery.html → "Grant access to device photos" → bridge
        // → here. Permission requests have to launch from an Activity to
        // receive the onRequestPermissionsResult callback; if our
        // context happens to be a non-Activity (shouldn't normally),
        // we skip silently — the bridge returns the requested status,
        // and the user just won't get a system dialog.
        mediaLibraryBridge.permissionRequester = {
            try {
                val act = context as? android.app.Activity
                if (act != null) {
                    val needed = com.TapLink.app.media.DcimEnumerator
                        .requiredPermissions()
                        .filter { p ->
                            androidx.core.content.ContextCompat
                                .checkSelfPermission(act, p) !=
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                    if (needed.isNotEmpty()) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                            act, needed.toTypedArray(), 124
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // Keep WebAppInterface for referencing context/logic if needed, but primary comms via URL
        // scheme
        // Enable Native Bridge for Chat
        // GroqBridge removed

        webView.webViewClient =
                object : android.webkit.WebViewClient() {
                    /**
                     * Intercept requests to the virtual media host
                     * (appassets.androidplatform.net/media/...) and serve
                     * directly from the on-glasses Media/ folder with
                     * Range-request support. Everything else flows through
                     * normal WebView networking.
                     */
                    override fun shouldInterceptRequest(
                        view: android.webkit.WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val resp = mediaFileInterceptor.handle(request)
                        if (resp != null) return resp
                        return super.shouldInterceptRequest(view, request)
                    }

                    /**
                     * Log any load failure on our virtual host so we can tell
                     * whether a spinner hang is "interceptor never responded"
                     * vs "page loaded but the audio tag stalled".
                     */
                    override fun onReceivedError(
                        view: android.webkit.WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        val url = request?.url?.toString().orEmpty()
                        if (url.contains("appassets.androidplatform.net")) {
                            val code = try { error?.errorCode } catch (_: Exception) { null }
                            val desc = try { error?.description?.toString() } catch (_: Exception) { null }
                            android.util.Log.w(
                                "MediaFileInterceptor",
                                "onReceivedError url=$url code=$code desc=$desc"
                            )
                        }
                        super.onReceivedError(view, request, error)
                    }

                    override fun onReceivedHttpError(
                        view: android.webkit.WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        val url = request?.url?.toString().orEmpty()
                        if (url.contains("appassets.androidplatform.net")) {
                            android.util.Log.w(
                                "MediaFileInterceptor",
                                "onReceivedHttpError url=$url status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase}"
                            )
                        }
                        super.onReceivedHttpError(view, request, errorResponse)
                    }

                    override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        DebugLog.d("GroqUrl", "Checking URL: $url")

                        if (url.startsWith("taplink://chat")) {
                            DebugLog.d("GroqUrl", "Intercepted taplink://chat")
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                // Use the top-level WebAppInterface class we created
                                WebAppInterface(context, view).chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        // Intercept media file links → open in TapInsight media player
                        if (view != null && interceptMediaUrl(view, url)) return true
                        return false
                    }

                    override fun onPageStarted(
                            view: android.webkit.WebView?,
                            url: String?,
                            favicon: Bitmap?
                    ) {
                        super.onPageStarted(view, url, favicon)
                        // Tell the JS-bridge trust gate which page we're on.
                        mediaBridgeUrlRef.set(url ?: "")
                        // Task #17 (restored): a YouTube VIDEO beginning to
                        // load silences TapRadio immediately — page START,
                        // not finish, so the station never plays under the
                        // video's first seconds. Watch/shorts/autoplay URLs
                        // only; browsing YouTube home/search keeps the radio.
                        val lower = url?.lowercase(java.util.Locale.US).orEmpty()
                        if ((lower.contains("youtube.com") || lower.contains("youtu.be")) &&
                            (lower.contains("/watch") || lower.contains("/shorts/") ||
                                lower.contains("taplink_autoplay="))
                        ) {
                            (context as? MainActivity)?.stopTapRadioForYouTube("page-start")
                        }
                        resetScrollBarVisibilityMemory(url)
                        clearExternalScrollMetrics()
                        stabilizeWebViewViewportAfterNavigation(
                                targetWebView = view,
                                resetVerticalScroll = false
                        )
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            url: String?
                    ): Boolean {
                        DebugLog.d("GroqUrl", "Checking URL (deprecated): $url")
                        if (url != null && url.startsWith("taplink://chat")) {
                            DebugLog.d("GroqUrl", "Intercepted taplink://chat (deprecated)")
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                WebAppInterface(context, view).chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        if (url != null && view != null && interceptMediaUrl(view, url)) return true
                        return false
                    }

                    /**
                     * Trust the TapInsight companion server's self-signed certificate
                     * *only* when the URL is on the loopback interface (127.0.0.1,
                     * [::1] or localhost).  Without this override, top-level navigations
                     * from the dashboard to https://127.0.0.1:19110/library — and fetch()
                     * calls from media_player.html — fail with a blank screen because
                     * the WebView rejects the cert.  The key is generated inside this
                     * app's private files dir, so anything reachable only on loopback
                     * is by definition served by us.
                     */
                    override fun onReceivedSslError(
                        view: android.webkit.WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        val host = error?.url?.let { android.net.Uri.parse(it).host }?.lowercase() ?: ""
                        val isLoopback =
                            host == "127.0.0.1" ||
                            host == "localhost" ||
                            host == "[::1]" ||
                            host == "::1"
                        if (isLoopback && handler != null) {
                            android.util.Log.i(
                                "TapLink",
                                "Accepting self-signed cert for loopback URL: ${error?.url}"
                            )
                            handler.proceed()
                        } else {
                            super.onReceivedSslError(view, handler, error)
                        }
                    }

                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        mediaBridgeUrlRef.set(url ?: "")
                        try {
                            view?.let { injectPageObservers(it) }
                            stabilizeWebViewViewportAfterNavigation(
                                    targetWebView = view,
                                    resetVerticalScroll = false
                            )
                            updateScrollBarsVisibility()

                            // Record the URL the WebView is actually showing.
                            // This is the ground-truth URL that TapClaw tools
                            // need when the user later says "email me this
                            // video" — Gemini cannot see this page, so it
                            // would otherwise invent a URL from training
                            // memory.  We record YouTube watch, search, and
                            // asset pages; LastUrlBridge classifies the URL
                            // and skips obvious duplicates / blank pages.
                            LastUrlBridge.record(
                                context = view?.context,
                                url = url,
                                title = view?.title
                            )

                            // Video-quality hint: on YouTube pages,
                            // inject a small JS shim that escalates
                            // playback quality to the highest available
                            // rung. No-op on non-YouTube pages.
                            // See VideoQualityHints.kt for rationale.
                            if (view != null) {
                                YouTubeCaptionEnforcer.maybeInject(view, url)
                                VideoQualityHints.maybeApplyYouTubeQualityShim(view, url)
                                // Hermes glasses-log tail: keep the WebView
                                // pinned to the bottom across the page's
                                // ~30s self-refresh so the user doesn't have
                                // to scroll down after every reload.
                                HermesLogAutoScroll.maybeInject(view, url)
                                // This WebView renders manually-clicked
                                // YouTube links, so it needs the same
                                // YouTube treatments MainActivity's two
                                // WebViewClients install — parity gaps here
                                // were the cause of both the vanishing
                                // pause timeline and the mute-after-20s
                                // dim-mode bug. All three are idempotent.
                                if (isYoutubePlayerUrl(url.orEmpty())) {
                                    injectYouTubePausedChromeHold(view)
                                    injectYouTubeUnmuteHelper(view)
                                    YouTubeDimCaptions.install(view, url)
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TapLink", "Error in onPageFinished", e)
                        }
                    }
                }

        webView.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            // Apply SmartTube-inspired video quality / performance flags
            // (renderer priority + off-screen pre-raster). Idempotent.
            VideoQualityHints.applyMediaPerformanceSettings(this)
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setOnTouchListener { _, _ -> keyboardContainer.visibility == View.VISIBLE }
            setOnLongClickListener { true }

            setOnScrollChangeListener { _, _, _, _, _ ->
                if (isWebViewScrollEnabled()) {
                    updateScrollBarThumbs(0, 0)
                    val now = System.currentTimeMillis()
                    if (now - lastScrollBarCheckTime > scrollBarVisibilityThrottleMs) {
                        updateScrollBarsVisibility()
                        lastScrollBarCheckTime = now
                    }
                }
            }
        }
    }

    /**
     * Hold YouTube's native pause overlay (the timeline + the size /
     * fullscreen "expand" buttons) visible for a usable tap window after
     * the user pauses, on ANY YouTube watch page. Desktop YouTube exposes
     * .ytp-chrome-bottom; m.youtube.com uses a separate .player-controls /
     * ytmCustomControlsContainer layer, so both are handled here.
     *
     * This is the SAME idempotent injected block MainActivity installs in
     * its two WebViewClients — this class's WebViewClient (the one that
     * renders manually-clicked YouTube links) was the third site that
     * never got it, which left those videos with YouTube's near-instant
     * autohide. Idempotent via window.__tl_pause_chrome_bound; suppressed
     * while a TapLink Full/Theater/Mini style is active (inTapLinkMode).
     * Touches only player chrome/control containers — NOT the caption
     * windows or caption text.
     */
    private fun injectYouTubePausedChromeHold(view: WebView) {
        view.evaluateJavascript(
            """
            (function extendPausedPlayerChrome() {
                try {
                    if (window.__tl_pause_chrome_bound) return;
                    window.__tl_pause_chrome_bound = true;
                    var HOLD_MS = 6000;
                    var holdUntil = 0;
                    var holdTimer = null;

                    if (!document.getElementById('__tl_pause_chrome_style')) {
                        var s = document.createElement('style');
                        s.id = '__tl_pause_chrome_style';
                        s.textContent =
                            'html.__tl_hold_yt_chrome .ytp-chrome-bottom,' +
                            'html.__tl_hold_yt_chrome .ytp-chrome-top,' +
                            'html.__tl_hold_yt_chrome .ytp-gradient-bottom,' +
                            'html.__tl_hold_yt_chrome .ytp-gradient-top,' +
                            'html.__tl_hold_yt_chrome .ytp-chrome-controls,' +
                            'html.__tl_hold_yt_chrome .ytp-progress-bar-container,' +
                            'html.__tl_hold_yt_chrome .player-controls,' +
                            'html.__tl_hold_yt_chrome .player-controls *,' +
                            'html.__tl_hold_yt_chrome .player-controls-background,' +
                            'html.__tl_hold_yt_chrome .player-controls-content,' +
                            'html.__tl_hold_yt_chrome .ytmCustomControlsContainer,' +
                            'html.__tl_hold_yt_chrome .ytmCustomControlsContainer *,' +
                            'html.__tl_hold_yt_chrome .ytp-mobile,' +
                            'html.__tl_hold_yt_chrome .mobile-topbar-header,' +
                            'html.__tl_hold_yt_chrome ytm-player button,' +
                            'html.__tl_hold_yt_chrome ytm-watch-player button,' +
                            'html.__tl_hold_yt_chrome .html5-video-player button,' +
                            'html.__tl_hold_yt_chrome .html5-video-player [role=\"button\"],' +
                            'html.__tl_hold_yt_chrome .html5-video-player [role=\"slider\"],' +
                            'html.__tl_hold_yt_chrome .html5-video-player input[type=\"range\"],' +
                            'html.__tl_hold_yt_chrome .progress-bar,' +
                            'html.__tl_hold_yt_chrome .progress-bar-line,' +
                            'html.__tl_hold_yt_chrome .ytm-progress-bar{' +
                            'opacity:1!important;visibility:visible!important;pointer-events:auto!important}' +
                            'html.__tl_hold_yt_chrome .player-controls,' +
                            'html.__tl_hold_yt_chrome .ytmCustomControlsContainer{' +
                            'display:block!important}' +
                            'html.__tl_hold_yt_chrome .html5-video-player{' +
                            'cursor:auto!important}';
                        document.head.appendChild(s);
                    }

                    function inTapLinkMode() {
                        // Defer ONLY in true fullscreen, where the
                        // automation manages chrome itself. Theater/mini
                        // ("non-fullscreen") used to match here too, so
                        // manually-opened videos in those views had NO
                        // hold at all and the pause timeline flashed for
                        // a fraction of a second (June-12 report — same
                        // visible symptom as the original bug).
                        return !!document.getElementById('__taplink_fs_style');
                    }

                    function releaseHold() {
                        try { document.documentElement.classList.remove('__tl_hold_yt_chrome'); } catch(e) {}
                        if (holdTimer) {
                            try { clearInterval(holdTimer); } catch(e) {}
                            holdTimer = null;
                        }
                        clearForcedChromeStyles();
                    }

                    function applyHold() {
                        if (inTapLinkMode()) {
                            releaseHold();
                            return;
                        }
                        holdUntil = Date.now() + HOLD_MS;
                        try { document.documentElement.classList.add('__tl_hold_yt_chrome'); } catch(e) {}
                        keepChromeVisible();
                        if (!holdTimer) {
                            holdTimer = setInterval(function() {
                                try {
                                    if (Date.now() >= holdUntil || inTapLinkMode()) {
                                        releaseHold();
                                        return;
                                    }
                                    keepChromeVisible();
                                } catch(e) {}
                            }, 250);
                        }
                    }

                    function keepChromeVisible() {
                        try {
                            var players = document.querySelectorAll('.html5-video-player,#movie_player');
                            for (var i = 0; i < players.length; i++) {
                                players[i].classList.remove('ytp-autohide');
                                players[i].classList.add('ytp-user-active');
                                players[i].dispatchEvent(new MouseEvent('mousemove', {
                                    bubbles: true,
                                    cancelable: true,
                                    view: window,
                                    clientX: Math.max(1, Math.floor(window.innerWidth / 2)),
                                    clientY: Math.max(1, Math.floor(window.innerHeight / 2))
                                }));
                            }
                            var mobileControls = document.querySelectorAll(
                                '.player-controls,.player-controls-background,.player-controls-content,' +
                                '.ytmCustomControlsContainer,.ytp-mobile,.mobile-topbar-header,' +
                                'ytm-player button,ytm-watch-player button,.html5-video-player button,' +
                                '.html5-video-player [role=\"button\"],.html5-video-player [role=\"slider\"],' +
                                '.html5-video-player input[type=\"range\"],.progress-bar,.progress-bar-line,.ytm-progress-bar'
                            );
                            for (var j = 0; j < mobileControls.length; j++) {
                                mobileControls[j].style.setProperty('opacity', '1', 'important');
                                mobileControls[j].style.setProperty('visibility', 'visible', 'important');
                                mobileControls[j].style.setProperty('pointer-events', 'auto', 'important');
                            }
                        } catch(e) {}
                    }

                    function clearForcedChromeStyles() {
                        try {
                            var players = document.querySelectorAll('.html5-video-player,#movie_player');
                            for (var i = 0; i < players.length; i++) {
                                players[i].classList.remove('ytp-user-active');
                                players[i].classList.add('ytp-autohide');
                            }
                            var mobileControls = document.querySelectorAll(
                                '.player-controls,.player-controls-background,.player-controls-content,' +
                                '.ytmCustomControlsContainer,.ytp-mobile,.mobile-topbar-header,' +
                                'ytm-player button,ytm-watch-player button,.html5-video-player button,' +
                                '.html5-video-player [role=\"button\"],.html5-video-player [role=\"slider\"],' +
                                '.html5-video-player input[type=\"range\"],.progress-bar,.progress-bar-line,.ytm-progress-bar'
                            );
                            for (var j = 0; j < mobileControls.length; j++) {
                                mobileControls[j].style.removeProperty('opacity');
                                mobileControls[j].style.removeProperty('visibility');
                                mobileControls[j].style.removeProperty('pointer-events');
                            }
                        } catch(e) {}
                    }

                    function bindVideo(v) {
                        if (!v || v.__tl_pause_chrome_listener) return;
                        v.__tl_pause_chrome_listener = true;
                        v.addEventListener('pause', applyHold, true);
                        v.addEventListener('play', releaseHold, true);
                    }

                    function bindAll() {
                        try {
                            var vids = document.querySelectorAll('video');
                            for (var i = 0; i < vids.length; i++) bindVideo(vids[i]);
                        } catch(e) {}
                    }

                    bindAll();
                    setInterval(bindAll, 1500);
                } catch(e) {
                    console.log('[TapLink-YT] standalone pause chrome bind failed: ' + e);
                }
            })();
            """,
            null
        )
    }

    /**
     * Shared YouTube unmute helper (parity with MainActivity's clients).
     *
     * Why element-level `video.muted = false` is not enough: YouTube's
     * player keeps its OWN mute state, and its periodic state
     * reconciliation re-mutes the element ~20s in when the two disagree
     * (the crossed-out speaker icon stays on). This helper reconciles all
     * three layers — the `<video>` element, the player API
     * (`movie_player.unMute()` + volume restore, the part that prevents
     * the re-mute), and the UI mute button as a fallback — retrying for
     * 30 seconds, plus a mutation observer so swapped-in `<video>`
     * elements are caught immediately. Idempotent via
     * window.__tl_unmute_helper_bound.
     */
    private fun injectYouTubeUnmuteHelper(view: WebView) {
        view.evaluateJavascript(
            """
            (function tlEnsureUnmuted() {
                try {
                    if (window.__tl_unmute_helper_bound) return;
                    window.__tl_unmute_helper_bound = true;
                    var DEADLINE = Date.now() + 30000;

                    function playerEl() {
                        return document.getElementById('movie_player');
                    }

                    function unmuteOnce() {
                        // Layer 1: the media element itself.
                        try {
                            var vids = document.querySelectorAll('video');
                            for (var i = 0; i < vids.length; i++) {
                                var v = vids[i];
                                if (v.muted) { try { v.muted = false; } catch(e) {} }
                                if (v.volume === 0) { try { v.volume = 1.0; } catch(e) {} }
                            }
                        } catch(e) {}
                        // Layer 2: the player API — this is what stops
                        // YouTube's state reconciliation from re-muting.
                        try {
                            var p = playerEl();
                            if (p && typeof p.isMuted === 'function' && p.isMuted()) {
                                try { p.unMute(); } catch(e) {}
                                try {
                                    var vol = 0;
                                    try { vol = p.getVolume(); } catch(e2) {}
                                    if (!vol || vol < 1) p.setVolume(100);
                                } catch(e) {}
                            }
                        } catch(e) {}
                        // Layer 3: UI fallback — only click when the button
                        // explicitly offers "Unmute" so we can never mute.
                        try {
                            var btns = document.querySelectorAll(
                                '.ytp-mute-button,button.mute-button,[aria-label]');
                            for (var k = 0; k < btns.length; k++) {
                                var b = btns[k];
                                var label = (b.getAttribute('aria-label') || '') + ' ' +
                                    (b.getAttribute('title') || '') + ' ' +
                                    (b.getAttribute('data-title-no-tooltip') || '');
                                if (/\bunmute\b/i.test(label)) {
                                    try { b.click(); } catch(e) {}
                                    break;
                                }
                            }
                        } catch(e) {}
                    }

                    unmuteOnce();
                    var timer = setInterval(function() {
                        if (Date.now() > DEADLINE) { clearInterval(timer); return; }
                        unmuteOnce();
                    }, 1000);
                    try {
                        var mo = new MutationObserver(function() {
                            if (Date.now() > DEADLINE) {
                                try { mo.disconnect(); } catch(e) {}
                                return;
                            }
                            unmuteOnce();
                        });
                        mo.observe(document.documentElement || document,
                            { childList: true, subtree: true });
                    } catch(e) {}
                } catch(e) {}
            })();
            """,
            null
        )
    }

    init {
        // Initialize the first WebView
        // Initial WebView configuration
        val initialWebView = InternalWebView(context)
        webView = initialWebView
        configureWebView(webView) // Local basic config
        // The default WebView UA includes a "wv" marker (e.g.
        // "...; wv) AppleWebKit/...") which Cloudflare and other bot-
        // detection services flag immediately, locking the user out of
        // ordinary article pages. Strip it so the UA looks like normal
        // mobile Chrome — same browser engine under the hood, just
        // without the embedded-WebView signal. This is the single most
        // effective change for bot-detection avoidance: most "Just a
        // moment…" Cloudflare challenges come from the wv flag alone.
        mobileUserAgent = stripWebViewMarker(webView.settings.userAgentString)
        webView.settings.userAgentString = mobileUserAgent
        desktopUserAgent = buildDesktopUserAgentFromMobile(mobileUserAgent)

        // CRITICAL FIX: Do NOT add the initial webview to the container or windows list yet.
        // This prevents the "Dashboard flash" on startup.
        // The container starts empty.
        // restoreState() will either:
        // 1. Restore saved windows (and set active one)
        // 2. Or call createNewWindow() calls which will add a window and load the default URL.

        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        isDesktopMode = prefs.getBoolean("isDesktopMode", false)
        currentWebZoom = prefs.getFloat("webZoomLevel", 1.0f)
        updateBrowsingMode(isDesktopMode)

        // Set the background of the entire DualWebViewGroup to black
        setBackgroundColor(Color.BLACK)

        // Ensure the left eye (Activity Window) uses the same pixel format as the right eye
        // (SurfaceView)
        // This ensures consistent color saturation between both eyes.
        (context as? Activity)?.window?.setFormat(PixelFormat.RGBA_8888)

        fullScreenOverlayContainer.setOnTouchListener { _, event ->
            if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
                fullScreenTapDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }

        // Initial WebView configuration moved to configureWebView() and MainActivity

        // Configure SurfaceView for right eye mirroring
        rightEyeView.apply {
            isClickable = false
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            setupBitmap(width, height)
                            startRefreshing()
                        }

                        override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                        ) {
                            setupBitmap(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            synchronized(bitmapLock) {
                                val currentBitmap = bitmap
                                bitmap = null // Set to null first
                                currentBitmap?.let { bmp ->
                                    if (!bmp.isRecycled) {
                                        bmp.recycle()
                                    }
                                }
                            }
                            stopRefreshing()
                        }
                    }
            )
        }

        // Initialize keyboard containers
        keyboardContainer.apply {
            visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                // DebugLog.d("KeyboardDebug", "leftKeyboardContainer clicked")
            }
            setOnTouchListener { _, _ ->
                // DebugLog.d("KeyboardDebug", "leftKeyboardContainer received touch event:
                // ${event.action}")
                true
            }
        }

        // Initialize navigation bars
        leftNavigationBar =
                LayoutInflater.from(context).inflate(R.layout.navigation_bar, this, false).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, navBarHeightPx)
                    setBackgroundColor(Color.parseColor("#202020"))
                    visibility = View.VISIBLE
                    setPadding(16, 0, 16, 0)
                }

        // Initialize navigation buttons
        navButtons =
                mapOf(
                        "back" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnBack),
                                        right = leftNavigationBar.findViewById(R.id.btnBack)
                                ),
                        "forward" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnForward),
                                        right = leftNavigationBar.findViewById(R.id.btnForward)
                                ),
                        "home" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnHome),
                                        right = leftNavigationBar.findViewById(R.id.btnHome)
                                ),
                        "link" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnLink),
                                        right = leftNavigationBar.findViewById(R.id.btnLink)
                                ),
                        "settings" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnSettings),
                                        right = leftNavigationBar.findViewById(R.id.btnSettings)
                                ),
                        "refresh" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnRefresh),
                                        right = leftNavigationBar.findViewById(R.id.btnRefresh)
                                ),
                        "hide" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnHide),
                                        right = leftNavigationBar.findViewById(R.id.btnHide)
                                ),
                        "quit" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnQuit),
                                        right = leftNavigationBar.findViewById(R.id.btnQuit)
                                ),
                        "chat" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnChat),
                                        right = leftNavigationBar.findViewById(R.id.btnChat)
                                )
                )

        // Initialize all buttons with same base properties
        navButtons.values.forEach { navButton ->
            navButton.left.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
            }
            navButton.right.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
            }
        }

        // Ensure physical pointer clicks (mouse/touch) work on all nav buttons, not only via
        // cursor hit-testing.
        navButtons.forEach { (key, navButton) ->
            navButton.left.setOnClickListener { triggerNavigationAction(key, navButton) }
            if (navButton.right !== navButton.left) {
                navButton.right.setOnClickListener { triggerNavigationAction(key, navButton) }
            }
        }

        // Initialize left toggle bar
        leftToggleBar =
                LayoutInflater.from(context).inflate(R.layout.toggle_bar, this, false).apply {
                    layoutParams = LayoutParams(toggleBarWidthPx, 480 - navBarHeightPx)
                    setBackgroundColor(Color.parseColor("#202020"))
                    visibility = View.VISIBLE
                    clipToOutline = true // Add this
                    clipChildren = true // Add this
                    isClickable = true // Add this
                    isFocusable = true // Add this
                }

        // DebugLog.d("ViewDebug", "Toggle bar initialized with hash: ${leftToggleBar.hashCode()}")

        setupMaskOverlayUI()
        setupFullScreenControlsUI()

        // Set background styles - use gradient drawables for modern look
        setBackgroundColor(Color.BLACK)
        leftNavigationBar.background =
                ContextCompat.getDrawable(context, R.drawable.nav_bar_background)
        leftToggleBar.background =
                ContextCompat.getDrawable(context, R.drawable.toggle_bar_background)

        // Set up the toggle buttons with explicit configurations
        leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle).apply {
            configureToggleButton(R.string.fa_mobile_screen)
        }

        leftToggleBar.findViewById<FontIconView>(R.id.btnYouTube).apply {
            configureToggleButton(R.string.fa_glasses)
        }

        leftToggleBar.findViewById<FontIconView>(R.id.btnBookmarks).apply {
            visibility = View.VISIBLE
            setText(R.string.fa_bookmark)
            setBackgroundResource(R.drawable.nav_button_background)
            gravity = android.view.Gravity.CENTER
            setPadding(8, 8, 8, 8)
            alpha = 1.0f
            elevation = 2f
            stateListAnimator = null
        }

        // Initialize URL EditTexts
        urlEditText = setupUrlEditText(true)

        // Bring urlEditTextLeft to front
        urlEditText.bringToFront()

        // Disable text handles for both EditTexts
        disableTextHandles(urlEditText)

        //

        // Initialize the edit fields
        leftEditField =
                EditText(context).apply {
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(Color.parseColor("#303030"))
                    setTextColor(Color.WHITE)
                    visibility = View.GONE
                    setPadding(16, 12, 16, 12)

                    // Style the edit field
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#303030"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 8f
                            }
                }

        rightEditField =
                EditText(context).apply {
                    // Same styling as leftEditField
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(Color.parseColor("#303030"))
                    setTextColor(Color.WHITE)
                    visibility = View.GONE
                    setPadding(16, 12, 16, 12)
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#303030"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 8f
                            }
                }

        // Add edit fields to view hierarchy
        addView(leftEditField)
        addView(rightEditField)

        leftSystemInfoView =
                SystemInfoView(context).apply {
                    layoutParams =
                            LayoutParams(
                                            200, // Fixed initial width, will be adjusted after
                                            // measure
                                            24
                                    )
                                    .apply { gravity = Gravity.TOP or Gravity.END }
                    elevation = 900f
                    // Respect the companion toggle from first layout — the
                    // old unconditional VISIBLE flashed the bar before the
                    // first updateSystemInfoBarVisibility() pass.
                    visibility =
                            if (isBrowserSystemInfoEnabled()) View.VISIBLE else View.GONE
                }

        viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
        )

        // Make sure they're above other elements
        leftSystemInfoView.bringToFront()

        post {
            // Ensure bookmarks views are always on top when added to view hierarchy
            if (::leftBookmarksView.isInitialized) {}
            if (::chatView.isInitialized) {
                chatView.bringToFront()
            }
        }

        // Set up the container hierarchy
        leftEyeClipParent.addView(leftEyeUIContainer)
        leftEyeClipParent.addView(
                fullScreenOverlayContainer
        ) // Add to clip parent for proper clipping
        // Lock overlay added last so it draws above the fullscreen overlay.
        leftEyeClipParent.addView(lockOverlayContainer)

        // Add views to UI container
        leftEyeUIContainer.apply {
            // Add views in the correct z-order
            // Add webViewsContainer with correct position
            addView(
                    webViewsContainer,
                    FrameLayout.LayoutParams(640 - toggleBarWidthPx, LayoutParams.MATCH_PARENT)
                            .apply {
                                leftMargin = toggleBarWidthPx // Position after toggle bar
                                topMargin = unipanelTopReservePx // Reserve native HUD + chat-card lane
                                bottomMargin = navBarHeightPx // Account for nav bar
                                gravity = Gravity.TOP or Gravity.START
                            }
            )
            addView(leftToggleBar)
            // DebugLog.d("ViewDebug", "Toggle bar added to UI container with hash:
            // ${leftToggleBar.hashCode()}")

            addView(leftNavigationBar.apply { elevation = 101f })
            addView(btnShowNavBars) // Add show nav bars button
            addView(progressBar) // Add progress bar
            addView(keyboardContainer)
            addView(dialogContainer)
            addView(leftSystemInfoView)
            addView(urlEditText)
            addView(
                    maskOverlay
            ) // Add mask overlay for proper mirroring to both eyes // Add mask overlay for proper
            // mirroring to both eyes

            // Initialize ChatView here
            chatView =
                    ChatView(context).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(560, 420)
                                        .apply { // Slightly smaller than full window
                                            gravity = Gravity.CENTER
                                        }
                        visibility = View.GONE
                        elevation = 2000f // High elevation
                        keyboardListener = this@DualWebViewGroup.keyboardListener
                    }
            addView(chatView)
            chatView.disableSystemKeyboard()

            // Setup listener for Chat button
            leftNavigationBar.findViewById<View>(R.id.btnChat)?.setOnClickListener { toggleChat() }
            postDelayed(
                    {
                        initializeToggleButtons()
                        requestLayout()
                        invalidate()
                    },
                    100
            )

            post {
                leftSystemInfoView.measure(
                        MeasureSpec.makeMeasureSpec(640, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(24, MeasureSpec.EXACTLY)
                )
                updateSystemInfoBarVisibility()
                leftSystemInfoView.requestLayout()
                leftSystemInfoView.invalidate()
            }

            // Make sure container is visible and properly layered
            visibility = View.VISIBLE
            elevation = 100f // Keep it above webview
        }

        // After other view initializations

        // Add the clip parent to the main view
        addView(leftEyeClipParent)
        // REFACTORED: rightEyeView no longer added - single viewport mode
        // addView(rightEyeView) // Keep right eye view separate
        // maskOverlay now added to leftEyeUIContainer above for proper mirroring

        // Create horizontal scroll bar
        horizontalScrollBar =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(Color.TRANSPARENT) // Transparent background
                    visibility = View.GONE
                    elevation = 150f
                    isClickable = true // Prevent click propagation
                    isFocusable = false
                    isFocusableInTouchMode = false

                    // Left arrow button
                    // Left arrow button
                    val btnLeft =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_left)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnLeft)

                    // Track container with thumb
                    val trackContainer =
                            FrameLayout(context).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 20, 1f)
                                setBackgroundColor(Color.parseColor("#303030"))
                            }
                    hScrollThumb =
                            View(context).apply {
                                layoutParams =
                                        FrameLayout.LayoutParams(60, 16).apply {
                                            gravity = Gravity.CENTER_VERTICAL
                                            leftMargin = 0
                                        }
                                setBackgroundResource(R.drawable.scroll_button_background)
                            }
                    trackContainer.addView(hScrollThumb)
                    addView(trackContainer)

                    // Right arrow button
                    // Right arrow button
                    val btnRight =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_right)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnRight)

                    // Click handlers
                    btnLeft.setOnClickListener { clickScrollBarArrow { scrollPageHorizontal(-10) } }
                    btnRight.setOnClickListener { clickScrollBarArrow { scrollPageHorizontal(10) } }
                    trackContainer.setOnTouchListener { v, event ->
                        val fullWidth = v.width
                        val thumbWidth = hScrollThumb.width
                        val trackableWidth = fullWidth - thumbWidth
                        if (trackableWidth <= 0) return@setOnTouchListener true

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                beginScrollBarInteraction()
                                v.parent.requestDisallowInterceptTouchEvent(true)
                                // Immediate jump on touch down
                                val clickX = event.x
                                val clickLeft = clickX - thumbWidth / 2
                                val percent = (clickLeft / trackableWidth).coerceIn(0f, 1f)
                                updateHorizontalScroll(percent)

                                // Optimistic visual update
                                val hMargin = (percent * trackableWidth).toInt()
                                hScrollThumb.translationX = hMargin.toFloat()
                                hScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                beginScrollBarInteraction()
                                val clickX = event.x
                                val clickLeft = clickX - thumbWidth / 2
                                val percent = (clickLeft / trackableWidth).coerceIn(0f, 1f)
                                updateHorizontalScroll(percent)

                                // Optimistic visual update
                                val hMargin = (percent * trackableWidth).toInt()
                                hScrollThumb.translationX = hMargin.toFloat()
                                hScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                v.parent.requestDisallowInterceptTouchEvent(false)
                                finishScrollBarInteraction()
                                true
                            }
                            else -> false
                        }
                    }
                }

        // Create vertical scroll bar
        verticalScrollBar =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.TRANSPARENT) // Transparent background
                    visibility = View.GONE
                    elevation = 150f
                    isClickable = true // Prevent click propagation
                    isFocusable = false
                    isFocusableInTouchMode = false

                    // Up arrow button
                    // Up arrow button
                    val btnUp =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_up)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnUp)

                    // Track container with thumb
                    val trackContainer =
                            FrameLayout(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 0, 1f)
                                setBackgroundColor(Color.parseColor("#303030"))
                            }
                    vScrollThumb =
                            View(context).apply {
                                layoutParams =
                                        FrameLayout.LayoutParams(16, 60).apply {
                                            gravity = Gravity.CENTER_HORIZONTAL
                                            topMargin = 0
                                        }
                                setBackgroundResource(R.drawable.scroll_button_background)
                            }
                    trackContainer.addView(vScrollThumb)
                    addView(trackContainer)

                    // Down arrow button
                    // Down arrow button
                    val btnDown =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_down)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnDown)

                    // Click handlers
                    btnUp.setOnClickListener { clickScrollBarArrow { scrollPageVertical(-10) } }
                    btnDown.setOnClickListener { clickScrollBarArrow { scrollPageVertical(10) } }
                    trackContainer.setOnTouchListener { v, event ->
                        val fullHeight = v.height
                        val thumbHeight = vScrollThumb.height
                        val trackableHeight = fullHeight - thumbHeight
                        if (trackableHeight <= 0) return@setOnTouchListener true

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                beginScrollBarInteraction()
                                v.parent.requestDisallowInterceptTouchEvent(true)
                                // Immediate jump on touch down
                                val clickY = event.y
                                val clickTop = clickY - thumbHeight / 2
                                val percent = (clickTop / trackableHeight).coerceIn(0f, 1f)

                                DebugLog.d(
                                        "ScrollDebug",
                                        "Vertical Down: y=$clickY, height=$fullHeight, percent=$percent"
                                )

                                updateVerticalScroll(percent)

                                // Optimistic visual update
                                val vMargin = (percent * trackableHeight).toInt()
                                vScrollThumb.translationY = vMargin.toFloat()
                                vScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                beginScrollBarInteraction()
                                val clickY = event.y
                                val clickTop = clickY - thumbHeight / 2
                                val percent = (clickTop / trackableHeight).coerceIn(0f, 1f)

                                // DebugLog.d("ScrollDebug", "Vertical Move: y=$clickY,
                                // percent=$percent")

                                updateVerticalScroll(percent)

                                // Optimistic visual update
                                val vMargin = (percent * trackableHeight).toInt()
                                vScrollThumb.translationY = vMargin.toFloat()
                                vScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                v.parent.requestDisallowInterceptTouchEvent(false)
                                finishScrollBarInteraction()
                                true
                            }
                            else -> false
                        }
                    }
                }

        // Add scroll bars to UI container
        leftEyeUIContainer.addView(
                horizontalScrollBar,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 20).apply {
                    gravity = Gravity.BOTTOM
                    leftMargin = toggleBarWidthPx
                    rightMargin = 20 // Prevent overlap with vertical scroll bar
                    bottomMargin = navBarHeightPx // Sit on top of the nav bar
                }
        )
        leftEyeUIContainer.addView(
                verticalScrollBar,
                FrameLayout.LayoutParams(20, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                    bottomMargin = navBarHeightPx // End at the nav bar
                }
        )

        // Load and apply saved UI scale after view hierarchy is ready
        post {
            val savedScaleProgress =
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .getInt("uiScaleProgress", 100)
            val savedScale = 0.35f + (savedScaleProgress / 100f) * 0.65f
            updateUiScale(savedScale)
        }
    }

    // Track fullscreen toggles for debugging
    private var fullscreenEntryCount = 0
    private var lastFullscreenViewHashCode = 0

    fun showFullScreenOverlay(view: View) {
        fullscreenEntryCount++
        val viewHashCode = view.hashCode()
        // val isSameView = viewHashCode == lastFullscreenViewHashCode
        lastFullscreenViewHashCode = viewHashCode

        // Remove from current parent if any
        if (view.parent is ViewGroup) {
            // DebugLog.d("FullscreenDebug", "  Removing view from parent: ${(view.parent as
            // ViewGroup).javaClass.simpleName}")
            (view.parent as ViewGroup).removeView(view)
        }

        // Clear any existing children
        if (fullScreenOverlayContainer.childCount > 0) {
            // DebugLog.d("FullscreenDebug", "  Clearing ${fullScreenOverlayContainer.childCount}
            // existing children from container")
            fullScreenOverlayContainer.removeAllViews()
        }

        // Add the new view
        fullScreenOverlayContainer.addView(
                view,
                FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        )

        // Add the full screen controls overlay
        if (::fullScreenControlsContainer.isInitialized) {
            // Remove from parent if it was already added (defensive)
            (fullScreenControlsContainer.parent as? ViewGroup)?.removeView(
                    fullScreenControlsContainer
            )

            fullScreenOverlayContainer.addView(fullScreenControlsContainer)
            fullScreenControlsContainer.visibility = View.VISIBLE
            if (::fullScreenMediaControls.isInitialized) {
                fullScreenMediaControls.visibility =
                        if (suppressFullscreenMediaControls) View.GONE else View.VISIBLE
            }
            fullScreenControlsContainer.bringToFront()
        }

        // DebugLog.d("FullscreenDebug", "  View added. Container child count:
        // ${fullScreenOverlayContainer.childCount}")

        previousFullScreenVisibility.clear()
        DebugLog.d("FullscreenDebug", "Hiding ${fullScreenHiddenViews.size} UI elements")
        fullScreenHiddenViews.forEach { target ->

            // DebugLog.d("FullscreenDebug", "  Hiding $name (was ${if (target.visibility ==
            // View.VISIBLE) "VISIBLE" else "GONE/INVISIBLE"})")
            previousFullScreenVisibility[target] = target.visibility
            // Use GONE for everything to maximize power saving (remove from layout)
            target.visibility = View.GONE
        }

        fullScreenOverlayContainer.visibility = View.VISIBLE
        fullScreenOverlayContainer.elevation = 2000f
        fullScreenOverlayContainer.bringToFront()

        // Force refresh to ensure the fullscreen content is captured
        post {
            fullScreenOverlayContainer.invalidate()
            fullScreenOverlayContainer.requestLayout()
            startRefreshing()
            // DebugLog.d("FullscreenDebug", "  Post-show refresh triggered")
        }

        // DebugLog.d("FullscreenDebug", "About to call hideSystemUI()")
        hideSystemUI()

        // Power saving: reduce refresh rate and notify listener
        fullscreenListener?.onEnterFullscreen()
        updateRefreshRate()
    }

    fun hideFullScreenOverlay() {

        // Get reference to the view being removed for logging
        val removedView =
                if (fullScreenOverlayContainer.childCount > 0) {
                    fullScreenOverlayContainer.getChildAt(0)
                } else null

        if (removedView != null) {
            // DebugLog.d("FullscreenDebug", "  Removing view: ${removedView.javaClass.simpleName},
            // hashCode: ${removedView.hashCode()}")
        }

        fullScreenOverlayContainer.removeAllViews()

        // Use INVISIBLE instead of GONE to keep the container surface attached
        // This may help prevent surface corruption on second fullscreen entry
        fullScreenOverlayContainer.visibility = View.INVISIBLE
        fullScreenOverlayContainer.elevation = 0f

        previousFullScreenVisibility.forEach { (target, visibility) ->

            // DebugLog.d("FullscreenDebug", "  Restoring $name to ${if (visibility == View.VISIBLE)
            // "VISIBLE" else "GONE/INVISIBLE"}")
            target.visibility = visibility
        }
        previousFullScreenVisibility.clear()

        // Force WebView to redraw
        webView.invalidate()
        webView.requestLayout()

        // Force the entire UI container to relayout and redraw
        leftEyeUIContainer.invalidate()
        leftEyeUIContainer.requestLayout()

        // Also refresh the parent to ensure proper alignment
        leftEyeClipParent.invalidate()
        leftEyeClipParent.requestLayout()

        // Force a full view hierarchy refresh
        this.invalidate()
        this.requestLayout()

        // Restart the mirroring refresh with a slight delay to let layout complete
        postDelayed(
                {
                    // Reset capture throttling so next capture runs immediately
                    lastCaptureTime = 0L

                    // Force bitmap recreation on next capture
                    synchronized(bitmapLock) {
                        bitmap?.recycle()
                        bitmap = null
                    }

                    startRefreshing()
                    // DebugLog.d("FullscreenDebug", "  Post-hide refresh triggered")
                },
                300
        ) // Small delay to let layout settle

        hideSystemUI()

        // Restore normal refresh rate and notify listener
        fullscreenListener?.onExitFullscreen()
        updateRefreshRate()
        restoreScrollBarsAfterFullscreen()

        // DebugLog.d("FullscreenDebug", "hideFullScreenOverlay complete")
    }

    fun restoreScrollBarsAfterFullscreen() {
        isInteractingWithScrollBar = false
        removeCallbacks(scrollBarSettleRunnable)
        clearExternalScrollMetrics()
        lastScrollBarCheckTime = 0L
        resetScrollBarVisibilityMemory(webView.url)
        injectPageObservers(webView)
        webView.evaluateJavascript(
                """
            (function() {
                try {
                    // ROOT CAUSE of "scroll dead after fullscreen": the CSS
                    // fullscreen (enterCssFullscreen) injects a style node that
                    // pins html,body{overflow:hidden!important}. On some exit
                    // routes that node is never removed, so the page — and the
                    // custom scroll bar that mirrors it — stays frozen even
                    // after leaving fullscreen. Strip it here, the single
                    // chokepoint every fullscreen-exit path runs through.
                    //
                    // We deliberately remove ONLY __taplink_fs_style and never
                    // the Theater/Mini nodes: Full->Theater/Mini transitions
                    // call exitImmersiveMode (which lands here) and then inject
                    // the Theater/Mini style immediately AFTER, so touching
                    // those ids would strip a freshly-applied mode.
                    var fs = document.getElementById('__taplink_fs_style');
                    if (fs && fs.parentNode) fs.parentNode.removeChild(fs);
                    var de = document.documentElement, b = document.body;
                    if (de && de.style) { de.style.overflow = ''; de.style.overflowY = ''; de.style.overflowX = ''; }
                    if (b && b.style) { b.style.overflow = ''; b.style.overflowY = ''; b.style.overflowX = ''; }
                } catch (e) {}
                try {
                    if (window.__taplinkReportScroll) window.__taplinkReportScroll();
                    if (window.__taplinkWarmupScroll) window.__taplinkWarmupScroll();
                } catch (e) {}
            })();
            """.trimIndent(),
                null
        )
        longArrayOf(0L, 120L, 350L, 800L).forEach { delayMs ->
            postDelayed({
                updateScrollBarsVisibility(force = true)
                updateScrollBarThumbs(0, 0)
            }, delayMs)
        }
    }

    private fun updateRefreshRate() {
        val isFullscreen = fullScreenOverlayContainer.visibility == View.VISIBLE
        val now = System.currentTimeMillis()
        val isIdle = (now - lastUserInteractionTime) > idleThresholdMs

        // With BinocularSbsLayout handling SBS rendering directly (no PixelCopy),
        // the refresh loop only drives scrollbar checks and cursor blink.
        // Lower rates save CPU/GPU for audio decoding and reduce thermal throttling.
        //
        // 1. Screen masked + media playing: 0.5fps (2000ms) — projector is
        //    off so nothing is visible, AND the audio decoder needs all
        //    the main-thread headroom it can get. Aggressive slowdown
        //    here eliminates the periodic ~15s skips users hear when
        //    listening with the screen dimmed.
        // 2. Screen masked (no media): 1fps (1000ms) — projector off,
        //    nothing to draw, but still cycle slow enough that the
        //    scrollbar self-check inside captureLeftEyeContent gets a
        //    chance to run on entry/exit transitions.
        // 3. Scrolling: 60fps (16ms) - smooth scroll bar tracking
        // 4. Idle and not playing media: 10fps (100ms)
        // 5. Media playing (audio/video, screen on): 4fps (250ms) —
        //    scrollbars rarely change, and freeing the main thread +
        //    GPU eliminates audio-thread starvation.
        // 6. Anchored browsing: 30fps (33ms) - responsive scroll bars
        // 7. Default: 30fps (33ms)
        refreshInterval =
                when {
                    isScreenMasked && isMediaPlaying -> maskedMediaRefreshIntervalMs
                    isScreenMasked -> maskedRefreshIntervalMs
                    isInScrollMode -> 16L
                    isIdle && !isMediaPlaying -> idleRefreshIntervalMs
                    isMediaPlaying -> 250L
                    isAnchored && !isFullscreen -> 33L
                    else -> 33L
                }
    }

    /** Call this from touch handlers to reset idle timer */
    fun noteUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        // If we were in idle mode, restore normal refresh rate
        updateRefreshRate()
    }

    private fun hideSystemUI() {
        val activity =
                context as? Activity
                        ?: run {
                            Log.w(
                                    "FullscreenDebug",
                                    "Cannot hide system UI - context is not an Activity"
                            )
                            return
                        }

        post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30+) - Use WindowInsetsController
                    // CRITICAL: Must set decorFitsSystemWindows to false first
                    @Suppress("DEPRECATION") activity.window.setDecorFitsSystemWindows(false)

                    activity.window.insetsController?.let { controller ->
                        controller.hide(
                                android.view.WindowInsets.Type.statusBars() or
                                        android.view.WindowInsets.Type.navigationBars()
                        )
                        controller.systemBarsBehavior =
                                android.view.WindowInsetsController
                                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        DebugLog.d("FullscreenDebug", "System UI hidden (API 30+)")
                    }
                            ?: Log.w("FullscreenDebug", "WindowInsetsController is null!")
                } else {
                    // Older Android versions - Use deprecated flags
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                            (View.SYSTEM_UI_FLAG_FULLSCREEN or
                                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    DebugLog.d("FullscreenDebug", "System UI hidden (legacy API)")
                }
            } catch (e: Exception) {
                Log.e("FullscreenDebug", "Error hiding system UI", e)
            }
        }
    }

    private fun showSystemUI() {
        val activity =
                context as? Activity
                        ?: run {
                            Log.w(
                                    "FullscreenDebug",
                                    "Cannot show system UI - context is not an Activity"
                            )
                            return
                        }

        post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30+) - Use WindowInsetsController
                    // Restore decorFitsSystemWindows
                    @Suppress("DEPRECATION") activity.window.setDecorFitsSystemWindows(false)

                    activity.window.insetsController?.show(
                            android.view.WindowInsets.Type.statusBars() or
                                    android.view.WindowInsets.Type.navigationBars()
                    )
                    DebugLog.d("FullscreenDebug", "System UI shown (API 30+)")
                } else {
                    // Older Android versions - Clear flags
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    DebugLog.d("FullscreenDebug", "System UI shown (legacy API)")
                }
            } catch (e: Exception) {
                Log.e("FullscreenDebug", "Error showing system UI", e)
            }
        }
    }

    fun isAudioVisualizerShown(): Boolean {
        val v = breathingVisualizer
        return v != null && v.visibility == View.VISIBLE
    }

    /**
     * Show the night-sky breathing visualizer over the dim mask (swipe UP
     * on the right temple pad while dimmed; swipe DOWN returns to dim).
     * Attaches to the audio session from [audioSessionIdProvider], or the
     * global output mix (session 0) when none is available.
     */
    fun showAudioVisualizer() {
        val view = breathingVisualizer ?: BreathingVisualizerView(context).also {
            breathingVisualizer = it
            maskOverlay.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        // The mask overlay's custom layout pass never measures children
        // added after layout — the same "w=0 h=0 even when VISIBLE" quirk
        // the dim clock has a safety net for — so explicitly size the view
        // to the overlay's bounds on every show. The view additionally
        // self-heals per frame if it still finds itself zero-sized.
        val w = if (maskOverlay.width > 0) maskOverlay.width else 640
        val h = if (maskOverlay.height > 0) maskOverlay.height else height
        if (w > 0 && h > 0) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, w, h)
        }
        view.visibility = View.VISIBLE
        view.bringToFront()
        // Nothing but the night sky on this screen: hide the caption line
        // and the now-playing title immediately at swipe-up (the gates in
        // updateMaskCaption / refreshMaskedNowPlaying keep them hidden for
        // as long as the visualizer is showing).
        if (::maskCaptionText.isInitialized) {
            maskCaptionText.visibility = View.INVISIBLE
        }
        if (::maskNowPlayingText.isInitialized) {
            maskNowPlayingText.visibility = View.INVISIBLE
            lastShownMaskLabel = null
        }
        val sessionId = runCatching { audioSessionIdProvider?.invoke() }.getOrNull() ?: 0
        view.start(sessionId)
    }

    fun hideAudioVisualizer() {
        breathingVisualizer?.let {
            it.stop()
            it.visibility = View.GONE
        }
        // Swiping back to dim: bring the title back right away; the
        // caption line returns on the engine's next push/heartbeat.
        if (isScreenMasked) {
            refreshMaskedNowPlaying()
        }
    }

    fun maskScreen() {
        if (isScreenMasked) return  // Idempotent: prevent duplicate handler accumulation
        isScreenMasked = true
        maskOverlay.visibility = View.VISIBLE
        maskOverlay.bringToFront()
        // Hide both cursor views
        leftToggleBar.findViewById<FontIconView>(R.id.btnMask)?.setText(R.string.fa_eye_slash)
        keepScreenOn = true
        updatePlaybackWakeLocks()
        // Belt-and-suspenders: clear cache + force the text widget into
        // a known fresh state on every entry. Some refresh paths bail
        // early when the cached label matches, so a stale `lastShownMaskLabel`
        // from a previous session would prevent the very first label
        // of *this* session from being committed to the TextView.
        lastShownMaskLabel = null
        if (::maskNowPlayingText.isInitialized) {
            maskNowPlayingText.bringToFront()
        }
        refreshMaskedNowPlaying()
        refreshMaskedNowPlayingFromJs()
        // Start periodic now-playing/caption refresh — remove first to guarantee single handler.
        removeCallbacks(maskNowPlayingPeriodicRefresh)
        postDelayed(maskNowPlayingPeriodicRefresh, maskedNowPlayingRefreshDelayMs())
        // Reset double-tap state so a stale single-tap doesn't fire an
        // unintended exit on first interaction after entering dim mode.
        lastMaskOverlayTapTime = 0L
        // Render the clock + battery once on entry, then keep them
        // refreshed every 30 s via the runnable.
        updateMaskClockAndBattery()
        removeCallbacks(maskClockBatteryRefresh)
        postDelayed(maskClockBatteryRefresh, 30_000L)
        // Start the dim caption ticker (DimCaptionEngine → mask caption
        // line). Reset its memory first so a line from the previous dim
        // session can't suppress the first write of this one.
        lastDimCaptionLine = null
        dimCaptionOwnsSlot = false
        removeCallbacks(dimCaptionTick)
        post(dimCaptionTick)
        updateRefreshRate()
    }

    fun unmaskScreen() {
        isScreenMasked = false
        // Swiping/leaving dim mode always closes the visualizer too.
        hideAudioVisualizer()
        removeCallbacks(dimCaptionTick)
        lastDimCaptionLine = null
        dimCaptionOwnsSlot = false
        updatePlaybackWakeLocks()
        removeCallbacks(maskNowPlayingPeriodicRefresh)
        removeCallbacks(maskClockBatteryRefresh)
        lastMaskOverlayTapTime = 0L
        lastShownMaskLabel = null
        pendingMaskFallbackStation = null
        lastMaskedDomTitle = null
        lastMaskedDomTitleUrl = null
        lastMaskedDomTitleAt = 0L
        maskOverlay.visibility = View.GONE
        if (::maskNowPlayingText.isInitialized) {
            // INVISIBLE not GONE — see setupMaskOverlayUI for why.
            // The TextView must remain in the parent's measure path
            // even when hidden so its dimensions are non-zero by the
            // time we flip to VISIBLE on the next dim-mode entry.
            maskNowPlayingText.visibility = View.INVISIBLE
        }
        if (::maskCaptionText.isInitialized) {
            maskCaptionText.visibility = View.INVISIBLE
        }
        // Let MainActivity handle cursor visibility restoration - cursors will be shown
        // if they were visible before masking through updateCursorPosition call
        leftToggleBar.findViewById<FontIconView>(R.id.btnMask)?.setText(R.string.fa_eye)
        keepScreenOn = false
        updateRefreshRate()
    }

    fun isScreenMasked() = isScreenMasked

    fun setHostPaused(paused: Boolean) {
        if (isHostPaused == paused) return
        isHostPaused = paused
        updatePlaybackWakeLocks()
    }

    fun isFullScreenOverlayVisible() = fullScreenOverlayContainer.visibility == View.VISIBLE

    // ── Swipe-unlock boot screen ──────────────────────────────────────────
    /** Show [view] (a LockScreenView) full-bleed in both eyes, above all UI. */
    fun showLockScreen(view: View) {
        if (view.parent is ViewGroup) (view.parent as ViewGroup).removeView(view)
        lockOverlayContainer.removeAllViews()
        lockOverlayContainer.addView(
                view,
                FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        )
        lockOverlayContainer.visibility = View.VISIBLE
        lockOverlayContainer.elevation = 3000f
        lockOverlayContainer.bringToFront()
        lockOverlayContainer.invalidate()
        startRefreshing()
    }

    /** Remove the lock screen and let the browser UI show through again. */
    fun hideLockScreen() {
        lockOverlayContainer.removeAllViews()
        lockOverlayContainer.visibility = View.GONE
        lockOverlayContainer.elevation = 0f
        leftEyeUIContainer.invalidate()
        leftEyeUIContainer.requestLayout()
        this.invalidate()
        startRefreshing()
    }

    fun isLockScreenVisible() = lockOverlayContainer.visibility == View.VISIBLE

    fun dispatchMaskOverlayTouch(screenX: Float, screenY: Float) {
        // Global debounce: prevent the same physical tap from triggering this method
        // multiple times via different code paths (maskOverlay listener + MainActivity handlers)
        val now = SystemClock.uptimeMillis()
        if (now - lastMaskOverlayDispatchTime < MASK_OVERLAY_DISPATCH_DEBOUNCE_MS) return
        lastMaskOverlayDispatchTime = now

        val location = IntArray(2)
        maskOverlay.getLocationOnScreen(location)
        val scale = uiScale

        // Convert to local coordinates relative to mask overlay
        // val localX = screenX - location[0]
        // val localY = screenY - location[1]

        // DebugLog.d("MediaControls", "dispatchMaskOverlayTouch at local ($localX, $localY), scale:
        // $scale")

        // Check unmask button hit (account for scale in button dimensions)
        val unmaskLocation = IntArray(2)
        btnMaskUnmask.getLocationOnScreen(unmaskLocation)
        val unmaskWidth = btnMaskUnmask.width * scale
        val unmaskHeight = btnMaskUnmask.height * scale
        if (screenX >= unmaskLocation[0] &&
                        screenX <= unmaskLocation[0] + unmaskWidth &&
                        screenY >= unmaskLocation[1] &&
                        screenY <= unmaskLocation[1] + unmaskHeight
        ) {
            // DebugLog.d("MediaControls", "Unmask button pressed")
            unmaskScreen()
            return
        }

        // Check media control buttons
        if (maskMediaControlsContainer.visibility == View.VISIBLE) {
            val controlsLocation = IntArray(2)
            maskMediaControlsContainer.getLocationOnScreen(controlsLocation)

            // Iterate through children (the media buttons)
            for (i in 0 until maskMediaControlsContainer.childCount) {
                val button = maskMediaControlsContainer.getChildAt(i)
                if (button.visibility != View.VISIBLE) continue

                val btnLocation = IntArray(2)
                button.getLocationOnScreen(btnLocation)
                val btnWidth = button.width * scale
                val btnHeight = button.height * scale

                if (screenX >= btnLocation[0] &&
                                screenX <= btnLocation[0] + btnWidth &&
                                screenY >= btnLocation[1] &&
                                screenY <= btnLocation[1] + btnHeight
                ) {
                    // DebugLog.d("MediaControls", "Media button $i pressed")
                    button.performClick()
                    return
                }
            }
        }

        // DebugLog.d("MediaControls", "Touch on mask overlay but not on any button")
    }

    // ── Dim-mode gesture handlers ───────────────────────────────────
    // Public entry points called from MainActivity's dim-mode
    // GestureDetector (see MainActivity.maskedGestureDetector). All
    // gesture detection happens at the activity level so cyttsp5
    // (main touchpad) and cyttsp6 (temple controller) inputs route
    // through the same path without one of the early dispatchTouchEvent
    // short-circuits swallowing them.
    //
    // We delegate next/prev to the original button click handlers via
    // .performClick() so the elaborate per-site JS (YouTube SPA
    // navigation, Spotify selectors, radio handlers) stays in one
    // place and isn't duplicated. The buttons themselves are no
    // longer attached to the maskOverlay view hierarchy but are still
    // constructed in memory specifically for this delegation.

    /** Swipe LEFT → next track / next radio station / next video.
     *
     *  Earlier this delegated to btnMaskNextTrack.performClick(), but
     *  performClick on a View that's never been attached to the
     *  window hierarchy is fragile — Android's input dispatch / focus
     *  system sometimes silently no-ops the call. We now run the same
     *  per-site JS the button's click listener runs, directly against
     *  the active media WebView, so the swipe path doesn't depend on
     *  any view-tree state.
     */
    fun onMaskSwipeNext() {
        try {
            val targetWebView = getMediaControlWebView()
            val url = targetWebView.url.orEmpty()
            val isYoutube = url.contains("youtube.com", ignoreCase = true) ||
                url.contains("youtu.be", ignoreCase = true)
            val isMediaPlayer = url.contains("media_player.html", ignoreCase = true)
            val isSpotify = isSpotifyPlayerUrl(url)
            val activity = resolveHostingActivity()
            val hasNextInQueue = activity?.hasNextYoutubePlaylistEntry() == true
            val queueActive = activity?.hasActiveYoutubePlaylist() == true
            android.util.Log.d(
                "DimMaskHud",
                "onMaskSwipeNext: youtube=$isYoutube mediaPlayer=$isMediaPlayer queue=$queueActive hasNext=$hasNextInQueue url=$url"
            )

            if (isYoutube) {
                if (hasNextInQueue) {
                    // Gemini built a queue and there's a next entry —
                    // step through it using the SAME path the hijacked
                    // next-button uses so the playlist index advances
                    // properly and the watch-page chrome refreshes.
                    targetWebView.evaluateJavascript(
                        "(function(){try{window.GroqBridge.playNextInPlaylist();return 'queue-next';}catch(e){return 'err:'+e;}})();"
                    ) { r ->
                        android.util.Log.d("DimMaskHud", "swipe-next queue → ${r?.trim('"', ' ')}")
                    }
                    scheduleTrackChangeRefresh()
                    return
                }
                if (queueActive) {
                    // Queue exists but we're at its end — wrap to the
                    // first suggested up-next video instead of stalling.
                    android.util.Log.d("DimMaskHud", "swipe-next queue exhausted, using YouTube up-next")
                }
                // No queue (or queue exhausted) — navigate to YouTube's
                // suggested up-next / autoplay video. Falls back to
                // ".ytp-next-button" → "ended" → seek +10.
                navigateYoutubeNextSuggested(targetWebView)
                scheduleTrackChangeRefresh()
                return
            }
            if (isMediaPlayer) {
                // Local Media Library track. We deliberately call
                // gestureNextTrack rather than the button-UX playNext —
                // playNext silently no-ops past the end of the queue
                // unless repeatMode is "all", whereas gesture handlers
                // always wrap around (a swipe past the last track
                // should never feel "stuck"). gesture* functions are
                // exposed by media_player.html for exactly this path.
                targetWebView.evaluateJavascript(
                    "(function(){try{if(typeof window.gestureNextTrack==='function'){return window.gestureNextTrack();}return 'no-fn';}catch(e){return 'err:'+e;}})();"
                ) { r ->
                    android.util.Log.d("DimMaskHud", "swipe-next media_player → ${r?.trim('"', ' ')}")
                }
                scheduleTrackChangeRefresh()
                return
            }
            if (isSpotify) {
                targetWebView.evaluateJavascript(
                    "(function(){try{if(typeof window.gestureNextTrack==='function'){return window.gestureNextTrack();}if(typeof window.tapSpotifyNext==='function'){return window.tapSpotifyNext();}return 'no-fn';}catch(e){return 'err:'+e;}})();"
                ) { r ->
                    android.util.Log.d("DimMaskHud", "swipe-next spotify → ${r?.trim('"', ' ')}")
                }
                scheduleTrackChangeRefresh()
                return
            }
            // Non-YouTube media (TapRadio etc.) — keep prev/next station
            // semantics via the existing per-site bridge JS.
            evaluateMediaControlCommand(
                targetWebView,
                "(function(){ var m=document.querySelector('video,audio'); if(m){ m.currentTime+=10; } })();",
                "(function(){ if(window.handleNext){ window.handleNext(); } else if(window.nextStation){ window.nextStation(); } })();"
            )
            scheduleTrackChangeRefresh()
        } catch (e: Exception) {
            android.util.Log.w("DimMaskHud", "onMaskSwipeNext failed", e)
        }
    }

    /** Swipe RIGHT → previous track / previous station / previous video. */
    fun onMaskSwipePrev() {
        try {
            val targetWebView = getMediaControlWebView()
            val url = targetWebView.url.orEmpty()
            val isYoutube = url.contains("youtube.com", ignoreCase = true) ||
                url.contains("youtu.be", ignoreCase = true)
            val isMediaPlayer = url.contains("media_player.html", ignoreCase = true)
            val isSpotify = isSpotifyPlayerUrl(url)
            val activity = resolveHostingActivity()
            val hasPrevInQueue = activity?.hasPrevYoutubePlaylistEntry() == true
            val queueActive = activity?.hasActiveYoutubePlaylist() == true
            android.util.Log.d(
                "DimMaskHud",
                "onMaskSwipePrev: youtube=$isYoutube mediaPlayer=$isMediaPlayer queue=$queueActive hasPrev=$hasPrevInQueue url=$url"
            )

            if (isYoutube) {
                if (hasPrevInQueue) {
                    targetWebView.evaluateJavascript(
                        "(function(){try{window.GroqBridge.playPrevInPlaylist();return 'queue-prev';}catch(e){return 'err:'+e;}})();"
                    ) { r ->
                        android.util.Log.d("DimMaskHud", "swipe-prev queue → ${r?.trim('"', ' ')}")
                    }
                    scheduleTrackChangeRefresh()
                    return
                }
                // No previous in queue (either no queue, or we're at
                // index 0) — seek to start of current video so the
                // user gets feedback that the gesture registered.
                seekYoutubeBy(targetWebView, -10.0)
                return
            }
            if (isMediaPlayer) {
                // Mirror of the next-track branch. We use
                // gesturePrevTrack rather than the button-UX playPrev:
                // playPrev restarts the current track if the user is
                // more than ~3s in, which is right for a thumb on a
                // ⏮ button but wrong for a wrist swipe (the user
                // wants "previous track" regardless of position).
                // gesturePrev always navigates and wraps at index 0.
                targetWebView.evaluateJavascript(
                    "(function(){try{if(typeof window.gesturePrevTrack==='function'){return window.gesturePrevTrack();}return 'no-fn';}catch(e){return 'err:'+e;}})();"
                ) { r ->
                    android.util.Log.d("DimMaskHud", "swipe-prev media_player → ${r?.trim('"', ' ')}")
                }
                scheduleTrackChangeRefresh()
                return
            }
            if (isSpotify) {
                targetWebView.evaluateJavascript(
                    "(function(){try{if(typeof window.gesturePrevTrack==='function'){return window.gesturePrevTrack();}if(typeof window.tapSpotifyPrev==='function'){return window.tapSpotifyPrev();}return 'no-fn';}catch(e){return 'err:'+e;}})();"
                ) { r ->
                    android.util.Log.d("DimMaskHud", "swipe-prev spotify → ${r?.trim('"', ' ')}")
                }
                scheduleTrackChangeRefresh()
                return
            }
            // Non-YouTube media
            evaluateMediaControlCommand(
                targetWebView,
                "(function(){ var m=document.querySelector('video,audio'); if(m){ m.currentTime=Math.max(0, m.currentTime-10); } })();",
                "(function(){ if(window.handlePrev){ window.handlePrev(); } else if(window.prevStation){ window.prevStation(); } })();"
            )
            scheduleTrackChangeRefresh()
        } catch (e: Exception) {
            android.util.Log.w("DimMaskHud", "onMaskSwipePrev failed", e)
        }
    }

    // Dim-mode "show lyrics" gesture intentionally removed (user
    // 2026-05-30). Synced lyrics auto-load on track change and the active
    // line is rendered in dim mode by [refreshMaskedNowPlaying]; no
    // explicit gesture is required.

    /**
     * When there's no Gemini-built queue (or the queue has finished),
     * step to YouTube's own "Up Next" suggested video so swipe-LEFT
     * still feels like "next song" instead of just rewinding. Tries
     * three strategies in order: navigate to the up-next link,
     * trigger the player's next-button, then synthesize an "ended"
     * event so YouTube's autoplay takes over.
     */
    private fun navigateYoutubeNextSuggested(webView: WebView) {
        val js = """
            (function() {
                var beforeUrl = window.location.href;
                var forceReloadIfUrlAdvanced = function(delayMs) {
                    setTimeout(function() {
                        try {
                            var now = window.location.href;
                            if (now !== beforeUrl) { window.location.replace(now); }
                        } catch(e) {}
                    }, delayMs);
                };
                // Strategy A: navigate directly to first up-next link
                var upLink =
                    document.querySelector('ytd-compact-autoplay-renderer a#thumbnail[href*="/watch"]') ||
                    document.querySelector('#related ytd-compact-video-renderer a#thumbnail[href*="/watch"]') ||
                    document.querySelector('ytd-compact-video-renderer a#thumbnail[href*="/watch"]') ||
                    document.querySelector('ytm-compact-autoplay-renderer a[href*="/watch"]');
                if (upLink && upLink.href) {
                    window.location.href = upLink.href;
                    return 'up-link';
                }
                // Strategy B: click the player's next-button (works in
                // mixes / radio-style auto-generated queues)
                var nextBtn = document.querySelector('.ytp-next-button:not([aria-disabled="true"])');
                if (nextBtn) {
                    try { nextBtn.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window})); }
                    catch(e) { nextBtn.click(); }
                    forceReloadIfUrlAdvanced(1800);
                    return 'next-btn';
                }
                // Strategy C: fire 'ended' so YouTube autoplay advances
                var media = document.querySelector('video, audio');
                if (media && isFinite(media.duration)) {
                    try {
                        media.currentTime = Math.max(0, media.duration - 0.05);
                        media.dispatchEvent(new Event('ended', {bubbles:true}));
                    } catch(e) {}
                    forceReloadIfUrlAdvanced(2500);
                    return 'ended-event';
                }
                return 'no-strategy';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { r ->
            android.util.Log.d("DimMaskHud", "youtube-up-next → ${r?.trim('"', ' ')}")
        }
    }

    /**
     * Seek the active YouTube video by [deltaSeconds] (positive or
     * negative). YouTube's html5-video-player exposes seekBy() which
     * is the SAME function the player's own keyboard shortcuts call,
     * so it always works regardless of whether the page has been fully
     * built out into the SPA chrome. Falls back to direct
     * <video>.currentTime mutation when the player API isn't available.
     */
    private fun seekYoutubeBy(webView: WebView, deltaSeconds: Double) {
        val js = """
            (function() {
                var d = $deltaSeconds;
                var p = document.getElementById('movie_player') ||
                        document.querySelector('.html5-video-player');
                if (p && typeof p.seekBy === 'function') {
                    try { p.seekBy(d); return 'api-seek:' + d; } catch(e) {}
                }
                if (p && typeof p.getCurrentTime === 'function' && typeof p.seekTo === 'function') {
                    try {
                        var t = p.getCurrentTime();
                        p.seekTo(Math.max(0, t + d), true);
                        return 'api-seekTo:' + (t + d);
                    } catch(e) {}
                }
                var v = document.querySelector('video');
                if (v) {
                    var nt = Math.max(0, (v.currentTime || 0) + d);
                    if (isFinite(v.duration)) nt = Math.min(nt, v.duration - 0.5);
                    try { v.currentTime = nt; return 'video-seek:' + nt; } catch(e) {}
                }
                return 'no-video';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            android.util.Log.d("DimMaskHud", "seekYoutubeBy(${deltaSeconds}s) -> ${result?.trim('"', ' ')}")
        }
    }

    /**
     * Single-tap → toggle play/pause based on the active media context.
     *
     * Routing priority (URL-first, not isMediaPlaying-first — the JS
     * media-state bridge is unreliable on YouTube because YouTube uses
     * MSE/custom player chrome that doesn't always fire stock <video>
     * play/pause events the bridge listens for, leaving isMediaPlaying
     * stale-false even while the user is watching a video):
     *
     *   1. URL is YouTube/youtu.be → toggle the <video> element directly
     *      via JS. Always wins over the fallback path so we never start
     *      a radio station while the user is on YouTube.
     *   2. Native TapRadio session is active or paused → pause/resume
     *      via the native ExoPlayer.
     *   3. Other site with a tracked media element playing →
     *      pauseMedia()/playMedia() through the existing per-site router.
     *   4. No media context AND a fallback station is advertised in the
     *      HUD → launch that station (the "tap to start playing this"
     *      affordance for empty dim mode).
     *   5. Otherwise → playMedia() catch-all.
     */
    fun onMaskSingleTapPlayPause() {
        try {
            val webView = try { getMediaControlWebView() } catch (_: Exception) { null }
            val url = webView?.url.orEmpty()
            val isYoutube = url.contains("youtube.com", ignoreCase = true) ||
                url.contains("youtu.be", ignoreCase = true)
            val isMediaPlayer = url.contains("media_player.html", ignoreCase = true)
            val isSpotify = isSpotifyPlayerUrl(url)

            // 1. YouTube — direct JS toggle. Bypasses isMediaPlaying so
            //    we never accidentally trigger the fallback radio path
            //    while the user is on a video page.
            if (isYoutube && webView != null) {
                android.util.Log.d("DimMaskHud", "onMaskSingleTap: youtube toggle on $url")
                webView.evaluateJavascript(
                    """
                    (function() {
                        var v = document.querySelector('video');
                        if (!v) return 'no-video';
                        if (v.paused || v.ended) {
                            try { v.play(); } catch(e) {}
                            return 'play';
                        } else {
                            try { v.pause(); } catch(e) {}
                            return 'pause';
                        }
                    })();
                    """.trimIndent()
                ) { result ->
                    val status = result?.trim('"', ' ').orEmpty()
                    android.util.Log.d("DimMaskHud", "youtube toggle → $status")
                    when {
                        status.contains("playing", ignoreCase = true) ||
                            status.equals("play", ignoreCase = true) ->
                                post { handleMediaStateChanged(webView, true) }
                        status.contains("paused", ignoreCase = true) ||
                            status.equals("pause", ignoreCase = true) ->
                                post { handleMediaStateChanged(webView, false) }
                    }
                    scheduleMaskedNowPlayingRefresh()
                }
                pendingMaskFallbackStation = null
                return
            }

            // 1b. Local media_player.html — direct JS toggle, mirroring
            //     the YouTube path. We DO NOT route through pauseMedia/
            //     playMedia here because dispatchPlayMediaCommand can
            //     fall through to shouldRouteMediaControlsToNativeTapRadio
            //     and resume a stale TapRadio session instead of the
            //     song the user is actually looking at. Toggling the
            //     <audio>/<video> element directly is deterministic and
            //     self-resyncs the isMediaPlaying flag via the JS-side
            //     play/pause event listeners that call notifyMediaState.
            if (isMediaPlayer && webView != null) {
                android.util.Log.d("DimMaskHud", "onMaskSingleTap: media_player toggle on $url")
                webView.evaluateJavascript(
                    """
                    (function() {
                        if (typeof window.tapInsightTogglePlayback === 'function') {
                            return window.tapInsightTogglePlayback();
                        }
                        var videoActive = false;
                        try {
                            var vp = document.getElementById('videoPlayer');
                            videoActive = !!(vp && vp.classList && vp.classList.contains('active'));
                        } catch(e) {}
                        var m = videoActive ? document.querySelector('video') : document.querySelector('audio');
                        if (!m) m = document.querySelector('audio, video');
                        if (!m) return 'no-media';
                        if (m.paused || m.ended) {
                            try { m.play(); } catch(e) {}
                            return 'play';
                        } else {
                            try { m.pause(); } catch(e) {}
                            return 'pause';
                        }
                    })();
                    """.trimIndent()
                ) { result ->
                    val status = result?.trim('"', ' ').orEmpty()
                    android.util.Log.d("DimMaskHud", "media_player toggle → $status")
                    when {
                        status.contains("playing", ignoreCase = true) ||
                            status.equals("play", ignoreCase = true) ->
                                post { handleMediaStateChanged(webView, true) }
                        status.contains("paused", ignoreCase = true) ||
                            status.equals("pause", ignoreCase = true) ->
                                post { handleMediaStateChanged(webView, false) }
                    }
                    scheduleMaskedNowPlayingRefresh()
                }
                pendingMaskFallbackStation = null
                return
            }

            if (isSpotify && webView != null) {
                android.util.Log.d("DimMaskHud", "onMaskSingleTap: spotify toggle on $url")
                webView.evaluateJavascript(
                    """
                    (function() {
                        try {
                            if (typeof window.tapSpotifyTogglePlay === 'function') {
                                window.tapSpotifyTogglePlay();
                                return 'toggle';
                            }
                            if (typeof window.tapInsightTogglePlayback === 'function') {
                                window.tapInsightTogglePlayback();
                                return 'toggle';
                            }
                        } catch(e) { return 'err:' + e; }
                        return 'no-fn';
                    })();
                    """.trimIndent()
                ) { result ->
                    android.util.Log.d("DimMaskHud", "spotify toggle → ${result?.trim('"', ' ')}")
                    postDelayed({ refreshMaskedNowPlayingFromJs() }, 500L)
                    postDelayed({ refreshMaskedNowPlayingFromJs() }, 1400L)
                }
                pendingMaskFallbackStation = null
                return
            }

            // 2. Native TapRadio session — toggle via ExoPlayer.
            if (nativeTapRadioPlaying || hasNativeTapRadioSession()) {
                android.util.Log.d("DimMaskHud", "onMaskSingleTap: native radio toggle (playing=$nativeTapRadioPlaying)")
                if (nativeTapRadioPlaying) pauseMedia() else playMedia()
                return
            }

            // 3. Tracked browser media playing.
            if (isMediaPlaying) {
                android.util.Log.d("DimMaskHud", "onMaskSingleTap: pause tracked media")
                pauseMedia()
                return
            }

            // 4. Fallback station advertised — launch it.
            val fallback = pendingMaskFallbackStation
            if (fallback != null) {
                val activity = resolveHostingActivity()
                if (activity != null) {
                    android.util.Log.d(
                        "DimMaskHud",
                        "onMaskSingleTap: launching fallback (${fallback.sourceLabel}) ${fallback.name}"
                    )
                    try {
                        activity.startTapRadioFromMaskFallback(
                            fallback.url,
                            fallback.name,
                            fallback.genre
                        )
                        pendingMaskFallbackStation = null
                        post { refreshMaskedNowPlaying() }
                        return
                    } catch (e: Exception) {
                        android.util.Log.w("DimMaskHud", "fallback launch failed", e)
                    }
                }
            }

            // 5. Catch-all.
            android.util.Log.d("DimMaskHud", "onMaskSingleTap: catch-all playMedia()")
            playMedia()
        } catch (e: Exception) {
            android.util.Log.w("DualWebViewGroup", "onMaskSingleTapPlayPause failed", e)
        }
    }

    /**
     * Refresh the dim-mode time + battery TextViews. Called every 30 s
     * by [maskClockBatteryRefresh] while dim mode is active. Renders
     * `HH:MM` for the clock and `NN%` (with a charging bolt suffix when
     * plugged in) for the battery — both deliberately small + dim so
     * they don't compete with media metadata for attention.
     */
    private fun updateMaskClockAndBattery() {
        if (!::maskTimeText.isInitialized || !::maskBatteryText.isInitialized) return
        // Clock (12h with no AM/PM marker — the user's eye fills that
        // in from context; saves precious horizontal pixels).
        val cal = java.util.Calendar.getInstance()
        val hour12 = ((cal.get(java.util.Calendar.HOUR) + 11) % 12) + 1
        val minute = cal.get(java.util.Calendar.MINUTE)
        maskTimeText.text = String.format(java.util.Locale.US, "%d:%02d", hour12, minute)

        // Battery — one quick BatteryManager broadcast read. We don't
        // hold a long-lived BroadcastReceiver because the 30-second
        // poll cadence is more than enough resolution for the user
        // and avoids leak surface area.
        try {
            val intent = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val plugged = (intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            maskBatteryText.text = when {
                percent < 0 -> if (plugged) "⚡" else ""
                plugged -> "$percent% ⚡"
                else -> "$percent%"
            }
        } catch (e: Exception) {
            maskBatteryText.text = ""
        }
    }

    fun dispatchFullScreenOverlayTouch(screenX: Float, screenY: Float) {
        val scale = uiScale
        DebugLog.d("FullscreenTouch", "Touch at screen ($screenX, $screenY), scale: $scale")

        // Check controls container if visible
        if (::fullScreenControlsContainer.isInitialized &&
                        fullScreenControlsContainer.visibility == View.VISIBLE
        ) {
            DebugLog.d("FullscreenTouch", "Controls container is visible")

            // Check exit button
            if (::btnFsExit.isInitialized && btnFsExit.visibility == View.VISIBLE) {
                val btnLocation = IntArray(2)
                btnFsExit.getLocationOnScreen(btnLocation)
                val btnWidth = btnFsExit.width * scale
                val btnHeight = btnFsExit.height * scale
                DebugLog.d(
                        "FullscreenTouch",
                        "Exit button: loc=(${btnLocation[0]}, ${btnLocation[1]}), size=($btnWidth, $btnHeight), raw=(${btnFsExit.width}, ${btnFsExit.height})"
                )
                if (screenX >= btnLocation[0] &&
                                screenX <= btnLocation[0] + btnWidth &&
                                screenY >= btnLocation[1] &&
                                screenY <= btnLocation[1] + btnHeight
                ) {
                    DebugLog.d("FullscreenTouch", "Exit button HIT!")
                    btnFsExit.performClick()
                    return
                }
            }

            // Check media control buttons
            if (::fullScreenMediaControls.isInitialized &&
                            fullScreenMediaControls.visibility == View.VISIBLE
            ) {
                DebugLog.d(
                        "FullscreenTouch",
                        "Media controls visible with ${fullScreenMediaControls.childCount} children"
                )
                for (i in 0 until fullScreenMediaControls.childCount) {
                    val button = fullScreenMediaControls.getChildAt(i)
                    if (button.visibility != View.VISIBLE) continue

                    val btnLocation = IntArray(2)
                    button.getLocationOnScreen(btnLocation)
                    val btnWidth = button.width * scale
                    val btnHeight = button.height * scale
                    DebugLog.d(
                            "FullscreenTouch",
                            "Button $i: loc=(${btnLocation[0]}, ${btnLocation[1]}), size=($btnWidth, $btnHeight)"
                    )

                    if (screenX >= btnLocation[0] &&
                                    screenX <= btnLocation[0] + btnWidth &&
                                    screenY >= btnLocation[1] &&
                                    screenY <= btnLocation[1] + btnHeight
                    ) {
                        DebugLog.d("FullscreenTouch", "Button $i HIT!")
                        button.performClick()
                        return
                    }
                }
            }
        } else {
            DebugLog.d("FullscreenTouch", "Controls container NOT visible or not initialized")
        }

        // If no button hit, toggle controls visibility
        DebugLog.d("FullscreenTouch", "No button hit, toggling controls visibility")
        if (::fullScreenControlsContainer.isInitialized) {
            if (fullScreenControlsContainer.visibility == View.VISIBLE) {
                fullScreenControlsContainer.visibility = View.GONE
            } else {
                fullScreenControlsContainer.visibility = View.VISIBLE
                fullScreenControlsContainer.bringToFront()
            }
        }
    }

    private fun drawBitmapToSurface() {
        // REFACTORED: No-op in single viewport mode
        // BinocularSbsLayout handles the rendering - no mirroring needed
    }

    fun getCurrentLinkText(): String {
        return urlEditText.text.toString()
    }

    fun toggleIsUrlEditing(isEditing: Boolean) {
        _isUrlEditing = isEditing
        // DebugLog.d("LinkEditing", "DualWebViewGroup isUrlEditing toggled to: $isEditing")
    }

    fun setLinkText(text: String, newCursorPosition: Int = -1) {
        urlEditText.setText(text)

        // If no specific cursor position requested, maintain current position
        val cursorPos =
                if (newCursorPosition >= 0) {
                    // Ensure requested position doesn't exceed text length
                    minOf(newCursorPosition, text.length)
                } else {
                    // Keep current cursor position but ensure it's valid
                    minOf(urlEditText.selectionStart, text.length)
                }

        urlEditText.setSelection(cursorPos)
    }

    fun adjustViewportAndFields(adjustment: Float) {
        // Apply adjustment to all elements
        // translationY = adjustment // Don't move the entire group, just children
        webView.translationY = adjustment
        urlEditText.translationY = adjustment
        dialogContainer.translationY = adjustment

        if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
            // Ensure bookmarks view stays above keyboard
            leftBookmarksView.translationY = adjustment

            // Get the current edit field from bookmarks view
            val editField = leftBookmarksView.getCurrentEditField()
            editField?.translationY = adjustment
        }
    }

    fun getCurrentUrlEditField(): EditText? {
        return if (_isUrlEditing) urlEditText else null
    }

    fun animateViewportAdjustment() {
        webView.animate().setDuration(200).translationY(webView.translationY).start()
    }

    // Method to show link editing UI
    fun showLinkEditing() {
        if (!_isUrlEditing) {
            _isUrlEditing = true

            val currentUrl = webView.url ?: ""
            urlEditText.apply {
                text.clear()
                append(currentUrl)
                visibility = View.VISIBLE
                requestFocus()
                setSelection(text.length)
                bringToFront()
            }

            keyboardListener?.onShowKeyboard()
        }
    }

    fun isUrlEditing(): Boolean {
        // DebugLog.d("LinkEditing", "isUrlEditing check, value: $isUrlEditing")
        return _isUrlEditing
    }

    fun isBookmarksExpanded(): Boolean {
        return leftBookmarksView.visibility == View.VISIBLE
    }

    private fun toggleChat() {
        if (chatView.visibility == View.VISIBLE) {
            chatView.visibility = View.GONE
        } else {
            pauseYouTubeMediaAcrossAllWindows()
            chatView.visibility = View.VISIBLE
            chatView.bringToFront()
            maybePromptForGroqApiKey()
        }
        post {
            requestLayout()
            invalidate()
        }
    }

    fun hideChat() {
        if (!::chatView.isInitialized || chatView.visibility != View.VISIBLE) return
        chatView.visibility = View.GONE
        post {
            requestLayout()
            invalidate()
        }
    }

    private fun maybePromptForGroqApiKey() {
        if (dialogContainer.visibility == View.VISIBLE) return
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val currentKey = prefs.getString("groq_api_key", null)?.trim()
        if (!currentKey.isNullOrBlank()) return

        showPromptDialog(
                "Enter Groq API Key",
                currentKey,
                { key ->
                    val trimmed = key.trim()
                    if (trimmed.isBlank()) {
                        showToast("API Key Required")
                        post { maybePromptForGroqApiKey() }
                        return@showPromptDialog
                    }
                    prefs.edit().putString("groq_api_key", trimmed).apply()
                    showToast("API Key Saved")
                    keyboardListener?.onHideKeyboard()
                },
                { showToast("API Key Required") }
        )
    }

    private fun toggleBookmarks() {
        leftBookmarksView.toggle()

        if (leftBookmarksView.visibility == View.VISIBLE) {
            leftBookmarksView.bringToFront()
            leftBookmarksView.elevation = 1000f

            // Force immediate refresh to ensure mirroring
            post {
                invalidate()
                startRefreshing()
            }
        }

        // Request layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun handleBookmarkTap(): Boolean {
        if (leftBookmarksView.visibility != View.VISIBLE) {
            // DebugLog.d("BookmarksDebug", "No tap handling - bookmarks not visible")
            return false
        }

        // Let BookmarksView handle the tap
        val handled = leftBookmarksView.handleTap()
        if (handled) {
            // Force refresh to update the mirrored view
            startRefreshing()
        }
        return handled
    }

    fun handleBookmarkDoubleTap(): Boolean {
        return if (leftBookmarksView.visibility == View.VISIBLE) {
            // DebugLog.d("BookmarksDebug", "handleBookmarkDoubleTap() called.
            // leftVisibility=${leftBookmarksView.visibility}")
            val handled = leftBookmarksView.handleDoubleTap()
            if (handled) {
                leftBookmarksView.logStackTrace(
                        "BookmarksDebug",
                        "handleBookmarkDoubleTap(): double tap handled"
                )
                // Force refresh to update the mirrored view
                startRefreshing()
            }
            handled
        } else false
    }

    fun getBookmarksView(): BookmarksView {
        return leftBookmarksView
    }

    // Provide WebView access
    @SuppressLint("SetJavaScriptEnabled")
    fun getWebView(): WebView {
        return webView.apply {
            val settings = this.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION") run { settings.databaseEnabled = true }
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false

            // Clean up legacy JS interface - we use URL scheme now
            // addJavascriptInterface(WebAppInterface(context, this), "Android")

            // Set User Agent

            // Set User Agent
            // settings.userAgentString = desktopUserAgent // Default to Desktop
        }
    }

    private fun setupUrlEditText(isRight: Boolean = false): EditText {
        return EditText(context).apply {
            layoutParams =
                    FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    Gravity.TOP
                            )
                            .apply {
                                leftMargin = toggleBarWidthPx // Single margin for left side
                            }
            setBackgroundColor(Color.parseColor("#202020"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 12, 32, 12)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = urlFieldMinHeight
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            highlightColor = Color.parseColor("#404040")

            // Set hardware acceleration for better cursor rendering
            setLayerType(
                    View.LAYER_TYPE_HARDWARE,
                    Paint().apply {
                        color = Color.WHITE // Set cursor color to white
                    }
            )

            // Set hardware acceleration for better cursor rendering
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Make both EditTexts share focus state
            setOnFocusChangeListener { _, hasFocus ->
                if (isRight && hasFocus) {
                    urlEditText.requestFocus()
                }
            }

            // Add text change listener to sync content
            addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                                s: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int
                        ) {}
                        override fun onTextChanged(
                                s: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int
                        ) {}
                        override fun afterTextChanged(s: Editable?) {}
                    }
            )
        }
    }

    // Set up the bitmap for capturing content
    private fun setupBitmap(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        synchronized(bitmapLock) {
            try {
                bitmap?.let { oldBitmap ->
                    if (!oldBitmap.isRecycled) {
                        oldBitmap.recycle()
                    }
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (e: Exception) {
                Log.e("DualWebViewGroup", "Error creating bitmap", e)
                bitmap = null
            }
        }
    }

    fun updateLeftEyePosition(xOffset: Float, yOffset: Float, rotationDeg: Float) {

        // Store the translations
        _translationX = yOffset
        _translationY = xOffset

        // If you also want to store rotation in a field:
        _rotationZ = rotationDeg

        leftEyeUIContainer.translationX = yOffset
        leftEyeUIContainer.translationY = xOffset
        leftEyeUIContainer.rotation = rotationDeg

        // Only apply same transformations to full screen overlay when it's actually visible
        // This prevents the video from being positioned incorrectly when fullscreen is activated
        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            fullScreenOverlayContainer.translationX = yOffset
            fullScreenOverlayContainer.translationY = xOffset
            fullScreenOverlayContainer.rotation = rotationDeg
        } else {
            // Keep at zero when not visible to ensure clean state
            fullScreenOverlayContainer.translationX = 0f
            fullScreenOverlayContainer.translationY = 0f
            fullScreenOverlayContainer.rotation = 0f
        }

        // Pass the fixed screen cursor position to hover detection
        // In anchored mode, the cursor is visually fixed at the center (320, 240)
        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)
        val screenX = 320f + containerLocation[0]
        val screenY = 240f + containerLocation[1]

        updateButtonHoverStates(screenX, screenY)

        // Ensure visual cursor scale/visibility is refreshed in anchored mode
        listener?.onCursorPositionChanged(320f, 240f, true)

        // Only do expensive operations occasionally, not every frame
        // The Choreographer already ensures smooth vsync timing
        if (!isRefreshing) {
            post { startRefreshing() }
        }
    }

    // Capture and mirror content to left SurfaceView
    private fun captureLeftEyeContent() {
        if (!isRefreshing) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureTime < MIN_CAPTURE_INTERVAL) {
            return
        }
        lastCaptureTime = currentTime

        try {
            // Check scrollbar visibility periodically (once per second)
            // Skip if in fullscreen mode to save power
            val isFullScreen = fullScreenOverlayContainer.visibility == View.VISIBLE

            if (!isFullScreen && currentTime - lastScrollBarCheckTime > 1000) {
                if (!shouldFreezeScrollBars()) {
                    updateScrollBarsVisibility()
                }
                lastScrollBarCheckTime = currentTime
            }

            // Force cursor refresh if editing - skip in fullscreen
            if (!isFullScreen && _isUrlEditing && urlEditText.isFocused) {
                urlEditText.invalidate()
            }

            // NOTE: PixelCopy + drawBitmapToSurface() removed — BinocularSbsLayout now
            // renders the SBS output directly from the view hierarchy, making the old
            // capture-to-bitmap-then-draw pipeline dead code. Removing it frees ~60 GPU
            // PixelCopy ops/sec and eliminates bitmapLock contention that was starving
            // the audio decoder thread on the X3 Pro (manifesting as periodic stutters).
        } catch (e: Exception) {
            Log.e("MirrorDebug", "Error in refresh tick", e)
            stopRefreshing()
        }
    }

    fun onKeyboardHidden() {
        // Reset views when keyboard is hidden
        post {
            requestLayout()
            invalidate()

            // Force bitmap recreation with new dimensions
            // setupBitmap(webView.width, height - 48)

            // Ensure mirroring is updated
            startRefreshing()
        }
    }

    fun syncKeyboardStates() {
        customKeyboard?.let { Kb ->

            // Force update of the keyboard
            Kb.post {
                Kb.invalidate()
                Kb.requestLayout()
                keyboardContainer.invalidate()
                keyboardContainer.requestLayout()
            }
        }
    }

    // Refresh handling
    private var refreshCount = 0
    private var lastRefreshLogTime = 0L

    private val refreshRunnable =
            object : Runnable {
                override fun run() {
                    refreshCount++

                    // Log every 2 seconds to avoid spam
                    val now = System.currentTimeMillis()
                    if (now - lastRefreshLogTime > 2000) {
                        // DebugLog.d("MirrorDebug", "RefreshLoop running, count=$refreshCount,
                        // isRefreshing=$isRefreshing,
                        // webViewAttached=${webView.isAttachedToWindow},
                        // fsOverlayVisible=${fullScreenOverlayContainer.visibility ==
                        // View.VISIBLE}")
                        lastRefreshLogTime = now
                    }

                    if (isRefreshing) {
                        if (webView.isAttachedToWindow) {
                            captureLeftEyeContent()
                        }
                        refreshHandler.postDelayed(this, refreshInterval)
                    } else {
                        Log.w("MirrorDebug", "RefreshLoop STOPPING! isRefreshing=$isRefreshing")
                        // No need to call stopRefreshing() here as we just stop posting callbacks
                    }
                }
            }

    fun startRefreshing() {
        // REFACTORED: No-op in single viewport mode
        // BinocularSbsLayout handles the rendering - no mirroring needed
    }

    fun stopRefreshing() {
        // REFACTORED: No-op in single viewport mode
        // BinocularSbsLayout handles the rendering - no mirroring needed
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {

        // BinocularSbsLayout gives us the logical viewport size; use actual measured dimensions.
        val eyeWidth = r - l
        val eyeHeight = b - t
        val halfWidth = eyeWidth

        val toggleBarWidth = toggleBarWidthPx
        val navBarHeight = navBarHeightPx

        // Ensure toggle bar is measured correctly
        val topReserve = unipanelTopReservePx.coerceAtMost((eyeHeight - navBarHeight).coerceAtLeast(0))

        leftToggleBar.measure(
                MeasureSpec.makeMeasureSpec(toggleBarWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((eyeHeight - navBarHeight - topReserve).coerceAtLeast(0), MeasureSpec.EXACTLY)
        )
        if (!isInScrollMode && !isNavBarsHidden) {
            if (leftToggleBar.visibility != View.VISIBLE) {
                leftToggleBar.visibility = View.VISIBLE
            }
        }

        // Ensure navigation bar is measured correctly
        leftNavigationBar.measure(
                MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(navBarHeight, MeasureSpec.EXACTLY)
        )

        // Force a layout pass on the container if needed
        if (leftToggleBar.measuredWidth == 0) {
            leftEyeUIContainer.requestLayout()
        }

        val height = b - t
        // Use actual measured height of keyboard if visible, otherwise default
        val keyboardHeight =
                if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160
        // Keyboard width is same regardless of mode (matches original keyboard size)
        val keyboardWidth = halfWidth - toggleBarWidth

        // Position the WebView differently based on scroll mode
        // Shrink the WebView when keyboard is visible so content isn't blocked
        val isKeyboardVisible = keyboardContainer.visibility == View.VISIBLE

        val horizontalReserve = if (horizontalScrollBar.visibility == View.VISIBLE) 20 else 0

        if (isInScrollMode || isNavBarsHidden) {
            val keyboardLimit =
                    if (isKeyboardVisible) {
                        eyeHeight - keyboardHeight // Shrink to fit above keyboard
                    } else {
                        eyeHeight
                    }
            // Respect proper measurement which accounts for margins (scrollbars)
            val measuredBottom = topReserve + webViewsContainer.measuredHeight
            val adjustedKeyboardLimit = (keyboardLimit - horizontalReserve).coerceAtLeast(topReserve)

            webViewsContainer.layout(
                    0, // No left margin in scroll mode
                    topReserve,
                    0 + webViewsContainer.measuredWidth, // Full width minus margins
                    minOf(adjustedKeyboardLimit, measuredBottom)
            )
        } else {
            val navBarTop = eyeHeight - navBarHeight

            val keyboardLimit =
                    if (isKeyboardVisible) {
                        minOf(navBarTop, eyeHeight - keyboardHeight) // Shrink to fit above keyboard
                    } else {
                        navBarTop // Default bottom for 30px nav bar
                    }
            // Respect proper measurement which accounts for margins (scrollbars)
            val measuredBottom = topReserve + webViewsContainer.measuredHeight
            val adjustedKeyboardLimit = (keyboardLimit - horizontalReserve).coerceAtLeast(topReserve)

            webViewsContainer.layout(
                    toggleBarWidth, // Account for toggle bar
                    topReserve,
                    toggleBarWidth +
                            webViewsContainer.measuredWidth, // Standard width + toggle bar offset
                    minOf(adjustedKeyboardLimit, measuredBottom)
            )
        }

        // Calculate available content height based on keyboard visibility
        val contentHeight =
                if (keyboardContainer.visibility == View.VISIBLE) {
                    eyeHeight - keyboardHeight - topReserve
                } else {
                    eyeHeight - navBarHeight - topReserve
                }.coerceAtLeast(0)

        // Layout the clip parent - hardcoded 640x480
        leftEyeClipParent.layout(
                0, // After toggle bar
                0,
                eyeWidth, // Fixed width for left eye
                eyeHeight
        )

        fullScreenOverlayContainer.layout(
                0, // Relative to leftEyeClipParent
                0,
                halfWidth, // 640px width (matches clip parent)
                eyeHeight
        )

        // REFACTORED: rightEyeView layout no longer needed - single viewport mode
        // rightEyeView.layout(eyeWidth, 0, eyeWidth * 2, eyeHeight)

        // Layout toggle bar below the native unipanel lane so browser chrome
        // never sits behind the clock / voice HUD / mini chat card overlay.
        leftToggleBar.layout(0, topReserve, toggleBarWidth, eyeHeight - navBarHeight)
        //            DebugLog.d("ToggleBarDebug", """
        //        Toggle Bar Layout:
        //        Visibility: ${leftToggleBar.visibility}
        //        Width: $toggleBarWidth
        //        Height: 596
        //        Background: ${leftToggleBar.background}
        //        Parent: ${leftToggleBar.parent?.javaClass?.simpleName}
        //    """.trimIndent())

        val keyboardY = eyeHeight - keyboardHeight
        keyboardContainer.layout(
                toggleBarWidth,
                keyboardY,
                toggleBarWidth + keyboardWidth,
                eyeHeight
        )

        // Position ProgressBar - at bottom in scroll mode, above nav bar otherwise
        val progressBarHeight = 4
        if (isInScrollMode) {
            // In scroll mode, position at very bottom, full width
            progressBar.measure(
                    MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(progressBarHeight, MeasureSpec.EXACTLY)
            )
            if (progressBar.visibility == View.VISIBLE) {
                val pbY = eyeHeight - progressBarHeight
                progressBar.layout(0, pbY, halfWidth, eyeHeight)
                progressBar.bringToFront()
            } else {
                progressBar.layout(0, 0, 0, 0)
            }
        } else {
            // Normal mode - position above navigation bar
            progressBar.measure(
                    MeasureSpec.makeMeasureSpec(halfWidth - toggleBarWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(progressBarHeight, MeasureSpec.EXACTLY)
            )
            if (progressBar.visibility == View.VISIBLE) {
                val pbY = eyeHeight - navBarHeight - progressBarHeight
                progressBar.layout(toggleBarWidth, pbY, halfWidth, pbY + progressBarHeight)
            } else {
                progressBar.layout(0, 0, 0, 0)
            }
        }

        // Hide navigation bars
        leftNavigationBar.visibility = View.GONE

        if (keyboardContainer.visibility == View.VISIBLE) {
            // Position keyboards at the bottom
            // In scroll mode, center keyboard (no toggle bar offset)
            val kbLeft =
                    if (isInScrollMode) {
                        (halfWidth - keyboardWidth) / 2 // Center in left half
                    } else {
                        toggleBarWidth
                    }
            keyboardContainer.layout(kbLeft, keyboardY, kbLeft + keyboardWidth, eyeHeight)

            // Hide navigation bars
            leftNavigationBar.visibility = View.GONE

            // Position bookmarks menu if visible
            if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                val bookmarksHeight = leftBookmarksView.measuredHeight
                val isEditingAnywhere = _isUrlEditing || leftBookmarksView.isEditing()
                val bookmarksY =
                        if (isEditingAnywhere) {
                            40 // Below URL edit field area / top of screen
                        } else {
                            keyboardY - bookmarksHeight
                        }

                // Constrain bottom to keyboardY to avoid overlapping with keyboard
                val bookmarksBottom =
                        if (isEditingAnywhere) {
                            minOf(bookmarksY + bookmarksHeight, keyboardY)
                        } else {
                            bookmarksY + bookmarksHeight
                        }

                leftBookmarksView.layout(
                        toggleBarWidth,
                        bookmarksY,
                        toggleBarWidth + 480,
                        bookmarksBottom
                )

                leftBookmarksView.bringToFront()
            }

            // Handle edit fields for both URL and bookmark editing
            if (_isUrlEditing || isBookmarkEditing) {
                val editFieldHeight = maxOf(urlFieldMinHeight, urlEditText.measuredHeight)
                val editFieldLeft = keyboardContainer.left.takeIf { it > 0 } ?: toggleBarWidth
                val editFieldRight =
                        keyboardContainer.right.takeIf { it > editFieldLeft }
                                ?: (editFieldLeft + keyboardWidth)

                // Position left edit field only
                urlEditText.apply {
                    layout(editFieldLeft, 0, editFieldRight, editFieldHeight)
                    translationY = (keyboardY - editFieldHeight).toFloat()
                    visibility = View.VISIBLE
                    elevation = 1001f
                }
            }

            // Ensure keyboard containers are on top but below edit fields
            keyboardContainer.elevation = 1000f
        } else {
            // DebugLog.d("EditFieldDebug", "Skipping edit field positioning - conditions not met")

            // Hide keyboard containers
            keyboardContainer.layout(
                    toggleBarWidth,
                    eyeHeight,
                    toggleBarWidth + keyboardWidth,
                    eyeHeight + keyboardHeight
            )

            // Position bookmarks when keyboard is not visible
            if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                leftBookmarksView.layout(
                        toggleBarWidth,
                        30,
                        toggleBarWidth + 480,
                        eyeHeight - navBarHeight
                )
            }

            // Show navigation bar only in normal mode (hide in scroll mode to avoid overlap)
            if (isInScrollMode) {
                leftNavigationBar.visibility = View.GONE
                leftNavigationBar.layout(0, 0, 0, 0)
            } else {
                leftNavigationBar.visibility = View.VISIBLE
                leftNavigationBar.layout(0, eyeHeight - navBarHeight, halfWidth, eyeHeight)
            }
        }

        // Update bitmap capture when layout changes
        if (changed) {
            post {
                setupBitmap(webView.width, contentHeight)
                startRefreshing()
            }
        }

        // Hide system info bar when disabled or while nav/scroll overlays are hidden.
        updateSystemInfoBarVisibility()
        if (leftSystemInfoView.visibility == View.VISIBLE) {
            // Calculate system info bar position
            val infoBarHeight = 24
            val infoBarY = eyeHeight - navBarHeight - infoBarHeight // Position above nav bar

            // First measure the info views to get their width
            leftSystemInfoView.measure(
                    MeasureSpec.makeMeasureSpec(320, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(infoBarHeight, MeasureSpec.EXACTLY)
            )

            val infoBarWidth = leftSystemInfoView.measuredWidth
            val leftX =
                    (halfWidth - infoBarWidth) / 2 +
                            toggleBarWidth // Center in left half, account for toggle bar

            // Position the info bars
            leftSystemInfoView.layout(
                    leftX,
                    infoBarY,
                    leftX + infoBarWidth,
                    infoBarY + infoBarHeight
            )
        }

        // Position Dialog Container (Center it in the left view)
        if (dialogContainer.visibility != View.GONE) {
            val dialogWidth = 500

            // Measure the dialog container first if needed
            dialogContainer.measure(
                    MeasureSpec.makeMeasureSpec(dialogWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(eyeHeight, MeasureSpec.AT_MOST)
            )

            val measuredH = dialogContainer.measuredHeight

            val dialogLeft = toggleBarWidth + (keyboardWidth - dialogWidth) / 2

            // Calculate available vertical space, respecting the keyboard if it is visible
            val availableHeight =
                    if (keyboardContainer.visibility == View.VISIBLE) {
                        eyeHeight - keyboardHeight
                    } else {
                        eyeHeight
                    }
            // Center the dialog within the available space
            val dialogTop = (availableHeight - measuredH) / 2

            dialogContainer.layout(
                    dialogLeft,
                    dialogTop,
                    dialogLeft + dialogWidth,
                    dialogTop + measuredH
            )
            dialogContainer.elevation = 2000f
            dialogContainer.bringToFront()
        }

        // Layout maskOverlay to cover left eye only (will be mirrored to right eye)
        maskOverlay.layout(0, 0, halfWidth, height)

        // Layout the unhide button when in scroll mode
        if (isInScrollMode && btnShowNavBars.visibility == View.VISIBLE) {
            val btnSize = 40
            val btnRight = halfWidth - 8 // 8px margin from right
            val btnBottom = height - 8 // 8px margin from bottom
            btnShowNavBars.layout(btnRight - btnSize, btnBottom - btnSize, btnRight, btnBottom)
            btnShowNavBars.bringToFront()
        }

        // Layout scroll bars for non-anchored mode
        // Eye button size is 40px with 8px margin from bottom/right, so reserve 48px for it
        val scrollChromeHidden = isInScrollMode || isNavBarsHidden
        val eyeButtonSpace =
                if (scrollChromeHidden && btnShowNavBars.visibility == View.VISIBLE) 48 else 0

        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val hScrollHeight = 20
            val navBarTop =
                    if (leftNavigationBar.visibility == View.VISIBLE) eyeHeight - navBarHeight
                    else eyeHeight
            val hScrollY =
                    if (scrollChromeHidden) eyeHeight - hScrollHeight
                    else navBarTop - hScrollHeight // Sit right above nav bar

            val leftInset =
                    if (leftToggleBar.visibility == View.VISIBLE) {
                        leftToggleBar.measuredWidth.takeIf { it > 0 } ?: toggleBarWidth
                    } else {
                        0
                    }
            val scrollLeft = leftInset
            var scrollWidth =
                    if (scrollChromeHidden) halfWidth - leftInset - eyeButtonSpace
                    else halfWidth - leftInset

            // Prevent overlap with vertical scrollbar if visible
            if (verticalScrollBar.visibility == View.VISIBLE) {
                scrollWidth -= 20 // Subtract width of vertical scrollbar
            }

            horizontalScrollBar.measure(
                    MeasureSpec.makeMeasureSpec(scrollWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(hScrollHeight, MeasureSpec.EXACTLY)
            )
            horizontalScrollBar.layout(
                    scrollLeft,
                    hScrollY,
                    scrollLeft + scrollWidth,
                    hScrollY + hScrollHeight
            )
        }

        if (verticalScrollBar.visibility == View.VISIBLE) {
            val vScrollWidth = 20
            val vScrollRight = halfWidth // Align to right edge
            val vScrollTop = topReserve // Start below the unipanel HUD/card lane

            // In scroll/nav-hidden mode, stop above the eye button. Normal
            // mode stops at the nav bar.
            val vScrollBottom =
                    if (scrollChromeHidden) eyeHeight - eyeButtonSpace else eyeHeight - navBarHeight
            val vScrollHeight = vScrollBottom - vScrollTop

            verticalScrollBar.measure(
                    MeasureSpec.makeMeasureSpec(vScrollWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(vScrollHeight, MeasureSpec.EXACTLY)
            )
            verticalScrollBar.layout(
                    vScrollRight - vScrollWidth,
                    vScrollTop,
                    vScrollRight,
                    vScrollTop + vScrollHeight
            )
        }
        applyScrollbarTransform()

        // Layout the UI container to cover just the left half
        leftEyeUIContainer.layout(0, 0, halfWidth, height)

        if (::chatView.isInitialized &&
                        chatView.visibility == View.VISIBLE &&
                        keyboardContainer.visibility == View.VISIBLE
        ) {
            val chatMargin = 8.dp()
            val availableHeight = (eyeHeight - keyboardHeight - chatMargin).coerceAtLeast(0)
            val baseWidth =
                    chatView.layoutParams.width.takeIf { it > 0 }
                            ?: chatView.measuredWidth.takeIf { it > 0 } ?: 560
            val baseHeight =
                    chatView.layoutParams.height.takeIf { it > 0 }
                            ?: chatView.measuredHeight.takeIf { it > 0 } ?: 420
            val targetWidth = baseWidth.coerceAtMost(halfWidth)
            val targetHeight = baseHeight.coerceAtMost(availableHeight)

            if (targetHeight > 0) {
                chatView.measure(
                        MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
                )
                val left = (halfWidth - targetWidth) / 2
                val bottom = eyeHeight - keyboardHeight - chatMargin
                val top = bottom - chatView.measuredHeight
                chatView.layout(left, top, left + targetWidth, bottom)
            }
        }
    }

    private var sharedConfigListenerRegistered = false

    fun cleanupResources() {
        if (maskWakeLock.isHeld) maskWakeLock.release()
        if (pausedMediaWakeLock.isHeld) pausedMediaWakeLock.release()
        try {
            if (mediaWifiLock?.isHeld == true) mediaWifiLock.release()
        } catch (_: Exception) {}
        if (sharedConfigListenerRegistered) {
            sharedConfigPrefs.unregisterOnSharedPreferenceChangeListener(sharedConfigListener)
            sharedConfigListenerRegistered = false
        }
        stopRefreshing()
        synchronized(bitmapLock) {
            bitmap?.let { currentBitmap ->
                if (!currentBitmap.isRecycled) {
                    currentBitmap.recycle()
                }
            }
            bitmap = null
        }
        System.gc() // Request garbage collection
    }

    fun getCurrentEditText(): String {
        return urlEditText.text.toString()
    }

    fun hideLinkEditing() {
        _isUrlEditing = false
        isBookmarkEditing = false

        urlEditText.apply {
            clearFocus()
            visibility = View.GONE
            elevation = 0f
        }

        post {
            startRefreshing()
            requestLayout()
            invalidate()
        }
    }

    private fun EditText.setOnSelectionChangedListener(listener: (Int, Int) -> Unit) {
        try {
            val field = TextView::class.java.getDeclaredField("mEditor")
            field.isAccessible = true
            val editor = field.get(this)

            val listenerField = editor.javaClass.getDeclaredField("mSelectionChangedListener")
            listenerField.isAccessible = true
            listenerField.set(
                    editor,
                    object : Any() {
                        fun onSelectionChanged(selStart: Int, selEnd: Int) {
                            listener(selStart, selEnd)
                        }
                    }
            )
        } catch (e: Exception) {
            Log.e("DualWebViewGroup", "Error setting selection listener", e)
        }
    }

    fun showInfoBars() {
        updateSystemInfoBarVisibility()
    }

    fun hideInfoBars() {
        leftSystemInfoView.visibility = View.GONE
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    private fun isBrowserSystemInfoEnabled(): Boolean =
            // Default OFF (0.2b): the battery/Wi-Fi/date strip duplicates
            // the unipanel HUD and clutters the browser. Opt-in via the
            // companion app (browser_show_system_info).
            sharedConfigPrefs.getBoolean(KEY_BROWSER_SHOW_SYSTEM_INFO, false)

    private fun updateSystemInfoBarVisibility() {
        leftSystemInfoView.visibility =
                if (!isBrowserSystemInfoEnabled() || isInScrollMode || isNavBarsHidden) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
    }

    // Add keyboard mirror handling
    fun setKeyboard(originalKeyboard: CustomKeyboardView) {
        // DebugLog.d("KeyboardDebug", "setKeyboard called with keyboard:
        // ${originalKeyboard.hashCode()}")

        // Clear container
        keyboardContainer.removeAllViews()

        // Clear animations
        keyboardContainer.clearAnimation()
        webView.clearAnimation()
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.clearAnimation()

        // Reset translations
        keyboardContainer.translationY = 0f
        webView.translationY = 0f
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.translationY = 0f

        // Set keyboard
        customKeyboard = originalKeyboard
        customKeyboard?.setAnchoredMode(isAnchored)
        keyboardContainer.addView(
                originalKeyboard,
                FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { gravity = Gravity.BOTTOM }
        )

        // Explicitly set visibility based on keyboard's current state
        val visibility =
                if (originalKeyboard.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        keyboardContainer.visibility = visibility

        // Hide navigation bars when keyboard is visible
        if (visibility == View.VISIBLE) {
            leftNavigationBar.visibility = View.GONE
        }

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Force redraw of toggle buttons
        leftToggleBar.findViewById<View>(R.id.btnModeToggle)?.invalidate()
    }

    private fun getCursorInContainerCoords(): Pair<Float, Float> {
        // Calculate the actual screen position of the cursor first
        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)

        val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
        val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

        val visualX = 320f + (lastCursorX - 320f) * uiScale + transX
        val visualY = 240f + (lastCursorY - 240f) * uiScale + transY

        val screenX = visualX + containerLocation[0]
        val screenY = visualY + containerLocation[1]

        return computeAnchoredCoordinates(screenX, screenY)
    }

    private fun computeAnchoredKeyboardCoordinates(): Pair<Float, Float>? {
        val keyboard = keyboardContainer
        if (keyboard.width == 0 || keyboard.height == 0) {
            // DebugLog.d("TouchDebug", "computeAnchoredKeyboardCoordinates: keyboard not laid out")
            return null
        }

        val (adjustedX, adjustedY) = getCursorInContainerCoords()

        val keyboardLocation = IntArray(2)
        keyboard.getLocationOnScreen(keyboardLocation)
        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)
        val localXContainer = adjustedX - keyboard.x
        val localYContainer = adjustedY - keyboard.y

        val kbView = customKeyboard ?: return null

        val localX = localXContainer - kbView.x
        val localY = localYContainer - kbView.y

        return Pair(localX, localY)
    }

    private fun computeAnchoredCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> {
        val parent = leftEyeUIContainer.parent as View
        val parentLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)

        val relativeX = screenX - parentLocation[0]
        val relativeY = screenY - parentLocation[1]

        val points = floatArrayOf(relativeX, relativeY)

        val inverse = android.graphics.Matrix()
        leftEyeUIContainer.matrix.invert(inverse)
        inverse.mapPoints(points)

        return Pair(points[0], points[1])
    }

    private fun isTouchOnView(view: View, x: Float, y: Float): Boolean {
        return view.visibility == View.VISIBLE &&
                x >= view.left &&
                x <= view.right &&
                y >= view.top &&
                y <= view.bottom
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Let windows overview handle its own touch events
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            return false // Don't intercept, let children handle touches
        }

        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            // Allow interactions with menus that are on top
            if (::leftBookmarksView.isInitialized && isTouchOnView(leftBookmarksView, ev.x, ev.y)) {
                return false
            }

            fullScreenTapDetector.onTouchEvent(ev)
            return true
        }

        // Skip anchored gesture handling when in scroll mode - touches should go directly to
        // WebView
        if (isAnchored && !isInScrollMode) {
            var isOverTarget = false
            val (cursorX, cursorY) = getCursorInContainerCoords()

            // Check Keyboard
            if (keyboardContainer.visibility == View.VISIBLE) {
                val localCoords = computeAnchoredKeyboardCoordinates()
                if (localCoords != null) {
                    val (localX, localY) = localCoords
                    if (localX >= 0 &&
                                    localX <= keyboardContainer.width &&
                                    localY >= 0 &&
                                    localY <= keyboardContainer.height
                    ) {
                        isOverTarget = true
                        anchoredTarget = 1
                    }
                }
            }

            // Check Bookmarks (if not already over keyboard)
            if (!isOverTarget &&
                            ::leftBookmarksView.isInitialized &&
                            leftBookmarksView.visibility == View.VISIBLE
            ) {
                if (cursorX >= leftBookmarksView.left &&
                                cursorX <= leftBookmarksView.right &&
                                cursorY >= leftBookmarksView.top &&
                                cursorY <= leftBookmarksView.bottom
                ) {
                    isOverTarget = true
                    anchoredTarget = 2
                    // DebugLog.d("TouchDebug", "Intercepting anchored tap for bookmarks")
                }
            }

            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    anchoredGestureActive = isOverTarget
                    if (anchoredGestureActive) {
                        anchoredTouchStartX = cursorX
                        anchoredTouchStartY = cursorY
                        lastAnchoredY = cursorY
                        isAnchoredDrag = false
                        // DebugLog.d("TouchDebug", "Intercepting anchored ACTION_DOWN
                        // target=$anchoredTarget")
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (anchoredGestureActive) return true
                    if (isOverTarget) {
                        anchoredGestureActive = true
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (anchoredGestureActive || isOverTarget) {
                        // DebugLog.d("TouchDebug", "Intercepting anchored ACTION_UP")
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (anchoredGestureActive) {
                        anchoredGestureActive = false
                        anchoredTarget = 0
                        return true
                    }
                }
            }
            return false
        }

        // Non-anchored keyboard handling
        if (keyboardContainer.visibility == View.VISIBLE && !isAnchored) {
            return true
        }

        // Non-anchored bookmarks handling
        if (::leftBookmarksView.isInitialized &&
                        leftBookmarksView.visibility == View.VISIBLE &&
                        !isAnchored
        ) {
            return true
        }

        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        // BinocularSbsLayout already gives us half the screen; use full given width.
        val halfWidth = widthSize
        val navBarHeight = navBarHeightPx
        val toggleBarWidth = toggleBarWidthPx
        val topReserve = unipanelTopReservePx.coerceAtMost((heightSize - navBarHeight).coerceAtLeast(0))
        val keyboardWidth = halfWidth - toggleBarWidth

        // Measure keyboard container first to get its actual height
        keyboardContainer.measure(
                MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        val keyboardHeight =
                if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160

        val contentHeight =
                (if (keyboardContainer.visibility == View.VISIBLE) {
                    heightSize - keyboardHeight - topReserve
                } else {
                    heightSize - navBarHeight - topReserve
                }).coerceAtLeast(0)

        // Measure WebView with different dimensions based on scroll mode
        // FIX: Respect the LayoutParams set by updateScrollBarsVisibility
        val lp = webViewsContainer.layoutParams

        if (isInScrollMode || isNavBarsHidden) {
            val targetWidth = if (lp != null && lp.width > 0) lp.width else 640
            val targetHeight = if (lp != null && lp.height > 0) lp.height else (heightSize - topReserve).coerceAtLeast(0)

            webViewsContainer.measure(
                    MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            )
        } else {
            // Normal Mode
            // Use layout params if available (set by updateScrollBarsVisibility)
            // Default fallback: 640 - toggle bar = width, content height
            val targetWidth = if (lp != null && lp.width > 0) lp.width else (640 - toggleBarWidth)

            // For height in normal mode, we used MATCH_PARENT in updateScrollBarsVisibility
            // usually,
            // but sometimes explicit. If MATCH_PARENT (-1), we use the calculated contentHeight.
            val targetHeight =
                    if (lp != null && lp.height > 0) lp.height
                    else if (contentHeight > 0) contentHeight else 440

            webViewsContainer.measure(
                    MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            )
        }

        // REFACTORED: rightEyeView measuring no longer needed - single viewport mode
        // rightEyeView.measure(
        //         MeasureSpec.makeMeasureSpec(halfWidth - toggleBarWidth, MeasureSpec.EXACTLY),
        //         MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
        // )

        leftNavigationBar.measure(
                MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(navBarHeight, MeasureSpec.EXACTLY)
        )

        leftToggleBar.measure(
                MeasureSpec.makeMeasureSpec(toggleBarWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((heightSize - navBarHeight - topReserve).coerceAtLeast(0), MeasureSpec.EXACTLY)
        )

        // keyboardContainer is already measured above, but we can measure it again with EXACTLY if
        // we want to enforce constraints,
        // but UNSPECIFIED allowed it to size itself. Let's stick to the measurement we did.

        fullScreenOverlayContainer.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        maskOverlay.measure(
                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        // Measure leftEyeUIContainer and its children
        leftEyeUIContainer.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        // Measure windowsOverviewContainer if visible
        windowsOverviewContainer?.let { woc ->
            if (woc.visibility == View.VISIBLE) {
                val containerWidth = 640 - toggleBarWidthPx
                val containerHeight = heightSize - navBarHeightPx
                woc.measure(
                        MeasureSpec.makeMeasureSpec(containerWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(containerHeight, MeasureSpec.EXACTLY)
                )
            }
        }

        // Measure leftEyeClipParent
        leftEyeClipParent.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(widthSize, heightSize)
    }

    // at class top
    private var downWhen = 0L
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val kbVisible = (keyboardContainer.visibility == View.VISIBLE)

        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            fullScreenTapDetector.onTouchEvent(event)
            return true
        }

        // Skip anchored gesture handling when in scroll mode - touches should go directly to
        // WebView
        if (isAnchored && !isInScrollMode) {
            // Track velocity for anchored interactions (bookmarks scroll, etc.)
            if (velocityTracker == null) {
                velocityTracker = android.view.VelocityTracker.obtain()
            }
            velocityTracker?.addMovement(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (anchoredGestureActive) return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (anchoredGestureActive) {
                        val (cursorX, cursorY) = getCursorInContainerCoords()

                        // Check for drag threshold
                        if (!isAnchoredDrag) {
                            val dx = kotlin.math.abs(cursorX - anchoredTouchStartX)
                            val dy = kotlin.math.abs(cursorY - anchoredTouchStartY)
                            if (dx > ANCHORED_TOUCH_SLOP || dy > ANCHORED_TOUCH_SLOP) {
                                isAnchoredDrag = true
                            }
                        }

                        if (isAnchoredDrag && anchoredTarget == 2) { // Bookmarks
                            val deltaY = lastAnchoredY - cursorY
                            if (::leftBookmarksView.isInitialized &&
                                            leftBookmarksView.visibility == View.VISIBLE
                            ) {
                                leftBookmarksView.handleAnchoredSwipe(deltaY)
                            }
                        }

                        lastAnchoredY = cursorY
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val wasTracking = anchoredGestureActive
                    anchoredGestureActive = false

                    if (wasTracking) {
                        if (!isAnchoredDrag) {
                            val (cursorX, cursorY) = getCursorInContainerCoords()

                            // Dispatch tap based on target determined at ACTION_DOWN
                            when (anchoredTarget) {
                                1 -> { // Keyboard
                                    // Managed by MainActivity dispatchKeyboardTap
                                }
                                2 -> { // Bookmarks
                                    if (::leftBookmarksView.isInitialized &&
                                                    leftBookmarksView.visibility == View.VISIBLE
                                    ) {
                                        // DebugLog.d("TouchDebug", "Dispatching anchored tap to
                                        // bookmarks")
                                        leftBookmarksView.handleAnchoredTap(
                                                cursorX - leftBookmarksView.left,
                                                cursorY - leftBookmarksView.top
                                        )
                                    }
                                }
                            }
                        } else if (anchoredTarget == 2) {
                            // Anchored Fling for Bookmarks
                            velocityTracker?.computeCurrentVelocity(1000)
                            val velocityY = velocityTracker?.yVelocity ?: 0f
                            // Pass raw velocityY.
                            handleAnchoredFling(velocityY)
                        }
                    }

                    anchoredTarget = 0
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (wasTracking) return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (anchoredGestureActive) {
                        anchoredGestureActive = false
                        anchoredTarget = 0
                        return true
                    }
                }
            }
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downWhen = event.eventTime
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dur = event.eventTime - downWhen
                val travelX = kotlin.math.abs(event.x - downX)
                val travelY = kotlin.math.abs(event.y - downY)
                val wasTap = dur < 300 && travelX < 8 && travelY < 8

                if (wasTap && !isAnchored) {
                    postDelayed({ updateScrollBarsVisibility() }, 500)
                }

                // Handle non-anchored tap for keyboard
                if (kbVisible && !isAnchored && wasTap) {
                    customKeyboard?.performFocusedTap()
                    return true
                }

                // Handle non-anchored tap for bookmarks
                if (::leftBookmarksView.isInitialized &&
                                leftBookmarksView.visibility == View.VISIBLE &&
                                !isAnchored &&
                                wasTap
                ) {
                    leftBookmarksView.performFocusedTap()
                    return true
                }
            }
        }

        // Let the keyboard keep handling movement (your current behavior)
        if (kbVisible && !isAnchored) {
            return customKeyboard?.dispatchTouchEvent(event) == true
        }

        // Let the bookmarks view handle movement in non-anchored mode
        if (::leftBookmarksView.isInitialized &&
                        leftBookmarksView.visibility == View.VISIBLE &&
                        !isAnchored
        ) {
            leftBookmarksView.handleDrag(event.x, event.action)
            return true
        }

        return super.onTouchEvent(event)
    }

    fun getKeyboardLocation(location: IntArray) {
        keyboardContainer.getLocationOnScreen(location)
    }

    fun getLogicalKeyboardLocation(location: IntArray) {
        location[0] = keyboardContainer.left
        location[1] = keyboardContainer.top
    }

    fun isPointInBookmarks(screenX: Float, screenY: Float): Boolean {
        if (!::leftBookmarksView.isInitialized || leftBookmarksView.visibility != View.VISIBLE)
                return false

        val bookmarksLocation = IntArray(2)
        leftBookmarksView.getLocationOnScreen(bookmarksLocation)

        return screenX >= bookmarksLocation[0] &&
                screenX <= bookmarksLocation[0] + leftBookmarksView.width &&
                screenY >= bookmarksLocation[1] &&
                screenY <= bookmarksLocation[1] + leftBookmarksView.height
    }

    fun isChatVisible(): Boolean {
        return ::chatView.isInitialized && chatView.visibility == View.VISIBLE
    }

    fun sendTextToChatInput(text: String) {
        if (!isChatVisible()) return
        chatView.sendTextToFocusedInput(text)
    }

    fun sendBackspaceToChatInput() {
        if (!isChatVisible()) return
        chatView.sendBackspaceToFocusedInput()
    }

    fun sendEnterToChatInput() {
        if (!isChatVisible()) return
        chatView.sendEnterToFocusedInput()
    }

    fun isPointInChat(screenX: Float, screenY: Float): Boolean {
        if (!isChatVisible()) return false

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        return localX >= chatView.left &&
                localX <= chatView.right &&
                localY >= chatView.top &&
                localY <= chatView.bottom
    }

    fun dispatchChatTouchEvent(screenX: Float, screenY: Float) {
        if (!isChatVisible()) return

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        val finalX = localX - chatView.left
        val finalY = localY - chatView.top
        chatView.handleAnchoredTap(finalX, finalY)
    }

    fun isPointInKeyboard(screenX: Float, screenY: Float): Boolean {
        if (keyboardContainer.visibility != View.VISIBLE) return false
        val kbView = customKeyboard ?: return false
        if (kbView.visibility != View.VISIBLE) return false

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        return localX >= keyboardContainer.left &&
                localX <= keyboardContainer.right &&
                localY >= keyboardContainer.top &&
                localY <= keyboardContainer.bottom
    }

    fun getKeyboardSize(): Pair<Int, Int> {
        return Pair(keyboardContainer.width, keyboardContainer.height)
    }

    // Called from MainActivity when the cursor is over the keyboard
    // Called from MainActivity to dispatch a tap to the custom keyboard
    fun dispatchKeyboardTap(screenX: Float, screenY: Float) {
        val kbView = customKeyboard ?: return
        if (kbView.visibility != View.VISIBLE) return

        val groupLocation = IntArray(2)
        getLocationOnScreen(groupLocation)

        // Translate screen coordinates to be relative to the UI container's screen origin
        // Note: keyboardContainer is a child of leftEyeUIContainer
        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()

            // Interaction is already scaled in MainActivity for non-anchored,
            // but in anchored mode screen coordinates are absolute.
            // However, the UI inside the container is logical.
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        // Subtract keyboard's logical position within the container
        val finalX = localX - keyboardContainer.left
        val finalY = localY - keyboardContainer.top

        // DebugLog.d("KeyboardDebug", "Keyboard tap: screen($screenX, $screenY) -> local($finalX,
        // $finalY)")
        kbView.handleAnchoredTap(finalX, finalY)
    }

    fun isDesktopMode(): Boolean {
        return isDesktopMode
    }

    fun setMobileUserAgent(ua: String) {
        mobileUserAgent = ua
        desktopUserAgent = buildDesktopUserAgentFromMobile(ua)
    }

    fun getDesktopUserAgent(): String {
        return desktopUserAgent
    }

    /**
     * Remove the "wv" embedded-WebView marker from an Android default
     * user-agent string. The default looks like:
     *   "Mozilla/5.0 (Linux; Android 14; X3-Pro; wv) AppleWebKit/..."
     * The "; wv" between the device id and the closing paren is what
     * Cloudflare and other bot-detection services use to identify the
     * page as a WebView (vs. real Chrome). Stripping it makes the UA
     * indistinguishable from the underlying Chrome's real UA — same
     * engine, same version, just without the embedded-app signal.
     *
     * Two patterns are handled because the marker location varies by
     * Android/WebView version:
     *   • "; wv)"   — most common (Android 7+)
     *   • "; wv;"   — earlier variants (rare)
     *   • " wv "    — defensive
     * Multiple spaces collapsed at the end so the output stays valid.
     */
    private fun stripWebViewMarker(ua: String): String {
        if (ua.isBlank()) return ua
        return ua
            .replace(Regex(""";\s*wv\s*\)"""), ")")
            .replace(Regex(""";\s*wv\s*;"""), ";")
            .replace(Regex("""\s+wv\s+"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun buildDesktopUserAgentFromMobile(mobileUa: String): String {
        val chromeVersion = Regex("""Chrome/([0-9.]+)""").find(mobileUa)?.groupValues?.get(1)
        if (!chromeVersion.isNullOrBlank()) {
            // Use real runtime Chrome version so desktop mode is plausible, not hardcoded/fake.
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$chromeVersion Safari/537.36"
        }

        // Fallback: keep runtime UA and strip obvious embedded/mobile markers.
        return mobileUa.replace(Regex(""";\s*wv\b"""), "")
                .replace(Regex("""\sVersion/\d+(\.\d+)*"""), "")
                .replace(Regex("""\sMobile\b"""), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
    }

    fun updateBrowsingMode(isDesktop: Boolean) {
        // DebugLog.d("ModeToggle", "Updating browsing mode to: ${if (isDesktop) "desktop" else
        // "mobile"}")

        isDesktopMode = isDesktop

        // Step 1: Update WebView settings (user agent)
        // If on Netflix, we preserve the current UA (which should be the system default) to prevent
        // DRM errors.
        applyBrowsingModeToWebView(webView, isDesktop)

        // Step 2: Update viewport using JavaScript without forcing a complete reload
        val viewportContent =
                if (isDesktop) "width=1280, initial-scale=0.8"
                else "width=600, initial-scale=1.0, maximum-scale=1.0"

        webView.post {
            webView.evaluateJavascript(
                    """
            (function() {
                var viewport = document.querySelector('meta[name="viewport"]');
                if (!viewport) {
                    viewport = document.createElement('meta');
                    viewport.name = 'viewport';
                    document.head.appendChild(viewport);
                }
                viewport.content = '$viewportContent';
            })();
            """,
                    null
            )

            // Step 3: Soft reload the page by re-navigating to the current URL
            val currentUrl = webView.url
            if (currentUrl != null && currentUrl != "about:blank") {
                // Use loadUrl to "soft reload" and keep browsing history
                webView.loadUrl("javascript:window.location.href = window.location.href")
            }
        }

        // Update toggle button icons
        syncBrowsingModeUi()
    }

    private fun applyBrowsingModeToWebView(targetWebView: WebView, isDesktop: Boolean) {
        val isNetflix = targetWebView.url?.contains("netflix.com") == true
        if (isNetflix) return

        targetWebView.settings.apply {
            userAgentString =
                    if (isDesktop) {
                        desktopUserAgent
                    } else {
                        mobileUserAgent
                    }
            loadWithOverviewMode = true
            useWideViewPort = true
        }
    }

    private fun syncBrowsingModeUi() {
        webView.post {
            val leftButton = leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle)
            leftButton?.text =
                    context.getString(
                            if (isDesktopMode) R.string.fa_desktop else R.string.fa_mobile_screen
                    )
        }
    }

    private fun loadARDashboard() {
        webView.loadUrl(Constants.DEFAULT_URL)
    }

    // Method to disable text handles
    @SuppressLint("DiscouragedPrivateApi")
    private fun disableTextHandles(editText: EditText) {
        // Don’t allow long-press to start selection
        editText.isLongClickable = false
        editText.setOnLongClickListener { true }

        // Don’t allow selection mode (copy/paste toolbar)
        editText.setTextIsSelectable(false)

        // Block the selection action mode
        editText.customSelectionActionModeCallback =
                object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(
                            mode: android.view.ActionMode,
                            menu: android.view.Menu
                    ) = false
                    override fun onPrepareActionMode(
                            mode: android.view.ActionMode,
                            menu: android.view.Menu
                    ) = false
                    override fun onActionItemClicked(
                            mode: android.view.ActionMode,
                            item: android.view.MenuItem
                    ) = false
                    override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                }

        // Block the insertion/caret handle action mode (API 23+)
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            editText.customInsertionActionModeCallback =
                    object : android.view.ActionMode.Callback {
                        override fun onCreateActionMode(
                                mode: android.view.ActionMode,
                                menu: android.view.Menu
                        ) = false
                        override fun onPrepareActionMode(
                                mode: android.view.ActionMode,
                                menu: android.view.Menu
                        ) = false
                        override fun onActionItemClicked(
                                mode: android.view.ActionMode,
                                item: android.view.MenuItem
                        ) = false
                        override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                    }
        }

        // Optional: consume double-tap/long-press gestures that can trigger selection on some OEM
        // skins
        editText.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.eventTime - ev.downTime > 0) {
                // Let simple taps through; block long-press-ish starts if needed
                false
            } else {
                false
            }
        }
    }

    // In DualWebViewGroup.kt
    fun showEditField(initialText: String) {
        urlEditText.apply {
            text.clear()
            append(initialText)
            visibility = View.VISIBLE
            requestFocus()
            setSelection(text.length)
            bringToFront()
            // Add logging to verify state
        }
        // Make sure we're in edit mode
        isBookmarkEditing = true
        keyboardListener?.onShowKeyboard()

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    private fun showButtonClickFeedback(button: View) {
        button.isPressed = true
        // DebugLog.d("buttonFeedbackDebug", "button feedback shown")
        Handler(Looper.getMainLooper())
                .postDelayed({ button.isPressed = false }, buttonFeedbackDuration)
    }

    private fun handleLeftMenuAction(buttonId: Int) {
        if (buttonId != R.id.btnAnchor) {
            keyboardListener?.onHideKeyboard()
        }

        val button = leftToggleBar.findViewById<View>(buttonId)

        when (buttonId) {
            R.id.btnModeToggle -> {
                button?.let { showButtonClickFeedback(it) }
                isDesktopMode = !isDesktopMode

                // Save preference
                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("isDesktopMode", isDesktopMode)
                        .apply()

                updateBrowsingMode(isDesktopMode)
            }
            R.id.btnYouTube -> {
                button?.let { showButtonClickFeedback(it) }
                loadARDashboard()
            }
            R.id.btnBookmarks -> {
                button?.let { showButtonClickFeedback(it) }
                toggleBookmarks()
            }
            R.id.btnZoomOut -> {
                button?.let { showButtonClickFeedback(it) }
                handleZoomButtonClick("out")
            }
            R.id.btnZoomIn -> {
                button?.let { showButtonClickFeedback(it) }
                handleZoomButtonClick("in")
            }
            R.id.btnMask -> {
                button?.let { showButtonClickFeedback(it) }
                maskToggleListener?.onMaskTogglePressed()
            }
            R.id.btnAnchor -> {
                button?.let { showButtonClickFeedback(it) }
                anchorToggleListener?.onAnchorTogglePressed()
            }
        }
    }

    fun hideBookmarkEditing() {
        isBookmarkEditing = false
        urlEditText.apply {
            visibility = View.GONE
            text.clear()
        }

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun isBookmarkEditing(): Boolean {
        return isBookmarkEditing
    }

    // Add this method to handle cursor hovering
    private fun updateButtonHoverStates(screenX: Float, screenY: Float) {
        // Clear all states initially
        clearAllHoverStates()

        if (::chatView.isInitialized && chatView.visibility == View.VISIBLE) {
            val uiLocation = IntArray(2)
            leftEyeUIContainer.getLocationOnScreen(uiLocation)

            val translatedX = screenX - uiLocation[0]
            val translatedY = screenY - uiLocation[1]

            val localX: Float
            val localY: Float

            if (isAnchored) {
                val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
                val cos = Math.cos(rotationRad).toFloat()
                val sin = Math.sin(rotationRad).toFloat()
                localX = (translatedX * cos + translatedY * sin) / uiScale
                localY = (-translatedX * sin + translatedY * cos) / uiScale
            } else {
                localX = translatedX / uiScale
                localY = translatedY / uiScale
            }

            val chatLocalX = localX - chatView.left
            val chatLocalY = localY - chatView.top

            if (chatView.updateHoverLocal(chatLocalX, chatLocalY)) {
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check bottom navigation bar buttons ONLY if nav bar is visible
        if (leftNavigationBar.visibility == View.VISIBLE) {
            navButtons.forEach { (_, navButton) ->
                if (isOver(navButton.left, screenX, screenY)) {
                    navButton.isHovered = true
                    navButton.left.isHovered = true
                    navButton.right.isHovered = true
                    customKeyboard?.clearHover() // Clear keyboard hover
                    return // Found the hovered button, stop checking
                }
            }
        }

        // Check left toggle bar buttons
        val toggleBarButtons =
                listOf(
                        Triple(R.id.btnModeToggle, "ModeToggle") { isHoveringModeToggle = true },
                        Triple(R.id.btnYouTube, "Dashboard") { isHoveringDashboardToggle = true },
                        Triple(R.id.btnBookmarks, "Bookmarks") { isHoveringBookmarksMenu = true },
                        Triple(R.id.btnZoomOut, "ZoomOut") { isHoveringZoomOut = true },
                        Triple(R.id.btnZoomIn, "ZoomIn") { isHoveringZoomIn = true },
                        Triple(R.id.btnMask, "Mask") { isHoveringMaskToggle = true },
                        Triple(R.id.btnAnchor, "Anchor") { isHoveringAnchorToggle = true }
                )

        for ((buttonId, _, setHoverFlag) in toggleBarButtons) {
            val button = leftToggleBar.findViewById<View>(buttonId)
            if (isOver(button, screenX, screenY)) {
                button?.isHovered = true
                setHoverFlag()
                clearNavigationButtonStates()
                // DebugLog.d("HoverDebug", "Hovering over toggle button: $name")
                customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                return // Found the hovered button, stop checking
            }
        }

        // Check Windows button separately (programmatically created, no resource ID)
        windowsButton?.let { btn ->
            if (isOver(btn, screenX, screenY)) {
                btn.isHovered = true
                isHoveringWindowsToggle = true
                clearNavigationButtonStates()
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check settings window elements if visible
        if (isSettingsVisible) {
            settingsMenu?.let { menu ->
                val settingsElements =
                        listOf(
                                R.id.volumeSeekBar,
                                R.id.brightnessSeekBar,
                                R.id.btnToggleForceDark,
                                R.id.smoothnessSeekBar,
                                R.id.screenSizeSeekBar,
                                R.id.btnResetScreenSize,
                                R.id.fontSizeSeekBar,
                                R.id.btnResetFontSize,
                                R.id.btnResetWebpageZoom,
                                R.id.colorWheelView,
                                R.id.btnResetTextColor,
                                R.id.btnOutdoorBrightness,
                                R.id.horizontalPosSeekBar,
                                R.id.verticalPosSeekBar,
                                R.id.btnResetPosition,
                                R.id.btnHelp,
                                R.id.btnCloseSettings,
                                R.id.btnGroqApiKey
                        )
                for (id in settingsElements) {
                    val view = menu.findViewById<View>(id)
                    if (isOver(view, screenX, screenY)) {
                        view?.isHovered = true
                        // DebugLog.d("HoverDebug", "Hovering over settings element: $id")
                        customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                        return // Found the hovered element, stop checking
                    }
                }
            }
        }

        // Check active dialog buttons if visible
        if (dialogContainer.visibility == View.VISIBLE) {
            val dialogView = dialogContainer.getChildAt(0) as? ViewGroup
            dialogView?.let { viewGroup ->
                // Dialog structure: Title(0), Message(1), optional Input(2), ButtonContainer(last)
                val btnContainer = viewGroup.getChildAt(viewGroup.childCount - 1) as? ViewGroup
                btnContainer?.let { container ->
                    for (i in 0 until container.childCount) {
                        val button = container.getChildAt(i)
                        if (isOver(button, screenX, screenY)) {
                            button.isHovered = true
                            // DebugLog.d("HoverDebug", "Hovering over dialog button: $i")
                            customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                            return
                        }
                    }
                }
            }
        }

        // Check windows overview if visible
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            val woc = windowsOverviewContainer ?: return

            // Use anchored coordinates if needed
            if (isAnchored) {
                val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

                // Perform hit testing relative to leftEyeUIContainer
                // woc is a child of leftEyeUIContainer
                val wocLeft = woc.left + woc.translationX
                val wocTop = woc.top + woc.translationY

                if (localX >= wocLeft &&
                                localX <= wocLeft + woc.width &&
                                localY >= wocTop &&
                                localY <= wocTop + woc.height
                ) {

                    val container = woc.getChildAt(0) as? LinearLayout ?: return
                    // Container is inside ScrollView woc
                    // local in woc
                    val xInWoc = localX - wocLeft + woc.scrollX
                    val yInWoc = localY - wocTop + woc.scrollY

                    val containerLeft = container.left + container.translationX
                    val containerTop = container.top + container.translationY

                    val xInContainer = xInWoc - containerLeft
                    val yInContainer = yInWoc - containerTop

                    // Helper for checking children
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i)
                        if (xInContainer >= child.left &&
                                        xInContainer <= child.right &&
                                        yInContainer >= child.top &&
                                        yInContainer <= child.bottom
                        ) {

                            if (i == 0) { // Add Button
                                child.isHovered = true
                                hoveredWindowsOverviewItem = child
                                customKeyboard?.updateHover(-1f, -1f)
                                return
                            }

                            // Rows
                            if (child is ViewGroup) {
                                val xInChild = xInContainer - child.left
                                val yInChild = yInContainer - child.top

                                for (j in 0 until child.childCount) {
                                    val item = child.getChildAt(j)
                                    if (xInChild >= item.left &&
                                                    xInChild <= item.right &&
                                                    yInChild >= item.top &&
                                                    yInChild <= item.bottom
                                    ) {

                                        // Check for delete button first (FontIconView child)
                                        if (item is ViewGroup) {
                                            val xInItem = xInChild - item.left
                                            val yInItem = yInChild - item.top
                                            for (k in 0 until item.childCount) {
                                                val itemChild = item.getChildAt(k)
                                                if (itemChild is FontIconView &&
                                                                xInItem >= itemChild.left &&
                                                                xInItem <= itemChild.right &&
                                                                yInItem >= itemChild.top &&
                                                                yInItem <= itemChild.bottom
                                                ) {
                                                    itemChild.isHovered = true
                                                    hoveredWindowsOverviewItem = itemChild
                                                    customKeyboard?.updateHover(-1f, -1f)
                                                    return
                                                }
                                            }
                                        }

                                        // Set hover on the whole item
                                        item.isHovered = true
                                        hoveredWindowsOverviewItem = item
                                        customKeyboard?.updateHover(-1f, -1f)
                                        return
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Non-anchored mode: Use the isOver function with getGlobalVisibleRect
                val container = woc.getChildAt(0) as? LinearLayout ?: return

                // Check all children (add button + rows of window items)
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)

                    // First child (i == 0) is the Add Button
                    if (i == 0 && isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        hoveredWindowsOverviewItem = child
                        customKeyboard?.updateHover(-1f, -1f)
                        return
                    }

                    // Other children are rows containing window items
                    if (child is ViewGroup) {
                        for (j in 0 until child.childCount) {
                            val windowItem = child.getChildAt(j)

                            // Check for delete button first (it's a FontIconView child of the
                            // window item)
                            if (windowItem is ViewGroup) {
                                for (k in 0 until windowItem.childCount) {
                                    val itemChild = windowItem.getChildAt(k)
                                    // Delete button is a FontIconView with the X icon
                                    if (itemChild is FontIconView &&
                                                    isOver(itemChild, screenX, screenY)
                                    ) {
                                        itemChild.isHovered = true
                                        hoveredWindowsOverviewItem = itemChild
                                        customKeyboard?.updateHover(-1f, -1f)
                                        return
                                    }
                                }
                            }

                            // Then check the whole window item
                            if (isOver(windowItem, screenX, screenY)) {
                                windowItem.isHovered = true
                                hoveredWindowsOverviewItem = windowItem
                                customKeyboard?.updateHover(-1f, -1f)
                                return
                            }
                        }
                    }
                }
            }
        }

        // Check bookmarks view if visible
        if (isBookmarksExpanded()) {
            val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

            val finalX = localX - leftBookmarksView.left
            val finalY = localY - leftBookmarksView.top

            if (leftBookmarksView.updateHover(finalX, finalY)) {
                customKeyboard?.updateHoverScreen(-1f, -1f, 1f) // Clear keyboard hover
                return
            }
        }

        // Check scrollbars if visible (UI scale < 0.99f or forced visible)
        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            horizontalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + horizontalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + horizontalScrollBar.height
            ) {

                // Check children (arrows and track)
                for (i in 0 until horizontalScrollBar.childCount) {
                    val child = horizontalScrollBar.getChildAt(i)
                    if (isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        child.isActivated = true

                        // If we are over the track container, check the thumb specifically
                        if (child == horizontalScrollBar.getChildAt(1)) {
                            if (isOver(hScrollThumb, screenX, screenY)) {
                                hScrollThumb.isHovered = true
                                hScrollThumb.isActivated = true
                            }
                        }
                    }
                }
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        if (verticalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            verticalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + verticalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + verticalScrollBar.height
            ) {

                // Check children (arrows and track)
                for (i in 0 until verticalScrollBar.childCount) {
                    val child = verticalScrollBar.getChildAt(i)
                    if (isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        child.isActivated = true

                        // If we are over the track container, check the thumb specifically
                        if (child == verticalScrollBar.getChildAt(1)) {
                            if (isOver(vScrollThumb, screenX, screenY)) {
                                vScrollThumb.isHovered = true
                                vScrollThumb.isActivated = true
                            }
                        }
                    }
                }
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check keyboard elements if visible
        if (keyboardContainer.visibility == View.VISIBLE) {
            val kbView = customKeyboard
            if (kbView != null && kbView.visibility == View.VISIBLE) {
                val uiLocation = IntArray(2)
                leftEyeUIContainer.getLocationOnScreen(uiLocation)

                // Use screen coordinates for keyboard hit testing to avoid drift
                // Pass raw screenX/screenY and let CustomKeyboardView check against actual screen
                // positions
                kbView.updateHoverScreen(screenX, screenY, uiScale)

                // We don't return here because updateHoverScreen will internally check if a key was
                // hit.
                // However, we should check if a key WAS hit to know if we should "consume" the
                // hover event.
                // For now, if the keyboard is visible, we let it process.
                return // Stop checking after keyboard processing
            }
        }
    }

    // Helper function to clear all hover states
    private fun clearAllHoverStates() {
        // Clear toggle button states
        isHoveringModeToggle = false
        isHoveringDashboardToggle = false
        isHoveringBookmarksMenu = false
        isHoveringZoomIn = false
        isHoveringZoomOut = false

        isHoveringMaskToggle = false
        isHoveringAnchorToggle = false
        isHoveringWindowsToggle = false

        // Clear windows overview hover
        hoveredWindowsOverviewItem?.isHovered = false
        hoveredWindowsOverviewItem = null

        // Clear visual hover states
        listOf(
                        R.id.btnModeToggle,
                        R.id.btnYouTube,
                        R.id.btnBookmarks,
                        R.id.btnZoomIn,
                        R.id.btnZoomOut,
                        R.id.btnMask,
                        R.id.btnAnchor
                )
                .forEach { id -> leftToggleBar.findViewById<View>(id)?.isHovered = false }

        // Clear Windows button hover state (programmatically created)
        windowsButton?.isHovered = false

        // Clear settings hover states
        if (isSettingsVisible) {
            settingsMenu?.let { menu ->
                val settingsElements =
                        listOf(
                                R.id.volumeSeekBar,
                                R.id.brightnessSeekBar,
                                R.id.btnToggleForceDark,
                                R.id.smoothnessSeekBar,
                                R.id.screenSizeSeekBar,
                                R.id.btnResetScreenSize,
                                R.id.fontSizeSeekBar,
                                R.id.btnResetFontSize,
                                R.id.btnResetWebpageZoom,
                                R.id.colorWheelView,
                                R.id.btnResetTextColor,
                                R.id.btnOutdoorBrightness,
                                R.id.horizontalPosSeekBar,
                                R.id.verticalPosSeekBar,
                                R.id.btnResetPosition,
                                R.id.btnHelp,
                                R.id.btnCloseSettings,
                                R.id.btnGroqApiKey
                        )
                for (id in settingsElements) {
                    menu.findViewById<View>(id)?.isHovered = false
                }
            }
        }

        // Clear dialog button states
        if (dialogContainer.visibility == View.VISIBLE) {
            val dialogView = dialogContainer.getChildAt(0) as? ViewGroup
            dialogView?.let { viewGroup ->
                val btnContainer = viewGroup.getChildAt(viewGroup.childCount - 1) as? ViewGroup
                btnContainer?.let { container ->
                    for (i in 0 until container.childCount) {
                        container.getChildAt(i).isHovered = false
                    }
                }
            }
        }

        // Clear navigation button states
        clearNavigationButtonStates()

        if (::chatView.isInitialized) {
            chatView.clearHover()
        }

        // Clear keyboard hover
        customKeyboard?.updateHoverScreen(-1f, -1f, 1f)

        // Clear scroll bar hover states
        if (horizontalScrollBar.visibility == View.VISIBLE) {
            for (i in 0 until horizontalScrollBar.childCount) {
                horizontalScrollBar.getChildAt(i).isHovered = false
                horizontalScrollBar.getChildAt(i).isActivated = false
            }
            hScrollThumb.isHovered = false
            hScrollThumb.isActivated = false
        }
        if (verticalScrollBar.visibility == View.VISIBLE) {
            for (i in 0 until verticalScrollBar.childCount) {
                verticalScrollBar.getChildAt(i).isHovered = false
                verticalScrollBar.getChildAt(i).isActivated = false
            }
            vScrollThumb.isHovered = false
            vScrollThumb.isActivated = false
        }
    }

    // Helper method to check if a point is within any visible scrollbar
    fun isPointInScrollbar(screenX: Float, screenY: Float): Boolean {
        return mirroredScreenXCandidates(screenX).any { candidateX ->
            isOver(horizontalScrollBar, candidateX, screenY) ||
                    isOver(verticalScrollBar, candidateX, screenY)
        }
    }

    // Dispatch touch/click to the appropriate scrollbar element
    fun dispatchScrollbarTouch(screenX: Float, screenY: Float) {
        val xCandidates = mirroredScreenXCandidates(screenX)

        fun visibleRect(view: View?): android.graphics.Rect? {
            if (view == null || view.visibility != View.VISIBLE) return null
            val rect = android.graphics.Rect()
            if (!view.getGlobalVisibleRect(rect)) return null
            if (rect.width() <= 0 || rect.height() <= 0) return null
            return rect
        }

        fun contains(rect: android.graphics.Rect?, x: Float, y: Float): Boolean {
            if (rect == null) return false
            return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
        }

        fun dispatchHorizontal(testX: Float): Boolean {
            if (horizontalScrollBar.visibility != View.VISIBLE) return false

            val leftArrow = horizontalScrollBar.getChildAt(0)
            val track = horizontalScrollBar.getChildAt(1) as? FrameLayout
            val rightArrow = horizontalScrollBar.getChildAt(2)

            if (contains(visibleRect(leftArrow), testX, screenY)) {
                clickScrollBarArrow { scrollPageHorizontal(-10) }
                return true
            }
            if (contains(visibleRect(rightArrow), testX, screenY)) {
                clickScrollBarArrow { scrollPageHorizontal(10) }
                return true
            }

            val trackRect = visibleRect(track) ?: return false
            if (!contains(trackRect, testX, screenY)) return false

            beginScrollBarInteraction()
            val thumbWidth =
                    visibleRect(hScrollThumb)?.width()?.takeIf { it > 0 }
                            ?: hScrollThumb.width.takeIf { it > 0 }
                            ?: 60
            val trackableWidth = (trackRect.width() - thumbWidth).coerceAtLeast(1)
            val percent =
                    ((testX - trackRect.left) - (thumbWidth / 2f)) / trackableWidth.toFloat()
            val clamped = percent.coerceIn(0f, 1f)
            updateHorizontalScroll(clamped)
            val localTrackable = ((track?.width ?: 0) - hScrollThumb.width).coerceAtLeast(0)
            hScrollThumb.translationX = clamped * localTrackable
            hScrollThumb.invalidate()
            finishScrollBarInteraction()
            return true
        }

        fun dispatchVertical(testX: Float): Boolean {
            if (verticalScrollBar.visibility != View.VISIBLE) return false

            val upArrow = verticalScrollBar.getChildAt(0)
            val track = verticalScrollBar.getChildAt(1) as? FrameLayout
            val downArrow = verticalScrollBar.getChildAt(2)

            if (contains(visibleRect(upArrow), testX, screenY)) {
                clickScrollBarArrow { scrollPageVertical(-10) }
                return true
            }
            if (contains(visibleRect(downArrow), testX, screenY)) {
                clickScrollBarArrow { scrollPageVertical(10) }
                return true
            }

            val trackRect = visibleRect(track) ?: return false
            if (!contains(trackRect, testX, screenY)) return false

            beginScrollBarInteraction()
            val thumbHeight =
                    visibleRect(vScrollThumb)?.height()?.takeIf { it > 0 }
                            ?: vScrollThumb.height.takeIf { it > 0 }
                            ?: 60
            val trackableHeight = (trackRect.height() - thumbHeight).coerceAtLeast(1)
            val percent =
                    ((screenY - trackRect.top) - (thumbHeight / 2f)) /
                            trackableHeight.toFloat()
            val clamped = percent.coerceIn(0f, 1f)
            updateVerticalScroll(clamped)
            val localTrackable = ((track?.height ?: 0) - vScrollThumb.height).coerceAtLeast(0)
            vScrollThumb.translationY = clamped * localTrackable
            vScrollThumb.invalidate()
            finishScrollBarInteraction()
            return true
        }

        for (candidateX in xCandidates) {
            if (dispatchVertical(candidateX)) return
            if (dispatchHorizontal(candidateX)) return
        }
    }

    private fun mirroredScreenXCandidates(screenX: Float): List<Float> {
        val candidates = ArrayList<Float>(3)
        fun add(x: Float) {
            if (x.isFinite() && candidates.none { kotlin.math.abs(it - x) < 0.5f }) {
                candidates.add(x)
            }
        }

        add(screenX)

        // BinocularSbsLayout draws the logical viewport twice. Android view
        // bounds only exist for the logical copy, so a cursor/tap that lands on
        // the mirrored copy must be folded back into logical screen space for
        // custom views like our scrollbars.
        val sbsView = findBinocularSbsHost() ?: return candidates
        val sbsLocation = IntArray(2)
        sbsView.getLocationOnScreen(sbsLocation)
        val sbsWidth = sbsView.width.takeIf { it > 0 } ?: return candidates
        val eyeWidth = (sbsWidth / 2f).takeIf { it > 0f } ?: return candidates
        val xInParent = screenX - sbsLocation[0]
        if (xInParent >= eyeWidth) {
            add(screenX - eyeWidth)
        } else {
            add(screenX + eyeWidth)
        }
        return candidates
    }

    private fun findBinocularSbsHost(): View? {
        var current: View? = this
        while (current != null) {
            if (current.javaClass.simpleName == "BinocularSbsLayout") {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    fun isNavBarVisible(): Boolean {
        // Check both visibility AND scroll mode - in scroll mode, bars are hidden even during fade
        // animation
        return !isInScrollMode && leftNavigationBar.visibility == View.VISIBLE
    }

    fun isToggleBarVisible(): Boolean {
        return leftToggleBar.visibility == View.VISIBLE
    }

    private fun isPointInToggleBarButton(screenX: Float, screenY: Float): Boolean {
        if (leftToggleBar.visibility != View.VISIBLE) return false
        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)
        val buttonIds = listOf(
            R.id.btnModeToggle,
            R.id.btnYouTube,
            R.id.btnBookmarks,
            R.id.btnZoomOut,
            R.id.btnZoomIn,
            R.id.btnMask,
            R.id.btnAnchor
        )
        if (buttonIds.any { id -> isPointInChild(localX, localY, leftToggleBar, leftToggleBar.findViewById(id)) }) {
            return true
        }
        return windowsButton?.let { isPointInChild(localX, localY, leftToggleBar, it) } == true
    }

    fun isPointInToggleBar(screenX: Float, screenY: Float): Boolean {
        return isPointInToggleBarButton(screenX, screenY)
    }

    fun isPointInNavBar(screenX: Float, screenY: Float): Boolean {
        if (leftNavigationBar.visibility != View.VISIBLE) return false
        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)
        return navButtons.entries.any { isPointInChild(localX, localY, leftNavigationBar, it.value.left) }
    }

    private fun isPointInView(containerX: Float, containerY: Float, view: View?): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        return containerX >= view.left &&
                containerX <= view.right &&
                containerY >= view.top &&
                containerY <= view.bottom
    }

    private fun isPointInChild(
            containerX: Float,
            containerY: Float,
            parent: View,
            child: View?
    ): Boolean {
        if (child == null || parent.visibility != View.VISIBLE || child.visibility != View.VISIBLE)
                return false
        val localX = containerX - parent.left
        val localY = containerY - parent.top
        return localX >= child.left &&
                localX <= child.right &&
                localY >= child.top &&
                localY <= child.bottom
    }

    fun isPointInRestoreButton(x: Float, y: Float): Boolean {
        if (btnShowNavBars.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        btnShowNavBars.getLocationOnScreen(loc)
        return x >= loc[0] &&
                x <= loc[0] + (btnShowNavBars.width * uiScale) &&
                y >= loc[1] &&
                y <= loc[1] + (btnShowNavBars.height * uiScale)
    }

    fun performRestoreButtonClick() {
        if (btnShowNavBars.visibility == View.VISIBLE) {
            btnShowNavBars.performClick()
        }
    }

    fun isWindowsOverviewVisible(): Boolean {
        return windowsOverviewContainer?.visibility == View.VISIBLE
    }

    fun isPointInWindowsOverview(x: Float, y: Float): Boolean {
        val woc = windowsOverviewContainer ?: return false
        if (woc.visibility != View.VISIBLE) return false

        val loc = IntArray(2)
        woc.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + woc.width && y >= loc[1] && y <= loc[1] + woc.height
    }

    fun performWindowsOverviewClick() {
        val current = hoveredWindowsOverviewItem
        if (current == null || !current.isAttachedToWindow || !current.isHovered) {
            refreshHoverAtCurrentCursor()
        }

        val item = hoveredWindowsOverviewItem ?: return
        if (!item.isAttachedToWindow || !item.isHovered) return

        showButtonClickFeedback(item)
        item.performClick()
    }

    private fun isOver(button: View?, screenX: Float, screenY: Float): Boolean {
        if (button == null || button.visibility != View.VISIBLE) return false

        // Use getGlobalVisibleRect for accurate screen bounds detection
        val rect = android.graphics.Rect()
        if (!button.getGlobalVisibleRect(rect)) return false

        return screenX >= rect.left &&
                screenX <= rect.right &&
                screenY >= rect.top &&
                screenY <= rect.bottom
    }

    fun handleNavigationClick(screenX: Float, screenY: Float) {
        if (isInScrollMode) return

        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

        if (isSettingsVisible && settingsMenu != null) {
            if (isPointInView(localX, localY, settingsMenu)) {
                dispatchSettingsTouchEvent(screenX, screenY)
                return
            }
        }

        if (leftToggleBar.visibility == View.VISIBLE) {
            val toggleBarButtons =
                    listOf(
                            R.id.btnModeToggle,
                            R.id.btnYouTube,
                            R.id.btnBookmarks,
                            R.id.btnZoomOut,
                            R.id.btnZoomIn,
                            R.id.btnMask,
                            R.id.btnAnchor
                    )

            for (buttonId in toggleBarButtons) {
                val button = leftToggleBar.findViewById<View>(buttonId)
                if (isPointInChild(localX, localY, leftToggleBar, button)) {
                    handleLeftMenuAction(buttonId)
                    return
                }
            }

            windowsButton?.let { btn ->
                if (isPointInChild(localX, localY, leftToggleBar, btn)) {
                    showButtonClickFeedback(btn)
                    toggleWindowMode()
                    return
                }
            }
        }

        if (leftNavigationBar.visibility == View.VISIBLE) {
            navButtons.entries.firstOrNull {
                    isPointInChild(localX, localY, leftNavigationBar, it.value.left)
                }?.let { (key, button) ->
                    triggerNavigationAction(key, button)
                }
        }
    }

    private fun triggerNavigationAction(key: String, button: NavButton) {
        keyboardListener?.onHideKeyboard()
        showButtonClickFeedback(button.left)
        showButtonClickFeedback(button.right)
        if (key == "hide") {
            setNavBarsHidden(true) // Hide nav bars but keep cursor visible
            return
        }
        if (key == "chat") {
            toggleChat()
            return
        }
        navigationListener?.let { listener ->
            when (key) {
                "back" -> listener.onNavigationBackPressed()
                "forward" -> listener.onNavigationForwardPressed()
                "home" -> listener.onHomePressed()
                "link" -> listener.onHyperlinkPressed()
                "settings" -> listener.onSettingsPressed()
                "refresh" -> listener.onRefreshPressed()
                "quit" -> listener.onQuitPressed()
            }
        }
    }

    fun resetPositions() {
        // Reset translations
        _translationX = 0f
        _translationY = 0f
        _rotationZ = 0f

        // Reset translations on views
        leftEyeUIContainer.translationX = 0f
        leftEyeUIContainer.translationY = 0f
        leftEyeUIContainer.rotation = 0f

        // Reset translations on views
        leftEyeClipParent.translationX = 0f
        leftEyeClipParent.translationY = 0f
        leftEyeClipParent.rotation = 0f

        // Also reset fullscreen overlay
        fullScreenOverlayContainer.translationX = 0f
        fullScreenOverlayContainer.translationY = 0f
        fullScreenOverlayContainer.rotation = 0f

        postDelayed(
                {
                    startRefreshing()
                    requestLayout()
                    invalidate()
                },
                100
        )
    }

    private fun handleZoomButtonClick(direction: String) {
        // For ar_nav.html: delegate to the 3D map's own zoom handler
        webView.evaluateJavascript(
                """
        (function() {
            if (window.__arNavZoom) { window.__arNavZoom('$direction'); return; }
            document.body.style.zoom = "${if (direction == "in") currentWebZoom * 1.1f else currentWebZoom * 0.9f}";
        })();
    """,
                null
        )

        // Only update CSS zoom tracking for non-AR pages
        val url = webView.url ?: ""
        if (!url.contains("ar_nav.html")) {
            val zoomFactor = if (direction == "in") 1.1f else 0.9f
            currentWebZoom *= zoomFactor
            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putFloat("webZoomLevel", currentWebZoom)
                    .apply()
        }

        postDelayed(
                {
                    updateScrollBarsVisibility()
                    lastScrollBarCheckTime = System.currentTimeMillis()
                },
                100
        )
    }

    fun refreshBothBookmarks() {
        // Refresh left bookmarks view
        leftBookmarksView.refreshBookmarks()
        leftBookmarksView.visibility = View.VISIBLE
        leftBookmarksView.bringToFront()
        leftBookmarksView.measure(
                MeasureSpec.makeMeasureSpec(420, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        leftBookmarksView.layout(
                leftBookmarksView.left,
                leftBookmarksView.top,
                leftBookmarksView.left + 480,
                leftBookmarksView.top + leftBookmarksView.measuredHeight
        )

        // Force a layout update
        leftBookmarksView.post {
            leftBookmarksView.requestLayout()
            leftBookmarksView.invalidate()
            // Ensure the mirroring is updated
            startRefreshing()
        }
    }

    // In DualWebViewGroup.kt, add these methods:
    fun startAnchoring() {
        isAnchored = true
        webView.visibility = View.VISIBLE
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.visibility = View.VISIBLE
        startRefreshing()

        // Update scrollbars immediately
        updateScrollBarsVisibility()

        // Update keyboard behavior
        customKeyboard?.setAnchoredMode(true)

        // Update bookmarks view mode
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.setAnchoredMode(true)
        }

        // Use solid anchor icon when anchored
        leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)?.text =
                context.getString(R.string.fa_anchor)
    }

    fun stopAnchoring() {
        isAnchored = false
        resetPositions()

        // Update scrollbars immediately
        updateScrollBarsVisibility()

        // Update keyboard behavior
        customKeyboard?.setAnchoredMode(false)

        // Update bookmarks view mode
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.setAnchoredMode(false)
        }

        // Use barred anchor icon when not anchored
        leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)?.text =
                context.getString(R.string.fa_anchor_circle_xmark)
        webView.visibility = View.VISIBLE
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.visibility = View.VISIBLE

        updateUiTranslation()

        post {
            startRefreshing()
            invalidate()
        }
    }

    fun setBookmarksView(bookmarksView: BookmarksView) {
        this.leftBookmarksView =
                bookmarksView.apply {
                    val params =
                            MarginLayoutParams(420, LayoutParams.WRAP_CONTENT).apply {
                                leftMargin = toggleBarWidthPx // After toggle bar
                                topMargin = 10 // Move higher up
                            }
                    layoutParams = params
                    elevation = 1000f
                    visibility = View.GONE
                }

        // Remove existing view if present
        (leftBookmarksView.parent as? ViewGroup)?.removeView(leftBookmarksView)

        // Add view to hierarchy
        leftEyeUIContainer.addView(leftBookmarksView)
        leftBookmarksView.bringToFront()

        // Request layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun handleAnchoredFling(velocity: Float) {
        if (isBookmarksExpanded()) {
            // No-op for bookmarks in anchored mode (pagination used)
        } else {
            // Forward to general handleFling which handles WebView scroll
            handleFling(velocity)
        }
    }

    fun handleFling(velocityX: Float) {
        // DebugLog.d("Fling Debug", "Fling handled by DualWebViewGroup")

        // First check if bookmarks are visible (Non-Anchored Mode legacy behavior)
        if (leftBookmarksView.visibility == View.VISIBLE && !isAnchored) {
            // DebugLog.d("DualWebViewGroup", "Delegating fling to bookmarks: velocity=$velocityX")

            // Determine direction based on velocity and delegate to both views
            val isForward = velocityX > 0

            // Update both left and right bookmark views to maintain synchronization
            leftBookmarksView.handleFling(isForward)

            // Force layout update to ensure visual sync between views
            post {
                requestLayout()
                invalidate()
            }
            return
        }

        // If bookmarks aren't visible, handle normal scrolling behavior
        // Slow down the velocity for smoother scrolling
        val slowedVelocity = velocityX * 0.15f

        // Handle vertical scrolling
        webView.evaluateJavascript(
                """
            (function() {
                window.scrollBy({
                    top: ${(-slowedVelocity).toInt()},
                    behavior: 'smooth'
                });
            })();
        """,
                null
        )

        // Provide a native scroll backup only if JS execution fails or is slow?
        // Actually, since we want to avoid double-scroll bouncing, relying on JS scrollBy is safer
        // with 'smooth' behavior.
        // However, if we remove this, we rely solely on JS.
        // Let's remove the unconditional native backup to prevent fighting/overshoot.
    }

    private fun initializeToggleButtons() {
        DebugLog.d(
                "ViewDebug",
                """
    Toggle bar parent: ${leftToggleBar.parent?.javaClass?.simpleName}
    Toggle bar children: ${(leftToggleBar as? ViewGroup)?.childCount ?: "Not a ViewGroup"}
    UI Container children count: ${leftEyeUIContainer.childCount}
    UI Container children:
    ${(0 until leftEyeUIContainer.childCount).joinToString("n") { index ->
            val child = leftEyeUIContainer.getChildAt(index)
            "Child $index: ${child.javaClass.simpleName} (${child.hashCode()})"+
                    "n    Location: (${child.x}, ${child.y})"+
                    "n    Size: ${child.width}x${child.height}"+
                    "n    Translation: (${child.translationX}, ${child.translationY})"
        }}
""".trimIndent()
        )

        // Get references to all buttons
        val leftModeToggleButton = leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle)
        val leftDashboardButton = leftToggleBar.findViewById<FontIconView>(R.id.btnYouTube)
        val leftBookmarksButton = leftToggleBar.findViewById<FontIconView>(R.id.btnBookmarks)
        val leftZoomInButton = leftToggleBar.findViewById<FontIconView>(R.id.btnZoomIn)
        val leftZoomOutButton = leftToggleBar.findViewById<FontIconView>(R.id.btnZoomOut)
        val leftMaskButton = leftToggleBar.findViewById<FontIconView>(R.id.btnMask)
        val leftAnchorButton = leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)

        // Create Windows button programmatically since it's not in XML
        val leftWindowsButton =
                FontIconView(context).apply {
                    id = View.generateViewId() // Generate an ID for the view
                    tag = "btnWindows" // Tag for identification
                    configureToggleButton(R.string.fa_window_restore)
                }
        windowsButton = leftWindowsButton // Store reference for hover/click handling

        // Insert it into toggle bar - we need to add it to the layout
        if (leftToggleBar is ViewGroup) {
            // Find where to insert - maybe after dashboard button?
            // Actually, toggle_bar.xml is a LinearLayout (based on usage).
            // We can just addView. But we need to insert it in the correct order visually.
            // XML has: Mode, YouTube, Bookmarks, ZoomOut, ZoomIn, Mask, Anchor.
            // Let's put Windows button after Bookmarks.
            val index = (leftToggleBar as ViewGroup).indexOfChild(leftBookmarksButton) + 1
            if (index > 0) {
                (leftToggleBar as ViewGroup).addView(leftWindowsButton, index)
            } else {
                (leftToggleBar as ViewGroup).addView(leftWindowsButton)
            }
        }

        // Calculate positioning constants
        val iconPadding = 4.dp()
        val orderedButtons =
                listOf(
                        leftModeToggleButton,
                        leftDashboardButton,
                        leftBookmarksButton,
                        leftWindowsButton,
                        leftZoomOutButton,
                        leftZoomInButton,
                        leftMaskButton,
                        leftAnchorButton
                )

        orderedButtons.forEach { button ->
            try {
                button?.apply {
                    layoutParams =
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    visibility = View.VISIBLE
                    background =
                            ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                    // Icon already set via XML text attribute
                    if (id == R.id.btnAnchor) {
                        text = if (isAnchored) context.getString(R.string.fa_anchor)
                               else context.getString(R.string.fa_anchor_circle_xmark)
                    }
                    setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                    elevation = 4f
                    alpha = 1f
                    isEnabled = true
                    setOnTouchListener { v, _ ->
                        val location = IntArray(2)
                        v.getLocationOnScreen(location)
                        val parentLocation = IntArray(2)
                        leftToggleBar.getLocationOnScreen(parentLocation)

                        false
                    }
                }
            } catch (e: Exception) {
                Log.e("ToggleButton", "Error configuring button", e)
            }
        }

        mapOf(
                        leftModeToggleButton to R.id.btnModeToggle,
                        leftDashboardButton to R.id.btnYouTube,
                        leftBookmarksButton to R.id.btnBookmarks,
                        leftZoomOutButton to R.id.btnZoomOut,
                        leftZoomInButton to R.id.btnZoomIn,
                        leftMaskButton to R.id.btnMask,
                        leftAnchorButton to R.id.btnAnchor
                )
                .forEach { (button, id) -> button?.setOnClickListener { handleLeftMenuAction(id) } }

        leftWindowsButton.setOnClickListener {
            showButtonClickFeedback(leftWindowsButton)
            toggleWindowMode()
        }
    }

    // ── Browser Agent button glow ────────────────────────────────────

    private var agentGlowAnimator: android.animation.ObjectAnimator? = null

    /**
     * Pulse the agent button with a cyan glow while the agent session is
     * active (Gemini is listening / working). Call [setAgentGlowActive](false)
     * to stop.
     */
    fun setAgentGlowActive(active: Boolean) {
        val btn = leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor) ?: return
        agentGlowAnimator?.cancel()
        agentGlowAnimator = null
        if (active) {
            btn.setTextColor(android.graphics.Color.parseColor("#00E5FF")) // cyan
            val animator = android.animation.ObjectAnimator.ofFloat(btn, "alpha", 1f, 0.35f).apply {
                duration = 800
                repeatMode = android.animation.ObjectAnimator.REVERSE
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
            agentGlowAnimator = animator
        } else {
            btn.alpha = 1f
            btn.setTextColor(android.graphics.Color.WHITE)
        }
    }

    /**
     * Capture a JPEG screenshot of the primary WebView for the browser agent.
     * Returns null if capture fails.
     */
    fun captureWebViewScreenshot(): android.graphics.Bitmap? {
        return try {
            val bmp = android.graphics.Bitmap.createBitmap(
                webView.width, webView.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            webView.draw(canvas)
            bmp
        } catch (e: Exception) {
            Log.e("BrowserAgent", "Screenshot capture failed: ${e.message}", e)
            null
        }
    }

    fun isSettingsVisible(): Boolean {
        return isSettingsVisible
    }

    fun hideSettings() {
        if (isSettingsVisible) {
            isSettingsVisible = false
            settingsMenu?.visibility = View.GONE
            settingsScrim?.visibility = View.GONE
        }
    }

    // Reset all overlay UI state - call on app startup
    fun resetUiState() {
        isSettingsVisible = false
        settingsMenu?.visibility = View.GONE
        settingsScrim?.visibility = View.GONE
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.visibility = View.GONE
        }
    }

    fun showSettings() {
        // DebugLog.d("SettingsDebug", "showSettings() called, isSettingsVisible:
        // $isSettingsVisible")

        if (settingsMenu == null) {
            settingsMenu =
                    LayoutInflater.from(context)
                            .inflate(R.layout.settings_layout, null, false)
                            .apply {
                                isClickable = false
                                isFocusable = false
                                elevation = 1001f // Even higher elevation than scrim
                            }

            // Add click handler for close button
            settingsMenu?.findViewById<View>(R.id.btnCloseSettings)?.setOnClickListener {
                // DebugLog.d("SettingsDebug", "Close button clicked")
                isSettingsVisible = false
                settingsMenu?.visibility = View.GONE
                settingsScrim?.visibility = View.GONE
                startRefreshing()
            }

            // Add click handler for help button
            settingsMenu?.findViewById<ImageButton>(R.id.btnHelp)?.setOnClickListener {
                // DebugLog.d("SettingsDebug", "Help button clicked")
                showHelpDialog()
            }

            settingsMenu?.findViewById<Button>(R.id.btnOutdoorBrightness)?.setOnClickListener {
                toggleOutdoorBrightness(
                        settingsMenu?.findViewById(R.id.brightnessSeekBar),
                        settingsMenu?.findViewById(R.id.btnOutdoorBrightness)
                )
            }

            val layoutParams =
                    FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT
                            )
                            .apply { gravity = Gravity.CENTER }

            leftEyeUIContainer.addView(settingsMenu, layoutParams)
            settingsMenu?.elevation = 1001f

            // DebugLog.d("SettingsDebug", "Menu added with height:
            // ${settingsMenu?.measuredHeight}")
        }

        // Only initialize seekbars when we are about to SHOW settings (not when closing)
        if (!isSettingsVisible) {
            settingsMenu?.let { menu ->
                // Initialize volume seekbar
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val volumeSeekBar = menu.findViewById<SeekBar>(R.id.volumeSeekBar)
                volumeSeekBar?.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeSeekBar?.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                // Initialize brightness seekbar
                val brightnessSeekBar = menu.findViewById<SeekBar>(R.id.brightnessSeekBar)
                brightnessSeekBar?.max = 100
                val currentBrightness =
                        (context as? Activity)?.window?.attributes?.screenBrightness ?: 0.5f
                brightnessSeekBar?.progress = (currentBrightness * 100).toInt()
                updateOutdoorBrightnessButtonLabel(menu.findViewById(R.id.btnOutdoorBrightness))
                val forceDarkButton = menu.findViewById<Button>(R.id.btnToggleForceDark)
                val forceDarkEnabled =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getBoolean("forceDarkWebEnabled", true)
                forceDarkButton?.text =
                        if (forceDarkEnabled) "Force Dark: On" else "Force Dark: Off"

                // Initialize smoothness seekbar from saved preference
                val smoothnessSeekBar = menu.findViewById<SeekBar>(R.id.smoothnessSeekBar)
                val savedSmoothness =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("anchorSmoothness", 40)
                smoothnessSeekBar?.progress = savedSmoothness

                // Initialize screen size seekbar (just update the UI, don't apply scale)
                val screenSizeSeekBar = menu.findViewById<SeekBar>(R.id.screenSizeSeekBar)
                val savedScaleProgress =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("uiScaleProgress", 100)
                screenSizeSeekBar?.progress = savedScaleProgress

                // Calculate scale for position slider visibility check only
                val currentScale = 0.25f + (savedScaleProgress / 100f) * 0.75f

                // Initialize position sliders
                val showPosSliders = !isAnchored && currentScale < 0.99f
                val visibility = if (showPosSliders) View.VISIBLE else View.GONE

                menu.findViewById<View>(R.id.settingsPositionLayout)?.visibility = visibility

                menu.findViewById<SeekBar>(R.id.horizontalPosSeekBar)?.apply {
                    progress =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getInt("uiTransXProgress", 50)
                }

                menu.findViewById<SeekBar>(R.id.verticalPosSeekBar)?.apply {
                    progress =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getInt("uiTransYProgress", 50)
                }

                // Initialize font size seekbar (50% = 50, 100% = 100, 200% = 200, slider is 0-150
                // mapping to 50-200%)
                val fontSizeSeekBar = menu.findViewById<SeekBar>(R.id.fontSizeSeekBar)
                val savedFontSize =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("webFontSize", 50) // Default 50 = 100%
                fontSizeSeekBar?.progress = savedFontSize

                // Initialize color buttons with visual background indicators
                // Initialize color wheel with saved color
                menu.findViewById<ColorWheelView>(R.id.colorWheelView)?.apply {
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val savedTextColor = getEffectiveWebTextColor(prefs)
                    try {
                        setColor(Color.parseColor(savedTextColor))
                    } catch (e: Exception) {
                        setColor(Color.WHITE)
                    }
                }

                // Apply saved font settings
                val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                val savedTextColor = getEffectiveWebTextColor(prefs)
                applyWebFontSettings(savedFontSize, savedTextColor)

                // Initialize cursor sensitivity seekbar
                val sensitivitySeekBar = menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                // Default 50 corresponds to 50%
                val savedSensitivity =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("cursorSensitivity", 50)
                sensitivitySeekBar?.progress = savedSensitivity
            }
        }

        // Toggle visibility state
        isSettingsVisible = !isSettingsVisible

        settingsMenu?.visibility = if (isSettingsVisible) View.VISIBLE else View.GONE
        settingsScrim?.visibility = if (isSettingsVisible) View.VISIBLE else View.GONE

        if (isSettingsVisible) {
            settingsScrim?.bringToFront()
            settingsMenu?.bringToFront()

            // Keep the force immediate layout code
            settingsMenu?.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            settingsMenu?.layout(
                    settingsMenu?.left ?: 0,
                    settingsMenu?.top ?: 0,
                    (settingsMenu?.left ?: 0) + (settingsMenu?.measuredWidth ?: 0),
                    (settingsMenu?.top ?: 0) + (settingsMenu?.measuredHeight ?: 0)
            )
        }

        startRefreshing()
        post {
            requestLayout()
            invalidate()
        }
    }

    fun getSettingsMenuLocation(location: IntArray) {
        settingsMenu?.getLocationOnScreen(location)
    }

    fun getSettingsMenuSize(): Pair<Int, Int> {
        return Pair(settingsMenu?.width ?: 0, settingsMenu?.height ?: 0)
    }

    private fun applySettingsBrightness(progress: Int, brightnessSeekBar: SeekBar? = null) {
        val clamped = progress.coerceIn(0, 100)
        brightnessSeekBar?.progress = clamped
        (context as? Activity)?.window?.attributes =
                (context as Activity).window.attributes.apply {
                    screenBrightness = clamped / 100f
                }
    }

    private fun updateOutdoorBrightnessButtonLabel(button: Button?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        button?.text =
                if (prefs.getBoolean(KEY_OUTDOOR_BRIGHTNESS_ACTIVE, false)) {
                    "Restore Brightness"
                } else {
                    "Outdoor Brightness"
                }
    }

    private fun toggleOutdoorBrightness(
            brightnessSeekBar: SeekBar?,
            outdoorBrightnessButton: Button?
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val active = prefs.getBoolean(KEY_OUTDOOR_BRIGHTNESS_ACTIVE, false)
        if (active) {
            val restoreProgress =
                    prefs.getInt(KEY_PRE_OUTDOOR_BRIGHTNESS_PROGRESS, 50).coerceIn(0, 100)
            applySettingsBrightness(restoreProgress, brightnessSeekBar)
            prefs.edit()
                    .putBoolean(KEY_OUTDOOR_BRIGHTNESS_ACTIVE, false)
                    .apply()
        } else {
            val currentProgress =
                    (brightnessSeekBar?.progress
                            ?: (((context as? Activity)?.window?.attributes?.screenBrightness ?: 0.5f) * 100)
                                    .toInt())
                            .coerceIn(0, 100)
            prefs.edit()
                    .putInt(KEY_PRE_OUTDOOR_BRIGHTNESS_PROGRESS, currentProgress)
                    .putBoolean(KEY_OUTDOOR_BRIGHTNESS_ACTIVE, true)
                    .apply()
            applySettingsBrightness(100, brightnessSeekBar)
        }
        updateOutdoorBrightnessButtonLabel(outdoorBrightnessButton)
    }

    fun dispatchSettingsTouchEvent(x: Float, y: Float) {
        settingsMenu?.let { menu ->
            // Get locations of all interactive elements
            val volumeSeekBar = menu.findViewById<SeekBar>(R.id.volumeSeekBar)
            val brightnessSeekBar = menu.findViewById<SeekBar>(R.id.brightnessSeekBar)
            val forceDarkButton = menu.findViewById<Button>(R.id.btnToggleForceDark)
            val smoothnessSeekBar = menu.findViewById<SeekBar>(R.id.smoothnessSeekBar)
            val screenSizeSeekBar = menu.findViewById<SeekBar>(R.id.screenSizeSeekBar)
            val horizontalPosSeekBar = menu.findViewById<SeekBar>(R.id.horizontalPosSeekBar)
            val verticalPosSeekBar = menu.findViewById<SeekBar>(R.id.verticalPosSeekBar)
            val closeButton = menu.findViewById<View>(R.id.btnCloseSettings)
            val helpButton = menu.findViewById<ImageButton>(R.id.btnHelp)
            val resetButton = menu.findViewById<Button>(R.id.btnResetPosition)
            val resetScreenSizeButton = menu.findViewById<Button>(R.id.btnResetScreenSize)
            val fontSizeSeekBar = menu.findViewById<SeekBar>(R.id.fontSizeSeekBar)
            val colorWheelView = menu.findViewById<ColorWheelView>(R.id.colorWheelView)
            val resetTextColorButton = menu.findViewById<Button>(R.id.btnResetTextColor)
            val outdoorBrightnessButton = menu.findViewById<Button>(R.id.btnOutdoorBrightness)
            val groqKeyButton = menu.findViewById<Button>(R.id.btnGroqApiKey)

            fun getRect(view: View?): Rect? {
                if (view == null || view.visibility != View.VISIBLE) return null
                val rect = Rect()
                return if (view.getGlobalVisibleRect(rect)) rect else null
            }

            fun contains(rect: Rect?, slopPx: Int): Boolean {
                if (rect == null) return false
                return x >= rect.left - slopPx &&
                        x <= rect.right + slopPx &&
                        y >= rect.top - slopPx &&
                        y <= rect.bottom + slopPx
            }

            val menuSlop = (2f * uiScale).roundToInt()
            val sliderSlop = (1f * uiScale).roundToInt()
            val buttonSlop = (3f * uiScale).roundToInt()

            if (contains(getRect(menu), menuSlop)) {
                // Check if click is on volume seekbar
                val volumeRect = getRect(volumeSeekBar)
                if (volumeSeekBar != null && contains(volumeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - volumeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, volumeSeekBar.width.toFloat()) /
                                    volumeSeekBar.width
                    val newProgress = (percentage * volumeSeekBar.max).toInt()

                    // Update volume
                    volumeSeekBar.progress = newProgress
                    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
                        setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                newProgress,
                                AudioManager.FLAG_SHOW_UI
                        )
                    }

                    // **Play system sound for feedback**
                    playSystemSound(context)

                    // Visual feedback
                    volumeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ volumeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on brightness seekbar
                val brightnessRect = getRect(brightnessSeekBar)
                if (brightnessSeekBar != null && contains(brightnessRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - brightnessRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, brightnessSeekBar.width.toFloat()) /
                                    brightnessSeekBar.width
                    val newProgress = (percentage * brightnessSeekBar.max).toInt()

                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_OUTDOOR_BRIGHTNESS_ACTIVE, false)
                            .apply()
                    updateOutdoorBrightnessButtonLabel(outdoorBrightnessButton)
                    applySettingsBrightness(newProgress, brightnessSeekBar)

                    // Visual feedback
                    brightnessSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ brightnessSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on force dark toggle button
                val forceDarkRect = getRect(forceDarkButton)
                if (forceDarkButton != null && contains(forceDarkRect, buttonSlop)) {
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val currentlyEnabled = prefs.getBoolean("forceDarkWebEnabled", true)
                    val newEnabled = !currentlyEnabled
                    (context as? MainActivity)?.setForceDarkWebEnabled(newEnabled)
                    forceDarkButton.text = if (newEnabled) "Force Dark: On" else "Force Dark: Off"

                    forceDarkButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ forceDarkButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on smoothness seekbar
                val smoothnessRect = getRect(smoothnessSeekBar)
                if (smoothnessSeekBar != null && contains(smoothnessRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - smoothnessRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, smoothnessSeekBar.width.toFloat()) /
                                    smoothnessSeekBar.width
                    val newProgress = (percentage * smoothnessSeekBar.max).toInt()

                    // Update smoothness
                    smoothnessSeekBar.progress = newProgress

                    // Save preference and notify MainActivity
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("anchorSmoothness", newProgress)
                            .apply()

                    // Call MainActivity to update smoothness
                    (context as? MainActivity)?.updateAnchorSmoothness(newProgress)

                    // Visual feedback
                    smoothnessSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ smoothnessSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on cursor sensitivity seekbar
                val sensitivitySeekBar = menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                val sensitivityRect = getRect(sensitivitySeekBar)

                if (sensitivitySeekBar != null && contains(sensitivityRect, sliderSlop)) {
                    // Calculate relative position on seekbar
                    val relativeX = (x - sensitivityRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, sensitivitySeekBar.width.toFloat()) /
                                    sensitivitySeekBar.width
                    val newProgress = (percentage * sensitivitySeekBar.max).toInt()

                    // Update sensitivity and save preference
                    sensitivitySeekBar.progress = newProgress
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("cursorSensitivity", newProgress)
                            .apply()

                    // Notify MainActivity
                    (context as? MainActivity)?.updateCursorSensitivity(newProgress)

                    // Visual feedback
                    sensitivitySeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ sensitivitySeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset sensitivity button
                val resetSensitivityButton =
                        menu.findViewById<Button>(R.id.btnResetCursorSensitivity)
                val resetSensitivityRect = getRect(resetSensitivityButton)

                if (resetSensitivityButton != null && contains(resetSensitivityRect, buttonSlop)) {
                    // Reset to 50%
                    // val sensitivitySeekBar =
                    //        menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                    sensitivitySeekBar?.progress = 50

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("cursorSensitivity", 50)
                            .apply()

                    (context as? MainActivity)?.updateCursorSensitivity(50)

                    // Visual feedback
                    resetSensitivityButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetSensitivityButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on screen size seekbar
                val screenSizeRect = getRect(screenSizeSeekBar)
                if (screenSizeSeekBar != null && contains(screenSizeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - screenSizeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, screenSizeSeekBar.width.toFloat()) /
                                    screenSizeSeekBar.width
                    var newProgress = (percentage * screenSizeSeekBar.max).toInt()

                    // Snap to 100% when close (>= 95%)
                    if (newProgress >= 95) {
                        newProgress = 100
                    }

                    // Update screen size
                    screenSizeSeekBar.progress = newProgress

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiScaleProgress", newProgress)
                            .apply()

                    // Apply scale: 35% (0.35) to 100% (1.0)
                    val scale = 0.35f + (newProgress / 100f) * 0.65f
                    updateUiScale(scale)

                    // Update visibility of position sliders
                    val showPosSliders = !isAnchored && scale < 0.99f
                    val posLayout = menu.findViewById<View>(R.id.settingsPositionLayout)
                    val newVisibility = if (showPosSliders) View.VISIBLE else View.GONE

                    if (posLayout?.visibility != newVisibility) {
                        posLayout?.visibility = newVisibility

                        // Force complete remeasure with UNSPECIFIED to allow width changes
                        menu.measure(
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        )
                        menu.layout(
                                menu.left,
                                menu.top,
                                menu.left + menu.measuredWidth,
                                menu.top + menu.measuredHeight
                        )

                        // Invalidate to redraw
                        menu.invalidate()

                        // Also request layout on parent to ensure proper positioning
                        (menu.parent as? View)?.requestLayout()
                    }

                    // Recalculate translation based on new scale
                    updateUiTranslation()

                    // Visual feedback
                    screenSizeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ screenSizeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on Groq API Key button
                val groqKeyRect = getRect(groqKeyButton)
                if (groqKeyButton != null && contains(groqKeyRect, buttonSlop)) {

                    // Visual feedback
                    groqKeyButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        groqKeyButton.isPressed = false
                                        // Show dialog
                                        (context as? MainActivity)?.showGroqKeyDialog()
                                    },
                                    100
                            )
                    return
                }

                // Check if click is on reset screen size button
                val resetScreenSizeRect = getRect(resetScreenSizeButton)
                if (resetScreenSizeButton != null && contains(resetScreenSizeRect, buttonSlop)) {

                    // Reset screen size to 100%
                    screenSizeSeekBar?.progress = 100

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiScaleProgress", 100)
                            .putInt("uiTransXProgress", 50)
                            .putInt("uiTransYProgress", 50)
                            .apply()

                    // Apply full scale
                    updateUiScale(1.0f)

                    // Hide position sliders and remeasure
                    val posLayout = menu.findViewById<View>(R.id.settingsPositionLayout)
                    if (posLayout?.visibility != View.GONE) {
                        posLayout?.visibility = View.GONE

                        // Force complete remeasure with UNSPECIFIED to allow width changes
                        menu.measure(
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        )
                        menu.layout(
                                menu.left,
                                menu.top,
                                menu.left + menu.measuredWidth,
                                menu.top + menu.measuredHeight
                        )

                        // Invalidate to redraw
                        menu.invalidate()

                        // Also request layout on parent to ensure proper positioning
                        (menu.parent as? View)?.requestLayout()
                    }

                    // Reset position to center
                    horizontalPosSeekBar?.progress = 50
                    verticalPosSeekBar?.progress = 50
                    updateUiTranslation()

                    // Visual feedback
                    resetScreenSizeButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetScreenSizeButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on horizontal pos seekbar
                val horizontalPosRect = getRect(horizontalPosSeekBar)
                if (horizontalPosSeekBar != null &&
                                horizontalPosSeekBar.visibility == View.VISIBLE &&
                                contains(horizontalPosRect, sliderSlop)
                ) {

                    val relativeX = (x - horizontalPosRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, horizontalPosSeekBar.width.toFloat()) /
                                    horizontalPosSeekBar.width
                    val newProgress = (percentage * horizontalPosSeekBar.max).toInt()

                    horizontalPosSeekBar.progress = newProgress

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransXProgress", newProgress)
                            .apply()

                    updateUiTranslation()

                    horizontalPosSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ horizontalPosSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on vertical pos seekbar
                val verticalPosRect = getRect(verticalPosSeekBar)
                if (verticalPosSeekBar != null &&
                                verticalPosSeekBar.visibility == View.VISIBLE &&
                                contains(verticalPosRect, sliderSlop)
                ) {

                    val relativeX = (x - verticalPosRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, verticalPosSeekBar.width.toFloat()) /
                                    verticalPosSeekBar.width
                    val newProgress = (percentage * verticalPosSeekBar.max).toInt()

                    verticalPosSeekBar.progress = newProgress

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransYProgress", newProgress)
                            .apply()

                    updateUiTranslation()

                    verticalPosSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ verticalPosSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset button
                val resetRect = getRect(resetButton)
                if (resetButton != null &&
                                resetButton.visibility == View.VISIBLE &&
                                contains(resetRect, buttonSlop)
                ) {

                    // Reset position progress to 50 (center)
                    horizontalPosSeekBar?.progress = 50
                    verticalPosSeekBar?.progress = 50

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransXProgress", 50)
                            .putInt("uiTransYProgress", 50)
                            .apply()

                    updateUiTranslation()

                    // Visual feedback
                    resetButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on help button
                val helpRect = getRect(helpButton)
                if (helpButton != null && contains(helpRect, buttonSlop)) {

                    // Visual feedback
                    helpButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        helpButton.isPressed = false
                                        // Show help dialog
                                        showHelpDialog()
                                    },
                                    100
                            )
                    return
                }

                // Check if click is on Reset Zoom button
                val resetZoomButton = menu.findViewById<Button>(R.id.btnResetFontSize)
                val resetZoomRect = getRect(resetZoomButton)

                if (resetZoomButton != null && contains(resetZoomRect, buttonSlop)) {

                    // Reset font size to 100% (progress 50)
                    fontSizeSeekBar?.progress = 50

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("webFontSize", 50)
                            .apply()

                    // Apply to WebView
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val savedTextColor = getEffectiveWebTextColor(prefs)
                    applyWebFontSettings(50, savedTextColor)

                    // Visual feedback
                    resetZoomButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetZoomButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on Reset Webpage Zoom button
                val resetWebpageZoomButton = menu.findViewById<Button>(R.id.btnResetWebpageZoom)
                val resetWebpageZoomRect = getRect(resetWebpageZoomButton)

                if (resetWebpageZoomButton != null && contains(resetWebpageZoomRect, buttonSlop)) {

                    // Reset webpage zoom to 1.0
                    currentWebZoom = 1.0f

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putFloat("webZoomLevel", currentWebZoom)
                            .apply()

                    webView.evaluateJavascript(
                            """
                        (function() {
                            document.body.style.zoom = "$currentWebZoom";
                        })();
                    """,
                            null
                    )

                    postDelayed(
                            {
                                updateScrollBarsVisibility()
                                lastScrollBarCheckTime = System.currentTimeMillis()
                            },
                            100
                    )

                    // Visual feedback
                    resetWebpageZoomButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetWebpageZoomButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on font size seekbar
                val fontSizeRect = getRect(fontSizeSeekBar)
                if (fontSizeSeekBar != null && contains(fontSizeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - fontSizeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, fontSizeSeekBar.width.toFloat()) /
                                    fontSizeSeekBar.width
                    val newProgress = (percentage * fontSizeSeekBar.max).toInt()

                    // Update font size
                    fontSizeSeekBar.progress = newProgress

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("webFontSize", newProgress)
                            .apply()

                    // Apply to WebView
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val savedTextColor = getEffectiveWebTextColor(prefs)
                    applyWebFontSettings(newProgress, savedTextColor)

                    // Visual feedback
                    fontSizeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ fontSizeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on color wheel
                val colorWheelRect = getRect(colorWheelView)
                if (colorWheelView != null && contains(colorWheelRect, sliderSlop)) {

                    // Calculate relative position
                    val relativeX = (x - colorWheelRect!!.left) / uiScale
                    val relativeY = (y - colorWheelRect.top) / uiScale

                    val selectedColor =
                            colorWheelView.calculateColorFromCoordinates(relativeX, relativeY)

                    // Update visual indicator
                    colorWheelView.setColor(selectedColor)

                    // Apply color
                    val hexColor = String.format("#%06X", (0xFFFFFF and selectedColor))
                    applyTextColor(hexColor)

                    // Visual feedback
                    colorWheelView.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ colorWheelView.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset text color button
                val resetTextColorRect = getRect(resetTextColorButton)
                if (resetTextColorButton != null && contains(resetTextColorRect, buttonSlop)) {

                    // Reset color to white
                    colorWheelView?.setColor(Color.WHITE)
                    // Reset color to white visually
                    colorWheelView?.setColor(Color.WHITE)
                    applyTextColor(null)

                    // Visual feedback
                    resetTextColorButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetTextColorButton.isPressed = false }, 100)
                    return
                }

                val outdoorBrightnessRect = getRect(outdoorBrightnessButton)
                if (outdoorBrightnessButton != null && contains(outdoorBrightnessRect, buttonSlop)) {
                    toggleOutdoorBrightness(brightnessSeekBar, outdoorBrightnessButton)
                    outdoorBrightnessButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ outdoorBrightnessButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on close button
                val closeRect = getRect(closeButton)
                if (closeButton != null && contains(closeRect, buttonSlop)) {

                    // Visual feedback
                    closeButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        closeButton.isPressed = false
                                        // Close settings
                                        isSettingsVisible = false
                                        settingsMenu?.visibility = View.GONE
                                        settingsScrim?.visibility = View.GONE
                                        startRefreshing()
                                    },
                                    100
                            )
                    return
                }
            } else {
                return
            }
        }
    }

    fun playSystemSound(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK) // Play a standard click sound
    }

    /**
     * Apply font size and text color settings to the WebView via JavaScript injection.
     * @param fontSizeProgress Slider progress (0-150) which maps to 50%-200% font size
     * @param textColor Optional hex color string (e.g., "#FFFFFF")
     */
    private fun applyWebFontSettings(fontSizeProgress: Int, textColor: String?) {
        // Map progress 0-150 to font size 50%-200%
        val fontSizePercent = 50 + fontSizeProgress

        val colorCss =
                if (textColor != null) {
                    "body, body *, p, span, div, h1, h2, h3, h4, h5, h6, a, li, td, th { color: $textColor !important; }"
                } else {
                    ""
                }

        val js =
                """
            (function() {
                var styleId = 'taplink-font-settings';
                var existingStyle = document.getElementById(styleId);
                if (existingStyle) {
                    existingStyle.remove();
                }
                var style = document.createElement('style');
                style.id = styleId;
                style.textContent = 'html { font-size: ${fontSizePercent}% !important; } $colorCss';
                document.head.appendChild(style);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)

        // Update system info bar color
        if (textColor != null) {
            try {
                leftSystemInfoView.setTextColor(Color.parseColor(textColor))
            } catch (e: Exception) {
                Log.e("DualWebViewGroup", "Error updating system info color", e)
            }
        }
    }

    fun getAllWebViews(): List<WebView> {
        return windows.map { it.webView }
    }

    private fun getEffectiveWebTextColor(prefs: android.content.SharedPreferences): String? {
        val overrideEnabled = prefs.getBoolean("webTextColorOverrideEnabled", false)
        return if (overrideEnabled) prefs.getString("webTextColor", null) else null
    }

    /**
     * Apply text color to webpage AND custom UI, then save preference.
     * @param colorHex Hex color string (e.g., "#FFFFFF")
     */
    private fun applyTextColor(colorHex: String?) {
        // Save preference
        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("webTextColor", colorHex)
                .putBoolean("webTextColorOverrideEnabled", colorHex != null)
                .apply()

        // Update Custom UI (Settings & Keyboard)
        updateCustomUiColor(colorHex)

        // Get current font size and apply both settings to WebView
        val fontSizeProgress =
                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .getInt("webFontSize", 50)
        applyWebFontSettings(fontSizeProgress, colorHex)
    }

    /** Update the color of Custom UI elements (Settings Menu and Keyboard) */
    private fun updateCustomUiColor(colorHex: String?) {
        val color =
                if (colorHex != null) {
                    try {
                        Color.parseColor(colorHex)
                    } catch (e: Exception) {
                        Color.WHITE
                    }
                } else {
                    Color.WHITE
                }

        // 1. Update Keyboard
        customKeyboard?.setCustomTextColor(color)

        // 2. Update Settings Menu (Recursively find TextViews/Buttons)
        settingsMenu?.let { menu -> updateViewColorsRecursively(menu, color) }

        // 3. Update Navigation Bar
        if (::leftNavigationBar.isInitialized) {
            updateViewColorsRecursively(leftNavigationBar, color)
        }

        // 4. Update Toggle Bar
        if (::leftToggleBar.isInitialized) {
            updateViewColorsRecursively(leftToggleBar, color)
        }
    }

    private fun updateViewColorsRecursively(view: View, color: Int) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                updateViewColorsRecursively(view.getChildAt(i), color)
            }
        } else if (view is TextView) {
            // Apply to TextViews and Buttons (Button is subclass of TextView)
            // But verify it's not one of our special icon views if they shouldn't change
            // (FontIconView IS a TextView, so it will get colored too, which is likely desired)
            view.setTextColor(color)
        }
    }

    /** Re-apply saved font settings to the WebView. Called when a new page loads. */
    fun reapplyWebFontSettings() {
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val fontSizeProgress = prefs.getInt("webFontSize", 50)
        val textColor = getEffectiveWebTextColor(prefs)

        applyWebFontSettings(fontSizeProgress, textColor)

        // Also ensure UI is synced (though this is mostly for initial load)
        updateCustomUiColor(textColor)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sharedConfigPrefs.registerOnSharedPreferenceChangeListener(sharedConfigListener)
        sharedConfigListenerRegistered = true
        updateSystemInfoBarVisibility()
        startRefreshing()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (sharedConfigListenerRegistered) {
            sharedConfigPrefs.unregisterOnSharedPreferenceChangeListener(sharedConfigListener)
            sharedConfigListenerRegistered = false
        }
        stopRefreshing()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            startRefreshing()
        } else {
            stopRefreshing()
        }
    }

    fun isInScrollMode(): Boolean {
        return isInScrollMode
    }

    fun setScrollMode(enabled: Boolean) {
        DebugLog.d(
                "NavBarDebug",
                "setScrollMode: enabled=$enabled, current=$isInScrollMode, navHidden=$isNavBarsHidden"
        )

        if (isInScrollMode == enabled) return
        isInScrollMode = enabled

        if (enabled) {

            leftToggleBar.isClickable = false
            leftNavigationBar.isClickable = false
            updateSystemInfoBarVisibility()

            // Then animate menus away
            leftToggleBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftToggleBar.visibility = View.GONE }
                    .start()

            leftNavigationBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftNavigationBar.visibility = View.GONE }
                    .start()

            // Eye/force-show button intentionally suppressed (user 2026-05-30):
            // user found the bottom-right eyeball overlay distracting in
            // fullscreen / scroll-mode. Navbars can still be restored via the
            // double-tap right-arm gesture and the empty-HUD toggle.
            btnShowNavBars.visibility = View.GONE
        } else {
            // Only restore UI if the other mode (isNavBarsHidden) is NOT active
            if (!isNavBarsHidden) {
                // Re-enable touch interception and show system info bar
                leftToggleBar.isClickable = true
                leftNavigationBar.isClickable = true
                updateSystemInfoBarVisibility()

                // Then show menus with animation
                leftToggleBar.visibility = View.VISIBLE
                leftToggleBar.alpha = 0f
                leftToggleBar.animate().alpha(1f).setDuration(200).start()

                leftNavigationBar.visibility = View.VISIBLE
                leftNavigationBar.alpha = 0f
                leftNavigationBar.animate().alpha(1f).setDuration(200).start()

                // Hide force-show button
                btnShowNavBars
                        .animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction { btnShowNavBars.visibility = View.GONE }
                        .start()
            }
        }

        // Update scrollbars and layout
        updateScrollBarsVisibility(force = true)

        // Force layout update
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    /**
     * Hides or shows the navigation bars without affecting scroll mode. When hidden, cursor remains
     * visible and movable (unlike scroll mode).
     */
    fun setNavBarsHidden(hidden: Boolean) {
        if (isNavBarsHidden == hidden) return
        isNavBarsHidden = hidden

        if (hidden) {
            // Immediately disable touch interception before animating
            leftToggleBar.isClickable = false
            leftNavigationBar.isClickable = false
            updateSystemInfoBarVisibility()

            // Then animate menus away
            leftToggleBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftToggleBar.visibility = View.GONE }
                    .start()

            leftNavigationBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftNavigationBar.visibility = View.GONE }
                    .start()

            // Eye/force-show button intentionally suppressed (user 2026-05-30):
            // gestures (double-tap right arm + empty-HUD tap) restore the
            // navbars, so the bottom-right eyeball overlay is unnecessary.
            btnShowNavBars.visibility = View.GONE
        } else {
            // Only restore UI if the other mode (isInScrollMode) is NOT active
            if (!isInScrollMode) {
                // Re-enable touch interception and show system info bar
                leftToggleBar.isClickable = true
                leftNavigationBar.isClickable = true
                updateSystemInfoBarVisibility()

                // Then show menus with animation
                leftToggleBar.visibility = View.VISIBLE
                leftToggleBar.alpha = 0f
                leftToggleBar.animate().alpha(1f).setDuration(200).start()

                leftNavigationBar.visibility = View.VISIBLE
                leftNavigationBar.alpha = 0f
                leftNavigationBar.animate().alpha(1f).setDuration(200).start()

                // Hide force-show button
                btnShowNavBars
                        .animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction { btnShowNavBars.visibility = View.GONE }
                        .start()
            }
        }

        // Update scrollbars and layout. Force this so leaving full-screen
        // browser mode immediately restores the bars even if media playback
        // recently froze automatic scrollbar visibility churn.
        updateScrollBarsVisibility(force = true)

        // Force layout update
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    fun isNavBarsHidden(): Boolean {
        return isNavBarsHidden
    }

    // Custom Dialog Logic
    fun showAlertDialog(message: String, onConfirm: () -> Unit) {
        showDialog("Alert", message, false, null, { _ -> onConfirm() }, null)
    }

    fun showConfirmDialog(message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
        showDialog("Confirm", message, false, null, { _ -> onConfirm() }, onCancel)
    }

    fun showPromptDialog(
            message: String,
            defaultValue: String?,
            onConfirm: (String) -> Unit,
            onCancel: () -> Unit
    ) {
        showDialog(
                "Prompt",
                message,
                true,
                defaultValue,
                { text -> onConfirm(text ?: "") },
                onCancel
        )
    }

    fun showHelpDialog(page: Int = 1) {
        val (title, message, hasNext, hasPrev) =
                when (page) {
                    1 ->
                            Quadruple(
                                    "Features: Touch & Menu",
                                    """
                TOUCH GESTURES:
                • Single Tap: Click links, buttons, and focus fields.
                • Double Tap: Go back to the previous page.
                
                TRIPLE TAP (Anchored Mode):
                • Re-centers the screen.
                """.trimIndent(),
                                    true,
                                    false
                            )
                    2 ->
                            Quadruple(
                                    "Features: Screen Modes",
                                    """
                ANCHORED MODE (Anchor Icon):
                • Screen stays fixed in space relative to the world.
                • Smoothness: Controls how rigidly the screen follows tracking.
                
                NON-ANCHORED MODE (Crossed Anchor):
                • Screen is "locked" to your head movement.
                • Screen Position: Shift the display H/V when UI Scale < 100%.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    3 ->
                            Quadruple(
                                    "Features: Display & Tools",
                                    """
                SCROLL MODE (Full Screen Icon):
                • Hides UI for an immersive browsing experience.
                • Restore UI: Tap the transparent "Show" button.
                
                UTILITIES:
                • Volume & Brightness Sliders.
                • Force Dark: Toggle dark rendering for supported webpages.
                • UI Scale: Adjust the global interface size.
                • Web Zoom (+/-): Content zoom level.
                • QR Scanner: Open Dashboard (glasses icon) and tap QR Scanner.

                VOICE / STT:
                • Uses device speech-to-text when supported.
                • Start STT, speak clearly, and text is inserted into the active field.
                • Use scrcpy keyboard to paste your API key into the prompt field.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    4 ->
                            Quadruple(
                                    "Features: Blank Screen Mode",
                                    """
                BLANK SCREEN MODE (Eye Toggle):
                • Blacks out display while media continues playing but allows media controls.
                • Perfect for listening to audio/podcasts.
                • Note: Disables anchored mode while active.
                
                MEDIA CONTROLS (shown when media is playing):
                • Play/Pause: Toggle media playback.
                • Skip Back/Forward: Jump 10 seconds.
                • Unmask (Eye Icon): Exit blank screen mode.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    5 ->
                            Quadruple(
                                    "TapLink AI",
                                    """
                TAPLINK AI (Chat Icon):
                • Open/close with the Chat button on the bottom bar.
                • Requires a Groq API Key (Settings -> Enter Groq API Key).
                • Ask questions or use Summarize to recap the current webpage.
                • Summarize works only when a normal webpage is open.
                • Speak replies: Toggle in chat to read assistant responses aloud.
                """.trimIndent(),
                                    false,
                                    true
                            )
                    else -> return
                }

        val footerButtons = mutableListOf<View>()

        if (hasPrev) {
            footerButtons.add(
                    Button(context).apply {
                        text = "Back"
                        textSize = 14f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { showHelpDialog(page - 1) }
                    }
            )
        }

        if (hasNext) {
            footerButtons.add(
                    Button(context).apply {
                        text = "Next"
                        textSize = 14f
                        setTextColor(Color.parseColor("#4488FF"))
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { showHelpDialog(page + 1) }
                    }
            )
        }

        showDialog(
                title = title,
                message = message,
                hasInput = false,
                confirmLabel = "Close",
                dismissOnAnyClick = true,
                additionalButtons = footerButtons
        )
    }

    private data class Quadruple<A, B, C, D>(
            val first: A,
            val second: B,
            val third: C,
            val fourth: D
    )

    private fun showDialog(
            title: String,
            message: String,
            hasInput: Boolean,
            defaultValue: String? = null,
            onConfirm: ((String?) -> Unit)? = null,
            onCancel: (() -> Unit)? = null,
            confirmLabel: String? = "OK",
            dismissOnAnyClick: Boolean = false,
            additionalButtons: List<View> = emptyList()
    ) {
        dialogContainer.removeAllViews()

        // Hide keyboard container initially to avoid overlapping, though we might show it again if
        // input is focused
        keyboardContainer.visibility = View.GONE

        val padding = 16.dp()
        val dialogView =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            500, // Fixed width for consistent look
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.CENTER }
                    setPadding(padding, padding, padding, padding)
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#202020"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 16f
                            }
                    elevation = 100f
                    isClickable = true
                    isFocusable = true
                }

        // Title
        val titleView =
                TextView(context).apply {
                    text = title
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { bottomMargin = 16.dp() }
                }
        dialogView.addView(titleView)

        // Message
        val messageView =
                TextView(context).apply {
                    text = message
                    textSize = 16f
                    setTextColor(Color.parseColor("#DDDDDD"))
                    maxLines = 15
                    isVerticalScrollBarEnabled = true
                    movementMethod = ScrollingMovementMethod.getInstance()
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { bottomMargin = 24.dp() }
                }
        dialogView.addView(messageView)

        var inputField: EditText? = null
        if (hasInput) {
            inputField =
                    EditText(context).apply {
                        setText(defaultValue ?: "")
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setPadding(16, 16, 16, 16)
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#303030"))
                                    cornerRadius = 8f
                                }
                        layoutParams =
                                LinearLayout.LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply { bottomMargin = 24.dp() }

                        // Important: Show custom keyboard on focus
                        setOnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) {
                                keyboardListener?.onShowKeyboard()
                            }
                        }

                        // Allow our custom keyboard to input text here
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setSingleLine()
                    }
            dialogView.addView(inputField)
        }

        // Buttons
        val buttonContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    layoutParams =
                            LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                }

        if (onCancel != null) {
            val cancelButton =
                    Button(context).apply {
                        text = "Cancel"
                        textSize = 16f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        background =
                                ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                        setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                        minWidth = 64.dp()
                        minHeight = 48.dp()
                        setOnClickListener {
                            onCancel()
                            hideDialog()
                        }
                    }
            buttonContainer.addView(cancelButton)
        }

        additionalButtons.forEach { button ->
            if (button is Button) {
                button.background =
                        ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            }
            buttonContainer.addView(button)
        }

        if (confirmLabel != null) {
            val confirmButton =
                    Button(context).apply {
                        text = confirmLabel
                        textSize = 16f
                        setTextColor(Color.parseColor("#4488FF"))
                        background =
                                ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                        setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                        minWidth = 64.dp()
                        minHeight = 48.dp()
                        setOnClickListener {
                            onConfirm?.invoke(inputField?.text?.toString())
                            hideDialog()
                        }
                    }
            buttonContainer.addView(confirmButton)
        }

        dialogView.addView(buttonContainer)
        dialogContainer.addView(dialogView)
        dialogContainer.visibility = View.VISIBLE
        dialogContainer.bringToFront()
        if (dismissOnAnyClick) {
            dialogContainer.setOnClickListener { hideDialog() }
            // DON'T set listener on dialogView, so clicks inside don't dismiss
        }

        // Ensure rendering updates
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    fun hideDialog() {
        dialogContainer.visibility = View.GONE
        dialogContainer.removeAllViews()
        // Determine whether to show keyboard container again
        if (customKeyboard?.visibility == View.VISIBLE) {
            keyboardContainer.visibility = View.VISIBLE
        }

        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    private var toastHandler: Handler? = Handler(Looper.getMainLooper())
    private var toastRunnable: Runnable? = null

    /**
     * Shows a toast message that renders in both eyes.
     * @param message The message to display
     * @param durationMs How long to show the toast (default 2000ms)
     */
    fun showToast(message: String, durationMs: Long = 2000L) {
        // DebugLog.d("Toast", "showToast called with message: $message")
        // Ensure we're on the UI thread
        post {
            // DebugLog.d("Toast", "Inside post block, creating toast view")
            // Cancel any existing toast
            toastRunnable?.let { toastHandler?.removeCallbacks(it) }
            dialogContainer.removeAllViews()

            val padding = 16.dp()
            val toastView =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                                FrameLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply {
                                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                                            bottomMargin = 64.dp()
                                        }
                        setPadding(padding * 2, padding, padding * 2, padding)
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#E0303030")) // Semi-transparent dark
                                    cornerRadius = 24f
                                }
                        elevation = 100f
                    }

            val messageView =
                    TextView(context).apply {
                        text = message
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }
            toastView.addView(messageView)

            // Use a transparent scrim for toast (unlike dialogs which block interaction)
            dialogContainer.setBackgroundColor(Color.TRANSPARENT)
            dialogContainer.addView(toastView)
            dialogContainer.visibility = View.VISIBLE
            dialogContainer.bringToFront()
            dialogContainer.isClickable = false // Allow clicks to pass through

            // DebugLog.d("Toast", "Toast view added, dialogContainer visible:
            // ${dialogContainer.visibility == View.VISIBLE}, child count:
            // ${dialogContainer.childCount}")

            // Ensure rendering updates
            requestLayout()
            invalidate()
            startRefreshing()

            // Auto-dismiss after duration
            toastRunnable = Runnable { hideToast() }
            toastHandler?.postDelayed(toastRunnable!!, durationMs)
        }
    }

    private fun hideToast() {
        dialogContainer.visibility = View.GONE
        dialogContainer.removeAllViews()
        // Restore dialog container background for future dialogs
        dialogContainer.setBackgroundColor(Color.parseColor("#CC000000"))
        dialogContainer.isClickable = true

        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    // Helper method to get the current dialog input if any
    fun getDialogInput(): EditText? {
        if (dialogContainer.visibility != View.VISIBLE) return null
        val dialogView = dialogContainer.getChildAt(0) as? ViewGroup ?: return null
        // Scan for EditText
        for (i in 0 until dialogView.childCount) {
            val child = dialogView.getChildAt(i)
            if (child is EditText) return child
        }
        return null
    }

    fun isDialogAction(x: Float, y: Float): Boolean {
        if (dialogContainer.visibility != View.VISIBLE || !dialogContainer.isClickable) return false
        val loc = IntArray(2)
        dialogContainer.getLocationOnScreen(loc)
        return x >= loc[0] &&
                x <= loc[0] + (dialogContainer.width * uiScale) &&
                y >= loc[1] &&
                y <= loc[1] + (dialogContainer.height * uiScale)
    }

    private fun setupMaskOverlayUI() {

        maskNowPlayingText =
                TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    // CRITICAL: must be INVISIBLE not GONE during initial
                    // layout. FrameLayout (the parent maskOverlay) skips
                    // measuring children whose visibility is GONE, so a
                    // child that starts GONE gets w=0 h=0 forever even
                    // after we flip it to VISIBLE — because the
                    // visibility change requests a layout but
                    // measureChildWithMargins still hasn't been run for
                    // it in the current layout pass. INVISIBLE keeps the
                    // child in the measure path while making it not draw,
                    // so when we later flip to VISIBLE the dimensions
                    // are already correct and the text shows up
                    // immediately. (This was the entire root cause of
                    // "metadata never appears in dim mode" — the log
                    // showed vis=0 alpha=0.55 text='...' but w=0 h=0.)
                    visibility = View.INVISIBLE
                    alpha = 0.55f
                    includeFontPadding = false
                }
        val nowPlayingParams =
                FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                        .apply {
                            // Bottom-center placement, but pulled up
                            // ~120 px from the framebuffer edge: AR
                            // glasses crop the corners of the
                            // rectangular framebuffer to the lens FOV,
                            // so anything within ~60-80 px of the very
                            // bottom can land in the cropped zone and
                            // be invisible to the wearer. 120 px sits
                            // comfortably inside the visible oval.
                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            leftMargin = 56
                            rightMargin = 56
                            bottomMargin = 120
                        }
        maskOverlay.addView(maskNowPlayingText, nowPlayingParams)

        maskCaptionText =
                TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    gravity = Gravity.CENTER
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    visibility = View.INVISIBLE
                    alpha = 0.88f
                    includeFontPadding = false
                    setShadowLayer(5f, 0f, 2f, Color.BLACK)
                }
        val captionParams =
                FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                        .apply {
                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            leftMargin = 54
                            rightMargin = 54
                            bottomMargin = 172
                        }
        maskOverlay.addView(maskCaptionText, captionParams)

        maskSpotifyInfoContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    alpha = 0.62f
                    visibility = View.INVISIBLE
                    setPadding(44, 0, 44, 0)
                }
        val spotifyParams =
                FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                        .apply {
                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            leftMargin = 42
                            rightMargin = 42
                            bottomMargin = 96
                        }
        maskOverlay.addView(maskSpotifyInfoContainer, spotifyParams)

        maskSpotifyTitleText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        maskSpotifyInfoContainer.addView(
            maskSpotifyTitleText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        maskSpotifyArtistText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            alpha = 0.82f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        maskSpotifyInfoContainer.addView(
            maskSpotifyArtistText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 5 }
        )

        maskSpotifyAlbumText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11.5f
            alpha = 0.62f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        maskSpotifyInfoContainer.addView(
            maskSpotifyAlbumText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
        )

        maskSpotifyProgressTrack = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2f
                setColor(0x35FFFFFF)
            }
        }
        maskSpotifyInfoContainer.addView(
            maskSpotifyProgressTrack,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                4
            ).apply {
                leftMargin = 46
                rightMargin = 46
                topMargin = 12
            }
        )
        maskSpotifyProgressFill = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2f
                setColor(0xFF1DB954.toInt())
            }
        }
        maskSpotifyProgressTrack.addView(
            maskSpotifyProgressFill,
            FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        maskSpotifyLyricsText = TextView(context).apply {
            // Outdoor AR readability depends on luminance contrast more than
            // brand colour. Keep active lyrics bright white with a black
            // shadow so they survive sunlight and busy video backgrounds.
            setTextColor(Color.WHITE)
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            textSize = 11f
            alpha = 0.90f
            gravity = Gravity.CENTER
            maxLines = 2
            // includeFontPadding stays at default (true): the karaoke render
            // dynamically grows the text to 18sp/2 lines, and disabling font
            // padding was clipping the descenders ('y', 'p', 'g') in dim mode.
            includeFontPadding = true
            // Reserve enough vertical room for the karaoke render up-front
            // (~2 lines at 18sp + line spacing) so the wrap_content parent
            // doesn't cache a small 11sp height and clip the larger text.
            minHeight = 64
            setPadding(paddingLeft, 6, paddingRight, 10)
            text = "Lyrics"
        }
        maskSpotifyInfoContainer.addView(
            maskSpotifyLyricsText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 9 }
        )

        // Time text (top-center) — minimal HH:MM clock.
        maskTimeText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            alpha = 0.55f
            includeFontPadding = false
            text = "" // populated by updateMaskClockAndBattery on show
        }
        val timeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 18
        }
        maskOverlay.addView(maskTimeText, timeParams)

        // Battery text (top-right) — "78%" or "78% ⚡" when charging.
        maskBatteryText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            alpha = 0.55f
            includeFontPadding = false
            text = ""
        }
        val batteryParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 18
            rightMargin = 18
        }
        maskOverlay.addView(maskBatteryText, batteryParams)

        // ── Removed dim-mode chrome ─────────────────────────────────
        // The X (btnMaskUnmask), the 6-button media toolbar, and every
        // music visualizer were intentionally removed in the dim-mode
        // revamp. The only interactions in dim mode are the activity-level
        // gestures: single-tap = play/pause and double-tap = exit dim mode.
        // The button fields below are still initialized for legacy code paths;
        // they're simply never added to the maskOverlay view hierarchy.
        btnMaskUnmask =
                ImageButton(context).apply {
                    setImageResource(R.drawable.ic_visibility_on)
                    setBackgroundColor(Color.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(8, 8, 8, 8)
                    alpha = 0.5f
                    setOnClickListener { unmaskScreen() }
                    visibility = View.GONE
                }
        // (intentionally NOT added to maskOverlay — removed UI element)

        // Media Controls Container (Bottom Center)
        maskMediaControlsContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.TRANSPARENT)
                    alpha = 0.5f
                    visibility = View.GONE // Hidden by default until media detected
                }
        // Media-controls container is built for legacy code paths but is
        // intentionally NOT added to the maskOverlay view hierarchy — the
        // dim-mode revamp removed the on-screen toolbar.
        // (no maskOverlay.addView for maskMediaControlsContainer)

        // Controls - Order: Prev Track, 10s Back, Play, Pause, 10s Forward, Next Track
        btnMaskPrevTrack =
                createMediaButton(R.string.fa_backward_step) {
                    // Try to click previous track button (works on YouTube, Spotify, etc.)
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    // Try common previous track selectors
                    var prevBtn = document.querySelector('.ytp-prev-button') ||
                                  document.querySelector('[aria-label*="previous" i]') ||
                                  document.querySelector('[title*="previous" i]') ||
                                  document.querySelector('button[data-testid="control-button-skip-back"]');
                    if (prevBtn) { prevBtn.click(); return; }
                    // Fallback: Skip to beginning
                    var media = document.querySelector('video, audio');
                    if (media) media.currentTime = 0;
                })();
            """.trimIndent(),
                            "(function(){ if(window.handlePrev){ window.handlePrev(); } else if(window.prevStation){ window.prevStation(); } })();"
                    )
                    scheduleTrackChangeRefresh()
                }
        btnMaskPrev =
                createMediaButton(R.string.fa_backward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime -= 10;",
                            "(function(){ if(window.handlePrev){ window.handlePrev(); } else if(window.prevStation){ window.prevStation(); } })();"
                    )
                }
        btnMaskPlay =
                createMediaButton(R.string.fa_play) {
                    playMedia()
                    // Immediately update button visibility for responsive UI
                    lastMediaInteractionTime = SystemClock.uptimeMillis()
                    btnMaskPlay.visibility = View.GONE
                    btnMaskPause.visibility = View.VISIBLE
                    maskMediaControlsContainer.requestLayout()
                }
        btnMaskPause =
                createMediaButton(R.string.fa_pause) {
                    pauseMedia()
                    // Immediately update button visibility for responsive UI
                    lastMediaInteractionTime = SystemClock.uptimeMillis()
                    btnMaskPause.visibility = View.GONE
                    btnMaskPlay.visibility = View.VISIBLE
                    maskMediaControlsContainer.requestLayout()
                }
        btnMaskNext =
                createMediaButton(R.string.fa_forward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime += 10;",
                            "(function(){ if(window.handleNext){ window.handleNext(); } else if(window.nextStation){ window.nextStation(); } })();"
                    )
                }
        btnMaskNextTrack =
                createMediaButton(R.string.fa_forward_step) {
                    // Advance to the next track. Programmatic .click() on
                    // .ytp-next-button often leaves YouTube's SPA chrome
                    // (title, description) stale, so we prefer navigating
                    // directly to the Up Next URL, which forces a clean
                    // yt-navigate cycle.
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    var beforeUrl = window.location.href;
                    var forceReloadIfUrlAdvanced = function(delayMs) {
                        // YouTube's SPA sometimes swaps the video stream via
                        // pushState but leaves the title/description/metadata
                        // chrome stale. If the URL has advanced (new ?v= or
                        // list index) but YouTube hasn't fully re-rendered,
                        // force a real navigation to the new URL so every
                        // piece of metadata refreshes in lockstep.
                        setTimeout(function() {
                            try {
                                var now = window.location.href;
                                if (now !== beforeUrl) {
                                    window.location.replace(now);
                                }
                            } catch(e) {}
                        }, delayMs);
                    };

                    // ── Strategy 1: Direct SPA navigation to Up Next video ──
                    // Covers autoplay rail, playlist panels, and YouTube Mix
                    // side panels. Navigating via window.location guarantees
                    // the page chrome re-renders.
                    var upLink =
                        // Autoplay "Up Next" block (single video, autoplay on)
                        document.querySelector('ytd-compact-autoplay-renderer a#thumbnail[href*="/watch"]') ||
                        // Playlist/Mix panel: track immediately after the
                        // currently-selected one (sibling-next selector).
                        document.querySelector('ytd-playlist-panel-video-renderer[selected] + ytd-playlist-panel-video-renderer a[href*="/watch"]') ||
                        document.querySelector('ytd-playlist-panel-video-renderer[selected] ~ ytd-playlist-panel-video-renderer a[href*="/watch"]') ||
                        // Related rail (single-video without autoplay on)
                        document.querySelector('#related ytd-compact-video-renderer a#thumbnail[href*="/watch"]') ||
                        document.querySelector('ytd-compact-video-renderer a#thumbnail[href*="/watch"]') ||
                        // Mobile layout
                        document.querySelector('ytm-compact-autoplay-renderer a[href*="/watch"]');
                    if (upLink && upLink.href) {
                        window.location.href = upLink.href;
                        return;
                    }

                    // ── Strategy 2: Dispatch a real MouseEvent on .ytp-next-button ──
                    var nextBtn =
                        document.querySelector('.ytp-next-button:not([aria-disabled="true"])') ||
                        document.querySelector('.ytp-next-button') ||
                        document.querySelector('button[aria-label^="Next" i]') ||
                        document.querySelector('button[data-testid="control-button-skip-forward"]');
                    if (nextBtn) {
                        try {
                            nextBtn.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
                        } catch(e) {
                            nextBtn.click();
                        }
                        // Catch the case where YouTube's SPA advances the
                        // URL but leaves the page chrome stale.
                        forceReloadIfUrlAdvanced(1500);
                        return;
                    }

                    // ── Strategy 3: Fire 'ended' so YouTube's autoplay takes over ──
                    var media = document.querySelector('video, audio');
                    if (media && isFinite(media.duration)) {
                        try {
                            media.currentTime = Math.max(0, media.duration - 0.05);
                            media.dispatchEvent(new Event('ended', {bubbles:true}));
                        } catch(e) {}
                        forceReloadIfUrlAdvanced(2500);
                    }
                })();
            """.trimIndent(),
                            "(function(){ if(window.handleNext){ window.handleNext(); } else if(window.nextStation){ window.nextStation(); } })();"
                    )
                    // YouTube SPA navigations take several seconds to update the title.
                    // Schedule aggressive delayed refreshes to catch the new video name.
                    scheduleTrackChangeRefresh()
                }

        btnMaskPause.visibility = View.GONE // Initially show Play

        maskMediaControlsContainer.addView(btnMaskPrevTrack)
        maskMediaControlsContainer.addView(btnMaskPrev)
        maskMediaControlsContainer.addView(btnMaskPlay)
        maskMediaControlsContainer.addView(btnMaskPause)
        maskMediaControlsContainer.addView(btnMaskNext)
        maskMediaControlsContainer.addView(btnMaskNextTrack)

        // Dim mode has NO mouse pointer, so an on-screen media toolbar can't
        // be tapped and is just visual clutter. We intentionally DO NOT add
        // maskMediaControlsContainer to the dim overlay. Media in dim mode is
        // driven by trackpad gestures (right-arm swipe = prev/next,
        // single-tap = play/pause) and the only on-screen element is the
        // maskNowPlayingText label. The container is still built above (and
        // still referenced by updateMediaState/dispatchMaskOverlayTouch) but,
        // being unparented, it never renders or intercepts touches in dim
        // mode. (Earlier Phase 4r re-attached it; removed at the user's
        // request since the pointer is hidden while masked.)
    }


    private fun updatePlaybackWakeLocks() {
        val shouldHoldMaskWakeLock = isScreenMasked
        if (shouldHoldMaskWakeLock) {
            if (!maskWakeLock.isHeld) maskWakeLock.acquire()
        } else if (maskWakeLock.isHeld) {
            maskWakeLock.release()
        }

        // Streaming radio/video needs a steady CPU + Wi-Fi path on these glasses.
        // The device log shows real AudioTrack underruns, so hold playback resources
        // for any active media session, not only when the host Activity is paused.
        val shouldHoldPlaybackWakeLock = isMediaPlaying
        if (shouldHoldPlaybackWakeLock) {
            if (!pausedMediaWakeLock.isHeld) pausedMediaWakeLock.acquire()
            try {
                if (mediaWifiLock?.isHeld == false) mediaWifiLock.acquire()
            } catch (_: Exception) {}
        } else {
            if (pausedMediaWakeLock.isHeld) pausedMediaWakeLock.release()
            try {
                if (mediaWifiLock?.isHeld == true) mediaWifiLock.release()
            } catch (_: Exception) {}
        }
    }



    private fun setupFullScreenControlsUI() {
        // Container for controls (Bottom bar)
        fullScreenControlsContainer =
                FrameLayout(context).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.BOTTOM }
                    // No background - just floating buttons
                    setPadding(0, 0, 0, 0)
                    visibility = View.GONE // Hidden by default
                    isClickable = true // Consume clicks
                }

        // Media Controls Container (Center)
        fullScreenMediaControls =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.WRAP_CONTENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.CENTER }
                }
        fullScreenControlsContainer.addView(fullScreenMediaControls)

        // Exit Button (Right)
        btnFsExit =
                FontIconView(context).apply {
                    setText(R.string.fa_compress)
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    setPadding(16, 16, 16, 16)
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.WRAP_CONTENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                    setOnClickListener {
                        (context as? AppCompatActivity)?.onBackPressedDispatcher?.onBackPressed()
                    }
                }
        fullScreenControlsContainer.addView(btnFsExit)

        // Create Media Buttons (reusing logic from mask controls)
        btnFsPrevTrack =
                createMediaButton(R.string.fa_backward_step) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    var prevBtn = document.querySelector('.ytp-prev-button') ||
                                  document.querySelector('[aria-label*="previous" i]') ||
                                  document.querySelector('[title*="previous" i]') ||
                                  document.querySelector('button[data-testid="control-button-skip-back"]');
                    if (prevBtn) { prevBtn.click(); return; }
                    var media = document.querySelector('video, audio');
                    if (media) media.currentTime = 0;
                })();
                """.trimIndent(),
                            "(function(){ if(window.handlePrev){ window.handlePrev(); } else if(window.prevStation){ window.prevStation(); } })();"
                    )
                }

        btnFsPrev =
                createMediaButton(R.string.fa_backward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime -= 10;",
                            "(function(){ if(window.handlePrev){ window.handlePrev(); } else if(window.prevStation){ window.prevStation(); } })();"
                    )
                }

        // Single Play/Pause toggle button
        btnFsPlayPause =
                createMediaButton(R.string.fa_play) {
                    if (isFsPlaying) {
                        // Currently playing, so pause
                        DebugLog.d("FullscreenTouch", "Pause clicked, switching to play icon")
                        val targetWebView = getMediaControlWebView()
                        evaluateMediaControlCommand(
                                targetWebView,
                                "document.querySelector('video, audio').pause();",
                                "(function(){ if(window.tapRadioNativePausePlayback){ window.tapRadioNativePausePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
                        )
                        btnFsPlayPause.setText(R.string.fa_play)
                        isFsPlaying = false
                    } else {
                        // Currently paused, so play
                        DebugLog.d("FullscreenTouch", "Play clicked, switching to pause icon")
                        val targetWebView = getMediaControlWebView()
                        evaluateMediaControlCommand(
                                targetWebView,
                                "document.querySelector('video, audio').play();",
                                "(function(){ if(window.tapRadioNativeResumePlayback){ window.tapRadioNativeResumePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
                        )
                        btnFsPlayPause.setText(R.string.fa_pause)
                        isFsPlaying = true
                    }
                }

        // Sync initial state with actual media state
        if (isMediaPlaying) {
            btnFsPlayPause.setText(R.string.fa_pause)
            isFsPlaying = true
        }

        btnFsNext =
                createMediaButton(R.string.fa_forward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime += 10;",
                            "(function(){ if(window.handleNext){ window.handleNext(); } else if(window.nextStation){ window.nextStation(); } })();"
                    )
                }

        btnFsNextTrack =
                createMediaButton(R.string.fa_forward_step) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    var beforeUrl = window.location.href;
                    var forceReloadIfUrlAdvanced = function(delayMs) {
                        setTimeout(function() {
                            try {
                                var now = window.location.href;
                                if (now !== beforeUrl) {
                                    window.location.replace(now);
                                }
                            } catch(e) {}
                        }, delayMs);
                    };

                    // ── Strategy 1: Direct SPA navigation to Up Next video ──
                    var upLink =
                        document.querySelector('ytd-compact-autoplay-renderer a#thumbnail[href*="/watch"]') ||
                        document.querySelector('ytd-playlist-panel-video-renderer[selected] + ytd-playlist-panel-video-renderer a[href*="/watch"]') ||
                        document.querySelector('ytd-playlist-panel-video-renderer[selected] ~ ytd-playlist-panel-video-renderer a[href*="/watch"]') ||
                        document.querySelector('#related ytd-compact-video-renderer a#thumbnail[href*="/watch"]') ||
                        document.querySelector('ytd-compact-video-renderer a#thumbnail[href*="/watch"]') ||
                        document.querySelector('ytm-compact-autoplay-renderer a[href*="/watch"]');
                    if (upLink && upLink.href) {
                        window.location.href = upLink.href;
                        return;
                    }

                    // ── Strategy 2: Dispatch a real MouseEvent on .ytp-next-button ──
                    var nextBtn =
                        document.querySelector('.ytp-next-button:not([aria-disabled="true"])') ||
                        document.querySelector('.ytp-next-button') ||
                        document.querySelector('button[aria-label^="Next" i]') ||
                        document.querySelector('button[data-testid="control-button-skip-forward"]');
                    if (nextBtn) {
                        try {
                            nextBtn.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
                        } catch(e) {
                            nextBtn.click();
                        }
                        forceReloadIfUrlAdvanced(1500);
                        return;
                    }

                    // ── Strategy 3: Fire 'ended' so YouTube's autoplay takes over ──
                    var media = document.querySelector('video, audio');
                    if (media && isFinite(media.duration)) {
                        try {
                            media.currentTime = Math.max(0, media.duration - 0.05);
                            media.dispatchEvent(new Event('ended', {bubbles:true}));
                        } catch(e) {}
                        forceReloadIfUrlAdvanced(2500);
                    }
                })();
                """.trimIndent(),
                            "(function(){ if(window.handleNext){ window.handleNext(); } else if(window.nextStation){ window.nextStation(); } })();"
                    )
                    // Same aggressive refresh schedule used by the masked-mode
                    // next-track button so the overlay title keeps pace with
                    // the YouTube SPA navigation.
                    scheduleTrackChangeRefresh()
                }

        fullScreenMediaControls.addView(btnFsPrevTrack)
        fullScreenMediaControls.addView(btnFsPrev)
        fullScreenMediaControls.addView(btnFsPlayPause)
        fullScreenMediaControls.addView(btnFsNext)
        fullScreenMediaControls.addView(btnFsNextTrack)
    }

    private fun createMediaButton(iconRes: Int, onClick: () -> Unit): FontIconView {
        return FontIconView(context).apply {
            setText(iconRes)
            setBackgroundResource(R.drawable.nav_button_background)
            setTextColor(Color.WHITE)
            alpha = 0.5f
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            layoutParams =
                    LinearLayout.LayoutParams(40, 40).apply {
                        leftMargin = 4
                        rightMargin = 4
                    }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onClick()
                scheduleMaskedNowPlayingRefresh()
            }
        }
    }

    fun updateMediaState(isPlaying: Boolean) {
        // DebugLog.d("MediaControls", "updateMediaState called: isPlaying=$isPlaying,
        // isScreenMasked=$isScreenMasked")

        // Ignore updates shortly after manual interaction to prevent race conditions
        if (SystemClock.uptimeMillis() - lastMediaInteractionTime < 500) {
            return
        }

        isMediaPlaying = isPlaying
        if (isPlaying) {
            lastMediaPlayingAt = SystemClock.uptimeMillis()
        }
        updatePlaybackWakeLocks()
        // Re-evaluate the refresh-loop interval — when audio starts in
        // dim mode we want to switch to the slower masked+playing rate
        // immediately, not wait for the next dim-mode transition. Without
        // this call, dim mode entered BEFORE playback starts keeps
        // running at 1 Hz, but if audio kicks off later we'd remain
        // there anyway… the bigger win is the symmetric case (audio
        // stops mid-dim-mode → bump back up to 1 Hz so any subsequent
        // exit-mask animation feels responsive).
        updateRefreshRate()
        post {
            if (isPlaying) {
                // DebugLog.d("MediaControls", "Setting to playing state")
                btnMaskPlay.visibility = View.GONE
                btnMaskPause.visibility = View.VISIBLE
                maskMediaControlsContainer.visibility = View.VISIBLE
                // DebugLog.d("MediaControls", "Controls container visibility:
                // ${maskMediaControlsContainer.visibility}, parent:
                // ${maskMediaControlsContainer.parent}")
            } else {
                // DebugLog.d("MediaControls", "Setting to paused state")
                btnMaskPlay.visibility = View.VISIBLE
                btnMaskPause.visibility = View.GONE
                // Keep controls visible if we know media exists
                maskMediaControlsContainer.visibility = View.VISIBLE
                // DebugLog.d("MediaControls", "Controls container visibility:
                // ${maskMediaControlsContainer.visibility}")
            }

            refreshMaskedNowPlaying()

            // Update full screen controls as well
            if (::btnFsPlayPause.isInitialized && !suppressFullscreenMediaControls) {
                if (isPlaying) {
                    btnFsPlayPause.setText(R.string.fa_pause)
                    isFsPlaying = true
                } else {
                    btnFsPlayPause.setText(R.string.fa_play)
                    isFsPlaying = false
                }
            }
        }
    }

    fun isMediaPlaying(): Boolean {
        return isMediaPlaying
    }

    fun setYoutubeCssFullModeActive(active: Boolean) {
        val url = webView.url.orEmpty()
        val isYoutube = url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true)
        youtubeCssFullModeActive = active && isYoutube
        if (youtubeCssFullModeActive) {
            // Belt-and-suspenders: clear any latched "scrollbar visible" state so
            // neither the sticky flag nor the hold timer can carry a stale bar
            // into Full mode (the bar would otherwise sit over the video).
            stickyHorzScrollable = false
            stickyVertScrollable = false
            lastHorzScrollableAt = 0L
            lastVertScrollableAt = 0L
        }
        updateScrollBarsVisibility(force = true)
    }

    fun toggleMediaPlayback() {
        if (isMediaPlaying) {
            pauseMedia()
        } else {
            playMedia()
        }
    }

    private fun isTapRadioWebView(targetWebView: WebView): Boolean {
        val url = targetWebView.url.orEmpty()
        return url.contains("radio.html", ignoreCase = true) ||
            url.contains("podcasts.html", ignoreCase = true)
    }

    private fun anyTrackedMediaPlaying(): Boolean {
        return nativeTapRadioPlaying || mediaStateByWindowId.values.any { it }
    }

    fun setMediaStateForWebView(sourceWebView: WebView, isPlaying: Boolean) {
        handleMediaStateChanged(sourceWebView, isPlaying)
    }

    fun setNativeTapRadioPlaybackActive(isPlaying: Boolean) {
        nativeTapRadioPlaying = isPlaying
        if (isPlaying) {
            nativeTapRadioLastActiveAt = SystemClock.uptimeMillis()
            lastMediaPlayingAt = SystemClock.uptimeMillis()
        }
        updateMediaState(anyTrackedMediaPlaying())
        refreshMaskedNowPlaying()
    }

    private fun resolveHostingActivity(): MainActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is MainActivity) return current
            current = current.baseContext
        }
        return current as? MainActivity
    }

    private fun hasNativeTapRadioSession(): Boolean {
        return resolveHostingActivity()?.hasNativeRadioSession() == true
    }

    private fun shouldRouteMediaControlsToNativeTapRadio(): Boolean {
        if (!hasNativeTapRadioSession()) return false
        if (nativeTapRadioPlaying) return true
        val latestWebMediaAt = mediaLastPlayedAtByWindowId.values.maxOrNull() ?: 0L
        return nativeTapRadioLastActiveAt >= latestWebMediaAt
    }

    private fun dispatchPlayMediaCommand() {
        if (!mediaStateByWindowId.values.any { it } && shouldRouteMediaControlsToNativeTapRadio()) {
            resolveHostingActivity()?.resumeNativeRadioFromToolbar()
            updateMediaState(true)
            return
        }
        val targetWebView = getMediaControlWebView()
        evaluateMediaControlCommand(
                targetWebView,
                "var m = document.querySelector('video, audio'); if (m) m.play();",
                "(function(){ if(window.tapRadioNativeResumePlayback){ window.tapRadioNativeResumePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
        )
        updateMediaState(true)
    }

    private fun dispatchPauseMediaCommand() {
        if (nativeTapRadioPlaying) {
            resolveHostingActivity()?.pauseNativeRadioFromToolbar()
            updateMediaState(false)
            return
        }
        val targetWebView = getMediaControlWebView()
        evaluateMediaControlCommand(
                targetWebView,
                "var m = document.querySelector('video, audio'); if (m) m.pause();",
                "(function(){ if(window.tapRadioNativePausePlayback){ window.tapRadioNativePausePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
        )
        updateMediaState(false)
    }

    private fun evaluateMediaControlCommand(targetWebView: WebView, fallbackJs: String, tapRadioJs: String? = null) {
        val script = if (tapRadioJs != null && isTapRadioWebView(targetWebView)) tapRadioJs else fallbackJs
        targetWebView.evaluateJavascript(script, null)
    }

    fun playMedia() {
        dispatchPlayMediaCommand()
    }

    fun pauseMedia() {
        dispatchPauseMediaCommand()
    }

    fun hideMediaControls() {
        post { maskMediaControlsContainer.visibility = View.GONE }
    }

    fun setSuppressFullscreenMediaControls(suppress: Boolean) {
        suppressFullscreenMediaControls = suppress
        post {
            if (::fullScreenMediaControls.isInitialized) {
                fullScreenMediaControls.visibility = if (suppress) View.GONE else View.VISIBLE
            }
        }
    }

    private fun handleMediaStateChanged(sourceWebView: WebView, isPlaying: Boolean) {
        if (isPlaying) {
            pauseBackgroundMedia(sourceWebView)
        }
        val windowId = windows.firstOrNull { it.webView == sourceWebView }?.id
        if (windowId == null) {
            if (isPlaying) {
                lastMediaPlayingAt = SystemClock.uptimeMillis()
            }
            updateMediaState(isPlaying || nativeTapRadioPlaying)
            return
        }

        mediaStateByWindowId[windowId] = isPlaying
        if (isPlaying) {
            mediaLastPlayedAtByWindowId[windowId] = SystemClock.uptimeMillis()
        }

        val anyPlaying = anyTrackedMediaPlaying()
        updateMediaState(anyPlaying)
        refreshMaskedNowPlaying()
    }

    private fun getMediaControlWebView(): WebView {
        val playingIds = mediaStateByWindowId.filterValues { it }.keys
        val targetId =
                if (playingIds.isNotEmpty()) {
                    playingIds.maxByOrNull { mediaLastPlayedAtByWindowId[it] ?: 0L }
                } else {
                    mediaLastPlayedAtByWindowId.keys.maxByOrNull {
                        mediaLastPlayedAtByWindowId[it] ?: 0L
                    }
                }

        return windows.firstOrNull { it.id == targetId }?.webView ?: webView
    }

    private var lastShownMaskLabel: String? = null

    /**
     * Holds the station the dim-mode HUD is currently advertising as a
     * fallback ("nothing is playing — single-tap to start this one").
     * Populated by [resolveMaskedNowPlayingLabel] when no real media is
     * playing and consumed by [onMaskSingleTapPlayPause]. Cleared as
     * soon as real playback starts so the single-tap goes back to its
     * normal play/pause behavior.
     */
    private data class MaskFallbackStation(
        val name: String,
        val url: String,
        val genre: String,
        val sourceLabel: String  // "Favorite" / "Genre: Jazz" / "All stations" — for log clarity
    )
    private var pendingMaskFallbackStation: MaskFallbackStation? = null

    /**
     * Read the saved TapRadio station list and active genre tab from
     * SharedPreferences and pick a sensible fallback to display in the
     * dim-mode HUD when nothing is playing. Order of preference:
     *   1. First favorite station (any genre)
     *   2. First station whose normalized genre matches the active tab
     *      — when the active tab is a real genre name, not "All" or
     *      "Favorites"
     *   3. First station in the entire list
     * Returns null only if the station list itself is empty or
     * unreadable (extremely unlikely — DEFAULT_STATIONS in radio.html
     * seeds the list on first launch).
     */
    private fun resolveMaskFallbackStation(): MaskFallbackStation? {
        val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("tapradio_stations", "")?.trim().orEmpty()
        val stations: org.json.JSONArray = if (raw.length < 2) {
            // Fresh-install path — user never opened TapRadio so the
            // SharedPreferences key is empty. Fall back to the same
            // hardcoded "favorite" stations radio.html seeds itself
            // with (DEFAULT_STATIONS), so dim mode still has something
            // to advertise on first entry.
            buildDefaultMaskStationList()
        } else {
            try {
                org.json.JSONArray(raw)
            } catch (e: Exception) {
                android.util.Log.w("DualWebViewGroup", "resolveMaskFallbackStation: bad JSON, using defaults", e)
                buildDefaultMaskStationList()
            }
        }
        if (stations.length() == 0) return null
        val activeGenre = prefs.getString("tapradio_active_genre", "")?.trim().orEmpty()

        fun stationAt(i: Int): org.json.JSONObject? = try {
            stations.optJSONObject(i)
        } catch (_: Exception) { null }

        fun toFallback(obj: org.json.JSONObject, source: String): MaskFallbackStation? {
            val name = obj.optString("name", "").trim()
            val url = obj.optString("url", "").trim()
            val genre = obj.optString("genre", "").trim()
            if (name.isEmpty() || url.isEmpty()) return null
            return MaskFallbackStation(name, url, genre, source)
        }

        // 1. First favorite — favorites should win over genre filter
        //    even if active tab isn't "Favorites", per user spec.
        for (i in 0 until stations.length()) {
            val obj = stationAt(i) ?: continue
            if (obj.optBoolean("fav", false)) {
                toFallback(obj, "Favorite")?.let { return it }
            }
        }

        // 2. First station in the active-genre tab (only when the active
        //    tab is a concrete genre — "All" and "Favorites" don't filter).
        if (activeGenre.isNotEmpty() &&
            !activeGenre.equals("All", ignoreCase = true) &&
            !activeGenre.equals("Favorites", ignoreCase = true)
        ) {
            for (i in 0 until stations.length()) {
                val obj = stationAt(i) ?: continue
                val rawGenre = obj.optString("genre", "").trim()
                if (rawGenre.equals(activeGenre, ignoreCase = true) ||
                    normalizeGenreBucket(rawGenre).equals(activeGenre, ignoreCase = true)
                ) {
                    toFallback(obj, "Genre: $activeGenre")?.let { return it }
                }
            }
        }

        // 3. First station overall.
        for (i in 0 until stations.length()) {
            val obj = stationAt(i) ?: continue
            toFallback(obj, "All stations")?.let { return it }
        }
        return null
    }

    /**
     * Hardcoded mirror of radio.html's DEFAULT_STATIONS — used when
     * SharedPreferences is empty (user has never opened TapRadio).
     * Three favorites first so the first-favorite branch in
     * [resolveMaskFallbackStation] hits something on fresh install.
     */
    private fun buildDefaultMaskStationList(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        fun add(name: String, url: String, genre: String, fav: Boolean) {
            arr.put(
                org.json.JSONObject()
                    .put("name", name)
                    .put("url", url)
                    .put("genre", genre)
                    .put("fav", fav)
            )
        }
        add("SomaFM Groove Salad", "https://ice1.somafm.com/groovesalad-256-mp3", "Chill", true)
        add("Radio Paradise Main", "https://stream.radioparadise.com/mp3-192", "Eclectic", true)
        add("SomaFM Secret Agent", "https://ice1.somafm.com/secretagent-256-mp3", "Jazz", true)
        add("SomaFM Drone Zone", "https://ice1.somafm.com/dronezone-256-mp3", "Ambient", false)
        add("SomaFM Space Station", "https://ice1.somafm.com/spacestation-128-mp3", "Ambient", false)
        add("SomaFM DEF CON Radio", "https://ice1.somafm.com/defcon-256-mp3", "Electronic", false)
        add("SomaFM Lush", "https://ice1.somafm.com/lush-128-mp3", "Chill", false)
        add("Radio Paradise Mellow", "https://stream.radioparadise.com/mellow-192", "Chill", false)
        add("SomaFM Indie Pop Rocks", "https://ice1.somafm.com/indiepop-128-mp3", "Rock", false)
        add("SomaFM Underground 80s", "https://ice1.somafm.com/u80s-256-mp3", "Rock", false)
        add("KCSM Jazz", "https://ice7.securenetsystems.net/KCSM2", "Jazz", false)
        add("KUSC Classical", "https://kusc.streamguys1.com/kusc-128k", "Classical", false)
        add("NPR Program Stream", "https://npr-ice.streamguys1.com/live.mp3", "News", false)
        add("BBC World Service", "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service", "News", false)
        return arr
    }

    /**
     * Mirror of radio.html's GENRE_BUCKETS / normalizeGenre. Kept in
     * sync by hand — radio.html is the source of truth, but the dim-mode
     * fallback only runs occasionally and the bucket list is short.
     */
    private fun normalizeGenreBucket(rawGenre: String): String {
        val lower = rawGenre.lowercase(java.util.Locale.US)
        if (lower.isBlank()) return "Mix"
        return when {
            Regex("ambient|drone|space|meditation").containsMatchIn(lower) -> "Ambient"
            Regex("chill|mellow|downtempo|lush").containsMatchIn(lower) -> "Chill"
            Regex("electronic|house|future soul|hip-hop|beats|fluid|blend").containsMatchIn(lower) -> "Electronic"
            Regex("rock|indie|pop|punk|wave|alt").containsMatchIn(lower) -> "Rock"
            Regex("jazz|lounge|bossa|spy-fi").containsMatchIn(lower) -> "Jazz"
            Regex("classical|orchestra|opera").containsMatchIn(lower) -> "Classical"
            Regex("news|npr|bbc|program").containsMatchIn(lower) -> "News"
            Regex("eclectic|folk|roots|world|americana|celtic").containsMatchIn(lower) -> "Mix"
            else -> "Mix"
        }
    }

    fun refreshMaskedNowPlaying() {
        if (!::maskNowPlayingText.isInitialized) {
            android.util.Log.d("DimMaskHud", "refreshMaskedNowPlaying: TextView not initialized yet")
            return
        }
        post {
            val label = resolveMaskedNowPlayingLabel()
            val currentUrl = try { getMediaControlWebView().url.orEmpty() } catch (_: Exception) { "" }
            val isYoutubePage = isYoutubePlayerUrl(currentUrl)
            if (!isSpotifyPlayerUrl(currentUrl)) {
                lastMaskedSpotifyInfo = null
            }
            val spotifyInfo =
                if (isSpotifyPlayerUrl(currentUrl)) lastMaskedSpotifyInfo else null
            updateMaskSpotifyInfo(spotifyInfo)
            // The poll never WRITES caption text anymore: on YouTube pages
            // the in-page YouTubeDimCaptions engine is the only writer
            // (pushing through MediaInterface.onDimCaption), and on radio
            // pages the dimCaptionTick owns the slot. The poll only clears
            // a leftover line when neither writer can be active — e.g.
            // after navigating away from YouTube mid-caption.
            if (!isYoutubePage && !dimCaptionOwnsSlot) {
                updateMaskCaption(null)
            }
            // ALWAYS commit text + visibility when we have a label. The
            // previous "skip if label unchanged" cache made the path
            // dependent on the TextView's last *intended* state, but
            // any out-of-band visibility change (layout pass, parent
            // detach, alpha animation, sibling added with greater
            // elevation) would silently leave the cache stale and the
            // TextView blank. setText() with an identical String is a
            // no-op inside TextView, so re-committing is free.
            when {
                !isScreenMasked -> {
                    maskNowPlayingText.visibility = View.INVISIBLE
                    lastShownMaskLabel = null
                }
                isAudioVisualizerShown() -> {
                    // Visualizer screen stays clean — no title overlay.
                    maskNowPlayingText.visibility = View.INVISIBLE
                    lastShownMaskLabel = null
                }
                spotifyInfo != null && spotifyInfo.title.isNotBlank() -> {
                    maskNowPlayingText.visibility = View.INVISIBLE
                    lastShownMaskLabel = null
                }
                label.isNullOrBlank() -> {
                    // Transient null (YouTube SPA navigation, brief
                    // resolver races): keep the last good label on
                    // screen instead of cycling to INVISIBLE and back.
                    if (lastShownMaskLabel == null) {
                        maskNowPlayingText.visibility = View.INVISIBLE
                    }
                }
                else -> {
                    if (maskNowPlayingText.text?.toString() != label) {
                        maskNowPlayingText.text = label
                    }
                    lastShownMaskLabel = label
                    // Restore the design alpha (0.55) in case some
                    // external animation parked it at 0. Do NOT clamp
                    // to 1.0 — that would override the deliberate
                    // "minimal brightness" choice in setupMaskOverlayUI.
                    if (maskNowPlayingText.alpha < 0.5f) {
                        maskNowPlayingText.alpha = 0.55f
                    }
                    if (maskNowPlayingText.visibility != View.VISIBLE) {
                        maskNowPlayingText.visibility = View.VISIBLE
                    }
                    maskNowPlayingText.bringToFront()
                    // Safety net for the "w=0 h=0 even when VISIBLE" bug
                    // we hit before: if the parent never measured this
                    // child during the prior layout pass, force one now.
                    // requestLayout() walks up to the root and schedules
                    // a fresh measure/layout pass which guarantees this
                    // TextView gets non-zero dimensions before the next
                    // frame.
                    if (maskNowPlayingText.width == 0 || maskNowPlayingText.height == 0) {
                        maskNowPlayingText.requestLayout()
                        (maskNowPlayingText.parent as? View)?.requestLayout()
                    }
                }
            }
            // Diagnostic: log the FULL visual state of the TextView so
            // any regression where it's "set but invisible" is
            // immediately obvious in logcat.
            val v = maskNowPlayingText
            val parent = v.parent
            android.util.Log.d(
                "DimMaskHud",
                "refresh: masked=$isScreenMasked label='${label ?: "<null>"}' " +
                    "vis=${v.visibility} alpha=${v.alpha} text='${v.text}' " +
                    "w=${v.width} h=${v.height} x=${v.x} y=${v.y} " +
                    "parent=${parent?.javaClass?.simpleName} " +
                    "parentVis=${(parent as? View)?.visibility} " +
                    "fallback=${pendingMaskFallbackStation?.name}"
            )
        }
    }

    private fun scheduleMaskedNowPlayingRefresh() {
        val delays = longArrayOf(120L, 500L, 1200L)
        refreshMaskedNowPlaying()
        delays.forEach { delayMs ->
            postDelayed({ refreshMaskedNowPlaying() }, delayMs)
        }
    }

    private fun updateMaskCaption(caption: String?) {
        if (!::maskCaptionText.isInitialized) return
        if (!isScreenMasked || caption.isNullOrBlank()) {
            maskCaptionText.visibility = View.INVISIBLE
            return
        }
        // The visualizer is its own calm surface (the user's daughter's
        // breathing screen): captions never draw over it. The heartbeat
        // (≤1.5s) restores the line as soon as the visualizer closes.
        if (isAudioVisualizerShown()) {
            maskCaptionText.visibility = View.INVISIBLE
            return
        }
        if (maskCaptionText.text?.toString() != caption) {
            maskCaptionText.text = caption
        }
        if (maskCaptionText.alpha < 0.8f) {
            maskCaptionText.alpha = 0.88f
        }
        maskCaptionText.visibility = View.VISIBLE
        maskCaptionText.bringToFront()
        // THE one-line bug, root-caused at last: the mask overlay's custom
        // layout pass never RE-measures children after content changes (the
        // same quirk the dim clock and the visualizer carry explicit
        // measure+layout safety nets for). The first caption of a dim
        // session is almost always a single line — the engine's history
        // line is empty at start — so this TextView got measured at
        // one-line height and STAYED there: every later "top\nbot" push
        // rendered two lines of text into a one-line-tall view. All five
        // engine-side fixes changed the string, never the stuck height.
        // Fix = the established pattern: explicitly measure at the
        // overlay's width and re-position bottom-centered on EVERY commit
        // (≤200ms cadence + 1.5s heartbeat keep this self-healing, exactly
        // like the visualizer's per-frame net).
        val ow = if (maskOverlay.width > 0) maskOverlay.width else 640
        val oh = if (maskOverlay.height > 0) maskOverlay.height else height
        val lp = maskCaptionText.layoutParams as? FrameLayout.LayoutParams
        val lm = lp?.leftMargin ?: 54
        val rm = lp?.rightMargin ?: 54
        val bm = lp?.bottomMargin ?: 172
        val availW = (ow - lm - rm).coerceAtLeast(1)
        maskCaptionText.measure(
            MeasureSpec.makeMeasureSpec(availW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(oh, MeasureSpec.AT_MOST)
        )
        val mh = maskCaptionText.measuredHeight
        val ct = (oh - bm - mh).coerceAtLeast(0)
        maskCaptionText.layout(lm, ct, lm + availW, ct + mh)
        // Verification line for the next build: pushedLines is what the
        // engine sent, lineCount is what the TextView will actually render
        // (includes soft wraps). If these ever disagree with the glasses,
        // `adb logcat -s DimMaskHud` settles where the line is lost.
        android.util.Log.d(
            "DimMaskHud",
            "caption commit: pushedLines=${caption.count { it == '\n' } + 1} " +
                "renderLines=${maskCaptionText.lineCount} h=$mh y=$ct"
        )
    }

    private fun updateMaskSpotifyInfo(info: MaskSpotifyInfo?) {
        if (!::maskSpotifyInfoContainer.isInitialized) return
        if (!isScreenMasked || info == null || info.title.isBlank()) {
            maskSpotifyInfoContainer.visibility = View.INVISIBLE
            return
        }
        maskSpotifyTitleText.text = info.title
        maskSpotifyArtistText.text = info.artist.ifBlank { "Spotify" }
        maskSpotifyAlbumText.text = info.album
        maskSpotifyAlbumText.visibility = if (info.album.isBlank()) View.GONE else View.VISIBLE
        // Dim-mode karaoke: when the spotify page has SYNCED lyrics for the
        // current track, show the active line by default (one line at a time,
        // updates with playback). Fall back to a tiny "♪" placeholder during
        // an instrumental gap, or fully hide the row when no synced lyrics
        // are available for the track. When a later track DOES have lyrics,
        // the row needs to come back — the user reported it stayed hidden.
        //
        // Root cause: when the TextView is GONE, the parent LinearLayout
        // caches the collapsed-to-0 state for that child. Flipping back to
        // VISIBLE alone doesn't always trigger the parent to re-measure, so
        // the row stays painted at height 0 even though the View itself is
        // now visible. Solution: track the previous visibility and force a
        // requestLayout() on both the TextView and its container whenever
        // we transition GONE → VISIBLE.
        val wasHidden = maskSpotifyLyricsText.visibility == View.GONE
        maskSpotifyLyricsText.visibility = View.VISIBLE
        val syncedLine = info.currentLyricLine.takeIf { it.isNotBlank() }
        if (syncedLine != null) {
            maskSpotifyLyricsText.text = syncedLine
            maskSpotifyLyricsText.textSize = 18f
            maskSpotifyLyricsText.alpha = 1.0f
            maskSpotifyLyricsText.maxLines = 2
        } else if (info.hasSyncedLyrics) {
            // Lyrics are loaded and timed, but we're between lines (intro /
            // outro / instrumental). Show a quiet marker so the row doesn't
            // collapse and we don't relayout each gap.
            maskSpotifyLyricsText.text = "♪"
            maskSpotifyLyricsText.textSize = 14f
            maskSpotifyLyricsText.alpha = 0.40f
            maskSpotifyLyricsText.maxLines = 1
        } else {
            // No synced lyrics for this track. Hide the row entirely — no
            // swipe-up gesture exists, so a hint would be misleading. The
            // row reappears automatically once a later track returns timed
            // lyrics (via the wasHidden re-layout dance below).
            maskSpotifyLyricsText.visibility = View.GONE
        }
        if (wasHidden && maskSpotifyLyricsText.visibility == View.VISIBLE) {
            // GONE → VISIBLE transition: nudge both the view and its parent
            // to re-measure, otherwise the parent's cached 0-height keeps
            // the row visually collapsed even after the visibility flip.
            maskSpotifyLyricsText.requestLayout()
            maskSpotifyInfoContainer.requestLayout()
        }

        val duration = info.durationMs.coerceAtLeast(0L)
        val progress = info.progressMs.coerceIn(0L, duration.takeIf { it > 0L } ?: info.progressMs)
        if (duration > 0L) {
            maskSpotifyProgressTrack.visibility = View.VISIBLE
            maskSpotifyProgressTrack.post {
                val trackWidth = maskSpotifyProgressTrack.width.coerceAtLeast(0)
                val fillWidth = ((trackWidth * progress.toDouble()) / duration.toDouble())
                    .roundToInt()
                    .coerceIn(0, trackWidth)
                val lp = maskSpotifyProgressFill.layoutParams
                if (lp.width != fillWidth) {
                    lp.width = fillWidth
                    maskSpotifyProgressFill.layoutParams = lp
                }
            }
        } else {
            maskSpotifyProgressTrack.visibility = View.INVISIBLE
        }
        maskSpotifyInfoContainer.visibility = View.VISIBLE
        maskSpotifyInfoContainer.bringToFront()
    }

    /**
     * After a track skip (next/prev), YouTube SPA navigations take several
     * seconds to update document.title.  We probe at multiple intervals and
     * also pull the title directly from the DOM which updates faster.
     *
     * The cached DOM title is invalidated immediately so the overlay stops
     * showing the OLD video's name while the new page is loading — otherwise
     * getFreshMaskedDomTitle would keep returning the stale cache value for
     * up to maskedDomTitleFreshMs.
     */
    private fun scheduleTrackChangeRefresh() {
        // Immediately drop the cached title so the overlay can't keep
        // displaying the previous video's name during the SPA transition.
        lastMaskedDomTitle = null
        lastMaskedDomTitleUrl = null
        lastMaskedDomTitleAt = 0L
        refreshMaskedNowPlaying()

        val delays = longArrayOf(300L, 800L, 1500L, 2500L, 4000L, 6000L, 8500L, 12000L)
        delays.forEach { delayMs ->
            postDelayed({
                refreshMaskedNowPlayingFromJs()
                refreshMaskedNowPlaying()
            }, delayMs)
        }
    }

    fun scheduleMaskedTrackChangeRefresh() {
        scheduleTrackChangeRefresh()
    }


    private fun getFreshMaskedDomTitle(currentUrl: String? = null): String? {
        val title = lastMaskedDomTitle?.trim().orEmpty()
        if (title.isBlank()) return null
        if (SystemClock.uptimeMillis() - lastMaskedDomTitleAt > maskedDomTitleFreshMs) return null
        if (!currentUrl.isNullOrBlank()) {
            val cachedUrl = lastMaskedDomTitleUrl.orEmpty()
            // The cached title is only trustworthy if it came from a URL
            // in the SAME page family as the one the user is currently
            // looking at. Without this guard, a stale YouTube title
            // could leak into a media_player session (or vice versa)
            // until the next periodic refresh tick replaces it.
            if (!isSameMaskedTitleFamily(currentUrl, cachedUrl)) return null
        }
        return title
    }

    /**
     * Are these two URLs from the same "page family" for purposes of
     * the dim-mode now-playing cache? Currently YouTube and media_player
     * are the two pages that publish their now-playing title via
     * `document.title`; any same-family URL pair can share a cached
     * title across navigations within that family (e.g. YouTube SPA
     * route changes, or advancing to the next track in a media_player
     * playlist that mutates `?url=`).
     */
    private fun isSameMaskedTitleFamily(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val ytA = isYoutubePlayerUrl(a)
        val ytB = isYoutubePlayerUrl(b)
        if (ytA && ytB) return true
        val mpA = a.contains("media_player.html", true)
        val mpB = b.contains("media_player.html", true)
        if (mpA && mpB) return true
        val spA = isSpotifyPlayerUrl(a)
        val spB = isSpotifyPlayerUrl(b)
        if (spA && spB) return true
        return false
    }

    private fun isYoutubePlayerUrl(url: String): Boolean {
        val lower = url.lowercase(java.util.Locale.US)
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private fun isSpotifyPlayerUrl(url: String): Boolean {
        val lower = url.lowercase(java.util.Locale.US)
        return lower.contains("spotify.html") ||
            lower.contains("open.spotify.com") ||
            lower.contains("spotify.com")
    }

    private fun maskedNowPlayingRefreshDelayMs(): Long {
        val currentUrl = try { getMediaControlWebView().url.orEmpty() } catch (_: Exception) { "" }
        return when {
            // R33 — URL checks FIRST. The old first branch was
            // `isSpotifyPlayerUrl(url) || lastMaskedSpotifyInfo != null`,
            // so a stale Spotify info object left over from an earlier dim
            // session forced YouTube onto the 1s Spotify cadence instead
            // of its 750ms caption poll. The URL is the authority on what
            // page we're actually polling; the legacy Spotify-info check
            // only matters when the URL recognizes neither player.
            isSpotifyPlayerUrl(currentUrl) -> 1000L
            isYoutubePlayerUrl(currentUrl) -> 750L
            // Legacy behavior, moved lower in the chain: a live Spotify
            // info object with an unrecognized URL (embedded player
            // variants) still keeps the 1s karaoke cadence.
            lastMaskedSpotifyInfo != null -> 1000L
            else -> 5000L
        }
    }

    /**
     * Use JS to extract the now-playing title directly from the DOM.
     *
     * On YouTube the title and caption nodes update before WebView title
     * callbacks settle across SPA route changes, so we sample the DOM directly
     * and mirror active captions into the native dim overlay. We do not move or
     * hide YouTube's own captions; this is only a low-brightness copy for dim mode.
     * On `media_player.html` the page assigns the track name to `document.title`
     * whenever a track loads or the playlist advances.
     */
    private fun refreshMaskedNowPlayingFromJs() {
        if (!isScreenMasked || !::maskNowPlayingText.isInitialized) return
        val webView = try { getMediaControlWebView() } catch (_: Exception) { return }
        val url = webView.url.orEmpty()
        // Only pages that publish their now-playing title via the DOM
        // contribute to this cache. Adding a URL family here also
        // requires updating isSameMaskedTitleFamily() and ideally
        // detectStreamingService() so the resulting label gets a
        // service-name prefix.
        val isYoutube = isYoutubePlayerUrl(url)
        val isMediaPlayer = url.contains("media_player.html", true)
        val isSpotify = isSpotifyPlayerUrl(url)
        if (isSpotify) {
            webView.evaluateJavascript(
                """
                (function() {
                    try {
                        if (typeof window.tapSpotifyNowPlaying === 'function') {
                            return window.tapSpotifyNowPlaying();
                        }
                        return JSON.stringify({
                            title: (document.getElementById('trackTitle') || {}).textContent || '',
                            artist: (document.getElementById('trackArtist') || {}).textContent || '',
                            album: '',
                            progressMs: 0,
                            durationMs: 0,
                            isPlaying: false,
                            lyricsLoaded: false
                        });
                    } catch(e) {
                        return '';
                    }
                })();
                """.trimIndent()
            ) { result ->
                val raw = result ?: return@evaluateJavascript
                val decoded = runCatching {
                    org.json.JSONTokener(raw).nextValue() as? String
                }.getOrNull() ?: raw.trim('"')
                if (decoded.isBlank() || decoded == "null") return@evaluateJavascript
                val info = runCatching {
                    val obj = JSONObject(decoded)
                    MaskSpotifyInfo(
                        title = obj.optString("title").trim(),
                        artist = obj.optString("artist").trim(),
                        album = obj.optString("album").trim(),
                        progressMs = obj.optLong("progressMs", 0L).coerceAtLeast(0L),
                        durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L),
                        isPlaying = obj.optBoolean("isPlaying", false),
                        lyricsLoaded = obj.optBoolean("lyricsLoaded", false),
                        hasSyncedLyrics = obj.optBoolean("hasSyncedLyrics", false),
                        currentLyricLine = obj.optString("currentLyricLine", "").trim()
                    )
                }.getOrNull() ?: return@evaluateJavascript
                post {
                    if (isScreenMasked) {
                        lastMaskedSpotifyInfo = info
                        lastMaskedDomTitle =
                            listOf(info.title, info.artist).filter { it.isNotBlank() }.joinToString(" — ")
                        lastMaskedDomTitleUrl = url
                        lastMaskedDomTitleAt = SystemClock.uptimeMillis()
                        refreshMaskedNowPlaying()
                    }
                }
            }
            return
        }
        if (isYoutube) {
            // R33 — scraping a YouTube page means we are definitively NOT
            // on Spotify: drop any stale Spotify info from an earlier dim
            // session right here, synchronously, so the very next
            // maskedNowPlayingRefreshDelayMs() can't see it. (The clear in
            // refreshMaskedNowPlaying() runs inside a post{} — one frame
            // too late for the delay computed by the poll that called us.)
            lastMaskedSpotifyInfo = null
            // Guaranteed (re-)install of the YouTube dim-caption engine from
            // the dim-mode poll itself. Page-start injections can land in the
            // dying page and be lost; this poll runs the moment the screen is
            // masked and then every ~750ms, so the engine is alive within
            // ~0.5s of dimming no matter what happened at page start.
            // install() is idempotent (in-page guard + debounced retry
            // ladder), so calling it on every tick is safe and intended.
            YouTubeDimCaptions.install(webView, url)
            // NOTE: this poll is a TITLE carrier only. The caption text
            // itself is pushed by the YouTubeDimCaptions in-page engine
            // through MediaInterface.onDimCaption — the old DOM caption
            // scrape that used to live here was one of the two writers
            // fighting over the caption TextView and was removed with the
            // YouTubeDimCaptions refactor.
            webView.evaluateJavascript(
                """
                (function() {
                    try {
                        function clean(value) {
                            return String(value || '').replace(/\s+/g, ' ').trim();
                        }
                        function firstText(selectors) {
                            for (var i = 0; i < selectors.length; i++) {
                                var el = document.querySelector(selectors[i]);
                                var text = clean(el && (el.textContent || el.innerText));
                                if (text) return text;
                            }
                            return '';
                        }

                        var title = firstText([
                            'yt-formatted-string.style-scope.ytd-watch-metadata',
                            '#info-contents yt-formatted-string',
                            'h1.title yt-formatted-string',
                            'h1 yt-formatted-string'
                        ]);
                        if (!title) title = clean(document.title || '');
                        title = title
                            .replace(/ - YouTube${'$'}/, '')
                            .replace(/ - YouTube Music${'$'}/, '')
                            .trim();
                        if (/^youtube$/i.test(title) || /^youtube music$/i.test(title)) title = '';
                        return JSON.stringify({ title: title });
                    } catch(e) {
                        return JSON.stringify({ title: '' });
                    }
                })();
                """.trimIndent()
            ) { result ->
                val raw = result ?: return@evaluateJavascript
                val decoded = runCatching {
                    org.json.JSONTokener(raw).nextValue() as? String
                }.getOrNull() ?: raw.trim('"')
                if (decoded.isBlank() || decoded == "null") return@evaluateJavascript
                val obj = runCatching { JSONObject(decoded) }.getOrNull() ?: return@evaluateJavascript
                val title = obj.optString("title").trim()
                post {
                    if (!isScreenMasked) return@post
                    if (title.isNotBlank()) {
                        lastMaskedDomTitle = title
                        lastMaskedDomTitleUrl = url
                        lastMaskedDomTitleAt = SystemClock.uptimeMillis()
                    }
                    refreshMaskedNowPlaying()
                }
            }
            return
        }
        if (!isMediaPlayer) return
        webView.evaluateJavascript(
            """
            (function() {
                var t = '';
                // YouTube-specific selectors — its SPA mutates these
                // before document.title settles.
                var el = document.querySelector('yt-formatted-string.style-scope.ytd-watch-metadata') ||
                         document.querySelector('#info-contents yt-formatted-string') ||
                         document.querySelector('h1.title yt-formatted-string');
                if (el) t = (el.textContent || el.innerText || '').trim();
                // Universal fallback (and the canonical source for
                // media_player.html, which keeps document.title in sync
                // with the active track).
                if (!t) t = (document.title || '').trim();
                t = t.replace(/ - YouTube${'$'}/, '').replace(/ - YouTube Music${'$'}/, '').trim();
                if (!t) return '';
                if (/^youtube$/i.test(t)) return '';
                if (/^youtube music$/i.test(t)) return '';
                // media_player.html ships with a static placeholder title
                // — treat it as "no title yet" so we don't pollute the
                // cache before the page sets a real one.
                if (/^TapInsight Media Player$/i.test(t)) return '';
                return t;
            })();
            """.trimIndent()
        ) { result ->
            val title = result?.trim('"', ' ') ?: return@evaluateJavascript
            if (title.isNotBlank() && title != "null") {
                post {
                    if (isScreenMasked) {
                        // Cache the DOM title only — do NOT write it
                        // directly to the TextView. The previous code
                        // wrote the bare title which lacked the
                        // "YouTube · " service prefix that the proper
                        // resolver adds, causing a 5-second flash to
                        // un-prefixed text every time the periodic
                        // refresh ran. Now we just refresh the cache
                        // and trigger refreshMaskedNowPlaying(), which
                        // picks up the new title via getFreshMaskedDomTitle
                        // and formats it consistently with the rest of
                        // the HUD.
                        lastMaskedDomTitle = title
                        lastMaskedDomTitleUrl = url
                        lastMaskedDomTitleAt = SystemClock.uptimeMillis()
                        refreshMaskedNowPlaying()
                    }
                }
            }
        }
    }

    private fun resolveMaskedNowPlayingLabel(): String? {
        val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
        val radioPlaying = prefs.getBoolean("tapradio_now_playing_active", false)
        val stationName = prefs.getString("tapradio_now_playing_name", "")?.trim().orEmpty()

        // During track transitions isMediaPlaying can briefly be false;
        // also check recency of last playback so we don't blank the label.
        val recentlyPlaying = isMediaPlaying ||
            (SystemClock.uptimeMillis() - lastMediaPlayingAt < 8000)
        val mediaWebView = try { getMediaControlWebView() } catch (_: Exception) { null }
        val currentUrl = mediaWebView?.url.orEmpty()
        val isYoutube = currentUrl.contains("youtube.com", ignoreCase = true) ||
            currentUrl.contains("youtu.be", ignoreCase = true)
        val isYoutubeMusic = currentUrl.contains("music.youtube.com", ignoreCase = true)
        val knownMediaService = detectStreamingService(currentUrl, isYoutubeMusic) != null

        // The dim-mode fallback ("first favorite station, tap to start")
        // is ONLY appropriate when the user is not on a known media
        // page. If they're on YouTube, Spotify, etc., a single tap must
        // toggle play/pause on that page — never start a radio station.
        // Setting pendingMaskFallbackStation while the user is on
        // YouTube was the bug behind "tap to pause resets to first in
        // list": isMediaPlaying briefly went false, this resolver set
        // the fallback, and onMaskSingleTapPlayPause fired the radio.
        fun clearFallback(): Unit {
            pendingMaskFallbackStation = null
        }
        fun fallbackLabel(): String? {
            // Refuse to advertise a fallback when we're on any known
            // media service URL — the user's intent is to control THAT
            // page, even if the bridge is briefly missing a title.
            if (knownMediaService) {
                clearFallback()
                return null
            }
            val fallback = resolveMaskFallbackStation()
            pendingMaskFallbackStation = fallback
            return fallback?.let { formatMaskLabel("TapRadio", it.name) }
        }

        if (isYoutube) {
            getFreshMaskedDomTitle(currentUrl)?.let {
                clearFallback()
                return formatMaskLabel(detectStreamingService(currentUrl, isYoutubeMusic), it)
            }
        }
        val shouldPreferTapRadioLabel =
            stationName.isNotBlank() &&
                !isYoutube &&
                (radioPlaying || hasNativeTapRadioSession())
        if (shouldPreferTapRadioLabel) {
            clearFallback()
            val track = (com.TapLink.app.unipanel.NowPlayingBridge.trackTitle
                ?: prefs.getString("tapradio_now_playing_track", ""))?.trim().orEmpty()
            return formatMaskLabel("TapRadio", if (track.isNotBlank()) "$stationName - $track" else stationName)
        }
        if (!recentlyPlaying || mediaWebView == null) {
            return fallbackLabel()
        }
        if (isYoutube) {
            val rawTitle = mediaWebView.title?.trim().orEmpty()
            val cleaned = rawTitle
                .removeSuffix(" - YouTube")
                .removeSuffix(" - YouTube Music")
                .trim()
            val title = cleaned.takeIf {
                it.isNotBlank() &&
                    !it.equals("YouTube", ignoreCase = true) &&
                    !it.equals("YouTube Music", ignoreCase = true)
            }
            if (title == null) {
                // YouTube tab open but no usable title yet — keep the
                // last good label visible (refreshMaskedNowPlaying will
                // honor the lastShownMaskLabel cache for null returns)
                // and DO NOT set a fallback radio station.
                clearFallback()
                return null
            }
            clearFallback()
            return formatMaskLabel(detectStreamingService(currentUrl, isYoutubeMusic), title)
        }
        if (shouldPreferTapRadioLabel) {
            clearFallback()
            val track = (com.TapLink.app.unipanel.NowPlayingBridge.trackTitle
                ?: prefs.getString("tapradio_now_playing_track", ""))?.trim().orEmpty()
            return formatMaskLabel("TapRadio", if (track.isNotBlank()) "$stationName - $track" else stationName)
        }
        val fallback = getFreshMaskedDomTitle(currentUrl)
        if (fallback == null) {
            return fallbackLabel()
        }
        clearFallback()
        return formatMaskLabel(detectStreamingService(currentUrl, isYoutubeMusic), fallback)
    }

    /**
     * Identify the streaming service backing the currently-playing
     * media so we can prefix the mask now-playing label with it.
     * Returns the human-readable service name (e.g. "YouTube",
     * "Spotify") or null when we can't tell — the caller treats null
     * as "no prefix" rather than "no label".
     */
    private fun detectStreamingService(url: String, isYoutubeMusic: Boolean): String? {
        if (url.isBlank()) return null
        val lower = url.lowercase(java.util.Locale.US)
        return when {
            isYoutubeMusic -> "YouTube Music"
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "YouTube"
            lower.contains("spotify.html") ||
                lower.contains("open.spotify.com") ||
                lower.contains("spotify.com") -> "Spotify"
            lower.contains("soundcloud.com") -> "SoundCloud"
            lower.contains("bandcamp.com") -> "Bandcamp"
            lower.contains("twitch.tv") -> "Twitch"
            lower.contains("vimeo.com") -> "Vimeo"
            lower.contains("netflix.com") -> "Netflix"
            lower.contains("hulu.com") -> "Hulu"
            lower.contains("disneyplus.com") || lower.contains("disney.com") -> "Disney+"
            lower.contains("primevideo.com") || lower.contains("amazon.com/gp/video") -> "Prime Video"
            lower.contains("apple.com/music") || lower.contains("music.apple.com") -> "Apple Music"
            lower.contains("tidal.com") -> "Tidal"
            lower.contains("pandora.com") -> "Pandora"
            lower.contains("radio.html") || lower.contains("radio4all") -> "TapRadio"
            lower.contains("podcasts.html") -> "Podcasts"
            // Local media files served through the in-app media player.
            // The companion JS keeps document.title in sync with the
            // active track, so the resolver pairs this prefix with the
            // current track name (e.g. "Library · beethoven-ode-to-joy").
            lower.contains("media_player.html") -> "Library"
            else -> null
        }
    }

    /** "<service> · <title>" when service is known, else the bare title. */
    private fun formatMaskLabel(service: String?, title: String): String {
        val cleaned = title.trim()
        if (cleaned.isEmpty()) return cleaned
        return if (service.isNullOrBlank()) cleaned else "$service · $cleaned"
    }

    fun injectLocation(latitude: Double, longitude: Double) {
        val script =
                """
            (function() {
                // Store the position globally so it persists
                window.__injectedPosition = {
                    coords: {
                        latitude: $latitude,
                        longitude: $longitude,
                        accuracy: 5.0,
                        altitude: null,
                        altitudeAccuracy: null,
                        heading: null,
                        speed: null
                    },
                    timestamp: new Date().getTime()
                };

                // Initialize watcher registry if not present
                if (!window.__geoWatchers) window.__geoWatchers = {};
                if (!window.__geoNextWatchId) window.__geoNextWatchId = 1;

                // Notify all registered watchPosition callbacks with updated position
                var watchers = window.__geoWatchers;
                for (var id in watchers) {
                    if (watchers.hasOwnProperty(id) && typeof watchers[id] === 'function') {
                        try { watchers[id](window.__injectedPosition); } catch(e) {
                            console.warn('[TapLink] watcher ' + id + ' error:', e);
                        }
                    }
                }

                // Only set up the mock geolocation API once
                if (window.__geoMockInstalled) {
                    console.log("[TapLink] Location updated: " + $latitude + ", " + $longitude + " (watchers: " + Object.keys(watchers).length + ")");
                    return;
                }
                window.__geoMockInstalled = true;

                // 1. Mock Permissions API to always return 'granted'
                if (navigator.permissions) {
                    var originalQuery = navigator.permissions.query.bind(navigator.permissions);
                    navigator.permissions.query = function(parameters) {
                        if (parameters.name === 'geolocation') {
                            return Promise.resolve({ state: 'granted', onchange: null });
                        }
                        return originalQuery(parameters);
                    };
                }

                // 2. Override Geolocation API using defineProperty for robustness
                var mockGeolocation = {
                    getCurrentPosition: function(success, error, options) {
                        setTimeout(function() {
                            if (window.__injectedPosition) {
                                success(window.__injectedPosition);
                            } else if (error) {
                                error({code: 2, message: 'Position unavailable'});
                            }
                        }, 10);
                    },
                    watchPosition: function(success, error, options) {
                        var watchId = window.__geoNextWatchId++;
                        window.__geoWatchers[watchId] = success;
                        // Fire immediately with current position
                        setTimeout(function() {
                            if (window.__injectedPosition) {
                                success(window.__injectedPosition);
                            }
                        }, 10);
                        return watchId;
                    },
                    clearWatch: function(id) {
                        delete window.__geoWatchers[id];
                    }
                };

                try {
                    Object.defineProperty(navigator, 'geolocation', {
                        value: mockGeolocation,
                        writable: false,
                        configurable: true
                    });
                } catch (e) {
                    // Fallback if defineProperty fails
                    navigator.geolocation.getCurrentPosition = mockGeolocation.getCurrentPosition;
                    navigator.geolocation.watchPosition = mockGeolocation.watchPosition;
                    navigator.geolocation.clearWatch = mockGeolocation.clearWatch;
                }

                console.log("[TapLink] Location mock installed + injected: " + $latitude + ", " + $longitude);
            })();
        """.trimIndent()

        post { webView.evaluateJavascript(script, null) }
    }

    fun injectPageObservers(targetWebView: WebView) {
        val script =
                """
            (function() {
                if (window.__taplinkReportScroll) {
                    window.__taplinkReportScroll();
                    if (window.__taplinkWarmupScroll) {
                        window.__taplinkWarmupScroll();
                    }
                    return;
                }

                function initTaplinkObservers() {
                    if (window.__observersInjected) return;
                    if (!document.body) {
                        setTimeout(initTaplinkObservers, 50);
                        return;
                    }
                    window.__observersInjected = true;

                    // --- Media Listeners ---
                    function attachMediaListeners(media) {
                        if (media.__listenersAttached) return;
                        media.__listenersAttached = true;
                        
                        const updateState = () => {
                            const allMedia = document.querySelectorAll('video, audio');
                            let anyPlaying = false;
                            for(let i=0; i<allMedia.length; i++) {
                                if(!allMedia[i].paused && !allMedia[i].ended && allMedia[i].readyState > 2) {
                                    anyPlaying = true;
                                    break;
                                }
                            }
                            if (window.MediaInterface) {
                                 window.MediaInterface.onMediaStateChanged(anyPlaying);
                            }
                        };

                        media.addEventListener('play', updateState);
                        media.addEventListener('pause', updateState);
                        media.addEventListener('ended', updateState);
                    }

                    const existingMedia = document.querySelectorAll('video, audio');
                    existingMedia.forEach(attachMediaListeners);

                    // --- Scroll Detection ---
                    let lastScrollTime = 0;
                    let lastScanTime = 0;
                    let cachedScroller = null;
                    let rescanRequested = false;
                    const SCAN_INTERVAL_MS = 1200;
                    const SCROLL_MIN_SIZE = 80;
                    const trackedScrollers = typeof WeakSet !== 'undefined' ? new WeakSet() : new Set();

                    function isRootScrollable(el) {
                        if (!el) return false;
                        return (el.scrollHeight - el.clientHeight) > 1 || (el.scrollWidth - el.clientWidth) > 1;
                    }

                    function isScrollable(el) {
                        if (!el || el.nodeType !== 1 || !el.getBoundingClientRect) return false;
                        const style = window.getComputedStyle(el);
                        const overflowY = style.overflowY;
                        const overflowX = style.overflowX;
                        const scrollY = (overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') &&
                            (el.scrollHeight - el.clientHeight) > 1;
                        const scrollX = (overflowX === 'auto' || overflowX === 'scroll' || overflowX === 'overlay') &&
                            (el.scrollWidth - el.clientWidth) > 1;
                        if (!(scrollY || scrollX)) return false;
                        return (el.clientHeight > SCROLL_MIN_SIZE || el.clientWidth > SCROLL_MIN_SIZE);
                    }

                    function ensureScrollListener(el) {
                        if (!el || trackedScrollers.has(el)) return;
                        trackedScrollers.add(el);
                        el.addEventListener('scroll', reportScroll, { passive: true });
                    }

                    function collectScrollableElements(root, out) {
                        if (!root || !root.querySelectorAll) return;
                        const elements = root.querySelectorAll('*');
                        for (let i = 0; i < elements.length; i++) {
                            const el = elements[i];
                            if (isScrollable(el)) out.push(el);
                            if (el.shadowRoot) {
                                collectScrollableElements(el.shadowRoot, out);
                            }
                        }
                    }

                    function pickBestScroller(candidates) {
                        let best = null;
                        let bestScore = -1;
                        for (let i = 0; i < candidates.length; i++) {
                            const el = candidates[i];
                            if (!el || !el.getBoundingClientRect) continue;
                            const rect = el.getBoundingClientRect();
                            const width = Math.max(0, Math.min(rect.width, window.innerWidth));
                            const height = Math.max(0, Math.min(rect.height, window.innerHeight));
                            const explicit = el.getAttribute && el.getAttribute('data-taplink-scroll') === 'true';
                            const score = (width * height) + (explicit ? 1000000000 : 0);
                            if (score > bestScore) {
                                bestScore = score;
                                best = el;
                            }
                        }
                        return best;
                    }

                    function findScrollableElement(forceScan) {
                        const now = Date.now();
                        if (cachedScroller && cachedScroller.isConnected === false) {
                            cachedScroller = null;
                        }
                        if (cachedScroller && !isScrollable(cachedScroller) && !isRootScrollable(cachedScroller)) {
                            cachedScroller = null;
                        }

                        const shouldScan = forceScan || !cachedScroller || (now - lastScanTime) >= SCAN_INTERVAL_MS;
                        if (!shouldScan && cachedScroller) {
                            return cachedScroller;
                        }

                        const candidates = [];
                        const rootScroller = document.scrollingElement || document.documentElement || document.body;
                        if (rootScroller && isRootScrollable(rootScroller)) {
                            candidates.push(rootScroller);
                        }

                        collectScrollableElements(document, candidates);

                        const deduped = [];
                        const seen = new Set();
                        for (let i = 0; i < candidates.length; i++) {
                            const el = candidates[i];
                            if (el && !seen.has(el)) {
                                seen.add(el);
                                deduped.push(el);
                            }
                        }

                        for (let i = 0; i < deduped.length; i++) {
                            ensureScrollListener(deduped[i]);
                        }

                        cachedScroller = pickBestScroller(deduped);
                        lastScanTime = now;
                        rescanRequested = false;
                        return cachedScroller;
                    }

                    function pickScrollerFromEvent(event) {
                        if (!event) return null;
                        if (event.composedPath) {
                            const path = event.composedPath();
                            for (let i = 0; i < path.length; i++) {
                                const node = path[i];
                                if (node && node.nodeType === 1) {
                                    const el = node;
                                    if (isScrollable(el) || isRootScrollable(el)) {
                                        ensureScrollListener(el);
                                        return el;
                                    }
                                }
                            }
                        }

                        if (event.target && event.target.nodeType === 1) {
                            const tgt = event.target;
                            if (isScrollable(tgt) || isRootScrollable(tgt)) {
                                ensureScrollListener(tgt);
                                return tgt;
                            }
                        }

                        return null;
                    }

                    function reportScroll(event) {
                        const now = Date.now();
                        // Basic throttle/debounce
                        if (now - lastScrollTime < 16) return;
                        lastScrollTime = now;

                        let scroller = pickScrollerFromEvent(event);
                        if (!scroller) {
                            scroller = findScrollableElement(rescanRequested);
                        }

                        if (!scroller) return;

                        window.__taplinkScrollTarget = scroller;

                        const rootScroller = document.scrollingElement || document.documentElement || document.body;

                        // If it's the root, use window metrics
                        let range, extent, offset, hRange, hExtent, hOffset;

                        if (scroller === rootScroller || scroller === document.documentElement || scroller === document.body) {
                            const docEl = document.documentElement;
                            range = docEl.scrollHeight;
                            extent = window.innerHeight;
                            offset = window.scrollY;

                            hRange = docEl.scrollWidth;
                            hExtent = window.innerWidth;
                            hOffset = window.scrollX;
                        } else {
                            range = scroller.scrollHeight;
                            extent = scroller.clientHeight;
                            offset = scroller.scrollTop;

                            hRange = scroller.scrollWidth;
                            hExtent = scroller.clientWidth;
                            hOffset = scroller.scrollLeft;
                        }

                        if (window.MediaInterface) {
                            window.MediaInterface.updateScrollMetrics(
                                Math.round(hRange),
                                Math.round(hExtent),
                                Math.round(hOffset),
                                Math.round(range),
                                Math.round(extent),
                                Math.round(offset)
                            );
                        }
                    }

                    function warmupScrollReports() {
                        if (window.__taplinkWarmupActive) return;
                        window.__taplinkWarmupActive = true;
                        const delays = [0, 120, 300, 600, 1000, 1500, 2000];
                        for (let i = 0; i < delays.length; i++) {
                            const delay = delays[i];
                            setTimeout(function() {
                                rescanRequested = true;
                                reportScroll();
                                if (i === delays.length - 1) {
                                    window.__taplinkWarmupActive = false;
                                }
                            }, delay);
                        }
                    }

                    window.__taplinkReportScroll = reportScroll;
                    window.__taplinkWarmupScroll = warmupScrollReports;

                    let reportTimer = null;
                    function scheduleReport() {
                        if (reportTimer !== null) return;
                        reportTimer = setTimeout(function() {
                            reportTimer = null;
                            reportScroll();
                        }, 250);
                    }

                    // Global capture listeners for scrollable activity
                    window.addEventListener('scroll', scheduleReport, { capture: true, passive: true });
                    window.addEventListener('wheel', scheduleReport, { capture: true, passive: true });
                    window.addEventListener('touchmove', scheduleReport, { capture: true, passive: true });

                    // Also check on resize
                    window.addEventListener('resize', scheduleReport);
                    document.addEventListener('DOMContentLoaded', scheduleReport, { passive: true });

                    // --- Mutation Observer ---
                    const observer = new MutationObserver(function(mutations) {
                        mutations.forEach(function(mutation) {
                            mutation.addedNodes.forEach(function(node) {
                                if (node.nodeName === 'VIDEO' || node.nodeName === 'AUDIO') {
                                    attachMediaListeners(node);
                                } else if (node.querySelectorAll) {
                                    node.querySelectorAll('video, audio').forEach(attachMediaListeners);
                                }
                            });
                        });
                        // Also re-check scroll on mutations
                        rescanRequested = true;
                        scheduleReport();
                    });
                    
                    observer.observe(document.body, { childList: true, subtree: true });

                    // Immediate metrics after setup
                    reportScroll();
                    warmupScrollReports();
                }

                initTaplinkObservers();
            })();
        """.trimIndent()

        targetWebView.evaluateJavascript(script, null)
    }

    // ── Media file interception for TapInsight media player ──
    companion object {
        private val MEDIA_TEXT_EXTS = setOf("txt","md","log","csv","json","xml","html","htm","rtf","ini","cfg","conf","yaml","yml","toml")
        private val MEDIA_AUDIO_EXTS = setOf("mp3","wav","ogg","m4a","aac","flac","wma","opus")
        private val MEDIA_VIDEO_EXTS = setOf("mp4","webm","mkv","avi","mov","m4v","ogv","3gp")
    }

    private fun interceptMediaUrl(view: android.webkit.WebView, url: String): Boolean {
        // Never treat our own asset pages as "media" — they ARE the media
        // player UI. Navigating to /assets/library_local.html should load
        // the page, not open it in a text reader just because the URL ends
        // in .html.
        val lower = url.lowercase()
        if (lower.startsWith("file:///android_asset/") ||
            lower.startsWith("https://appassets.androidplatform.net/assets/") ||
            lower.startsWith("http://appassets.androidplatform.net/assets/")
        ) return false

        val path = url.split("?")[0].split("#")[0]
        val ext = path.substringAfterLast('.', "").lowercase()
        val mediaType = when {
            MEDIA_TEXT_EXTS.contains(ext) -> "text"
            MEDIA_AUDIO_EXTS.contains(ext) -> "audio"
            MEDIA_VIDEO_EXTS.contains(ext) -> "video"
            else -> return false
        }
        pauseBackgroundMedia(view)
        val title = try {
            java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
        } catch (_: Exception) { path.substringAfterLast('/') }
        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val srtUrl = if (mediaType == "video") {
            java.net.URLEncoder.encode(path.substringBeforeLast('.') + ".srt", "UTF-8")
        } else ""

        // Read media player preferences from companion app settings
        val vcPrefs = context.getSharedPreferences("visionclaw_prefs", android.content.Context.MODE_PRIVATE)
        val voiceName = vcPrefs.getString("media_tts_voice", "") ?: ""
        val underline = vcPrefs.getBoolean("media_tts_underline", true)
        val mediaParams = buildString {
            if (voiceName.isNotBlank()) append("&voice=${java.net.URLEncoder.encode(voiceName, "UTF-8")}")
            if (!underline) append("&underline=false")
        }

        val playerUrl = "file:///android_asset/media_player.html" +
            "?url=$encodedUrl&type=$mediaType&title=$encodedTitle" +
            (if (srtUrl.isNotBlank()) "&srt=$srtUrl" else "") +
            mediaParams
        DebugLog.d("MediaPlayer", "DualWebView intercepted $mediaType ($ext): $url")
        view.loadUrl(playerUrl)
        return true
    }

    private class MediaInterface(
            private val parent: DualWebViewGroup,
            private val sourceWebView: WebView
    ) {
        @android.webkit.JavascriptInterface
        fun onMediaStateChanged(isPlaying: Boolean) {
            // Run on UI thread to update UI
            parent.post { parent.handleMediaStateChanged(sourceWebView, isPlaying) }
        }

        /**
         * Push channel for the YouTube dim-caption engine
         * (YouTubeDimCaptions): the in-page 200ms ticker pushes each
         * caption change here, so the dim line renders immediately
         * instead of waiting for the next native poll. An empty/blank
         * push clears the line (the engine sends '' when captions
         * expire or a new video starts).
         */
        @android.webkit.JavascriptInterface
        fun onDimCaption(line: String?) {
            val text = line?.trim().orEmpty()
            parent.post {
                if (!parent.isScreenMasked) return@post
                // Only the WebView that owns media controls may drive the
                // mask caption line — a backgrounded YouTube window must
                // not overwrite the active page's captions.
                val controlWebView =
                    runCatching { parent.getMediaControlWebView() }.getOrNull()
                if (controlWebView !== sourceWebView) return@post
                // The radio-lyrics ticker owns the slot on radio pages;
                // when it does, a YouTube push is stale — drop it.
                if (parent.dimCaptionOwnsSlot) return@post
                parent.updateMaskCaption(text.ifBlank { null })
            }
        }

        @android.webkit.JavascriptInterface
        fun updateScrollMetrics(
                rangeX: Int,
                extentX: Int,
                offsetX: Int,
                rangeY: Int,
                extentY: Int,
                offsetY: Int
        ) {
            parent.post {
                parent.updateExternalScrollMetrics(
                        rangeX,
                        extentX,
                        offsetX,
                        rangeY,
                        extentY,
                        offsetY
                )
            }
        }

        // ── Pending text for the pull-based media player approach ──
        @Volatile private var pendingReaderText: String? = null

        @android.webkit.JavascriptInterface
        fun getPendingText(): String {
            val text = pendingReaderText ?: ""
            pendingReaderText = null
            return text
        }

        @android.webkit.JavascriptInterface
        fun openTextReader(text: String, title: String) {
            android.util.Log.d("MediaInterface", "openTextReader: ${text.length} chars, title=$title")
            if (text.isBlank()) return
            pendingReaderText = text
            parent.post {
                val vcPrefs = parent.context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
                val voiceName = vcPrefs.getString("media_tts_voice", "") ?: ""
                val underline = vcPrefs.getBoolean("media_tts_underline", true)
                val encodedTitle = java.net.URLEncoder.encode(title.take(200), "UTF-8")
                val mediaParams = buildString {
                    if (voiceName.isNotBlank()) append("&voice=${java.net.URLEncoder.encode(voiceName, "UTF-8")}")
                    if (!underline) append("&underline=false")
                }
                val playerUrl = "file:///android_asset/media_player.html?type=text&title=$encodedTitle$mediaParams"
                sourceWebView.loadUrl(playerUrl)
            }
        }
    }
}
