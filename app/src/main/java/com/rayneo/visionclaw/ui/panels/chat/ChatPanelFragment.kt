package com.rayneo.visionclaw.ui.panels.chat

import android.util.Log
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.media.AudioManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import com.rayneo.visionclaw.core.storage.AppPreferences
import com.rayneo.visionclaw.core.storage.OrbImageStore
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Patterns
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rayneo.visionclaw.R
import com.rayneo.visionclaw.core.model.ChatMessage
import com.rayneo.visionclaw.core.notifications.NotificationCenter
import com.rayneo.visionclaw.ui.MainViewModel
import com.rayneo.visionclaw.ui.VoiceOscilloscopeView
import com.rayneo.visionclaw.ui.panels.TrackpadPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import android.view.animation.AccelerateDecelerateInterpolator
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat card list for the RayNeo X3 Pro AR display.
 *
 * ## Scroll & Focus Design (third-generation approach)
 *
 * Previous attempts used a [LinearSnapHelper] subclass that conflicted
 * with the trackpad-driven card navigation.  The X3 Pro has **no touch
 * scrolling** — all input comes from the trackpad via [onTrackpadScroll].
 * Therefore a SnapHelper (designed for finger flings) is the wrong tool.
 *
 * This version uses a purely index-driven approach:
 *
 *  1. [focusedCardIndex] is the single source of truth.
 *  2. [onTrackpadScroll] increments / decrements the index.
 *  3. [scrollToFocused] performs a 2-step center lock:
 *     - coarse jump with `scrollToPositionWithOffset(...)`
 *     - precise correction by measuring child-center vs. anchor-center.
 *  4. After centering, [applyFocusVisuals] scales / fades / glows every
 *     visible child.
 *
 * No SnapHelper.  No scroll-listener-driven focus inference.  No
 * coordinate math that can drift out of sync with the platform.
 */
class ChatPanelFragment : Fragment(), TrackpadPanel {
    private companion object {
        private const val SWIPE_STEP_LOCK_MS = 250L
        private const val CARD_NAV_MIN_DELTA = 0.35f
        private const val FAST_SWIPE_DELTA = 6.0f
        private const val SWIPE_RELEASE_RESET_MS = 280L
        private const val TAP_SETTLE_DELAY_MS = 150L
        private const val TAP_GUARD_VELOCITY_THRESHOLD_PX_PER_MS = 10f
        private const val TAP_GUARD_BLOCK_MS = 180L

        // ── Pop-out visual spec ──────────────────────────────────────────
        // Cards are short horizontal strips (matching the redesign mock-up)
        // that slightly expand when in focus.  The focused scale stays
        // modest so consecutive cards never overlap vertically.
        private const val CARD_HEIGHT_DP = 96f
        private const val CARD_FOCUS_SCALE = 1.04f
        private const val CARD_FOCUS_ALPHA = 1.0f
        private const val CARD_FOCUS_Z = 12f
        private const val CARD_UNFOCUSED_SCALE = 0.96f
        private const val CARD_UNFOCUSED_ALPHA = 0.78f
        private const val CARD_UNFOCUSED_Z = 0f
        private const val CARD_FOCUS_ANIM_MS = 200L
        private const val CARD_FOCUS_GLOW_PX = 3
        private const val CARD_FOCUS_GLOW_CORNER_DP = 14f
        private const val CARD_FOCUS_GLOW_COLOR = 0xFF00FFFF.toInt()
        private const val READER_SCROLL_SCALE = 48f
        // Trackpad swipe distance (in the same arbitrary units used by
        // CARD_NAV_MIN_DELTA) needed to advance the URL focus by one step
        // in reader mode. Generous threshold + per-gesture latch so one
        // physical swipe advances focus by exactly one entry, no skipping.
        private const val READER_FOCUS_STEP_DELTA = 1.4f
        // After a focus step fires we ignore further accumulations until
        // the user releases (this duration of stillness). Mirrors the
        // existing SWIPE_RELEASE_RESET_MS pattern used for card-nav.
        private const val READER_FOCUS_RELEASE_RESET_MS = 320L
        private const val READER_SCRIM_ANIM_MS = 160L

        // ── Battery-saving dark mode ────────────────────────────────────────
        private const val DARK_MODE_DOUBLE_SWIPE_WINDOW_MS = 2000L
        private const val DARK_MODE_BATTERY_ALPHA = 0.10f
        private const val READER_STREAM_FOLLOW_THRESHOLD_DP = 56f

        // ── HUD bell notification list ──────────────────────────────────────
        // Long-press toggles the list card; while open, trackpad swipes move
        // a one-step-per-swipe focus through the rows (same latch pattern as
        // reader-mode URL focus) and a tap activates the focused row.
        private const val NOTIF_LIST_MAX_VISIBLE = 6
        private const val NOTIF_ROLL_ANIM_MS = 200L
        private const val NOTIF_FOCUS_STEP_DELTA = 1.4f
        private const val NOTIF_FOCUS_RELEASE_RESET_MS = 320L
        private const val BELL_TINT_IDLE = 0xCCFFFFFF.toInt()
        private const val BELL_TINT_UNREAD = 0xFFFFC857.toInt()
    }

    private inner class DiscreteCarouselManager(context: Context) :
        LinearLayoutManager(context, RecyclerView.VERTICAL, false) {
        override fun canScrollVertically(): Boolean = false

        fun scrollToFocus(position: Int) {
            scrollToPositionWithOffset(position, computeFocusOffsetPx())
        }
    }

    interface CoreEyeSurfaceListener {
        fun onSurfaceAvailable()
        fun onSurfaceDestroyed()
    }

    interface CardActionListener {
        fun onAssistantRequested()
    }

    /** Notified when battery-saving dark mode is toggled so the host
     *  Activity can enable/disable backend power optimizations. */
    interface DarkModeListener {
        fun onDarkModeChanged(enabled: Boolean)
    }

    /** Notified when a HUD bell notification is activated (tap / trackpad
     *  select in the open bell list). The host drops it into the chat as
     *  an assistant card and reads it aloud. */
    interface NotificationActionListener {
        fun onNotificationActivated(notification: NotificationCenter.HudNotification)
    }

    enum class FocusedTapResult {
        OPENED_URL,
        ACTIVATE_ASSISTANT,
        IGNORED
    }

    enum class ConnectionStatus {
        IDLE,
        CONNECTING,
        GEMINI_CONNECTED,
        TOOLS_READY,
        ERROR
    }

    enum class OpenClawGatewayStatus {
        HIDDEN,
        GOOD,
        BAD
    }

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var root: View
    private lateinit var hudContainer: LinearLayout
    private lateinit var hudTime: TextView
    private lateinit var hudCalendar: TextView
    private lateinit var hudTasks: TextView
    private lateinit var hudNews: TextView
    private lateinit var hudCalendarCard: LinearLayout
    private lateinit var hudTasksCard: LinearLayout
    private lateinit var hudNewsCard: LinearLayout
    private lateinit var hudAiStatusBadge: TextView
    private lateinit var hudBatteryIcon: ImageView
    private lateinit var hudBatteryChargingIcon: ImageView
    private lateinit var hudBatteryText: TextView
    private lateinit var hudAqiText: TextView
    private lateinit var hudTapClawResultBadge: TextView
    private lateinit var hudOpenClawStatusIcon: ImageView
    private lateinit var hudHermesStatusIcon: ImageView
    private lateinit var hudRadioText: TextView
    private lateinit var hudHeartbeatText: TextView
    private lateinit var hudConnectionDot: View
    private lateinit var hudConnectionText: TextView
    private lateinit var hudBellContainer: FrameLayout
    private lateinit var hudBellIcon: ImageView
    private lateinit var hudBellBadge: TextView
    private lateinit var hudNotificationsCard: LinearLayout
    private lateinit var hudNotificationsList: LinearLayout
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatStreamIndicator: TextView
    private lateinit var readerOverlay: FrameLayout
    private lateinit var readerScroll: NestedScrollView
    private lateinit var readerText: TextView
    private lateinit var readerTopScrim: View
    private lateinit var readerBottomScrim: View
    private lateinit var inlineOscilloscope: VoiceOscilloscopeView
    private lateinit var coreEyeContainer: FrameLayout
    private lateinit var coreEyePreviewTexture: TextureView
    private lateinit var coreEyeRing: View
    private lateinit var coreEyeIdleIcon: ImageView

    // ── Earth-orb voice indicator views ─────────────────────────────────
    // The orb replaces the old line waveform.  It radiates red while the
    // microphone is recording the user and blue while Gemini speaks; the
    // glow halo fades to zero alpha when neither is active.  The entire
    // right-hand column collapses (visibility = GONE) when both the camera
    // and the orb are idle, which lets the chat-card recycler expand to
    // the full width of the panel.
    private lateinit var cameraColumn: View
    private lateinit var orbContainer: FrameLayout
    private lateinit var orbGlow: View
    private lateinit var coreEyeOrb: ImageView
    private var orbGlowFadeAnimator: ValueAnimator? = null
    private var orbActiveColor: Int = 0
    private var orbPendingLevel: Float = 0f

    // ── Dark mode overlay views ─────────────────────────────────────────
    private lateinit var darkModeOverlay: FrameLayout
    private lateinit var darkModeBatteryIcon: ImageView
    private lateinit var darkModeChargingIcon: ImageView
    private lateinit var darkModeBatteryText: TextView
    private lateinit var darkModeBatteryRow: LinearLayout
    private lateinit var darkModeCameraDot: View

    private val adapter = ChatAdapter(
        onUrlTapped = { url -> viewModel.openUrl(url) },
        onAssistantRequested = { cardActionListener?.onAssistantRequested() }
    )
    private lateinit var layoutManager: DiscreteCarouselManager

    private var renderedAssistantMessages: List<ChatMessage> = emptyList()
    private var lastMessageFingerprint = 0
    private var lastRenderedCardCount = 0
    private var accumulatedSwipeDeltaY = 0f
    private var swipeStepConsumed = false
    private var focusedCardIndex: Int = RecyclerView.NO_POSITION
        set(value) {
            field = value
            // Keep the adapter in sync so that onBindViewHolder can apply
            // correct focused/unfocused alpha for every card — including the
            // New Chat sentinel — the instant it is bound.
            adapter.focusedPosition = value
        }

    /**
     * Public accessor for the voice resolver — returns the line-start URL
     * entries embedded in whichever chat card is currently focused (or the
     * latest assistant card if nothing is explicitly focused). Returns empty
     * when there are no line-start URLs to act on.
     */
    fun getFocusedCardLinkEntries(): List<CardUrlExtractor.Entry> {
        val pos = if (focusedCardIndex == RecyclerView.NO_POSITION) {
            adapter.getLatestMessagePosition()
        } else {
            focusedCardIndex
        }
        return adapter.getCardLinkEntries(pos)
    }
    private var suppressFirstCollectorFocus = false
    private var tapBlockedUntilSnap = false
    private var swipeLockUntilMs = 0L
    private var hudModeEnabled = false
    private var readerCardUrl: String? = null          // URL of the card currently in reader mode
    // Focus-cycle state for multi-URL chat cards in reader mode (Option A
    // UX). When the focused card has 2+ line-start URL entries, the
    // trackpad swipes advance focus through the entries (clamping at ends),
    // and a single tap opens the focused URL. -1 = no focus.
    private var readerFocusedUrlIndex: Int = -1
    private var readerCachedLinkEntries: List<CardUrlExtractor.Entry> = emptyList()
    private var readerSwipeAccum: Float = 0f
    // True when a focus-cycle step just fired and we're waiting for the
    // user's swipe to release (idle for READER_FOCUS_RELEASE_RESET_MS).
    // While true, further accumulations are dropped — that's how one
    // physical swipe maps to exactly one step rather than skipping past
    // multiple entries.
    private var readerFocusStepConsumed: Boolean = false
    private val readerFocusReleaseRunnable = Runnable {
        readerFocusStepConsumed = false
        readerSwipeAccum = 0f
    }
    private var lastReaderTapMs = 0L                   // for double-tap detection in reader mode
    private val DOUBLE_TAP_THRESHOLD_MS = 400L
    private val pendingUrlOpenRunnable = Runnable {
        val url = readerCardUrl ?: return@Runnable
        viewModel.openUrl(url)
        readerCardUrl = null
        exitReaderMode(animated = false)
    }
    private var readerModeActive = false
    private var readerAutoFollowStreaming = false

    // ── Battery-saving dark mode state ──────────────────────────────────
    private var batterySavingDarkMode = false
    private var darkModeCameraActive = false
    private var lastSwipeDownStepMs = 0L   // for double-swipe-down detection
    private var lastSwipeUpStepMs = 0L     // for double-swipe-up detection
    private var isSettled = true
    private var lastScrollSampleMs = 0L
    private var velocityTapBlockUntilMs = 0L

    private var coreEyeEnabled = false
    private var coreEyeStreaming = false
    private var coreEyeSurfaceReady = false
    private var coreEyePulseAnimator: ValueAnimator? = null
    private var coreEyeSurfaceListener: CoreEyeSurfaceListener? = null
    private var cardActionListener: CardActionListener? = null
    private var darkModeListener: DarkModeListener? = null
    private var notificationActionListener: NotificationActionListener? = null

    // ── HUD bell notification list state ────────────────────────────────
    private var renderedNotifications: List<NotificationCenter.HudNotification> = emptyList()
    private var notificationListOpen = false
    private var focusedNotificationIndex = 0
    private var notifSwipeAccum = 0f
    // True when a focus step just fired; further swipe accumulation is
    // dropped until the gesture goes idle for NOTIF_FOCUS_RELEASE_RESET_MS
    // — one physical swipe moves focus by exactly one row.
    private var notifFocusStepConsumed = false
    private val notifFocusReleaseRunnable = Runnable {
        notifFocusStepConsumed = false
        notifSwipeAccum = 0f
    }
    private var externalCalendarSummary: String? = null
    private var externalTasksSummary: String? = null
    private var externalNewsSummary: String? = null
    private var externalAirQualityState: MainViewModel.AirQualityHudState? = null
    private var externalRadioState: MainViewModel.RadioHudState? = null
    private var persistentHeartbeatMessage: String? = null
    private var transientHeartbeatMessage: String? = null
    private var transientHeartbeatShouldScroll: Boolean = true
    // One-shot scroll animator for the heartbeat ticker. Replaces Android's
    // built-in ellipsize=marquee so we can park the text at the end (showing
    // the tail of the latest heartbeat) instead of looping forever and
    // resetting back to the start on each cycle.
    private var heartbeatScrollAnimator: ValueAnimator? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val coreEyeStreamTimeoutRunnable = Runnable {
        if (readerModeActive) {
            return@Runnable
        }
        if (coreEyeEnabled) {
            setCoreEyeStreamingVisuals(active = false)
        }
    }
    private val hudWarmupSyncRunnable = Runnable {
        if (!isAdded || !this::hudContainer.isInitialized) return@Runnable
        viewModel.refreshHudUpcomingCalendar(force = true)
        viewModel.refreshHudTasks(force = true)
        viewModel.refreshHudAirQuality(force = true)
        renderHudSnapshot()
    }
    private val swipeReleaseResetRunnable = Runnable {
        // Reset one-swipe session state after a short idle pause.
        accumulatedSwipeDeltaY = 0f
        swipeStepConsumed = false
        isSettled = true
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_chat_panel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        hudContainer = view.findViewById(R.id.hudContainer)
        hudTime = view.findViewById(R.id.hudTime)
        hudCalendar = view.findViewById(R.id.hudCalendar)
        hudTasks = view.findViewById(R.id.hudTasks)
        hudNews = view.findViewById(R.id.hudNews)
        hudCalendarCard = view.findViewById(R.id.hudCalendarCard)
        hudTasksCard = view.findViewById(R.id.hudTasksCard)
        hudNewsCard = view.findViewById(R.id.hudNewsCard)
        hudAiStatusBadge = view.findViewById(R.id.hudAiStatusBadge)
        hudBatteryIcon = view.findViewById(R.id.hudBatteryIcon)
        hudBatteryChargingIcon = view.findViewById(R.id.hudBatteryChargingIcon)
        hudBatteryText = view.findViewById(R.id.hudBatteryText)
        hudAqiText = view.findViewById(R.id.hudAqiText)
        hudTapClawResultBadge = view.findViewById(R.id.hudTapClawResultBadge)
        hudOpenClawStatusIcon = view.findViewById(R.id.hudOpenClawStatusIcon)
        hudHermesStatusIcon = view.findViewById(R.id.hudHermesStatusIcon)
        hudRadioText = view.findViewById(R.id.hudRadioText)
        hudHeartbeatText = view.findViewById(R.id.hudHeartbeatText)
        hudConnectionDot = view.findViewById(R.id.hudConnectionDot)
        hudConnectionText = view.findViewById(R.id.hudConnectionText)
        hudBellContainer = view.findViewById(R.id.hudBellContainer)
        hudBellIcon = view.findViewById(R.id.hudBellIcon)
        hudBellBadge = view.findViewById(R.id.hudBellBadge)
        hudNotificationsCard = view.findViewById(R.id.hudNotificationsCard)
        hudNotificationsList = view.findViewById(R.id.hudNotificationsList)
        hudBellContainer.setOnClickListener { toggleNotificationList() }
        chatRecycler = view.findViewById(R.id.chatRecycler)
        chatStreamIndicator = view.findViewById(R.id.chatStreamIndicator)
        readerOverlay = view.findViewById(R.id.readerOverlay)
        readerScroll = view.findViewById(R.id.readerScroll)
        readerText = view.findViewById(R.id.readerText)
        readerTopScrim = view.findViewById(R.id.readerTopScrim)
        readerBottomScrim = view.findViewById(R.id.readerBottomScrim)
        inlineOscilloscope = view.findViewById(R.id.chatInlineOscilloscope)
        coreEyeContainer = view.findViewById(R.id.coreEyeContainer)
        coreEyePreviewTexture = view.findViewById(R.id.coreEyePreviewTexture)
        coreEyeRing = view.findViewById(R.id.coreEyeRing)
        coreEyeIdleIcon = view.findViewById(R.id.coreEyeIdleIcon)
        cameraColumn = view.findViewById(R.id.cameraColumn)
        orbContainer = view.findViewById(R.id.orbContainer)
        orbGlow = view.findViewById(R.id.orbGlow)
        coreEyeOrb = view.findViewById(R.id.coreEyeOrb)

        // Dark mode overlay
        darkModeOverlay = view.findViewById(R.id.darkModeOverlay)
        darkModeBatteryIcon = view.findViewById(R.id.darkModeBatteryIcon)
        darkModeChargingIcon = view.findViewById(R.id.darkModeChargingIcon)
        darkModeBatteryText = view.findViewById(R.id.darkModeBatteryText)
        darkModeBatteryRow = view.findViewById(R.id.darkModeBatteryRow)
        darkModeCameraDot = view.findViewById(R.id.darkModeCameraDot)

        applyHudCardOrder()
        renderHudSnapshot()
        configureCoreEyeView()
        applyChatOrbCustomization()
        refreshBatteryStatusHud()
        setConnectionStatus(ConnectionStatus.IDLE)
        // Voice-activity indicator starts hidden.  pushVoiceOscilloscope() is
        // what makes the orb + halo appear; the legacy oscilloscope stub is
        // kept visibility=GONE permanently and is never drawn.
        inlineOscilloscope.visibility = View.GONE
        cameraColumn.visibility = View.GONE
        orbGlow.alpha = 0f
        coreEyeOrb.alpha = 0f

        layoutManager = DiscreteCarouselManager(requireContext())
        chatRecycler.layoutManager = layoutManager
        chatRecycler.adapter = adapter
        chatRecycler.setHasFixedSize(false)
        chatRecycler.clipChildren = false
        chatRecycler.itemAnimator = null
        // Disable direct touch scrolling; only trackpad gestures navigate cards.
        chatRecycler.isNestedScrollingEnabled = false
        chatRecycler.setOnTouchListener { _, _ -> true }
        readerOverlay.visibility = View.GONE
        readerScroll.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
                readerAutoFollowStreaming = shouldAutoFollowReaderStream()
                updateReaderScrollScrims(animated = true)
            }
        )
        updateReaderScrollScrims(animated = false)

        chatRecycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    applyFocusVisuals(animate = false)
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        applyFocusVisuals(animate = false)
                        accumulatedSwipeDeltaY = 0f
                        swipeStepConsumed = false
                        tapBlockedUntilSnap = false
                    }
                }
            }
        )
        chatRecycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // Keep focus visuals synchronized when layout changes after manual
            // index jumps (scrollToPositionWithOffset) and adapter rebinding.
            applyFocusVisuals(animate = false)
        }
        // Catch cards that scroll back into view from the RecyclerView cache
        // (these are re-attached without onBindViewHolder, so they keep stale
        // alpha from when they were last focused).
        chatRecycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    applyFocusVisuals(animate = false)
                }
                override fun onChildViewDetachedFromWindow(view: View) {}
            }
        )
        chatRecycler.post {
            focusedCardIndex = adapter.getLastContentPosition()
            snapFocusedCard()
            applyFocusVisuals(animate = false)
        }

        viewModel.hydrateAssistantHistory()
        bindInitialHistoryFromSnapshot()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { messages ->
                        val assistantMessages = messages.filterNot { it.fromUser }
                        renderedAssistantMessages = assistantMessages
                        adapter.submitMessages(assistantMessages)
                        val fingerprint = assistantMessages.joinToString("|") { it.text }.hashCode()
                        if (suppressFirstCollectorFocus) {
                            suppressFirstCollectorFocus = false
                            lastMessageFingerprint = fingerprint
                            focusedCardIndex = adapter.getLastContentPosition()
                            snapFocusedCard()
                        } else if (fingerprint != lastMessageFingerprint) {
                            val oldFingerprint = lastMessageFingerprint
                            lastMessageFingerprint = fingerprint
                            // Only auto-focus the latest card if a NEW message was added
                            // (card count changed). If only existing card text was updated
                            // (streaming chunk), update the card content but don't hijack
                            // the user's navigation — they may be browsing other cards.
                            val latestPos = adapter.getLatestMessagePosition()
                            if (assistantMessages.size != lastRenderedCardCount) {
                                lastRenderedCardCount = assistantMessages.size
                                focusCard(latestPos, animate = true)
                            } else {
                                // Streaming text update — refresh the card at latestPos
                                // but keep the user's current focus position.
                                adapter.notifyItemChanged(latestPos)
                                // Also update reader mode if it's open on the streaming card
                                if (readerModeActive && focusedCardIndex == latestPos) {
                                    val updatedText = adapter.getCardText(latestPos)?.trim().orEmpty()
                                    if (updatedText.isNotBlank()) {
                                        val keepStreamingTailVisible = shouldAutoFollowReaderStream()
                                        readerText.text = ChatCardSpannableBuilder.build(updatedText) { tappedUrl ->
                                            viewModel.openUrl(tappedUrl)
                                            readerCardUrl = null
                                            exitReaderMode(animated = false)
                                        }
                                        readerScroll.post {
                                            if (!readerModeActive || !isAdded) return@post
                                            if (keepStreamingTailVisible) {
                                                scrollReaderToComfortBottom(animated = false)
                                            } else {
                                                updateReaderScrollScrims(animated = false)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            snapFocusedCard()
                        }
                    }
                }
                launch {
                    viewModel.calendarSummary.collect { summary ->
                        renderCalendarSummary(summary)
                    }
                }
                launch {
                    viewModel.tasksSummary.collect { summary ->
                        renderTasksSummary(summary)
                    }
                }
                launch {
                    viewModel.newsSummary.collect { summary ->
                        renderNewsSummary(summary)
                    }
                }
                launch {
                    viewModel.airQualitySummary.collect { state ->
                        renderAirQualityState(state)
                    }
                }
                launch {
                    viewModel.radioSummary.collect { state ->
                        renderRadioState(state)
                    }
                }
                launch {
                    NotificationCenter.unreadCount.collect { count ->
                        renderBellBadge(count)
                    }
                }
                launch {
                    NotificationCenter.notifications.collect { list ->
                        renderedNotifications = list
                        if (notificationListOpen) {
                            focusedNotificationIndex = focusedNotificationIndex
                                .coerceIn(0, (list.size - 1).coerceAtLeast(0))
                            renderNotificationList()
                        }
                    }
                }
                launch {
                    val formatter = SimpleDateFormat("EEE MMM dd • HH:mm:ss", Locale.US)
                    while (true) {
                        hudTime.text = formatter.format(Date())
                        refreshBatteryStatusHud()
                        if (batterySavingDarkMode) refreshDarkModeBattery()
                        delay(1000)
                    }
                }
                launch {
                    while (true) {
                        renderHudSnapshot()
                        delay(3000)
                    }
                }
            }
        }
        view.post { renderHudSnapshot() }
    }

    override fun onResume() {
        super.onResume()
        if (this::hudContainer.isInitialized) {
            renderHudSnapshot()
        }
        // Pick up any orb image / visibility changes the user made via the
        // companion app while the chat panel was paused. Cheap operation
        // (a SharedPreferences read + small Bitmap decode if a custom file
        // exists) and there's no good lifecycle event from CompanionServer
        // to tell us "config changed", so a refresh-on-resume is the
        // straightforward solution.
        applyChatOrbCustomization()
        uiHandler.removeCallbacks(hudWarmupSyncRunnable)
        uiHandler.postDelayed(hudWarmupSyncRunnable, 1500L)
        uiHandler.postDelayed(hudWarmupSyncRunnable, 5000L)
    }

    override fun onDestroyView() {
        if (coreEyeSurfaceReady) {
            coreEyeSurfaceListener?.onSurfaceDestroyed()
        }
        coreEyeSurfaceReady = false
        coreEyePreviewTexture.surfaceTextureListener = null
        uiHandler.removeCallbacks(coreEyeStreamTimeoutRunnable)
        uiHandler.removeCallbacks(hudWarmupSyncRunnable)
        uiHandler.removeCallbacks(swipeReleaseResetRunnable)
        uiHandler.removeCallbacks(notifFocusReleaseRunnable)
        chatStreamIndicator.animate().cancel()
        coreEyePulseAnimator?.cancel()
        coreEyePulseAnimator = null
        orbGlowFadeAnimator?.cancel()
        orbGlowFadeAnimator = null
        super.onDestroyView()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Trackpad input (TrackpadPanel)
    // ══════════════════════════════════════════════════════════════════════

    override fun onTrackpadPan(deltaX: Float, deltaY: Float): Boolean {
        if (batterySavingDarkMode) return handleDarkModeSwipe(deltaY)
        if (notificationListOpen) return handleNotificationListSwipe(deltaY)
        if (hudModeEnabled) return true
        return onTrackpadScroll(deltaY)
    }

    override fun onTrackpadScroll(deltaY: Float): Boolean {
        if (batterySavingDarkMode) return handleDarkModeSwipe(deltaY)
        if (notificationListOpen) return handleNotificationListSwipe(deltaY)
        if (hudModeEnabled) return true
        if (readerModeActive) {
            // For multi-URL reader cards, vertical swipes advance the
            // URL focus-cycle instead of scrolling the text. The card
            // is short enough that the URL list fits without scrolling
            // anyway; trackpad swipes here are how the user picks a
            // URL to open. Single-URL or no-URL cards fall through to
            // the existing smooth-scroll path below.
            if (readerCachedLinkEntries.size >= 2) {
                // Reset the release timer on every motion so the latch only
                // clears once the user is truly idle.
                uiHandler.removeCallbacks(readerFocusReleaseRunnable)
                uiHandler.postDelayed(
                    readerFocusReleaseRunnable,
                    READER_FOCUS_RELEASE_RESET_MS
                )
                if (readerFocusStepConsumed) return true
                readerSwipeAccum += deltaY
                if (kotlin.math.abs(readerSwipeAccum) >= READER_FOCUS_STEP_DELTA) {
                    val direction = if (readerSwipeAccum > 0f) 1 else -1
                    readerSwipeAccum = 0f
                    readerFocusStepConsumed = true
                    val newIdx = (readerFocusedUrlIndex + direction)
                        .coerceIn(0, readerCachedLinkEntries.lastIndex)
                    if (newIdx != readerFocusedUrlIndex) {
                        readerFocusedUrlIndex = newIdx
                        rerenderReaderWithFocus()
                    }
                }
                return true
            }
            val step = (deltaY * READER_SCROLL_SCALE).toInt()
            if (step != 0) {
                readerScroll.smoothScrollBy(0, step)
                readerScroll.post {
                    updateReaderScrollScrims(animated = true)
                }
            }
            return true
        }
        val now = SystemClock.uptimeMillis()
        if (now < swipeLockUntilMs) return true
        if (abs(deltaY) < 0.01f) return true
        if (adapter.itemCount <= 0) return true
        isSettled = false
        if (lastScrollSampleMs > 0L) {
            val dtMs = (now - lastScrollSampleMs).coerceAtLeast(1L).toFloat()
            val velocity = abs(deltaY) / dtMs
            if (velocity > TAP_GUARD_VELOCITY_THRESHOLD_PX_PER_MS) {
                velocityTapBlockUntilMs = now + TAP_GUARD_BLOCK_MS
            }
        }
        lastScrollSampleMs = now
        accumulatedSwipeDeltaY += deltaY
        uiHandler.removeCallbacks(swipeReleaseResetRunnable)
        uiHandler.postDelayed(
            swipeReleaseResetRunnable,
            maxOf(SWIPE_RELEASE_RESET_MS, TAP_SETTLE_DELAY_MS)
        )
        if (swipeStepConsumed) return true
        if (abs(accumulatedSwipeDeltaY) < CARD_NAV_MIN_DELTA) return true
        if (abs(accumulatedSwipeDeltaY) >= FAST_SWIPE_DELTA) tapBlockedUntilSnap = true

        val direction = if (accumulatedSwipeDeltaY > 0f) 1 else -1
        accumulatedSwipeDeltaY = 0f
        swipeStepConsumed = true
        val firstContent = adapter.getFirstContentPosition()
        val lastContent = adapter.getLastContentPosition()
        val current = coerceFocusedIndex()

        // ── Dark mode activation: double swipe-down while New Chat focused ──
        if (direction == 1 && adapter.isNewChatCard(current)) {
            if (now - lastSwipeDownStepMs < DARK_MODE_DOUBLE_SWIPE_WINDOW_MS) {
                setBatterySavingDarkMode(true)
                lastSwipeDownStepMs = 0L
                return true
            }
            lastSwipeDownStepMs = now
        } else {
            lastSwipeDownStepMs = 0L  // reset if user swipes up or moves off New Chat
        }

        val next = (current + direction).coerceIn(firstContent, lastContent)
        if (next == current) return true
        swipeLockUntilMs = now + SWIPE_STEP_LOCK_MS
        playNavigationTick()
        focusCard(next, animate = true)
        return true
    }

    /**
     * Handles trackpad input while battery-saving dark mode is active.
     * Accumulates swipe-up gestures; two within [DARK_MODE_DOUBLE_SWIPE_WINDOW_MS]
     * exits dark mode. All other input is consumed silently.
     *
     * Uses a timer-based reset (like the normal card scroll code) because the
     * AR glasses trackpad does not emit near-zero deltas between discrete swipes.
     */
    private var darkModeSwipeAccum = 0f
    private var darkModeSwipeConsumed = false
    private val darkModeSwipeResetRunnable = Runnable {
        darkModeSwipeAccum = 0f
        darkModeSwipeConsumed = false
    }

    private fun handleDarkModeSwipe(deltaY: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        darkModeSwipeAccum += deltaY

        // Reschedule the release timer on every motion event
        uiHandler.removeCallbacks(darkModeSwipeResetRunnable)
        uiHandler.postDelayed(darkModeSwipeResetRunnable, SWIPE_RELEASE_RESET_MS)

        if (darkModeSwipeConsumed) return true
        if (abs(darkModeSwipeAccum) < CARD_NAV_MIN_DELTA) return true

        // A full swipe step detected
        val direction = if (darkModeSwipeAccum > 0f) 1 else -1
        darkModeSwipeAccum = 0f
        darkModeSwipeConsumed = true

        if (direction == -1) {  // swipe up
            if (now - lastSwipeUpStepMs < DARK_MODE_DOUBLE_SWIPE_WINDOW_MS) {
                uiHandler.removeCallbacks(darkModeSwipeResetRunnable)
                setBatterySavingDarkMode(false)
                lastSwipeUpStepMs = 0L
                return true
            }
            lastSwipeUpStepMs = now
        } else {
            lastSwipeUpStepMs = 0L  // reset on swipe down
        }
        // Refresh battery display on any swipe while in dark mode
        refreshDarkModeBattery()
        return true
    }

    override fun onTrackpadSelect(): Boolean {
        // Ignore taps while in dark mode — don't activate assistant
        if (batterySavingDarkMode) return true
        return handleFocusedCardTap() != FocusedTapResult.ACTIVATE_ASSISTANT
    }

    fun handleFocusedCardTap(): FocusedTapResult {
        if (hudModeEnabled) return FocusedTapResult.IGNORED

        // ── Check if the focused card is "New Chat" BEFORE applying scroll/settle
        //    guards — activating the assistant should always respond on first tap.
        val earlyIdx = coerceFocusedIndex()
        val isNewChat = earlyIdx < 0 || adapter.isNewChatCard(earlyIdx)
        if (isNewChat) {
            // Clear any lingering scroll/snap state so we don't block next time
            tapBlockedUntilSnap = false
            isSettled = true
            return FocusedTapResult.ACTIVATE_ASSISTANT
        }

        val now = SystemClock.uptimeMillis()
        if (!isSettled || now < velocityTapBlockUntilMs) {
            return FocusedTapResult.IGNORED
        }

        // ── Reader mode is active ──
        // Double-tap is handled externally by cyclePanelViaDoubleTap() →
        // exitReaderModeFromOutside(), so only single-tap arrives here.
        if (readerModeActive) {
            val expandedText = readerText.text?.toString().orEmpty()

            // When the focused card carries multiple line-start URL entries,
            // a trackpad single-tap opens the URL the user has currently
            // focused (Option A UX). Trackpad swipes advance the focus
            // through the entries; the focus is rendered with a ▶ prefix
            // and is pre-set to the first entry on enter. Voice
            // ("open the first link" / "open the X one" / "open all") is
            // still available as a parallel input. The HUD heartbeat shows
            // the inferred media type so the user can voice-correct if
            // classification was wrong.
            val multiUrlEntries = readerCachedLinkEntries.takeIf { it.size >= 2 }
                ?: getFocusedCardLinkEntries().takeIf { it.size >= 2 }
            if (multiUrlEntries != null) {
                val idx = readerFocusedUrlIndex.coerceIn(0, multiUrlEntries.lastIndex)
                val target = multiUrlEntries[idx]
                val host = runCatching {
                    android.net.Uri.parse(target.rawUrl).host
                        ?.removePrefix("www.")
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull() ?: target.rawUrl
                val titleSuffix = if (target.displayTitle.contains(host, ignoreCase = true)) {
                    target.displayTitle
                } else {
                    "$host — ${target.displayTitle}"
                }
                showHeartbeat(
                    "Opening ${target.mediaType.userLabel()}: $titleSuffix",
                    displayMs = 2_500L,
                    scroll = false
                )
                viewModel.openUrl(target.rawUrl)
                readerCardUrl = null
                exitReaderMode(animated = false)
                return FocusedTapResult.OPENED_URL
            }

            val url = inferBrowserUrlFromCardText(expandedText)
                ?: readerCardUrl
                ?: buildGenericSearchUrl(expandedText)
            if (!url.isNullOrBlank()) {
                viewModel.openUrl(url)
                readerCardUrl = null
                exitReaderMode(animated = false)
                return FocusedTapResult.OPENED_URL
            }
            return FocusedTapResult.IGNORED
        }

        if (chatRecycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            return FocusedTapResult.IGNORED
        }
        if (tapBlockedUntilSnap) {
            snapFocusedCard()
            tapBlockedUntilSnap = false
            return FocusedTapResult.IGNORED
        }
        val idx = coerceFocusedIndex()
        if (idx < 0) return FocusedTapResult.ACTIVATE_ASSISTANT
        if (adapter.isNewChatCard(idx)) return FocusedTapResult.ACTIVATE_ASSISTANT

        val cardText = adapter.getCardText(idx)?.trim().orEmpty()
        val resolvedUrl = inferBrowserUrlFromCardText(cardText) ?: adapter.getCardUrl(idx)
        // ── Always expand the focused card first. A second tap while expanded
        //    opens the relevant browser target, if any. ──
        readerCardUrl = resolvedUrl
        lastReaderTapMs = 0L
        return if (enterReaderMode(idx, animated = true)) {
            FocusedTapResult.IGNORED
        } else {
            readerCardUrl = null
            FocusedTapResult.ACTIVATE_ASSISTANT
        }
    }

    fun autoFocusLatestAssistantUrl() {
        if (adapter.itemCount <= 0) return
        val start = adapter.getLatestMessagePosition()
        val first = adapter.getFirstContentPosition()
        val urlPos = (start downTo first).firstOrNull {
            val text = adapter.getCardText(it).orEmpty()
            inferBrowserUrlFromCardText(text) != null || adapter.getCardUrl(it) != null
        }
        focusCard(urlPos ?: start, animate = true)
    }

    private fun inferBrowserUrlFromCardText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        Log.d("ARNav", "inferBrowserUrlFromCardText: input='${trimmed.take(200)}'")


        Patterns.WEB_URL.matcher(trimmed).run {
            if (find()) {
                val raw = group().orEmpty().trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')
                if (raw.isNotBlank()) {
                    return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                }
            }
        }

        val lines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return null

        val explicitMapCard = lines.any {
            it.startsWith("Maps:", ignoreCase = true) ||
                it.startsWith("Directions:", ignoreCase = true) ||
                it.startsWith("From:", ignoreCase = true) ||
                it.startsWith("To:", ignoreCase = true)
        }
        val numberedPlacesCard = looksLikeNumberedPlacesCard(trimmed, lines)
        val mapResultCard = explicitMapCard || numberedPlacesCard

        // Check for explicit "From:" / "To:" lines (from google_routes tool results)
        val fromLine = lines.firstOrNull { it.startsWith("From:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.trimEnd('.', ',', ';')
        val toLine = lines.firstOrNull { it.startsWith("To:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.trimEnd('.', ',', ';')
        if (!toLine.isNullOrBlank()) {
            val originAddr = fromLine?.takeIf { it.isNotBlank() }
            // Try strict address regex first; fall back to raw To: text
            // (Google Maps can geocode place names, cities, and landmarks)
            val destination = extractAddressFromCardText(toLine) ?: toLine
            val searchTopic = extractSearchTopicFromCardText(trimmed, listOf(destination))
            val url = buildDrivingDirectionsUrl(destination, originAddr, searchTopic)
            Log.d("ARNav", "  → From/To detected: origin='${originAddr ?: "GPS"}' dest='$destination' search='${searchTopic ?: ""}' URL: ${url.take(200)}")
            return url
        }

        val extractedAddresses = extractAddressesFromCardText(trimmed)
        Log.d("ARNav", "  extractedAddresses=${extractedAddresses.size}: ${extractedAddresses.joinToString(" | ") { it.take(60) }}")
        when {
            extractedAddresses.size == 1 -> {
                val address = extractedAddresses.first()
                val searchTopic = extractSearchTopicFromCardText(trimmed, extractedAddresses)
                if (mapResultCard) {
                    val url = buildDrivingDirectionsUrl(address, searchQuery = searchTopic)
                    Log.d("ARNav", "  → single address map URL: ${url.take(200)} search='${searchTopic ?: ""}'")
                    return url
                }
                val searchQuery = buildSearchQueryFromCardText(trimmed, lines, extractedAddresses)
                val url = buildGenericSearchUrl(searchQuery)
                Log.d("ARNav", "  → single address search URL: ${url?.take(200)} query='${searchQuery.take(120)}'")
                return url
            }
            extractedAddresses.size > 1 -> {
                if (mapResultCard) {
                    // Extract name+address pairs from numbered place lines and build
                    // a waypoint URL so each location gets its own pin on the map.
                    val namedLocations = extractNamedLocationsFromCard(lines, extractedAddresses)
                    val url = buildMultiPinMapsUrl(namedLocations, extractedAddresses)
                    Log.d("ARNav", "  → multi pin URL (${namedLocations.size} locations): ${url.take(200)}")
                    return url
                }
                val searchQuery = buildSearchQueryFromCardText(trimmed, lines, extractedAddresses)
                val url = buildGenericSearchUrl(searchQuery)
                Log.d("ARNav", "  → multi address search URL: ${url?.take(200)} query='${searchQuery.take(120)}'")
                return url
            }
        }

        extractDestinationQueryFromCardText(trimmed)?.let { destination ->
            val url = buildGenericSearchUrl(destination)
            Log.d("ARNav", "  → destination query search URL: ${url?.take(200)}")
            return url
        }

        val mapLike = trimmed.contains("open now", ignoreCase = true) ||
            trimmed.contains("closed", ignoreCase = true) ||
            trimmed.contains("currently closed", ignoreCase = true) ||
            trimmed.contains("currently open", ignoreCase = true) ||
            trimmed.contains("nearby alternatives", ignoreCase = true) ||
            trimmed.contains("eta:", ignoreCase = true) ||
            trimmed.contains("from:", ignoreCase = true) ||
            trimmed.contains("to:", ignoreCase = true) ||
            trimmed.contains("located at", ignoreCase = true) ||
            trimmed.contains("address:", ignoreCase = true) ||
            trimmed.contains("drive", ignoreCase = true) ||
            trimmed.contains("walk", ignoreCase = true) ||
            trimmed.contains("transit", ignoreCase = true) ||
            trimmed.contains("parking", ignoreCase = true) ||
            Regex("""\b\d{1,5}\s+[A-Za-z0-9.\- ]+\s(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Ln|Lane|Way|Pl|Place|Ct|Court|Pkwy|Parkway|Ter|Terrace)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(trimmed) ||
            Regex("""\b(?:restaurant|cafe|coffee|shop|store|market|pharmacy|gas station|hotel|bar|bakery|hospital|parking)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(trimmed)

        val usefulLines = lines
            .filterNot {
                it.startsWith("Maps:", ignoreCase = true) ||
                    it.startsWith("ETA:", ignoreCase = true) ||
                    it.startsWith("Nearby alternatives", ignoreCase = true) ||
                    it.startsWith("Directions:", ignoreCase = true)
            }
            .map { sanitizeMapCardLine(it) }
            .filter { it.isNotBlank() }
            .take(if (mapLike) 2 else 3)

        val query = usefulLines.joinToString(" ").trim()
        if (query.isBlank()) {
            return buildGenericSearchUrl(sanitizeMapCardLine(trimmed).take(280).ifBlank { trimmed })
        }
        return buildGenericSearchUrl(query)
    }

    private fun buildDrivingDirectionsUrl(
        destination: String,
        originAddress: String? = null,
        searchQuery: String? = null
    ): String {
        val encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8.toString())
        val encodedSearch = searchQuery?.takeIf { it.isNotBlank() }
            ?.let { URLEncoder.encode(it.take(280), StandardCharsets.UTF_8.toString()) }
        // Prefer an explicit text origin (from user voice command) over GPS coords
        val explicitOrigin = if (!originAddress.isNullOrBlank()) {
            originAddress
        } else {
            viewModel.getDeviceLocationContext()
                ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
                ?.let { context ->
                    "${"%.6f".format(Locale.US, context.latitude)},${"%.6f".format(Locale.US, context.longitude)}"
                }
        }

        val searchSuffix = encodedSearch?.let { "&taplink_query=$it" }.orEmpty()
        return if (explicitOrigin != null) {
            val encodedOrigin = URLEncoder.encode(explicitOrigin, StandardCharsets.UTF_8.toString())
            Log.d("ARNav", "buildDrivingDirectionsUrl: origin='$explicitOrigin' dest='$destination' search='${searchQuery ?: ""}'")
            "https://www.google.com/maps/dir/?api=1&origin=$encodedOrigin&destination=$encodedDestination&travelmode=driving$searchSuffix"
        } else {
            Log.d("ARNav", "buildDrivingDirectionsUrl: no origin, dest='$destination' search='${searchQuery ?: ""}'")
            "https://www.google.com/maps/dir/?api=1&destination=$encodedDestination&travelmode=driving$searchSuffix"
        }
    }

    private fun buildGoogleMapsSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "https://www.google.com/maps/search/?api=1&query=$encoded"
    }

    /**
     * Extract "Name, Address" pairs from numbered card lines like:
     *   1. Joe's Pizza — ★4.5 (120) — Open Now — 123 Main St, Boston
     * Returns a list of "Joe's Pizza, 123 Main St, Boston" strings.
     */
    private fun extractNamedLocationsFromCard(
        lines: List<String>,
        addresses: List<String>
    ): List<String> {
        val result = mutableListOf<String>()
        val addressLower = addresses.map { it.lowercase(Locale.US) }

        for (line in lines) {
            // Match numbered lines:  "1. Name — stuff — stuff — Address"
            val trimmed = line.trim()
            val nameMatch = Regex("""^\d+\.\s+(.+)""").find(trimmed) ?: continue
            val content = nameMatch.groupValues[1]

            // Split by " — " separator used in formatPlace()
            val parts = content.split(" — ", " - ").map { it.trim() }
            if (parts.isEmpty()) continue

            val placeName = parts.first().trim()
            // Find which address belongs to this line
            val lineAddr = addresses.firstOrNull { addr ->
                trimmed.contains(addr, ignoreCase = true)
            }

            if (lineAddr != null && placeName.isNotBlank()) {
                result.add("$placeName, $lineAddr")
            } else if (placeName.isNotBlank()) {
                // No address found on this line — use name alone as search term
                result.add(placeName)
            }
        }
        return result
    }

    /**
     * Build a URL to our custom multi_pin_map.html that shows ONLY the given
     * locations as numbered pins with a persistent, clickable legend panel.
     * Locations are passed as a JSON array in the query string.
     */
    private fun buildMultiPinMapsUrl(
        namedLocations: List<String>,
        fallbackAddresses: List<String>
    ): String {
        val apiKey = viewModel.preferences.googleMapsApiKey
        // Build a JSON array of {name, address} objects
        val jsonArray = JSONArray()
        if (namedLocations.isNotEmpty()) {
            for (loc in namedLocations) {
                val obj = JSONObject()
                // "Joe's Pizza, 123 Main St, Boston" → name="Joe's Pizza", address="123 Main St, Boston"
                val commaIdx = loc.indexOf(',')
                if (commaIdx > 0) {
                    obj.put("name", loc.substring(0, commaIdx).trim())
                    obj.put("address", loc.substring(commaIdx + 1).trim())
                } else {
                    obj.put("name", loc.trim())
                }
                jsonArray.put(obj)
            }
        } else {
            for (addr in fallbackAddresses) {
                val obj = JSONObject()
                obj.put("name", addr)
                obj.put("address", addr)
                jsonArray.put(obj)
            }
        }
        val encodedLocations = URLEncoder.encode(jsonArray.toString(), StandardCharsets.UTF_8.toString())
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString())
        // Pass user GPS so the map centers near the user while geocoding runs
        val loc = viewModel.getDeviceLocationContext()
        val latParam = loc?.latitude ?: 0.0
        val lngParam = loc?.longitude ?: 0.0
        return "file:///android_asset/multi_pin_map.html?gkey=$encodedKey&lat=$latParam&lng=$lngParam&locations=$encodedLocations"
    }

    private fun sanitizeMapCardLine(text: String): String {
        var value = text.trim()
            .removePrefix("→")
            .removePrefix("-")
            .replace(Regex("""^\d+\.\s*"""), "")
            .replace(Regex("""\s+—\s+★[0-9.]+(?:\s+\(\d+\))?"""), "")
            .trim()

        extractAddressFromCardText(value)?.let { return it }

        val patterns = listOf(
            Regex("""\baddress:\s*(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:is\s+)?(?:located|location)\s+at\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""\bis\s+at\s+(.+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(value)
            if (match != null) {
                value = match.groupValues[1].trim()
                break
            }
        }

        value = value
            .replace(Regex("""\b(?:currently\s+)?(?:open\s*now|openow|closed|closednow)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:clear|cloudy|overcast|rain|showers|fog|drizzle|snow|storm)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bAQI\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b\d{1,3}°\s*[FC]\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:walk|drive|transit|eta|parking)\b.*$""", RegexOption.IGNORE_CASE), "")

        listOf(" — ", " - ", " | ", ". ").forEach { separator ->
            val idx = value.indexOf(separator)
            if (idx > 5) value = value.substring(0, idx)
        }

        return value.trim().trimEnd('.', ',', ';', ':')
    }

    private fun looksLikeNumberedPlacesCard(
        fullText: String,
        lines: List<String>
    ): Boolean {
        val hasAddress = extractAddressesFromCardText(fullText).isNotEmpty()
        if (!hasAddress) return false

        val numberedLines = lines.filter { Regex("""^\d+\.\s+""").containsMatchIn(it) }
        if (numberedLines.isEmpty()) return false

        val numberedLinesWithAddresses = numberedLines.count { line ->
            extractAddressFromCardText(line) != null
        }

        return numberedLinesWithAddresses > 0 &&
            (numberedLines.size > 1 || fullText.contains("Nearby alternatives", ignoreCase = true))
    }

    private fun extractDestinationQueryFromCardText(text: String): String? {
        val normalizedLines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                it.startsWith("Maps:", ignoreCase = true) ||
                    it.startsWith("ETA:", ignoreCase = true) ||
                    it.startsWith("Nearby alternatives", ignoreCase = true) ||
                    it.startsWith("Directions:", ignoreCase = true)
            }
            .toList()
        if (normalizedLines.isEmpty()) return null

        extractAddressFromCardText(text)?.let { return it }
        normalizedLines.firstNotNullOfOrNull { line -> extractAddressFromCardText(line) }?.let { return it }

        val labelled = normalizedLines.firstNotNullOfOrNull { line ->
            listOf(
                Regex("""\baddress:\s*(.+)""", RegexOption.IGNORE_CASE),
                Regex("""(?:is\s+)?(?:located|location)\s+at\s+(.+)""", RegexOption.IGNORE_CASE),
                Regex("""\bis\s+at\s+(.+)""", RegexOption.IGNORE_CASE)
            ).firstNotNullOfOrNull { pattern ->
                pattern.find(line)?.groupValues?.getOrNull(1)?.trim()
            }
        }
        labelled?.let {
            val cleaned = sanitizeMapCardLine(it)
            if (cleaned.isNotBlank()) return cleaned
        }

        val firstUseful = normalizedLines
            .map { sanitizeMapCardLine(it) }
            .firstOrNull { it.isNotBlank() }
            ?.take(280)

        return firstUseful?.takeIf { it.isNotBlank() }
    }

    private fun extractAddressesFromCardText(text: String): List<String> {
        val addressRegex = Regex(
            """\b\d{1,5}\s+[A-Za-z0-9.'#\- ]+\s(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Ln|Lane|Way|Pl|Place|Ct|Court|Pkwy|Parkway|Ter|Terrace)\b(?:,\s*[A-Za-z .'-]+){0,3}""",
            RegexOption.IGNORE_CASE
        )
        return addressRegex.findAll(text)
            .map { it.value.trim().trimEnd('.', ',', ';', ':') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
            .toList()
    }

    private fun buildSearchQueryFromCardText(
        fullText: String,
        lines: List<String>,
        addresses: List<String>
    ): String {
        val pieces = linkedSetOf<String>()
        inferPlaceCategoryFromText(fullText)?.let { pieces += it }

        val joined = lines.joinToString(" ") { it.trim() }
            .replace(Regex("""\s+"""), " ")
            .trim()

        Regex(
            """([A-Z][A-Za-z'&.-]*(?:\s+(?:[A-Z][A-Za-z'&.-]*|&|and|of|the))+?)\s+(?:at\s+\d{1,5}|,\s*with|is\s+(?:open|closed)|—)"""
        ).findAll(joined).forEach { match ->
            val name = match.groupValues[1].trim().trim(',', '.', ';', ':')
            if (name.length > 2 && !name.startsWith("A search", ignoreCase = true)) {
                pieces += name
            }
        }

        Regex("""(?:include|consider)\s+(.+?)(?:\.|$)""", RegexOption.IGNORE_CASE)
            .find(joined)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { pieces += it }

        extractSearchTopicFromCardText(fullText, addresses)
            ?.takeIf {
                !it.startsWith("A search", ignoreCase = true) &&
                    !it.startsWith("A stellar pursuit", ignoreCase = true)
            }
            ?.let { pieces += it }

        val scrubbed = joined
            .replace(Regex("""(?i)^a\s+(?:search\s+reveals[^.]*|stellar\s+pursuit!?\s*)"""), "")
            .replace(Regex("""(?i)tap the card to see full map details\.?"""), "")
            .replace(Regex("""(?i)wishing you[^.]*\.?"""), "")
            .replace(Regex("""(?i)the weather[^.]*\.?"""), "")
            .replace(Regex("""(?i)other nearby alternatives include\s*"""), "")
            .replace(Regex("""(?i)with a stellar\s+[0-9.]+\s+rating,?"""), "")
            .replace(Regex("""(?i),?\s*is\s+open\w*\s+and\s+just\s+a?\s*\d+[- ]minute[^.]*\.?"""), " ")
            .replace(Regex("""(?i)\d+[- ]minute\s+(?:walk|drive|transit)"""), "")
            .replace(Regex("""(?i)(?:open\s*now|openow|closed|currently open|currently closed)"""), "")
            .replace(Regex("""\d{1,3}°\s*[FC]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', ',', '.', ';', ':')

        if (pieces.isEmpty() && scrubbed.isNotBlank()) {
            pieces += scrubbed
        }

        return pieces.joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(280)
            .ifBlank { sanitizeMapCardLine(fullText).take(280).ifBlank { fullText.take(280) } }
    }

    private fun buildMultiAddressMapsQuery(
        fullText: String,
        lines: List<String>,
        addresses: List<String>
    ): String {
        inferPlaceCategoryFromText(fullText)?.let { category ->
            return "$category near me"
        }

        val candidate = lines.firstNotNullOfOrNull { line ->
            val cleaned = sanitizeMapCardLine(line)
            cleaned.takeIf {
                it.isNotBlank() &&
                    addresses.none { address -> address.equals(cleaned, ignoreCase = true) } &&
                    !it.startsWith("maps:", ignoreCase = true) &&
                    !it.startsWith("eta:", ignoreCase = true)
            }
        }

        return candidate?.take(280)
            ?: sanitizeMapCardLine(fullText).take(280)
    }

    private fun extractSearchTopicFromCardText(
        fullText: String,
        addresses: List<String> = extractAddressesFromCardText(fullText)
    ): String? {
        val lines = fullText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                it.startsWith("Maps:", ignoreCase = true) ||
                    it.startsWith("ETA:", ignoreCase = true) ||
                    it.startsWith("Nearby alternatives", ignoreCase = true) ||
                    it.startsWith("Directions:", ignoreCase = true)
            }
            .toList()

        val candidate = lines.firstNotNullOfOrNull { line ->
            val cleaned = sanitizeMapCardLine(line)
            cleaned.takeIf {
                it.isNotBlank() &&
                    addresses.none { address -> address.equals(cleaned, ignoreCase = true) } &&
                    extractAddressFromCardText(it) == null
            }
        }

        return candidate?.take(280)
            ?: inferPlaceCategoryFromText(fullText)?.let { "$it near me" }
    }

    private fun inferPlaceCategoryFromText(text: String): String? {
        val lower = text.lowercase(Locale.US)
        val categories = listOf(
            "coffee shop",
            "cafe",
            "restaurant",
            "bakery",
            "bar",
            "pharmacy",
            "gas station",
            "grocery store",
            "supermarket",
            "hotel",
            "parking"
        )
        return categories.firstOrNull { lower.contains(it) }
    }

    private fun extractAddressFromCardText(text: String): String? {
        val addressRegex = Regex(
            """\b\d{1,5}\s+[A-Za-z0-9.'#\- ]+\s(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Ln|Lane|Way|Pl|Place|Ct|Court|Pkwy|Parkway|Ter|Terrace)\b(?:,\s*[A-Za-z .'-]+){0,3}""",
            RegexOption.IGNORE_CASE
        )
        return addressRegex.find(text)
            ?.value
            ?.trim()
            ?.trimEnd('.', ',', ';', ':')
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildGenericSearchUrl(text: String): String? {
        val cleaned = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        if (cleaned.isBlank()) return null
        val encoded = URLEncoder.encode(cleaned.take(1000), StandardCharsets.UTF_8.toString())
        return "https://www.google.com/search?q=$encoded"
    }

    fun focusNewChatCard(animate: Boolean = true) {
        if (adapter.itemCount <= 0) return
        if (readerModeActive) {
            exitReaderMode(animated = false)
        }
        focusCard(adapter.getLastContentPosition(), animate = animate)
    }

    fun prepareForAssistantLaunch() {
        if (readerModeActive) {
            exitReaderMode(animated = false)
        }
        if (hudModeEnabled) {
            setHudModeEnabled(false)
        }
        uiHandler.removeCallbacks(swipeReleaseResetRunnable)
        accumulatedSwipeDeltaY = 0f
        swipeStepConsumed = false
        tapBlockedUntilSnap = false
        isSettled = true
        velocityTapBlockUntilMs = 0L
        if (adapter.itemCount > 0) {
            focusedCardIndex = adapter.getLastContentPosition()
            applyFocusVisuals(animate = false)
        }
    }

    fun clearFocus() {
        if (readerModeActive) {
            exitReaderMode(animated = false)
        }
        focusedCardIndex = RecyclerView.NO_POSITION
        accumulatedSwipeDeltaY = 0f
        swipeStepConsumed = false
        isSettled = true
        lastScrollSampleMs = 0L
        velocityTapBlockUntilMs = 0L
    }

    override fun onTextInputFromHold(text: String): Boolean = false

    override fun onHeadYaw(yawDegrees: Float) {
        root.translationX = (yawDegrees * 1.25f).coerceIn(-40f, 40f)
    }

    override fun getReadableText(): String {
        val messages = renderedAssistantMessages.takeLast(8)
        return messages.joinToString("\n") { "Assistant: ${it.text}" }
    }

    fun isReaderModeActive(): Boolean = readerModeActive

    /** Called from MainActivity when a double-tap should close reader mode
     *  instead of cycling panels / launching TapBrowser.
     *
     *  The New Chat sentinel is focused **synchronously**, BEFORE the
     *  overlay fade-out begins, so the instant the overlay clears the
     *  user sees the New Chat card already centred and highlighted —
     *  matching the product requirement that a double-tap out of an
     *  expanded chat frame immediately focuses New Chat.  We jump the
     *  recycler with `focusCard(..., animate = false)` (which under the
     *  hood uses `scrollToPositionWithOffset`, not a smooth scroll) so
     *  there is no perceptible delay, and we intentionally bypass
     *  [focusNewChatCard] here because that helper would short-circuit
     *  the reader-exit fade animation by calling `exitReaderMode(false)`. */
    fun exitReaderModeFromOutside() {
        uiHandler.removeCallbacks(pendingUrlOpenRunnable)
        lastReaderTapMs = 0L
        readerCardUrl = null
        if (adapter.itemCount > 0) {
            // Set focusedCardIndex immediately so snapFocusedCard() inside
            // exitReaderMode's finalize block picks up the New Chat card as
            // its target.
            focusedCardIndex = adapter.getLastContentPosition()
            focusCard(focusedCardIndex, animate = false)
        }
        exitReaderMode(animated = true)
        // Belt-and-suspenders: the pre-position focusCard() call above runs
        // while chatRecycler is INVISIBLE (reader overlay still up), and the
        // RecyclerView can silently drop the scroll request if no layout
        // pass is active. After the 250ms fade-out animation completes, the
        // recycler is visible again — re-issue focus on the New Chat card
        // so double-tap-out always lands the user on New Chat regardless of
        // which card they were reading.
        uiHandler.postDelayed({
            if (!isAdded || readerModeActive) return@postDelayed
            if (adapter.itemCount <= 0) return@postDelayed
            focusCard(adapter.getLastContentPosition(), animate = false)
        }, 320L)
    }

    // ── OpenClaw heartbeat scrolling text under the clock ──────────

    private val hideHeartbeatRunnable = Runnable {
        transientHeartbeatMessage = null
        renderHeartbeat(animateHide = persistentHeartbeatMessage.isNullOrBlank())
    }

    private fun renderHeartbeat(animateHide: Boolean = false) {
        if (!isAdded || !::hudHeartbeatText.isInitialized) return
        val effectiveMessage = transientHeartbeatMessage ?: persistentHeartbeatMessage
        if (effectiveMessage.isNullOrBlank()) {
            hudHeartbeatText.animate().cancel()
            heartbeatScrollAnimator?.cancel()
            heartbeatScrollAnimator = null
            if (!animateHide || hudHeartbeatText.visibility != View.VISIBLE) {
                hudHeartbeatText.alpha = 1f
                hudHeartbeatText.visibility = View.GONE
                hudHeartbeatText.scrollX = 0
                return
            }
            hudHeartbeatText.animate()
                .alpha(0f)
                .setDuration(300L)
                .withEndAction {
                    hudHeartbeatText.visibility = View.GONE
                    hudHeartbeatText.alpha = 1f
                    hudHeartbeatText.scrollX = 0
                }
                .start()
            return
        }

        val prefix = "\u2764\uFE0F "
        val fullText = "$prefix$effectiveMessage"
        val shouldScroll = transientHeartbeatMessage != null && transientHeartbeatShouldScroll
        // Drop any in-flight scroll so the new message starts from the left.
        heartbeatScrollAnimator?.cancel()
        heartbeatScrollAnimator = null
        hudHeartbeatText.scrollX = 0
        hudHeartbeatText.text = fullText
        hudHeartbeatText.visibility = View.VISIBLE
        hudHeartbeatText.animate().cancel()
        hudHeartbeatText.alpha = 1f
        if (shouldScroll) {
            // Defer to post() so the TextView has been measured with the new
            // text before we compute how far it needs to scroll.
            hudHeartbeatText.post { startHeartbeatScroll(fullText) }
        }
    }

    /**
     * One-shot horizontal scroll for the HUD heartbeat ticker.
     *
     * Behavior we want (per user): scroll the message once from the start to
     * the end, then park there so the tail of the latest heartbeat stays on
     * screen — readable until a brand new heartbeat replaces it.
     *
     * Android's built-in `ellipsize="marquee"` can't do this: it either loops
     * forever or, with `marqueeRepeatLimit="1"`, resets back to the start of
     * the text when the cycle ends. So we animate scrollX directly.
     *
     * If the message already fits inside the TextView with no overflow, we
     * just leave it at scrollX=0 — no animation needed.
     */
    private fun startHeartbeatScroll(fullText: String) {
        if (!isAdded || !::hudHeartbeatText.isInitialized) return
        val tv = hudHeartbeatText
        val paint = tv.paint ?: return
        val innerWidth = tv.width - tv.paddingLeft - tv.paddingRight
        if (innerWidth <= 0) {
            // Layout hasn't settled yet — try again after the next frame. We
            // only retry once to avoid an infinite post loop if the view
            // genuinely has zero width (e.g. hidden parent).
            tv.post { if (tv.width > 0) startHeartbeatScroll(fullText) }
            return
        }
        val textWidth = paint.measureText(fullText)
        // How far we need to scroll so the *right edge of the text* lines up
        // with the right edge of the visible area. Anything past that just
        // shows empty space on the right.
        val scrollEnd = (textWidth - innerWidth).toInt()
        if (scrollEnd <= 0) {
            // Text fits fully — nothing to scroll, just display it.
            tv.scrollX = 0
            return
        }
        // Android's default marquee speed is about 30 dp/s. Mirror that so
        // the feel matches what users are used to. Keep a sensible floor so
        // tiny overflows don't finish too abruptly to read.
        val density = tv.resources.displayMetrics.density
        val pxPerSecond = (30f * density).coerceAtLeast(40f)
        val durationMs = ((scrollEnd / pxPerSecond) * 1000f).toLong().coerceIn(1500L, 20_000L)
        val animator = ValueAnimator.ofInt(0, scrollEnd).apply {
            duration = durationMs
            // Small initial pause so the user can read the first few words
            // before the scroll starts moving.
            startDelay = 600L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                if (!isAdded || !::hudHeartbeatText.isInitialized) return@addUpdateListener
                tv.scrollX = anim.animatedValue as Int
            }
        }
        heartbeatScrollAnimator = animator
        animator.start()
        // NOTE: no repeat, no reset-on-end. When the animator finishes, the
        // TextView stays at scrollX = scrollEnd, which shows the last words
        // of the heartbeat. That's exactly what we want until the next
        // showHeartbeat() arrives and replaces the text.
    }

    /**
     * Show an OpenClaw heartbeat status message in the ticker under the clock.
     * The message scrolls horizontally once (if it overflows the view) and
     * then parks at the end so the tail of the latest heartbeat stays
     * readable until a new one arrives. Auto-hides after [displayMs]
     * (default 6 s) when used as a transient override. Pass 0 for the
     * persistent ticker. Pass null or blank with displayMs > 0 to clear the
     * transient message and fall back to the persistent ticker. Pass null
     * or blank with 0 to clear the persistent ticker entirely.
     */
    fun showHeartbeat(message: String?, displayMs: Long = 6_000L, scroll: Boolean = displayMs > 0L) {
        if (!isAdded || !::hudHeartbeatText.isInitialized) return
        uiHandler.removeCallbacks(hideHeartbeatRunnable)

        if (message.isNullOrBlank()) {
            if (displayMs <= 0L) {
                persistentHeartbeatMessage = null
            } else {
                transientHeartbeatMessage = null
                transientHeartbeatShouldScroll = true
            }
            renderHeartbeat(animateHide = transientHeartbeatMessage.isNullOrBlank() && persistentHeartbeatMessage.isNullOrBlank())
            return
        }

        if (displayMs <= 0L) {
            persistentHeartbeatMessage = message
        } else {
            transientHeartbeatMessage = message
            transientHeartbeatShouldScroll = scroll
            uiHandler.postDelayed(hideHeartbeatRunnable, displayMs)
        }
        renderHeartbeat()
    }

    /** Clear a transient heartbeat override and fall back to the persistent ticker. */
    fun hideHeartbeat() {
        showHeartbeat(null, displayMs = 1L)
    }

    /** Hide the OpenClaw heartbeat bar completely. */
    fun clearHeartbeat() {
        uiHandler.removeCallbacks(hideHeartbeatRunnable)
        persistentHeartbeatMessage = null
        transientHeartbeatMessage = null
        transientHeartbeatShouldScroll = true
        renderHeartbeat(animateHide = true)
    }

    fun setOpenClawGatewayStatus(status: OpenClawGatewayStatus) {
        if (!::hudOpenClawStatusIcon.isInitialized) return
        applyAgentGatewayStatus(hudOpenClawStatusIcon, status)
    }

    /**
     * Hermes parallel of [setOpenClawGatewayStatus]. Drives the second
     * 16dp HUD icon (winged-helmet glyph) that sits beside the crab.
     * Same red/green/hidden semantics; independent of the OC status so
     * both agents can be visible simultaneously.
     */
    fun setHermesGatewayStatus(status: OpenClawGatewayStatus) {
        if (!::hudHermesStatusIcon.isInitialized) return
        applyAgentGatewayStatus(hudHermesStatusIcon, status)
    }

    private fun applyAgentGatewayStatus(icon: ImageView?, status: OpenClawGatewayStatus) {
        if (!isAdded || icon == null) return
        when (status) {
            OpenClawGatewayStatus.HIDDEN -> {
                icon.visibility = View.GONE
                icon.clearColorFilter()
            }
            OpenClawGatewayStatus.GOOD -> {
                icon.visibility = View.VISIBLE
                icon.setColorFilter(0xFF00E676.toInt())
                icon.alpha = 1f
            }
            OpenClawGatewayStatus.BAD -> {
                icon.visibility = View.VISIBLE
                icon.setColorFilter(0xFFFF5B5B.toInt())
                icon.alpha = 1f
            }
        }
    }

    fun setTapClawResultReadyStatus(visible: Boolean) {
        if (!isAdded || !::hudTapClawResultBadge.isInitialized) return
        hudTapClawResultBadge.animate().cancel()
        if (!visible) {
            hudTapClawResultBadge.alpha = 1f
            hudTapClawResultBadge.visibility = View.GONE
            return
        }
        if (hudTapClawResultBadge.visibility != View.VISIBLE) {
            hudTapClawResultBadge.alpha = 0f
            hudTapClawResultBadge.scaleX = 0.92f
            hudTapClawResultBadge.scaleY = 0.92f
            hudTapClawResultBadge.visibility = View.VISIBLE
            hudTapClawResultBadge.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            hudTapClawResultBadge.alpha = 1f
            hudTapClawResultBadge.scaleX = 1f
            hudTapClawResultBadge.scaleY = 1f
        }
    }

    fun setStreamActiveIndicator(active: Boolean) {
        if (!this::chatStreamIndicator.isInitialized) return
        if (!active) {
            chatStreamIndicator.animate().cancel()
            chatStreamIndicator.alpha = 1f
            chatStreamIndicator.visibility = View.GONE
            return
        }
        // Skip if already pulsing — avoids restarting the animation loop needlessly
        if (chatStreamIndicator.visibility == View.VISIBLE) return
        chatStreamIndicator.visibility = View.VISIBLE
        chatStreamIndicator.alpha = 1f
        chatStreamIndicator.animate()
            .alpha(0.28f)
            .setDuration(520L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (!isAdded || !this::chatStreamIndicator.isInitialized || chatStreamIndicator.visibility != View.VISIBLE) {
                    return@withEndAction
                }
                chatStreamIndicator.animate()
                    .alpha(1f)
                    .setDuration(520L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        if (chatStreamIndicator.visibility == View.VISIBLE) {
                            setStreamActiveIndicator(true)
                        }
                    }
                    .start()
            }
            .start()
    }

    private var readerScrimRunnable: Runnable? = null

    private fun enterReaderMode(position: Int, animated: Boolean): Boolean {
        val text = adapter.getCardText(position)?.trim().orEmpty()
        if (text.isBlank()) return false
        readerModeActive = true

        // Cancel any in-flight exit animation to prevent conflicts
        readerOverlay.animate().cancel()

        // Hide everything behind the overlay BEFORE making it visible —
        // prevents a single-frame flash where both layers render at full opacity
        chatRecycler.visibility = View.INVISIBLE
        hudContainer.visibility = View.GONE
        setStreamActiveIndicator(false)
        hideVoiceOscilloscope()
        coreEyeContainer.visibility = View.GONE

        // Load content and reset scroll position. Use the shared spannable
        // builder so the reader view renders bullets with bold cyan URL
        // headers + summary the same way the chat row does. Without this
        // step the reader showed raw markdown text (literal `**` markers,
        // full https://... URLs) which made multi-URL cards unreadable.
        //
        // For multi-URL cards we also seed the trackpad focus-cycle: pre-
        // focus the first URL entry so a single tap opens it without an
        // extra swipe (the most common case), and the user can swipe to
        // advance to other entries.
        readerCachedLinkEntries = CardUrlExtractor.extract(text)
        readerFocusedUrlIndex = if (readerCachedLinkEntries.size >= 2) 0 else -1
        readerSwipeAccum = 0f
        readerFocusStepConsumed = false
        uiHandler.removeCallbacks(readerFocusReleaseRunnable)
        readerText.text = ChatCardSpannableBuilder.build(
            text,
            focusedEntryIndex = readerFocusedUrlIndex
        ) { tappedUrl ->
            // Open the tapped URL through the same path the row uses. We
            // exit reader mode first so the user lands on the browser
            // cleanly. (ClickableSpan only fires on screen-touch surfaces;
            // the trackpad path is handled separately in handleFocusedCardTap.)
            viewModel.openUrl(tappedUrl)
            readerCardUrl = null
            exitReaderMode(animated = false)
        }
        readerText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        readerText.highlightColor = android.graphics.Color.TRANSPARENT
        readerScroll.scrollTo(0, 0)
        readerAutoFollowStreaming = false

        // Now reveal the overlay — everything behind it is already hidden
        readerOverlay.visibility = View.VISIBLE
        updateReaderScrollScrims(animated = false)

        // First post: reset scroll after initial layout pass
        readerScroll.post {
            readerScroll.scrollTo(0, 0)
            readerAutoFollowStreaming = shouldAutoFollowReaderStream()
            updateReaderScrollScrims(animated = false)
        }
        // Second post: catch late text measurement to update bottom scrim
        readerScrimRunnable?.let { readerScroll.removeCallbacks(it) }
        readerScrimRunnable = Runnable {
            if (readerModeActive && isAdded) updateReaderScrollScrims(animated = true)
        }
        readerScroll.postDelayed(readerScrimRunnable!!, 150L)

        applyFocusVisuals(animate = false)
        if (!animated) {
            readerOverlay.alpha = 1f
            readerOverlay.scaleX = 1f
            readerOverlay.scaleY = 1f
            return true
        }
        readerOverlay.alpha = 0f
        readerOverlay.scaleX = 0.96f
        readerOverlay.scaleY = 0.96f
        readerOverlay.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        return true
    }

    /**
     * Re-render the reader's text with the current `readerFocusedUrlIndex`
     * applied (chevron prefix on the focused entry, bold cyan on all
     * entries). Then scroll to keep the focused entry comfortably in view.
     * Called after each trackpad swipe that advances the URL focus.
     */
    private fun rerenderReaderWithFocus() {
        if (!readerModeActive) return
        val pos = focusedCardIndex
        val text = adapter.getCardText(pos)?.trim().orEmpty()
        if (text.isBlank()) return
        readerText.text = ChatCardSpannableBuilder.build(
            text,
            focusedEntryIndex = readerFocusedUrlIndex
        ) { tappedUrl ->
            viewModel.openUrl(tappedUrl)
            readerCardUrl = null
            exitReaderMode(animated = false)
        }
        // Scroll the focused entry into the comfortable middle of the
        // reader so the user can see it without it being cut off at the
        // top or bottom edge. We approximate the entry's vertical position
        // by counting line-start offsets in the rendered text.
        readerText.post {
            if (!readerModeActive) return@post
            val layout = readerText.layout ?: return@post
            val rendered = readerText.text?.toString().orEmpty()
            // The focused entry's display title is the rendered string we
            // need to find. Look for the chevron-prefixed marker we just
            // injected so it can't collide with a literal entry title.
            val chevron = "▶ "
            val markerIdx = rendered.indexOf(chevron)
            if (markerIdx < 0) return@post
            val targetLine = layout.getLineForOffset(markerIdx)
            val targetY = layout.getLineTop(targetLine)
            val viewportH = readerScroll.height
            val center = (targetY - viewportH / 3).coerceAtLeast(0)
            readerScroll.smoothScrollTo(0, center)
            updateReaderScrollScrims(animated = true)
        }
    }

    private fun exitReaderMode(animated: Boolean) {
        if (!readerModeActive) return
        readerModeActive = false
        readerAutoFollowStreaming = false
        readerCardUrl = null
        readerCachedLinkEntries = emptyList()
        readerFocusedUrlIndex = -1
        readerSwipeAccum = 0f
        readerFocusStepConsumed = false
        uiHandler.removeCallbacks(readerFocusReleaseRunnable)
        uiHandler.removeCallbacks(pendingUrlOpenRunnable)
        // Cancel any pending scrim update from enterReaderMode
        readerScrimRunnable?.let { readerScroll.removeCallbacks(it) }
        readerScrimRunnable = null
        val finalize: () -> Unit = {
            readerOverlay.visibility = View.GONE
            readerOverlay.alpha = 1f
            readerOverlay.scaleX = 1f
            readerOverlay.scaleY = 1f
            updateReaderScrollScrims(animated = false)
            hudContainer.visibility = View.VISIBLE
            chatRecycler.visibility = View.VISIBLE
            chatRecycler.alpha = 1f
            if (!hudModeEnabled) {
                coreEyeContainer.visibility = if (coreEyeEnabled) View.VISIBLE else View.GONE
            }
            snapFocusedCard()
        }
        if (!animated) {
            finalize()
            return
        }
        readerOverlay.animate().cancel()
        readerOverlay.animate()
            .alpha(0f)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(250L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction(finalize)
            .start()
    }

    private fun updateReaderScrollScrims(animated: Boolean) {
        if (!this::readerTopScrim.isInitialized || !this::readerBottomScrim.isInitialized) return
        if (!readerModeActive || readerOverlay.visibility != View.VISIBLE) {
            setReaderScrimState(readerTopScrim, visible = false, animated = animated)
            setReaderScrimState(readerBottomScrim, visible = false, animated = animated)
            return
        }
        val showTop = readerScroll.canScrollVertically(-1)
        val showBottom = readerScroll.canScrollVertically(1)
        setReaderScrimState(readerTopScrim, visible = showTop, animated = animated)
        setReaderScrimState(readerBottomScrim, visible = showBottom, animated = animated)
    }

    private fun shouldAutoFollowReaderStream(): Boolean {
        if (!this::readerScroll.isInitialized || readerOverlay.visibility != View.VISIBLE) return false
        val child = readerScroll.getChildAt(0) ?: return true
        val viewportHeight =
            (readerScroll.height - readerScroll.paddingTop - readerScroll.paddingBottom).coerceAtLeast(0)
        if (viewportHeight == 0) return false
        return child.height <= viewportHeight || isReaderNearBottom()
    }

    private fun isReaderNearBottom(): Boolean {
        val child = readerScroll.getChildAt(0) ?: return true
        val remainingBelowViewport =
            child.bottom - (readerScroll.scrollY + readerScroll.height - readerScroll.paddingBottom)
        return remainingBelowViewport <= dpToPx(READER_STREAM_FOLLOW_THRESHOLD_DP)
    }

    private fun scrollReaderToComfortBottom(animated: Boolean) {
        val child = readerScroll.getChildAt(0) ?: return
        val targetY = (child.bottom - (readerScroll.height - readerScroll.paddingBottom)).coerceAtLeast(0)
        if (animated) {
            readerScroll.smoothScrollTo(0, targetY)
        } else {
            readerScroll.scrollTo(0, targetY)
        }
        readerAutoFollowStreaming = true
        updateReaderScrollScrims(animated = false)
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setReaderScrimState(scrim: View, visible: Boolean, animated: Boolean) {
        scrim.animate().cancel()
        if (!animated) {
            scrim.alpha = if (visible) 1f else 0f
            return
        }
        scrim.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(READER_SCRIM_ANIM_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    // ══════════════════════════════════════════════════════════════════════
    // HUD
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Reorder the HUD info cards (calendar, tasks, news) inside [hudContainer]
     * based on the user's preferred display order stored in preferences.
     * The first two children of hudContainer (time/battery row and connection row)
     * are kept in place; only the card wrappers are reordered.
     */
    private fun applyHudCardOrder() {
        val orderStr = viewModel.preferences.hudDisplayOrder
        val cardMap = mapOf(
            "calendar" to hudCalendarCard,
            "tasks" to hudTasksCard,
            "news" to hudNewsCard
        )
        // Remove all card views from hudContainer
        cardMap.values.forEach { hudContainer.removeView(it) }
        // Re-add in preferred order
        val orderedKeys = orderStr.split(",").map { it.trim() }.filter { it in cardMap }
        // Append any missing keys (in case prefs are incomplete)
        val allKeys = orderedKeys + cardMap.keys.filter { it !in orderedKeys }
        for (key in allKeys) {
            cardMap[key]?.let { hudContainer.addView(it) }
        }
        renderHudSnapshot()
    }

    private fun renderHudSnapshot() {
        val calendarSummary = externalCalendarSummary ?: viewModel.calendarSummary.value
        val tasksSummary = externalTasksSummary ?: viewModel.tasksSummary.value
        val newsSummary = externalNewsSummary ?: viewModel.newsSummary.value
        val airQualityState = externalAirQualityState ?: viewModel.airQualitySummary.value
        val radioState = externalRadioState ?: viewModel.radioSummary.value
        renderCalendarSummary(calendarSummary)
        renderTasksSummary(tasksSummary)
        renderNewsSummary(newsSummary)
        renderAirQualityState(airQualityState)
        renderRadioState(radioState)
    }

    private fun renderCalendarSummary(summary: String) {
        if (viewModel.preferences.hudShowCalendar && summary.isNotBlank()) {
            hudCalendar.text = summary
            hudCalendarCard.visibility = View.VISIBLE
        } else {
            hudCalendarCard.visibility = View.GONE
        }
    }

    private fun renderTasksSummary(summary: String) {
        if (viewModel.preferences.hudShowTasks && summary.isNotBlank()) {
            hudTasks.text = summary
            hudTasksCard.visibility = View.VISIBLE
        } else {
            hudTasksCard.visibility = View.GONE
        }
    }

    private fun renderNewsSummary(summary: String) {
        if (viewModel.preferences.hudShowNews && summary.isNotBlank()) {
            hudNews.text = summary
            hudNewsCard.visibility = View.VISIBLE
        } else {
            hudNewsCard.visibility = View.GONE
        }
    }

    private fun renderAirQualityState(state: MainViewModel.AirQualityHudState?) {
        if (state == null || state.text.isBlank()) {
            hudAqiText.visibility = View.GONE
        } else {
            hudAqiText.text = state.text
            hudAqiText.setTextColor(colorForAqi(state.aqi))
            hudAqiText.visibility = View.VISIBLE
        }
    }

    private fun renderRadioState(state: MainViewModel.RadioHudState?) {
        val stationName = state?.stationName?.trim().orEmpty()
        if (state == null || !state.playing || stationName.isBlank()) {
            hudRadioText.visibility = View.GONE
            return
        }
        hudRadioText.text = stationName
        hudRadioText.visibility = View.VISIBLE
    }

    // ══════════════════════════════════════════════════════════════════════
    // HUD bell notification list
    // ══════════════════════════════════════════════════════════════════════

    /** Unread badge + bell tint. Driven by NotificationCenter.unreadCount. */
    private fun renderBellBadge(count: Int) {
        if (!isAdded || !this::hudBellBadge.isInitialized) return
        if (count > 0) {
            hudBellBadge.text = if (count > 99) "99+" else count.toString()
            hudBellBadge.visibility = View.VISIBLE
            hudBellIcon.setColorFilter(BELL_TINT_UNREAD)
        } else {
            hudBellBadge.visibility = View.GONE
            hudBellIcon.setColorFilter(BELL_TINT_IDLE)
        }
    }

    fun isNotificationListOpen(): Boolean = notificationListOpen

    fun setNotificationActionListener(listener: NotificationActionListener?) {
        notificationActionListener = listener
    }

    fun toggleNotificationList() {
        if (!isAdded || !this::hudNotificationsCard.isInitialized || batterySavingDarkMode) {
            return
        }
        if (notificationListOpen) {
            closeNotificationList()
        } else {
            openNotificationList()
        }
    }

    private fun openNotificationList() {
        if (readerModeActive) {
            exitReaderMode(animated = false)
        }
        notificationListOpen = true
        focusedNotificationIndex = 0
        notifSwipeAccum = 0f
        notifFocusStepConsumed = false
        renderNotificationList()
        // Opening the list is "seeing" the notifications — clear the badge
        // (the list itself stays populated).
        NotificationCenter.markAllSeen()
        hudNotificationsCard.pivotY = 0f
        hudNotificationsCard.scaleY = 0f
        hudNotificationsCard.alpha = 0f
        hudNotificationsCard.visibility = View.VISIBLE
        hudNotificationsCard.animate()
            .scaleY(1f)
            .alpha(1f)
            .setDuration(NOTIF_ROLL_ANIM_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun closeNotificationList() {
        notificationListOpen = false
        uiHandler.removeCallbacks(notifFocusReleaseRunnable)
        hudNotificationsCard.pivotY = 0f
        hudNotificationsCard.animate()
            .scaleY(0f)
            .alpha(0f)
            .setDuration(NOTIF_ROLL_ANIM_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                hudNotificationsCard.visibility = View.GONE
                hudNotificationsCard.scaleY = 1f
                hudNotificationsCard.alpha = 1f
            }
            .start()
    }

    /** Rebuild the list rows. The focused row gets a ▶ marker, full alpha
     *  and up to three lines; the rest collapse to one ellipsized line. */
    private fun renderNotificationList() {
        if (!isAdded || !this::hudNotificationsList.isInitialized) return
        hudNotificationsList.removeAllViews()
        val items = renderedNotifications.take(NOTIF_LIST_MAX_VISIBLE)
        if (items.isEmpty()) {
            hudNotificationsList.addView(
                TextView(requireContext()).apply {
                    text = getString(R.string.hud_notifications_empty)
                    setTextColor(0x99FFFFFF.toInt())
                    textSize = 12f
                    setPadding(0, dpToPx(2f), 0, dpToPx(2f))
                }
            )
            return
        }
        if (focusedNotificationIndex > items.lastIndex) {
            focusedNotificationIndex = items.lastIndex
        }
        items.forEachIndexed { index, notification ->
            val focused = index == focusedNotificationIndex
            hudNotificationsList.addView(
                TextView(requireContext()).apply {
                    text = if (focused) {
                        "▶ ${notification.displayText()}"
                    } else {
                        notification.displayText()
                    }
                    setTextColor(colorForNotificationSource(notification.source))
                    alpha = if (focused) 1f else 0.72f
                    textSize = 12f
                    maxLines = if (focused) 3 else 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dpToPx(2f), 0, dpToPx(2f))
                    setOnClickListener { activateNotificationAt(index) }
                }
            )
        }
    }

    private fun colorForNotificationSource(source: NotificationCenter.Source): Int {
        return when (source) {
            NotificationCenter.Source.CALENDAR -> 0xFF00E5FF.toInt()
            NotificationCenter.Source.TASK -> 0xFF00E676.toInt()
            NotificationCenter.Source.HERMES -> BELL_TINT_UNREAD
            NotificationCenter.Source.OPENCLAW -> 0xFFFF8A65.toInt()
            NotificationCenter.Source.SYSTEM -> 0xFFFFFFFF.toInt()
        }
    }

    /** Trackpad swipes while the list is open: one focus step per physical
     *  swipe (accumulate-latch-release, mirroring reader-mode URL focus). */
    private fun handleNotificationListSwipe(deltaY: Float): Boolean {
        if (renderedNotifications.isEmpty()) return true
        uiHandler.removeCallbacks(notifFocusReleaseRunnable)
        uiHandler.postDelayed(notifFocusReleaseRunnable, NOTIF_FOCUS_RELEASE_RESET_MS)
        if (notifFocusStepConsumed) return true
        notifSwipeAccum += deltaY
        if (abs(notifSwipeAccum) >= NOTIF_FOCUS_STEP_DELTA) {
            val direction = if (notifSwipeAccum > 0f) 1 else -1
            notifSwipeAccum = 0f
            notifFocusStepConsumed = true
            val lastIndex =
                (renderedNotifications.take(NOTIF_LIST_MAX_VISIBLE).size - 1).coerceAtLeast(0)
            val next = (focusedNotificationIndex + direction).coerceIn(0, lastIndex)
            if (next != focusedNotificationIndex) {
                focusedNotificationIndex = next
                playNavigationTick()
                renderNotificationList()
            }
        }
        return true
    }

    /** Trackpad select while the list is open. Returns false when closed
     *  so the caller falls through to its normal tap handling. */
    fun activateFocusedNotification(): Boolean {
        if (!notificationListOpen) return false
        activateNotificationAt(focusedNotificationIndex)
        return true
    }

    private fun activateNotificationAt(index: Int) {
        val notification = renderedNotifications.take(NOTIF_LIST_MAX_VISIBLE).getOrNull(index)
            ?: return
        if (index != focusedNotificationIndex) {
            focusedNotificationIndex = index
            renderNotificationList()
        }
        notificationActionListener?.onNotificationActivated(notification)
        if (notificationListOpen) {
            closeNotificationList()
        }
    }

    fun isHudModeEnabled(): Boolean = hudModeEnabled
    fun isBatterySavingDarkMode(): Boolean = batterySavingDarkMode

    // ══════════════════════════════════════════════════════════════════════
    // Battery-saving dark mode
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Activates battery-saving dark mode: blanks the entire display except
     * battery % and charging indicator shown at 10% brightness. The app
     * continues to function normally (Gemini listening, streaming, etc.).
     *
     * Activated: double swipe-down within 2 s while New Chat is focused.
     * Deactivated: double swipe-up within 2 s while in chat panel.
     */
    private fun setBatterySavingDarkMode(enabled: Boolean) {
        if (!this::darkModeOverlay.isInitialized) return
        if (batterySavingDarkMode == enabled) return
        batterySavingDarkMode = enabled

        if (enabled) {
            // Exit any active sub-modes first
            if (readerModeActive) exitReaderMode(animated = false)
            if (hudModeEnabled) {
                hudModeEnabled = false
            }

            // Hide all visible UI layers
            hudContainer.visibility = View.GONE
            chatRecycler.visibility = View.GONE
            coreEyeContainer.visibility = View.GONE
            readerOverlay.visibility = View.GONE
            setStreamActiveIndicator(false)
            hideVoiceOscilloscope()

            // Refresh battery state into the dark mode overlay, then show it
            refreshDarkModeBattery()
            darkModeBatteryRow.alpha = DARK_MODE_BATTERY_ALPHA
            darkModeOverlay.visibility = View.VISIBLE
            updateDarkModeCameraIndicator()
        } else {
            // Hide dark mode overlay
            darkModeOverlay.visibility = View.GONE
            updateDarkModeCameraIndicator()

            // Restore normal display — go into HUD mode as the natural
            // idle state the user was in before (New Chat was focused)
            hudContainer.visibility = View.VISIBLE
            chatRecycler.visibility = View.VISIBLE
            coreEyeContainer.visibility = if (coreEyeEnabled) View.VISIBLE else View.GONE
            snapFocusedCard()
            applyFocusVisuals(animate = false)
        }

        // Reset double-swipe trackers
        lastSwipeDownStepMs = 0L
        lastSwipeUpStepMs = 0L

        // Notify the host activity so it can enable/disable backend
        // power-saving optimizations (HUD polling, camera, etc.)
        darkModeListener?.onDarkModeChanged(enabled)
    }

    fun setDarkModeCameraActive(active: Boolean) {
        darkModeCameraActive = active
        updateDarkModeCameraIndicator()
    }

    private fun updateDarkModeCameraIndicator() {
        if (!this::darkModeCameraDot.isInitialized) return
        darkModeCameraDot.visibility =
            if (batterySavingDarkMode && darkModeCameraActive) View.VISIBLE else View.GONE
    }

    /**
     * Copies current battery state into the dark mode overlay views.
     * Called on activation and periodically while dark mode is active.
     */
    fun refreshDarkModeBattery() {
        if (!isAdded || !this::darkModeBatteryText.isInitialized) return
        val batteryIntent = runCatching {
            requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = batteryIntent.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        )
        val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged
        val percent = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else -1

        val tint = when {
            charging -> 0xFF00FFFF.toInt()
            percent in 0..15 -> 0xFFFF5B5B.toInt()
            percent in 16..35 -> 0xFFFFB14A.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

        darkModeBatteryText.text = if (percent >= 0) "$percent%" else "--%"
        darkModeBatteryText.setTextColor(tint)
        darkModeBatteryIcon.setColorFilter(tint)
        darkModeChargingIcon.visibility = if (charging) View.VISIBLE else View.GONE
        darkModeChargingIcon.setColorFilter(tint)
    }

    fun setHudModeEnabled(enabled: Boolean) {
        if (!this::chatRecycler.isInitialized) return
        if (batterySavingDarkMode) return  // dark mode takes priority over HUD mode
        if (hudModeEnabled == enabled) return
        if (enabled && readerModeActive) {
            exitReaderMode(animated = false)
        }
        hudModeEnabled = enabled
        hudContainer.visibility = View.VISIBLE
        chatRecycler.visibility = if (enabled) View.GONE else View.VISIBLE
        if (enabled) {
            setStreamActiveIndicator(false)
        }
        if (enabled) {
            hideVoiceOscilloscope()
            coreEyeContainer.visibility = View.GONE
            coreEyeContainer.alpha = 1f
        } else {
            coreEyeContainer.visibility = if (coreEyeEnabled) View.VISIBLE else View.GONE
            snapFocusedCard()
        }
        updateOrbColumnVisibility()
    }

    fun syncHudSnapshot(
        calendarSummary: String,
        tasksSummary: String,
        newsSummary: String,
        airQualityState: MainViewModel.AirQualityHudState?,
        radioState: MainViewModel.RadioHudState? = null
    ) {
        if (!isAdded || !this::hudContainer.isInitialized) return
        externalCalendarSummary = calendarSummary
        externalTasksSummary = tasksSummary
        externalNewsSummary = newsSummary
        externalAirQualityState = airQualityState
        externalRadioState = radioState
        renderCalendarSummary(calendarSummary)
        renderTasksSummary(tasksSummary)
        renderNewsSummary(newsSummary)
        renderAirQualityState(airQualityState)
        renderRadioState(radioState ?: viewModel.radioSummary.value)
    }

    fun setConnectionStatus(status: ConnectionStatus) {
        if (!isAdded || !::hudConnectionDot.isInitialized || !::hudConnectionText.isInitialized || !::hudAiStatusBadge.isInitialized) return
        val (label, color, alpha) = when (status) {
            ConnectionStatus.IDLE -> Triple("—", 0xB3FFFFFF.toInt(), 0.72f)
            ConnectionStatus.CONNECTING -> Triple("…", 0xFFFFC857.toInt(), 0.95f)
            ConnectionStatus.GEMINI_CONNECTED -> Triple("G", 0xFF00E676.toInt(), 1f)
            ConnectionStatus.TOOLS_READY -> Triple("G", 0xFF00E676.toInt(), 1f)
            ConnectionStatus.ERROR -> Triple("ERR", 0xFFFF5B5B.toInt(), 1f)
        }
        hudConnectionText.text = label
        hudConnectionText.setTextColor(color)
        hudConnectionText.alpha = alpha

        val dot = (hudConnectionDot.background as? GradientDrawable) ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        dot.setColor(color)
        hudConnectionDot.background = dot
        hudConnectionDot.alpha = alpha

        val badge = (hudAiStatusBadge.background as? GradientDrawable) ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        badge.setColor(color)
        hudAiStatusBadge.background = badge
        hudAiStatusBadge.text = "G"
        hudAiStatusBadge.setTextColor(0xFFFFFFFF.toInt())
        hudAiStatusBadge.alpha = alpha
        hudAiStatusBadge.contentDescription = getString(R.string.hud_ai_status)
    }

    /**
     * Formerly toggled the 📘 HUD badge for an unread research report.
     * Retained as a no-op so any stale callers compile; the badge itself
     * has been removed from the layout along with the completion-verification
     * concept. Safe to delete this method once no callers remain.
     */
    fun setResearchReadyStatus(@Suppress("UNUSED_PARAMETER") visible: Boolean) {
        // Intentionally empty.
    }

    // ══════════════════════════════════════════════════════════════════════
    // CoreEye camera PIP
    // ══════════════════════════════════════════════════════════════════════

    fun setCoreEyeSurfaceListener(listener: CoreEyeSurfaceListener?) {
        coreEyeSurfaceListener = listener
        if (listener != null && coreEyeSurfaceReady) {
            listener.onSurfaceAvailable()
        }
    }

    fun setCardActionListener(listener: CardActionListener?) {
        cardActionListener = listener
    }

    fun setDarkModeListener(listener: DarkModeListener?) {
        darkModeListener = listener
    }

    fun isCoreEyeSurfaceReady(): Boolean = coreEyeSurfaceReady

    fun getCoreEyeSurfaceProvider(): Preview.SurfaceProvider? {
        if (!this::coreEyePreviewTexture.isInitialized || !coreEyeSurfaceReady) return null
        val executor = ContextCompat.getMainExecutor(requireContext())
        return Preview.SurfaceProvider { request ->
            val texture = coreEyePreviewTexture.surfaceTexture
            if (texture == null || !coreEyeSurfaceReady) {
                request.willNotProvideSurface()
                return@SurfaceProvider
            }
            texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            val surface = Surface(texture)
            request.provideSurface(surface, executor) { surface.release() }
        }
    }

    fun setCoreEyeCaptureEnabled(enabled: Boolean) {
        coreEyeEnabled = enabled
        if (!enabled) {
            uiHandler.removeCallbacks(coreEyeStreamTimeoutRunnable)
            setCoreEyeStreamingVisuals(active = false)
        }
        if (!hudModeEnabled) {
            coreEyeContainer.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        // When the camera is idle we hide the idle-placeholder icon entirely
        // — the orb itself is the on-screen "something is listening" cue.
        coreEyeIdleIcon.visibility = View.GONE
        updateOrbColumnVisibility()
    }

    fun onCoreEyeFrameStreamed() {
        if (!coreEyeEnabled) return
        setCoreEyeStreamingVisuals(active = true)
        uiHandler.removeCallbacks(coreEyeStreamTimeoutRunnable)
        uiHandler.postDelayed(coreEyeStreamTimeoutRunnable, 1500L)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Voice-activity orb (replaces the old line waveform)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Drive the earth-orb glow from MainActivity's existing voice level
     * pipeline.  The incoming [color] selects the halo variant:
     *   • red glow (`bg_orb_glow_red`) while the user is being recorded
     *   • blue glow (`bg_orb_glow_blue`) while Gemini is speaking
     *
     * [level] is the peak amplitude in the [0, 1] range.  It drives the
     * glow's alpha so the halo visibly pulses with speech loudness; the
     * orb itself stays at full opacity whenever either state is active.
     */
    fun pushVoiceOscilloscope(level: Float, color: Int) {
        if (!::orbGlow.isInitialized || !::coreEyeOrb.isInitialized) return
        // Respect the user's "hide chat orb" preference. When the orb is
        // disabled, suppress the voice-activity reveal entirely — no
        // glow, no orb, no column. Audio level still flows through other
        // surfaces (HUD ticker etc.) so the user isn't deaf to whether
        // Gemini is speaking; only this visual indicator is silenced.
        val ctx = context
        if (ctx != null && !AppPreferences(ctx).chatOrbVisible) {
            if (::cameraColumn.isInitialized) cameraColumn.visibility = View.GONE
            return
        }
        // Drop the waveform stub — it no longer renders anything.
        if (::inlineOscilloscope.isInitialized) {
            inlineOscilloscope.visibility = View.GONE
        }

        // Pick a halo drawable based on which colour the caller chose.
        // The exact value comparison mirrors MainActivity's
        // OSCILLOSCOPE_USER_COLOR / OSCILLOSCOPE_MODEL_COLOR constants but
        // also falls back to a simple red-vs-blue decision based on the
        // dominant channel so that future callers still work without code
        // changes here.
        val wantsRed = isRedVoiceColor(color)
        if (orbActiveColor != color) {
            orbActiveColor = color
            orbGlow.setBackgroundResource(
                if (wantsRed) R.drawable.bg_orb_glow_red
                else R.drawable.bg_orb_glow_blue
            )
            // The earth orb itself is intentionally left untouched —
            // it stays photorealistic and static while only the outer
            // halo carries the voice-activity colour (warm eerie red
            // while listening, warm eerie blue while Gemini speaks).
            // Defensive: wipe any filter that an earlier build might
            // have applied so upgrades from the previous version don't
            // carry a stale tint into the new "halo-only" look.
            coreEyeOrb.clearColorFilter()
        }

        // Voice activity should light the orb only. The camera column
        // reserves horizontal chat space, so keep it tied strictly to the
        // camera preview state; otherwise "listening" can leave old chat
        // cards indented even after the camera is off.
        updateOrbColumnVisibility()
        coreEyeOrb.animate().cancel()
        coreEyeOrb.alpha = 1f

        // Remap the raw 0..1 level onto a comfortable alpha range so even
        // very quiet speech keeps the halo visible.  The Gemini-speaking
        // (blue) halo is biased brighter than the user-recording (red)
        // halo so the "the model is talking to you" state reads clearly
        // from across the HUD — matching the request that the orb
        // glow more blue while Gemini speaks.
        val normalizedLevel = level.coerceIn(0f, 1f)
        val targetAlpha = if (wantsRed) {
            (0.35f + normalizedLevel * 0.65f).coerceIn(0f, 1f)
        } else {
            // Blue floor is higher (0.65) and the dynamic range is
            // compressed toward 1.0, so the halo sits at a bright,
            // confident glow even on quiet Gemini output.
            (0.65f + normalizedLevel * 0.35f).coerceIn(0f, 1f)
        }
        orbPendingLevel = targetAlpha
        orbGlowFadeAnimator?.cancel()
        orbGlowFadeAnimator = ValueAnimator.ofFloat(orbGlow.alpha, targetAlpha).apply {
            duration = 120L
            addUpdateListener { anim ->
                orbGlow.alpha = (anim.animatedValue as Float)
            }
            start()
        }
    }

    fun hideVoiceOscilloscope() {
        if (::inlineOscilloscope.isInitialized) {
            inlineOscilloscope.stop()
            inlineOscilloscope.visibility = View.GONE
        }
        if (!::orbGlow.isInitialized || !::coreEyeOrb.isInitialized) return

        orbActiveColor = 0
        // Clear any active colour filter so the next speaking session
        // starts from the natural earth palette.
        coreEyeOrb.clearColorFilter()
        orbGlowFadeAnimator?.cancel()
        orbGlowFadeAnimator = ValueAnimator.ofFloat(orbGlow.alpha, 0f).apply {
            duration = 180L
            addUpdateListener { anim ->
                orbGlow.alpha = (anim.animatedValue as Float)
            }
            start()
        }
        coreEyeOrb.animate().cancel()
        coreEyeOrb.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction { updateOrbColumnVisibility() }
            .start()
    }

    /**
     * True iff [color] is closer to red than to blue.  Handles both the
     * exact constants used by MainActivity and any stray values by simply
     * comparing the red channel against the blue channel.
     */
    private fun isRedVoiceColor(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val b = color and 0xFF
        return r > b
    }

    /**
     * Toggle the upper-left camera column purely from camera state and
     * keep the avatar / orb container permanently in the layout tree.
     *
     *   • [cameraColumn] is VISIBLE when the camera is on, GONE otherwise.
     *     The recycler is start-anchored to it; on GONE the constraint
     *     collapses (goneMarginStart=14dp) and older chat history reads
     *     full-width.
     *
     *   • [orbContainer] stays VISIBLE at all times so the avatar is a
     *     persistent screen element on the bottom-left. The orb image
     *     and halo inside it fade in/out via alpha during voice
     *     activity — that's their existing animation.
     *
     *   • The LATEST chat row gets a small left inset (60dp orb +
     *     8dp gutter = 68dp) so the streaming card flows around the
     *     avatar without losing readable width. Older rows render at
     *     margin 0 and visually tuck behind the elevated orb. When the
     *     camera is ON, the recycler is already shifted right of the
     *     camera column so the inset reads as a small extra gutter
     *     rather than a horizontal squeeze.
     */
    private fun updateOrbColumnVisibility() {
        if (!::cameraColumn.isInitialized) return
        val cameraVisible = coreEyeEnabled && !hudModeEnabled
        val cameraTarget = if (cameraVisible) View.VISIBLE else View.GONE
        if (cameraColumn.visibility != cameraTarget) {
            cameraColumn.visibility = cameraTarget
            chatRecycler.requestLayout()
        }
        if (::orbContainer.isInitialized && orbContainer.visibility != View.VISIBLE) {
            orbContainer.visibility = View.VISIBLE
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Focus management — pure index-driven, NO SnapHelper
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns [focusedCardIndex] clamped to valid content range.
     * If it was out of range, it is corrected in place.
     */
    private fun coerceFocusedIndex(): Int {
        val first = adapter.getFirstContentPosition()
        val last = adapter.getLastContentPosition()
        if (focusedCardIndex in first..last) return focusedCardIndex
        focusedCardIndex = last
        return focusedCardIndex
    }

    /**
     * Calculate the exact offset needed so the focused card center aligns with
     * the vertical center of the RecyclerView's visible content area (the region
     * between paddingTop and paddingBottom).  This keeps cards inside the
     * recycler bounds and prevents them from overlapping the HUD above.
     */
    private fun computeFocusOffsetPx(): Int {
        if (!this::chatRecycler.isInitialized) return 0
        val density = resources.displayMetrics.density
        val cardHeightPx = (CARD_HEIGHT_DP * density).toInt()
        val visibleHeight = chatRecycler.height - chatRecycler.paddingTop - chatRecycler.paddingBottom
        val centerOffset = (visibleHeight - cardHeightPx) / 2
        return centerOffset.coerceAtLeast(0)
    }

    /** Set [focusedCardIndex] and place that card at the fixed focus offset. */
    private fun focusCard(position: Int, animate: Boolean) {
        val previous = focusedCardIndex
        val first = adapter.getFirstContentPosition()
        val last = adapter.getLastContentPosition()
        focusedCardIndex = position.coerceIn(first, last)
        if (previous != RecyclerView.NO_POSITION && previous != focusedCardIndex && adapter.isContentPosition(previous)) {
            adapter.notifyItemChanged(previous)
        }
        if (adapter.isContentPosition(focusedCardIndex)) {
            adapter.notifyItemChanged(focusedCardIndex)
        }
        layoutManager.scrollToFocus(focusedCardIndex)
        chatRecycler.post {
            applyFocusVisuals(animate = animate)
            // Second pass after layout settles to catch cards that became
            // visible only after the scroll animation completed.
            chatRecycler.postDelayed({ applyFocusVisuals(animate = false) }, 250L)
        }
        tapBlockedUntilSnap = false
    }

    private fun snapFocusedCard(): Boolean {
        if (adapter.itemCount <= 0) return false
        val previous = focusedCardIndex
        focusedCardIndex = coerceFocusedIndex()
        if (previous != RecyclerView.NO_POSITION && previous != focusedCardIndex && adapter.isContentPosition(previous)) {
            adapter.notifyItemChanged(previous)
        }
        if (adapter.isContentPosition(focusedCardIndex)) {
            adapter.notifyItemChanged(focusedCardIndex)
        }
        layoutManager.scrollToFocus(focusedCardIndex)
        chatRecycler.post {
            applyFocusVisuals(animate = false)
            chatRecycler.postDelayed({ applyFocusVisuals(animate = false) }, 250L)
        }
        return true
    }

    /**
     * Walk every visible child and apply focused / unfocused styling.
     *
     * Uses [RecyclerView.getChildAt] instead of the LayoutManager's
     * findFirst/LastVisibleItemPosition so that views re-attached from
     * the RecyclerView cache (which are NOT rebound) are always caught.
     */
    private fun applyFocusVisuals(animate: Boolean) {
        if (!this::chatRecycler.isInitialized) return
        for (i in 0 until chatRecycler.childCount) {
            val child = chatRecycler.getChildAt(i) ?: continue
            val position = chatRecycler.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            if (!adapter.isContentPosition(position)) continue
            val focusTarget = child.findViewById<View>(R.id.messageBubble) ?: child
            val focused = position == focusedCardIndex
            applyFocusGlow(focusTarget, focused && !readerModeActive)

            // When reader mode is active, flatten all cards to Z=0 so nothing
            // can poke above the reader overlay (elevation 24dp).
            if (readerModeActive) {
                setCardState(focusTarget, CARD_UNFOCUSED_SCALE, 0f, 0f, false)
                continue
            }
            val targetScale = if (focused) CARD_FOCUS_SCALE else CARD_UNFOCUSED_SCALE
            val targetAlpha = if (focused) CARD_FOCUS_ALPHA else CARD_UNFOCUSED_ALPHA
            val targetZ = if (focused) CARD_FOCUS_Z else CARD_UNFOCUSED_Z
            setCardState(focusTarget, targetScale, targetAlpha, targetZ, animate)
        }
    }

    private fun setCardState(
        child: View,
        scale: Float,
        alpha: Float,
        z: Float,
        animate: Boolean
    ) {
        if (!animate) {
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = alpha
            child.translationZ = z
            return
        }
        val sameState =
            abs(child.scaleX - scale) < 0.01f &&
                abs(child.alpha - alpha) < 0.01f &&
                abs(child.translationZ - z) < 0.01f
        if (sameState) return

        child.animate().cancel()
        child.animate()
            .scaleX(scale)
            .scaleY(scale)
            .alpha(alpha)
            .translationZ(z)
            .setDuration(CARD_FOCUS_ANIM_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun applyFocusGlow(target: View, focused: Boolean) {
        if (!focused) {
            if (target.foreground != null) target.foreground = null
            return
        }
        val density = target.resources.displayMetrics.density
        val glow = (target.foreground as? GradientDrawable) ?: GradientDrawable()
        glow.shape = GradientDrawable.RECTANGLE
        glow.cornerRadius = CARD_FOCUS_GLOW_CORNER_DP * density
        glow.setColor(Color.TRANSPARENT)
        glow.setStroke(CARD_FOCUS_GLOW_PX, CARD_FOCUS_GLOW_COLOR)
        target.foreground = glow
    }

    // ══════════════════════════════════════════════════════════════════════
    // Startup binding
    // ══════════════════════════════════════════════════════════════════════

    private fun bindInitialHistoryFromSnapshot() {
        val initialCards = viewModel.getAssistantCardsSnapshot()
        renderedAssistantMessages = initialCards
        adapter.submitMessages(initialCards)
        lastMessageFingerprint = initialCards.joinToString("|") { it.text }.hashCode()
        suppressFirstCollectorFocus = true
        focusedCardIndex = adapter.getLastContentPosition()
        snapFocusedCard()
    }

    private fun playNavigationTick() {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.5f)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // CoreEye surface management
    // ══════════════════════════════════════════════════════════════════════

    private fun configureCoreEyeView() {
        coreEyeContainer.visibility = View.GONE
        coreEyePreviewTexture.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    coreEyeSurfaceReady = true
                    coreEyeSurfaceListener?.onSurfaceAvailable()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) = Unit

                override fun onSurfaceTextureDestroyed(
                    surface: android.graphics.SurfaceTexture
                ): Boolean {
                    coreEyeSurfaceReady = false
                    coreEyeSurfaceListener?.onSurfaceDestroyed()
                    return true
                }

                override fun onSurfaceTextureUpdated(
                    surface: android.graphics.SurfaceTexture
                ) = Unit
            }
        if (coreEyePreviewTexture.isAvailable) {
            coreEyeSurfaceReady = true
            coreEyeSurfaceListener?.onSurfaceAvailable()
        }
        coreEyeRing.visibility = View.GONE
        // The idle placeholder icon inside the camera frame has been
        // retired — the earth orb now represents idle voice state.
        coreEyeIdleIcon.visibility = View.GONE
        coreEyePreviewTexture.alpha = 1f
    }

    /**
     * Read the user's custom orb image (if any) plus the visibility
     * preference, and apply both to the chat-panel's coreEyeOrb ImageView.
     *
     * Image source resolution:
     *   • Custom orb file present  → load that bitmap.
     *   • No custom file            → fall back to R.drawable.earth_orb.
     *
     * Shape: a circular ViewOutlineProvider clip is applied so the orb
     * always renders perfectly round, regardless of what's in the file.
     * The companion app's cropper saves a SQUARE PNG (the bounding box
     * of the visible circle); the device-side clip enforces the round
     * appearance at render time. This means the user can upload any
     * aspect-ratio image and we never stretch or distort.
     *
     * Visibility: when the user has chosen to hide the orb entirely (via
     * the companion-app toggle), we force GONE on the cameraColumn and
     * suppress the alpha animations from re-showing it. The orb is the
     * voice-activity indicator, so when it's off the user just sees the
     * chat content uninterrupted.
     *
     * Called from onCreateView and on every onResume so changes made via
     * the companion app appear without restarting the app.
     */
    private fun applyChatOrbCustomization() {
        if (!::coreEyeOrb.isInitialized) return
        val context = context ?: return

        // Apply circular outline clip once (idempotent). Doing this here
        // rather than in the layout XML keeps the round behavior in code
        // alongside the bitmap-loading code, so it's harder to forget.
        coreEyeOrb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        coreEyeOrb.clipToOutline = true

        // Custom image, else default earth_orb. Bitmap decoding is
        // synchronous on the UI thread, but the file is small (~50–150 KB
        // PNG) and reads from internal storage, so it completes in <5 ms.
        // No need for an off-thread loader for an icon-sized asset.
        val store = OrbImageStore(context)
        if (store.hasCustom()) {
            val bitmap = runCatching {
                BitmapFactory.decodeFile(store.customFile().absolutePath)
            }.getOrNull()
            if (bitmap != null) {
                coreEyeOrb.setImageBitmap(bitmap)
            } else {
                Log.w("ChatPanel/Orb", "Custom orb file present but failed to decode; using default.")
                coreEyeOrb.setImageResource(com.rayneo.visionclaw.R.drawable.earth_orb)
            }
        } else {
            coreEyeOrb.setImageResource(com.rayneo.visionclaw.R.drawable.earth_orb)
        }

        // Visibility — separate from custom-image presence. When the user
        // wants the orb hidden entirely, force GONE on the column. The
        // animation paths in pushVoiceOscilloscope() will still run their
        // alpha animations on coreEyeOrb / orbGlow, but a GONE view does
        // not draw, so the user sees nothing during voice activity either.
        val prefs = AppPreferences(context)
        if (!prefs.chatOrbVisible) {
            if (::cameraColumn.isInitialized) cameraColumn.visibility = View.GONE
            coreEyeOrb.visibility = View.GONE
            if (::orbGlow.isInitialized) orbGlow.visibility = View.GONE
        } else {
            // Restore baseline visibility (the cameraColumn's voice-activity
            // logic decides when to flip GONE/VISIBLE). VISIBLE here just
            // means "eligible to be shown" — the alpha gate still controls
            // actual render until voice fires.
            coreEyeOrb.visibility = View.VISIBLE
            if (::orbGlow.isInitialized) orbGlow.visibility = View.VISIBLE
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Battery HUD
    // ══════════════════════════════════════════════════════════════════════

    private fun refreshBatteryStatusHud() {
        if (!isAdded || !::hudBatteryText.isInitialized || !::hudBatteryIcon.isInitialized || !::hudBatteryChargingIcon.isInitialized) return
        val batteryIntent = runCatching {
            requireContext().registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        }.getOrNull() ?: return

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = batteryIntent.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        )
        val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged

        val percent = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else -1
        hudBatteryText.text = if (percent >= 0) "$percent%" else "--%"

        val tint = when {
            charging -> 0xFF00FFFF.toInt()
            percent in 0..15 -> 0xFFFF5B5B.toInt()
            percent in 16..35 -> 0xFFFFB14A.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        hudBatteryIcon.alpha = if (percent >= 0) 1f else 0.5f
        hudBatteryText.alpha = if (percent >= 0) 1f else 0.5f
        hudBatteryIcon.setColorFilter(tint)
        hudBatteryChargingIcon.visibility = if (charging) View.VISIBLE else View.GONE
        hudBatteryChargingIcon.alpha = if (percent >= 0) 1f else 0.5f
        hudBatteryChargingIcon.setColorFilter(tint)
        hudBatteryText.setTextColor(tint)
    }

    private fun colorForAqi(aqi: Int?): Int {
        val value = aqi ?: return 0xCCFFFFFF.toInt()
        return when {
            value <= 50 -> 0xFF00E676.toInt()
            value <= 100 -> 0xFFFFEB3B.toInt()
            value <= 150 -> 0xFFFF9800.toInt()
            value <= 200 -> 0xFFFF5B5B.toInt()
            value <= 300 -> 0xFFBA68C8.toInt()
            else -> 0xFF8E24AA.toInt()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // CoreEye streaming ring animation
    // ══════════════════════════════════════════════════════════════════════

    private fun setCoreEyeStreamingVisuals(active: Boolean) {
        if (coreEyeStreaming == active) return
        coreEyeStreaming = active
        // The cyan streaming-ring overlay has been retired.  The burgundy
        // camera frame itself now carries the "camera is live" visual
        // signal, so we keep `coreEyeRing` permanently hidden and skip
        // the pulsing animation entirely.  Cancel any previously-started
        // animator and force the ring View hidden so toggling camera
        // streaming state never flashes the old cyan oval.
        coreEyePulseAnimator?.cancel()
        coreEyePulseAnimator = null
        coreEyeRing.alpha = 0f
        coreEyeRing.visibility = View.GONE
    }
}
