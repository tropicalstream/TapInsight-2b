package com.rayneo.visionclaw.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.rayneo.visionclaw.R
import kotlin.math.abs

class CustomKeyboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
        LinearLayout(context, attrs) {
    private var keys: MutableList<Button> = mutableListOf()
    private var currentRow = 0
    private var currentColumn = 0

    private var tempRow = 0
    private var tempColumn = 0

    // Dynamic text color support
    private var defaultTextColor: Int = Color.WHITE

    fun setCustomTextColor(color: Int) {
        defaultTextColor = color
        keys.forEach { it.setTextColor(color) }
        updateKeyFocus()
    }

    fun getCustomTextColor(): Int = defaultTextColor

    private var isAnchoredMode = false

    private enum class KeyboardMode {
        LETTERS,
        SYMBOLS;

        fun toggle(): KeyboardMode = if (this == LETTERS) SYMBOLS else LETTERS
    }

    private data class KeyboardLayout(val rows: List<List<String>>, val dynamicKeys: List<String>)

    private val keyboardLayouts =
            mapOf(
                    KeyboardMode.LETTERS to
                            KeyboardLayout(
                                    rows =
                                            listOf(
                                                    listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
                                                    listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
                                                    listOf("Z", "X", "C", "V", "B", "N", "M", ".", "/")
                                            ),
                                    dynamicKeys = listOf("@")
                            ),
                    KeyboardMode.SYMBOLS to
                            KeyboardLayout(
                                    rows =
                                            listOf(
                                                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                                                    listOf("@", "#", "$", "_", "&", "-", "+", "(", ")"),
                                                    listOf("*", "'", ":", ";", "!", "?", "<", ">", "")
                                            ),
                                    dynamicKeys = listOf("\u25C0") // Left Arrow
                                    )
            )

    private val currentLayout: KeyboardLayout
        get() = keyboardLayouts.getValue(currentMode)

    private var currentMode = KeyboardMode.LETTERS

    private var firstMove = true

    private var isUpperCase = true
    private var isCapsLocked = false
    private var lastShiftPressTime = 0L
    private val doubleTapShiftTimeout = 300L
    private var lastEmittedChar: String? = null

    private var hideButton: Button? = null
    private var navAccumulatorX = 0f
    private var navAccumulatorY = 0f
    private val navStepThresholdPx = 56f
    private var lastNavMoveAtMs = 0L
    private val navMoveDebounceMs = 110L
    private val clickFeedbackDurationMs = 90L
    private val clickFeedbackColor = Color.WHITE
    private val clickFeedbackTextColor = Color.BLACK
    private var clickedKey: Button? = null
    private var clickFeedbackUntil = 0L

    interface OnKeyboardActionListener {
        fun onKeyPressed(key: String)
        fun onBackspacePressed()
        fun onEnterPressed()
        fun onHideKeyboard()
        fun onClearPressed()
        fun onMoveCursorLeft()
        fun onMoveCursorRight()
    }

    private var listener: OnKeyboardActionListener? = null

    fun setOnKeyboardActionListener(listener: OnKeyboardActionListener) {
        this.listener = listener
        Log.d("CustomKeyboardView", "Listener set: $listener")
    }

    init {
        orientation = VERTICAL

        tempRow = currentRow
        tempColumn = currentColumn

        layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.BOTTOM
                }

        LayoutInflater.from(context).inflate(R.layout.keyboard_layout, this, true)

        post {
            initializeKeys()
            findHideButton()
            updateKeyFocus()

            updateCapsButtonText()
        }

        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isEnabled = true

        setOnTouchListener(null)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            val keyboardLayout = getChildAt(0) as? LinearLayout
            keyboardLayout?.let { layout ->
                for (i in 0 until layout.childCount) {
                    val row = layout.getChildAt(i) as? LinearLayout
                    row?.let { rowLayout ->
                        for (j in 0 until rowLayout.childCount) {
                            val button = rowLayout.getChildAt(j) as? Button
                            button?.invalidate()
                        }
                        rowLayout.invalidate()
                    }
                }
                layout.invalidate()
            }
        }
    }

    private fun handleButtonClick(button: Button) {
        val buttonId = button.id
        val buttonLabel = button.text.toString()

        val now = SystemClock.uptimeMillis()
        clickedKey = button
        clickFeedbackUntil = now + clickFeedbackDurationMs
        if (button.isAttachedToWindow) {
            updateKeyFocus()
        }

        postDelayed(
                {
                    if (SystemClock.uptimeMillis() >= clickFeedbackUntil) {
                        clickedKey = null
                        clickFeedbackUntil = 0L
                    }
                    if (button.isAttachedToWindow) {
                        updateKeyFocus()
                    }

                    when (buttonId) {
                        R.id.btn_caps -> {
                            toggleCase()
                        }
                        R.id.btn_switch -> {
                            toggleKeyboardMode()
                        }
                        R.id.btn_hide -> {
                            listener?.onHideKeyboard()
                            return@postDelayed
                        }
                        R.id.btn_backspace -> {
                            listener?.onBackspacePressed()
                            lastEmittedChar = null
                        }
                        R.id.btn_enter -> {
                            listener?.onEnterPressed()
                            return@postDelayed
                        }
                        R.id.btn_space -> {
                            listener?.onKeyPressed(" ")
                            if (lastEmittedChar in listOf(".", "?", "!")) {
                                if (!isUpperCase && !isCapsLocked) {
                                    isUpperCase = true
                                    updateKeyboardKeys()
                                }
                            }
                            lastEmittedChar = " "
                        }
                        R.id.btn_clear -> {
                            listener?.onClearPressed()
                            lastEmittedChar = null
                        }
                        R.id.button_left_dynamic -> handleDynamicButtonClick(button.id)
                        else -> {
                            if (buttonLabel == "<") {
                                listener?.onMoveCursorLeft()
                            } else if (buttonLabel == ">") {
                                listener?.onMoveCursorRight()
                            } else {
                                listener?.onKeyPressed(buttonLabel)

                                lastEmittedChar = buttonLabel

                                if (isUpperCase &&
                                                !isCapsLocked &&
                                                currentMode == KeyboardMode.LETTERS
                                ) {
                                    isUpperCase = false
                                    updateKeyboardKeys()
                                }
                            }
                        }
                    }

                    if (!isAnchoredMode && isAttachedToWindow) {
                        updateKeyFocus()
                    }
                },
                clickFeedbackDurationMs
        )
    }

    private fun toggleKeyboardMode() {
        currentMode = currentMode.toggle()
        updateKeyboardKeys()
    }

    private fun handleDynamicButtonClick(buttonId: Int) {
        when (currentMode) {
            KeyboardMode.LETTERS -> {
                val index =
                        when (buttonId) {
                            R.id.button_left_dynamic -> 0
                            else -> return
                        }
                currentLayout.dynamicKeys.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                    listener?.onKeyPressed(if (isUpperCase) it.uppercase() else it)
                }
            }
            KeyboardMode.SYMBOLS ->
                    when (buttonId) {
                        R.id.button_left_dynamic -> listener?.onMoveCursorLeft()
                    }
        }
    }

    private fun updateKeyboardKeys() {
        val keyboardLayout = getChildAt(0) as? LinearLayout ?: return
        val layoutConfig = currentLayout

        keyboardLayout.children.take(layoutConfig.rows.size).forEachIndexed { rowIndex, rowView ->
            val rowLayout = rowView as? LinearLayout ?: return@forEachIndexed
            val keyRow = layoutConfig.rows[rowIndex]
            var keyIndex = 0
            rowLayout.children.forEach { child ->
                val button = child as? Button ?: return@forEach
                if (button.id in specialButtonIds) return@forEach

                val keyText = keyRow.getOrNull(keyIndex).orEmpty()
                keyIndex++

                if (keyText.isEmpty()) {
                    button.visibility = View.GONE
                } else {
                    button.text =
                            if (currentMode == KeyboardMode.LETTERS) {
                                if (isUpperCase) keyText.uppercase() else keyText.lowercase()
                            } else {
                                keyText
                            }
                    button.visibility = View.VISIBLE
                }
            }
        }

        val dynamicRow =
                keyboardLayout.children.elementAtOrNull(layoutConfig.rows.size) as? LinearLayout
        dynamicRow?.let { rowLayout ->
            val leftDynamicButton = rowLayout.findViewById<Button>(R.id.button_left_dynamic)
            val dynamicKeys = layoutConfig.dynamicKeys
            configureDynamicButton(leftDynamicButton, dynamicKeys.getOrNull(0))
        }

        adjustCurrentColumn()
        updateSwitchButtonText()
        updateCapsButtonVisibility()
        postInvalidate()
        requestLayout()
    }

    private fun configureDynamicButton(
            button: Button?,
            label: String?,
            isVisibleOverride: Boolean? = null
    ) {
        button ?: return
        val shouldShow = isVisibleOverride ?: !label.isNullOrBlank()
        button.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (shouldShow && !label.isNullOrEmpty()) {
            button.text = label
        }
    }

    private fun adjustCurrentColumn() {
        val keyboardLayout = getKeyboardLayout()
        keyboardLayout?.let { layout ->
            val row = layout.getChildAt(currentRow) as? LinearLayout
            val visibleButtons =
                    row?.children?.filter { it.visibility == View.VISIBLE }?.toList() ?: emptyList()
            val maxColumns = visibleButtons.size
            if (currentColumn >= maxColumns) {
                currentColumn = maxColumns - 1
            }
            if (currentColumn < 0) {
                currentColumn = 0
            }
        }
    }

    private fun updateCapsButtonText() {
        val capsButton = findViewById<Button>(R.id.btn_caps)
        capsButton?.let {
            it.text =
                    when {
                        isCapsLocked -> "CAPS"
                        isUpperCase -> "ABC"
                        else -> "abc"
                    }
            if (isCapsLocked) {
                it.setTextColor(Color.CYAN)
            } else {
                it.setTextColor(defaultTextColor)
            }
            it.invalidate()
        }
    }

    private fun updateSwitchButtonText() {
        val switchButton = findViewById<Button>(R.id.btn_switch)
        switchButton?.let {
            it.text = if (currentMode == KeyboardMode.LETTERS) "123" else "ABC"
            it.invalidate()
        }
    }

    private fun updateCapsButtonVisibility() {
        val capsButton = findViewById<Button>(R.id.btn_caps)
        capsButton?.visibility =
                if (currentMode == KeyboardMode.LETTERS) View.VISIBLE else View.GONE
        capsButton?.invalidate()
    }

    private fun initializeKeys() {
        keys.clear()

        if (childCount > 0) {
            val keyboardLayout = getChildAt(0) as? LinearLayout
            keyboardLayout?.let { layout ->
                for (i in 0 until layout.childCount) {
                    val row = layout.getChildAt(i) as? LinearLayout
                    row?.let { rowLayout ->
                        for (j in 0 until rowLayout.childCount) {
                            val button = rowLayout.getChildAt(j) as? Button
                            button?.let {
                                keys.add(it)
                                if (it.id !in specialButtonIds) {
                                    it.tag = it.text.toString().uppercase()
                                }
                                it.isClickable = false
                                it.isFocusable = false
                                it.setBackgroundColor(Color.DKGRAY)
                                it.setTextColor(defaultTextColor)
                            }
                        }
                    }
                }
            }
        }

        keys.find { it.id == R.id.btn_caps }?.let { capsButton ->
            capsButton.text =
                    when {
                        isCapsLocked -> "CAPS"
                        isUpperCase -> "ABC"
                        else -> "abc"
                    }
        }
    }

    private val specialButtonIds =
            setOf(
                    R.id.btn_hide,
                    R.id.btn_space,
                    R.id.btn_backspace,
                    R.id.btn_enter,
                    R.id.btn_switch,
                    R.id.btn_caps,
                    R.id.btn_clear,
                    R.id.button_left_dynamic
            )

    private fun toggleCase() {
        if (currentMode != KeyboardMode.LETTERS) return

        val now = SystemClock.uptimeMillis()

        if (isCapsLocked) {
            isCapsLocked = false
            isUpperCase = false
        } else {
            if (now - lastShiftPressTime < doubleTapShiftTimeout) {
                isCapsLocked = true
                isUpperCase = true
            } else {
                isUpperCase = !isUpperCase
            }
        }

        lastShiftPressTime = now
        updateKeyboardKeys()
        updateCapsButtonText()
    }

    fun updateKeyFocus() {
        val defaultBackground = Color.DKGRAY
        val hoverBackground = Color.parseColor("#FFC107")
        val now = SystemClock.uptimeMillis()

        keys.forEach { button ->
            when {
                button == clickedKey && now < clickFeedbackUntil -> {
                    button.setBackgroundColor(clickFeedbackColor)
                    button.setTextColor(clickFeedbackTextColor)
                }
                button == hoveredKey -> {
                    button.setBackgroundColor(hoverBackground)
                    button.setTextColor(Color.BLACK)
                }
                else -> {
                    button.setBackgroundColor(defaultBackground)
                    button.setTextColor(defaultTextColor)
                }
            }
            if (button.id == R.id.btn_hide) {
                val tint =
                        if (button == hoveredKey) Color.BLACK else ContextCompat.getColor(context, android.R.color.white)
                button.foreground?.setTint(tint)
            }
            button.invalidate()
        }

        invalidate()
        requestLayout()
    }

    private fun findHideButton() {
        hideButton = keys.find { it.id == R.id.btn_hide }
    }

    private fun getFocusedKey(): Button? {
        val keyboard = getKeyboardLayout()
        val row = keyboard?.getChildAt(currentRow) as? LinearLayout
        val visibleButtons =
                row?.children?.filter { it.visibility == View.VISIBLE }?.toList() ?: emptyList()
        return visibleButtons.getOrNull(currentColumn) as? Button
    }

    private fun getKeyboardLayout(): LinearLayout? {
        return if (childCount > 0) getChildAt(0) as? LinearLayout else null
    }

    fun setAnchoredMode(anchored: Boolean) {
        isAnchoredMode = anchored
        updateKeyFocus()
    }

    fun focusHideButton() {
        val target = hideButton ?: keys.find { it.id == R.id.btn_hide } ?: return
        hoveredKey = target
        val rows = visibleButtonGrid()
        rows.forEachIndexed { rowIndex, row ->
            val colIndex = row.indexOf(target)
            if (colIndex >= 0) {
                currentRow = rowIndex
                currentColumn = colIndex
                return@forEachIndexed
            }
        }
        navAccumulatorX = 0f
        navAccumulatorY = 0f
        updateKeyFocus()
    }

    fun handleTrackpadSwipe(deltaX: Float, deltaY: Float): Boolean {
        if (visibility != View.VISIBLE) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastNavMoveAtMs < navMoveDebounceMs) return false

        if (abs(deltaX) >= abs(deltaY)) {
            navAccumulatorX += deltaX
            navAccumulatorY = 0f
        } else {
            navAccumulatorY += deltaY
            navAccumulatorX = 0f
        }

        if (abs(navAccumulatorX) >= navStepThresholdPx) {
            val direction = if (navAccumulatorX > 0f) 1 else -1
            moveSelection(horizontal = direction, vertical = 0)
            navAccumulatorX = 0f
            navAccumulatorY = 0f
            lastNavMoveAtMs = now
            return true
        }
        if (abs(navAccumulatorY) >= navStepThresholdPx) {
            // Finger moves up (negative deltaY) -> move selection up.
            val direction = if (navAccumulatorY < 0f) -1 else 1
            moveSelection(horizontal = 0, vertical = direction)
            navAccumulatorX = 0f
            navAccumulatorY = 0f
            lastNavMoveAtMs = now
            return true
        }
        return false
    }

    fun performFocusedTap() {
        val targetKey = if (!isAnchoredMode) hoveredKey else (hoveredKey ?: getFocusedKey())
        targetKey?.let { button ->
            handleButtonClick(button)
            updateKeyFocus()
        }
    }

    private fun moveSelection(horizontal: Int, vertical: Int) {
        val rows = visibleButtonGrid()
        if (rows.isEmpty()) return

        val hovered = hoveredKey
        if (hovered != null) {
            rows.forEachIndexed { rowIndex, row ->
                val colIndex = row.indexOf(hovered)
                if (colIndex >= 0) {
                    currentRow = rowIndex
                    currentColumn = colIndex
                    return@forEachIndexed
                }
            }
        }

        currentRow = currentRow.coerceIn(0, rows.lastIndex)
        currentColumn = currentColumn.coerceIn(0, rows[currentRow].lastIndex)

        if (vertical != 0) {
            val nextRow = (currentRow + vertical).coerceIn(0, rows.lastIndex)
            currentRow = nextRow
            currentColumn = currentColumn.coerceIn(0, rows[nextRow].lastIndex)
        }
        if (horizontal != 0) {
            val nextCol = (currentColumn + horizontal).coerceIn(0, rows[currentRow].lastIndex)
            currentColumn = nextCol
        }

        hoveredKey = rows[currentRow][currentColumn]
        updateKeyFocus()
    }

    private fun visibleButtonGrid(): List<List<Button>> {
        val keyboard = getKeyboardLayout() ?: return emptyList()
        val rows = mutableListOf<List<Button>>()
        for (i in 0 until keyboard.childCount) {
            val row = keyboard.getChildAt(i) as? LinearLayout ?: continue
            val visible = row.children.filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }.toList()
            if (visible.isNotEmpty()) {
                rows.add(visible)
            }
        }
        return rows
    }

    // Hover extraction (simplified)
    private var hoveredKey: Button? = null

    fun updateHover(x: Float, y: Float) {
        val key = getKeyAtPosition(x, y)
        if (hoveredKey != key) {
            hoveredKey = key
            updateKeyFocus()
        }
    }

    fun clearHover() {
        if (hoveredKey != null) {
            hoveredKey = null
            updateKeyFocus()
        }
    }

    private fun getKeyAtPosition(x: Float, y: Float): Button? {
        val keyboard = getKeyboardLayout() ?: return null
        val kX = x - keyboard.x
        val kY = y - keyboard.y

        for (i in 0 until keyboard.childCount) {
            val row = keyboard.getChildAt(i) as? LinearLayout ?: continue
            val rX = kX - row.x
            val rY = kY - row.y

            if (rY < 0 || rY > row.height) continue

            for (j in 0 until row.childCount) {
                val button = row.getChildAt(j) as? Button ?: continue
                if (button.visibility != View.VISIBLE) continue

                if (rX >= button.left && rX <= button.right) {
                    return button
                }
            }
        }
        return null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { updateKeyFocus() }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            post {
                findHideButton()
                focusHideButton()
            }
        }
    }
}
