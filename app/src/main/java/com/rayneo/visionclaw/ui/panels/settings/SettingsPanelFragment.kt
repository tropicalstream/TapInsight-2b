package com.rayneo.visionclaw.ui.panels.settings

import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.rayneo.visionclaw.MainActivity
import com.rayneo.visionclaw.R
import com.rayneo.visionclaw.core.audio.AudioController
import com.rayneo.visionclaw.core.storage.Bookmark
import com.rayneo.visionclaw.ui.MainViewModel
import com.rayneo.visionclaw.ui.panels.TrackpadPanel
import kotlin.math.abs

class SettingsPanelFragment : Fragment(), TrackpadPanel {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var root: ScrollView
    private lateinit var geminiKeyInput: EditText
    private lateinit var calendarKeyInput: EditText
    private lateinit var calendarIdInput: EditText
    private lateinit var openClawInput: EditText
    private lateinit var saveSettingsButton: Button

    private lateinit var bookmarkTitleInput: EditText
    private lateinit var bookmarkUrlInput: EditText
    private lateinit var addBookmarkButton: Button
    private lateinit var bookmarksContainer: LinearLayout

    private lateinit var musicVolumeSeek: SeekBar
    private lateinit var musicMuteSwitch: Switch
    private lateinit var ttsVolumeSeek: SeekBar
    private lateinit var ttsMuteSwitch: Switch

    private lateinit var audioController: AudioController
    private val focusableControls = mutableListOf<View>()
    private var focusedControlIndex = 0
    private var trackpadScrollAccumulator = 0f
    private var lastScrollDirection = 0
    private var lastFocusStepAtMs = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings_panel, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view.findViewById(R.id.settingsRoot)
        geminiKeyInput = view.findViewById(R.id.geminiKeyInput)
        calendarKeyInput = view.findViewById(R.id.calendarKeyInput)
        calendarIdInput = view.findViewById(R.id.calendarIdInput)
        openClawInput = view.findViewById(R.id.openClawInput)
        saveSettingsButton = view.findViewById(R.id.saveSettingsButton)

        bookmarkTitleInput = view.findViewById(R.id.bookmarkTitleInput)
        bookmarkUrlInput = view.findViewById(R.id.bookmarkUrlInput)
        addBookmarkButton = view.findViewById(R.id.addBookmarkButton)
        bookmarksContainer = view.findViewById(R.id.bookmarksContainer)

        musicVolumeSeek = view.findViewById(R.id.musicVolumeSeek)
        musicMuteSwitch = view.findViewById(R.id.musicMuteSwitch)
        ttsVolumeSeek = view.findViewById(R.id.ttsVolumeSeek)
        ttsMuteSwitch = view.findViewById(R.id.ttsMuteSwitch)

        audioController = AudioController(requireContext(), viewModel.preferences)

        bindFromPreferences()
        bindListeners()
        renderBookmarks()
        rebuildTrackpadFocusableControls(resetToFirst = true)
    }

    override fun onTrackpadScroll(deltaY: Float): Boolean {
        if (focusableControls.isEmpty()) {
            root.scrollBy(0, (deltaY * FALLBACK_SCROLL_MULTIPLIER).toInt())
            return true
        }

        val magnitude = abs(deltaY)
        if (magnitude < MIN_SCROLL_SIGNAL) return true

        // Map trackpad Y movement to intuitive field traversal.
        // Up swipe moves to previous field, down swipe to next field.
        val direction = if (deltaY > 0f) -1 else 1

        if (lastScrollDirection != 0 && lastScrollDirection != direction) {
            trackpadScrollAccumulator = 0f
        }
        lastScrollDirection = direction

        // RayNeo MotionEvent deltas are typically large (tens of px per frame),
        // so normalize and cap to avoid jumping through the entire form at once.
        val normalizedDelta = (magnitude / RAW_DELTA_PER_STEP).coerceAtMost(MAX_NORMALIZED_DELTA_PER_EVENT)
        trackpadScrollAccumulator += normalizedDelta
        val accumulatedSteps = trackpadScrollAccumulator.toInt()

        if (accumulatedSteps > 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastFocusStepAtMs < MIN_STEP_INTERVAL_MS) return true
            lastFocusStepAtMs = now
            trackpadScrollAccumulator = 0f
            val steps = accumulatedSteps.coerceAtMost(MAX_STEPS_PER_EVENT)
            moveTrackpadFocus(direction * steps)
            Log.d(
                TAG,
                "Trackpad deltaY=$deltaY normalized=$normalizedDelta steps=${direction * steps} index=$focusedControlIndex"
            )
        } else {
            root.scrollBy(0, (direction * FALLBACK_SCROLL_MULTIPLIER).toInt())
        }
        return true
    }

    override fun onTrackpadSelect(): Boolean {
        val target = focusableControls.getOrNull(focusedControlIndex) ?: return false
        return when (target) {
            is EditText -> {
                target.requestFocus()
                target.isCursorVisible = true
                target.performClick()
                target.setSelection(target.text?.length ?: 0)
                showIme(target)
                true
            }
            is Button -> {
                (activity as? MainActivity)?.exitTextInputMode()
                target.requestFocus()
                target.performClick()
                true
            }
            is Switch -> {
                (activity as? MainActivity)?.exitTextInputMode()
                target.requestFocus()
                target.performClick()
                true
            }
            is SeekBar -> {
                (activity as? MainActivity)?.exitTextInputMode()
                target.requestFocus()
                true
            }
            else -> {
                (activity as? MainActivity)?.exitTextInputMode()
                target.requestFocus()
                target.performClick()
                true
            }
        }
    }

    override fun onTextInputFromHold(text: String): Boolean {
        val selectedControl = focusableControls.getOrNull(focusedControlIndex) as? EditText
        val focusedControl = requireActivity().currentFocus as? EditText
        val target = selectedControl ?: focusedControl ?: geminiKeyInput
        val existing = target.text?.toString().orEmpty()
        val joiner = if (existing.isBlank()) "" else " "
        target.setText(existing + joiner + text)
        target.setSelection(target.text?.length ?: 0)
        target.requestFocus()
        return true
    }

    override fun onHeadYaw(yawDegrees: Float) {
        root.translationX = (yawDegrees * 1.1f).coerceIn(-34f, 34f)
    }

    override fun getReadableText(): String {
        val bookmarks = viewModel.preferences.getBookmarks()
        return buildString {
            append("Settings panel. ")
            append("Bookmarks count: ${bookmarks.size}. ")
            append(if (viewModel.preferences.musicMuted) "Music muted. " else "Music unmuted. ")
            append(if (viewModel.preferences.ttsMuted) "TTS muted." else "TTS unmuted.")
        }
    }

    private fun bindFromPreferences() {
        val prefs = viewModel.preferences
        geminiKeyInput.setText(prefs.geminiApiKey)
        calendarKeyInput.setText(prefs.calendarApiKey)
        calendarIdInput.setText(prefs.calendarId)
        openClawInput.setText(prefs.openClawEndpoint)

        musicVolumeSeek.max = 100
        musicVolumeSeek.progress = (audioController.getMusicVolumeNormalized() * 100f).toInt()
        musicMuteSwitch.isChecked = prefs.musicMuted

        ttsVolumeSeek.progress = (prefs.ttsVolume * 100f).toInt()
        ttsMuteSwitch.isChecked = prefs.ttsMuted
    }

    private fun bindListeners() {
        saveSettingsButton.setOnClickListener {
            val prefs = viewModel.preferences
            prefs.geminiApiKey = geminiKeyInput.text?.toString().orEmpty().trim()
            prefs.calendarApiKey = calendarKeyInput.text?.toString().orEmpty().trim()
            prefs.calendarId = calendarIdInput.text?.toString().orEmpty().trim()
            prefs.openClawEndpoint = openClawInput.text?.toString().orEmpty().trim()
            viewModel.refreshCalendarNow()
            Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
        }

        addBookmarkButton.setOnClickListener {
            val title = bookmarkTitleInput.text?.toString().orEmpty().trim()
            val url = bookmarkUrlInput.text?.toString().orEmpty().trim()
            if (title.isBlank() || url.isBlank()) {
                Toast.makeText(requireContext(), "Bookmark needs title + URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
            viewModel.preferences.addBookmark(Bookmark(title = title, url = normalizedUrl))
            bookmarkTitleInput.text?.clear()
            bookmarkUrlInput.text?.clear()
            renderBookmarks()
        }

        musicVolumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioController.setMusicVolume(progress / 100f)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        musicMuteSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            audioController.setMusicMuted(checked)
        }

        ttsVolumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.preferences.ttsVolume = progress / 100f
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        ttsMuteSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            viewModel.preferences.ttsMuted = checked
        }
    }

    private fun renderBookmarks() {
        val bookmarks = viewModel.preferences.getBookmarks()
        bookmarksContainer.removeAllViews()

        if (bookmarks.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "No bookmarks"
                setTextColor(resources.getColor(R.color.text_primary, null))
            }
            bookmarksContainer.addView(empty)
            rebuildTrackpadFocusableControls(resetToFirst = false)
            return
        }

        bookmarks.forEach { bookmark ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
            }

            val openButton = Button(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = bookmark.title
                setOnClickListener {
                    viewModel.openUrl(bookmark.url)
                }
            }

            val removeButton = Button(requireContext()).apply {
                text = "Remove"
                inputType = InputType.TYPE_NULL
                setOnClickListener {
                    viewModel.preferences.removeBookmark(bookmark.url)
                    renderBookmarks()
                }
            }

            row.addView(openButton)
            row.addView(removeButton)
            bookmarksContainer.addView(row)
        }

        rebuildTrackpadFocusableControls(resetToFirst = false)
    }

    private fun rebuildTrackpadFocusableControls(resetToFirst: Boolean) {
        val currentlyFocused = view?.findFocus()

        focusableControls.clear()
        focusableControls.addAll(
            listOf(
                geminiKeyInput,
                calendarKeyInput,
                calendarIdInput,
                openClawInput,
                saveSettingsButton,
                bookmarkTitleInput,
                bookmarkUrlInput,
                addBookmarkButton,
                musicVolumeSeek,
                musicMuteSwitch,
                ttsVolumeSeek,
                ttsMuteSwitch
            )
        )

        bookmarksContainer.children.forEach { row ->
            if (row is ViewGroup) {
                row.children.forEach { child ->
                    if (child is Button) {
                        focusableControls.add(child)
                    }
                }
            }
        }

        focusableControls.forEach { control ->
            control.isFocusable = true
            control.isFocusableInTouchMode = true
            control.setOnFocusChangeListener(null)
        }

        trackpadScrollAccumulator = 0f
        lastScrollDirection = 0
        lastFocusStepAtMs = 0L

        val focusedIdx = focusableControls.indexOf(currentlyFocused)
        focusedControlIndex = when {
            resetToFirst -> 0
            focusedIdx >= 0 -> focusedIdx
            focusedControlIndex in focusableControls.indices -> focusedControlIndex
            else -> 0
        }

        applyTrackpadSelection()
    }

    private fun moveTrackpadFocus(step: Int) {
        if (focusableControls.isEmpty()) return
        val nextIndex = (focusedControlIndex + step)
            .coerceIn(0, focusableControls.lastIndex)
        if (nextIndex == focusedControlIndex) return
        focusedControlIndex = nextIndex
        applyTrackpadSelection()
    }

    private fun applyTrackpadSelection() {
        if (focusableControls.isEmpty()) return
        focusableControls.forEachIndexed { index, control ->
            val selected = index == focusedControlIndex
            control.alpha = if (selected) 1f else 0.42f
            control.scaleX = if (selected) 1.08f else 0.96f
            control.scaleY = if (selected) 1.08f else 0.96f
            control.translationX = if (selected) 24f else 0f
            control.elevation = if (selected) 14f else 0f
        }

        val target = focusableControls[focusedControlIndex]
        target.requestFocus()
        ensureVisible(target)
    }

    private fun ensureVisible(target: View) {
        root.post {
            val targetY = (target.top - root.height / 3).coerceAtLeast(0)
            root.smoothScrollTo(0, targetY)
        }
    }

    private fun showIme(target: EditText) {
        target.post {
            (activity as? MainActivity)?.enterTextInputMode()
            val imm = context?.getSystemService(InputMethodManager::class.java)
            target.requestFocusFromTouch()
            val shown = imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT) == true
            if (!shown) {
                target.postDelayed({
                    imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
                }, 80L)
            }
        }
    }

    companion object {
        private const val TAG = "SettingsPanel"
        private const val MIN_SCROLL_SIGNAL = 2f
        private const val RAW_DELTA_PER_STEP = 70f
        private const val MAX_NORMALIZED_DELTA_PER_EVENT = 2.5f
        private const val MAX_STEPS_PER_EVENT = 1
        private const val MIN_STEP_INTERVAL_MS = 120L
        private const val FALLBACK_SCROLL_MULTIPLIER = 42f
    }
}
