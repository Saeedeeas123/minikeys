package com.saeed.minikeys.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.saeed.minikeys.core.settings.KeyboardSettings
import com.saeed.minikeys.core.settings.KeyboardTheme

/**
 * The whole keyboard surface, drawn by hand on a single canvas instead of a tree of child views.
 *
 * Two reasons this is a single self-drawn view rather than a layout of buttons:
 *  - it renders identically in the IME window and inside the settings preview (same code path), and
 *  - it avoids nested horizontal layouts, which are a known launch-crash trigger on this MIUI build.
 *
 * The view is a pure presenter: it owns layout/press state and reports [Listener] callbacks.
 */
class MiniKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
    }

    var listener: Listener? = null

    private enum class ShiftState { OFF, ON, LOCKED }

    private data class PlacedKey(val key: KeyDef, val rect: RectF, val label: String)

    private var settings: KeyboardSettings = KeyboardSettings.DEFAULT
    private var theme: KeyboardTheme = KeyboardTheme.DEFAULT

    private val density: Float = resources.displayMetrics.density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val horizontalGap = dp(4f)
    private val verticalGap = dp(6f)
    private val sidePadding = dp(4f)
    private val topPadding = dp(6f)
    private val bottomPadding = dp(8f)

    /**
     * Bottom room reserved for the system navigation bar / gesture pill. Starts at 0 and is set from
     * real window insets the moment the view is attached.
     */
    private var systemBarBottomPaddingPx: Int = 0

    /*
     * Fallback for hosts/layers that report a 0 navigation-bar inset (seen with edge-to-edge IME
     * windows). Uses the platform's own bar height so the keyboard never relies on a guessed value.
     */
    private val navigationBarFallbackPx: Int = run {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private val cornerRadius = dp(7f)
    private var page: KeyboardPage = KeyboardPage.LETTERS
    private var shift: ShiftState = ShiftState.OFF
    private var enterLabel: String = DEFAULT_ENTER_LABEL

    private var geometry: List<PlacedKey> = emptyList()
    private var pressedKey: KeyDef? = null
    private var lastShiftTapMillis: Long = 0L
    private var backspaceRepeatFired: Boolean = false

    private val repeatHandler = Handler(Looper.getMainLooper())

    private val backspaceRepeat = object : Runnable {
        override fun run() {
            backspaceRepeatFired = true
            listener?.onBackspace()
            repeatHandler.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS)
        }
    }

    /** The height the keyboard asks for; the only place [KeyboardSettings.keyboardHeightDp] is used. */
    val desiredHeightPx: Int get() = (settings.keyboardHeightDp * density).toInt()

    /** Called by the IME service (on show and whenever the stored settings change) and by the preview. */
    fun applySettings(settings: KeyboardSettings) {
        this.settings = settings.normalized()
        this.theme = this.settings.theme
        rebuildGeometry()
        requestLayout()
        invalidate()
    }

    fun setEnterLabel(label: String) {
        val clean = label.trim().ifEmpty { DEFAULT_ENTER_LABEL }
        if (clean == enterLabel) return
        enterLabel = clean
        rebuildGeometry()
        invalidate()
    }

    fun showPage(page: KeyboardPage) {
        if (this.page == page) return
        this.page = page
        shift = ShiftState.OFF
        rebuildGeometry()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, desiredHeightPx)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(theme.keyboardBackground)

        for (placed in geometry) {
            val pressed = placed.key === pressedKey
            keyPaint.color = when {
                pressed -> theme.keyBackgroundPressed
                placed.key.special -> theme.specialKeyBackground
                else -> theme.keyBackground
            }
            canvas.drawRoundRect(placed.rect, cornerRadius, cornerRadius, keyPaint)

            val labelColor = when {
                pressed -> theme.accent
                placed.key.action == KeyAction.Shift && shift != ShiftState.OFF -> theme.accent
                else -> theme.keyText
            }
            drawLabel(canvas, placed, labelColor)
        }
    }

    private fun drawLabel(canvas: Canvas, placed: PlacedKey, color: Int) {
        val label = placed.label
        if (label.isBlank()) return

        labelPaint.color = color
        val nominal = sp(settings.keyTextSizeSp.toFloat())
        labelPaint.textSize = nominal

        // Shrink long labels (Done / Search / ?123) so they never overflow their key.
        val maxWidth = placed.rect.width() - dp(10f)
        val measured = labelPaint.measureText(label)
        if (measured > maxWidth && measured > 0f) {
            labelPaint.textSize = nominal * (maxWidth / measured)
        }

        val metrics = labelPaint.fontMetrics
        val baseline = placed.rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, placed.rect.centerX(), baseline, labelPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updatePressed(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressedKey != null || event.pointerCount == 1) updatePressed(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val key = keyAt(event.x, event.y)
                val repeatsHandledPress = pressedKey?.action == KeyAction.Backspace && backspaceRepeatFired
                clearPress()
                if (key != null && !repeatsHandledPress) fire(key)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                clearPress()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The same listener runs in every host (IME window and the settings preview) so both keep
        // the bottom row above the navigation bar / gesture pill.
        setOnApplyWindowInsetsListener { _, insets ->
            updateSystemBarBottomPadding(insets)
            insets
        }
        requestApplyInsets()
    }

    private fun updateSystemBarBottomPadding(insets: WindowInsets) {
        val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom
                .coerceAtLeast(navigationBarFallbackPx)
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom.coerceAtLeast(navigationBarFallbackPx)
        }
        if (bottom != systemBarBottomPaddingPx) {
            systemBarBottomPaddingPx = bottom
            rebuildGeometry()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        setOnApplyWindowInsetsListener(null)
        clearPress()
        super.onDetachedFromWindow()
    }

    private fun updatePressed(x: Float, y: Float) {
        val key = keyAt(x, y)
        if (key === pressedKey) return

        if (pressedKey?.action == KeyAction.Backspace) cancelBackspaceRepeat()
        pressedKey = key
        if (key != null) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            // A held backspace deletes continuously; a tap is committed on release instead.
            if (key.action == KeyAction.Backspace) startBackspaceRepeat()
        }
        invalidate()
    }

    private fun clearPress() {
        cancelBackspaceRepeat()
        pressedKey = null
        backspaceRepeatFired = false
        invalidate()
    }

    private fun startBackspaceRepeat() {
        cancelBackspaceRepeat()
        backspaceRepeatFired = false
        repeatHandler.postDelayed(backspaceRepeat, BACKSPACE_INITIAL_DELAY_MS)
    }

    private fun cancelBackspaceRepeat() {
        repeatHandler.removeCallbacks(backspaceRepeat)
    }

    private fun fire(key: KeyDef) {
        when (val action = key.action) {
            is KeyAction.Text -> {
                val text = if (action.letter && shift != ShiftState.OFF) {
                    action.value.uppercase()
                } else {
                    action.value
                }
                listener?.onText(text)
                if (action.letter && shift == ShiftState.ON) applyShift(ShiftState.OFF)
            }

            KeyAction.Backspace -> listener?.onBackspace()
            KeyAction.Enter -> listener?.onEnter()
            KeyAction.Shift -> toggleShift()
            KeyAction.ShowSymbols -> showPage(KeyboardPage.SYMBOLS)
            KeyAction.ShowLetters -> showPage(KeyboardPage.LETTERS)
        }
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        val doubleTap = now - lastShiftTapMillis <= SHIFT_DOUBLE_TAP_MS
        lastShiftTapMillis = now

        applyShift(
            when {
                shift == ShiftState.LOCKED -> ShiftState.OFF
                doubleTap -> ShiftState.LOCKED
                shift == ShiftState.ON -> ShiftState.OFF
                else -> ShiftState.ON
            }
        )
    }

    private fun applyShift(state: ShiftState) {
        if (shift == state) return
        shift = state
        rebuildGeometry()
        invalidate()
    }

    private fun keyAt(x: Float, y: Float): KeyDef? =
        geometry.firstOrNull { it.rect.contains(x, y) }?.key

    private fun rebuildGeometry() {
        val rows = KeyboardLayouts.rowsFor(page)
        val viewWidth = width
        val viewHeight = height

        if (viewWidth <= 0 || viewHeight <= 0 || rows.isEmpty()) {
            geometry = emptyList()
            return
        }

        val rowHeight = (
            viewHeight
                - topPadding
                - bottomPadding
                - systemBarBottomPaddingPx
                - verticalGap * (rows.size - 1)
            ) / rows.size
        if (rowHeight <= 0f) {
            geometry = emptyList()
            return
        }

        val availableWidth = viewWidth - sidePadding * 2f
        val placed = ArrayList<PlacedKey>(rows.sumOf { it.size })

        rows.forEachIndexed { rowIndex, keys ->
            val totalWeight = keys.sumOf { it.weight.toDouble() }.toFloat()
            val gaps = horizontalGap * (keys.size - 1)
            val widthPerWeight = (availableWidth - gaps) / totalWeight
            val top = topPadding + rowIndex * (rowHeight + verticalGap)
            var left = sidePadding

            for (key in keys) {
                val keyWidth = widthPerWeight * key.weight
                placed += PlacedKey(
                    key = key,
                    rect = RectF(left, top, left + keyWidth, top + rowHeight),
                    label = labelOf(key),
                )
                left += keyWidth + horizontalGap
            }
        }

        geometry = placed
    }

    private fun labelOf(key: KeyDef): String = when (val action = key.action) {
        is KeyAction.Text ->
            if (action.letter && shift != ShiftState.OFF) key.label.uppercase() else key.label

        KeyAction.Shift -> if (shift == ShiftState.LOCKED) "⇪" else "⇧"
        KeyAction.Enter -> enterLabel
        else -> key.label
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val DEFAULT_ENTER_LABEL = "⏎"
        const val BACKSPACE_INITIAL_DELAY_MS = 400L
        const val BACKSPACE_REPEAT_INTERVAL_MS = 70L
        const val SHIFT_DOUBLE_TAP_MS = 350L
    }
}