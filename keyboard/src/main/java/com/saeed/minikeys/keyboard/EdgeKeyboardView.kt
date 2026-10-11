package com.saeed.minikeys.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import com.saeed.minikeys.core.settings.KeyboardSettings
import com.saeed.minikeys.core.settings.KeyboardTheme

/**
 * Vertical keyboard: two independent, scrollable key columns hugging the left and right edges,
 * with the middle of the screen left free for the app.
 *
 * Implements the layout from Saeed's 2026-10-09 sketch:
 *   - keys are split between the two columns (left thumb / right thumb),
 *   - each column scrolls on its own when its content is taller than the screen,
 *   - the first key clears the notification bar and the last key clears the home bar,
 *   - a small arrow hint shows when a column can still scroll.
 *
 * Same self-drawn-canvas approach as [MiniKeyboardView] (no child views, no nested layouts), and it
 * reports through the same [MiniKeyboardView.Listener] so the IME needs no new input logic.
 */
class EdgeKeyboardView(context: Context) : View(context) {

    var listener: MiniKeyboardView.Listener? = null

    /** Notified when the view got a real size, so the IME can recompute its touchable region. */
    var onLayoutChanged: (() -> Unit)? = null

    private enum class ShiftState { OFF, ON, LOCKED }

    private enum class Column { LEFT, RIGHT }

    private data class PlacedKey(val key: KeyDef, val rect: RectF, val label: String)

    private var settings: KeyboardSettings = KeyboardSettings.DEFAULT
    private var theme: KeyboardTheme = KeyboardTheme.DEFAULT
    private var page: KeyboardPage = KeyboardPage.LETTERS
    private var shift: ShiftState = ShiftState.OFF
    private var enterLabel: String = DEFAULT_ENTER_LABEL

    private val density: Float = resources.displayMetrics.density
    private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val sideMargin = dp(6f)
    private val topPadding = dp(6f)
    private val bottomPadding = dp(6f)
    private val keyGap = dp(5f)
    private val cornerRadius = dp(9f)

    /** Room reserved for the system bars, filled from real insets the moment the view is attached. */
    private var systemTopPaddingPx: Int = 0
    private var systemBottomPaddingPx: Int = 0

    private val navigationBarFallbackPx: Int = run {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private val statusBarFallbackPx: Int = run {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private var leftScroll = 0f
    private var rightScroll = 0f
    private var leftContentHeight = 0f
    private var rightContentHeight = 0f

    private var leftKeys: List<PlacedKey> = emptyList()
    private var rightKeys: List<PlacedKey> = emptyList()

    private var pressedKey: KeyDef? = null
    private var lastShiftTapMillis: Long = 0L
    private var backspaceRepeatFired = false

    private var dragColumn: Column? = null
    private var dragStartY = 0f
    private var dragStartScroll = 0f
    private var dragging = false

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            backspaceRepeatFired = true
            listener?.onBackspace()
            repeatHandler.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS)
        }
    }

    /** Called by the IME service and by the settings preview, exactly like [MiniKeyboardView]. */
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

    /** The vertical keyboard always fills the window it is given. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, if (height > 0) height else suggestedMinimumHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
        onLayoutChanged?.invoke()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setOnApplyWindowInsetsListener { _, insets ->
            updateSystemBarPadding(insets)
            insets
        }
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        setOnApplyWindowInsetsListener(null)
        clearPress()
        repeatHandler.removeCallbacks(backspaceRepeat)
        super.onDetachedFromWindow()
    }

    private fun updateSystemBarPadding(insets: WindowInsets) {
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            top = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
                .coerceAtLeast(statusBarFallbackPx)
            bottom = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom
                .coerceAtLeast(navigationBarFallbackPx)
        } else {
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop.coerceAtLeast(statusBarFallbackPx)
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom.coerceAtLeast(navigationBarFallbackPx)
        }
        if (top != systemTopPaddingPx || bottom != systemBottomPaddingPx) {
            systemTopPaddingPx = top
            systemBottomPaddingPx = bottom
            rebuildGeometry()
            invalidate()
        }
    }

    // --- geometry -----------------------------------------------------------------------------

    private fun keyHeight(): Float {
        val fromText = sp(settings.keyTextSizeSp.toFloat()) * 2.5f
        return fromText.coerceAtLeast(dp(44f))
    }

    private fun columnWidth(): Float =
        (width * settings.columnWidthPercent / 100f).coerceAtLeast(dp(22f))

    /** Keys of the current page, split into the two thumbs: first half left, second half right. */
    private fun splitKeys(): Pair<List<KeyDef>, List<KeyDef>> {
        val flat = KeyboardLayouts.rowsFor(page).flatten()
        val half = (flat.size + 1) / 2
        val left = ArrayList<KeyDef>()
        val right = ArrayList<KeyDef>()
        left += KeyDef(KeyAction.Shift, "⇧")
        left += flat.take(half)
        left += KeyDef(KeyAction.Text(" ", letter = false), "space")
        left += if (page == KeyboardPage.LETTERS) {
            KeyDef(KeyAction.ShowSymbols, "?123")
        } else {
            KeyDef(KeyAction.ShowLetters, "ABC")
        }
        right += KeyDef(KeyAction.Backspace, "⌫")
        right += flat.drop(half)
        right += KeyDef(KeyAction.Text(",", letter = false), ",")
        right += KeyDef(KeyAction.Text(".", letter = false), ".")
        right += KeyDef(KeyAction.Enter, "⏎")
        return left to right
    }

    private fun rebuildGeometry() {
        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0) {
            leftKeys = emptyList()
            rightKeys = emptyList()
            return
        }

        val (leftDefs, rightDefs) = splitKeys()
        val keyH = keyHeight()
        val colW = columnWidth()
        val contentTop = topPadding + systemTopPaddingPx

        leftKeys = placeColumn(leftDefs, x = sideMargin, keyH = keyH, colW = colW, contentTop = contentTop)
        rightKeys = placeColumn(
            rightDefs,
            x = viewWidth - sideMargin - colW,
            keyH = keyH,
            colW = colW,
            contentTop = contentTop,
        )

        leftContentHeight = contentBottom(leftDefs.size, keyH)
        rightContentHeight = contentBottom(rightDefs.size, keyH)
        leftScroll = leftScroll.coerceIn(0f, maxScroll(leftContentHeight, viewHeight))
        rightScroll = rightScroll.coerceIn(0f, maxScroll(rightContentHeight, viewHeight))
    }

    private fun contentBottom(count: Int, keyH: Float): Float =
        topPadding + systemTopPaddingPx + count * keyH + (count - 1).coerceAtLeast(0) * keyGap +
            bottomPadding + systemBottomPaddingPx

    private fun maxScroll(contentHeight: Float, viewHeight: Int): Float =
        (contentHeight - viewHeight).coerceAtLeast(0f)

    private fun placeColumn(
        keys: List<KeyDef>,
        x: Float,
        keyH: Float,
        colW: Float,
        contentTop: Float,
    ): List<PlacedKey> {
        val placed = ArrayList<PlacedKey>(keys.size)
        var top = contentTop
        for (key in keys) {
            placed += PlacedKey(
                key = key,
                rect = RectF(x, top, x + colW, top + keyH),
                label = labelOf(key),
            )
            top += keyH + keyGap
        }
        return placed
    }

    private fun labelOf(key: KeyDef): String = when (val action = key.action) {
        is KeyAction.Text -> if (action.letter && shift != ShiftState.OFF) key.label.uppercase() else key.label
        KeyAction.Shift -> if (shift == ShiftState.LOCKED) "⇪" else "⇧"
        KeyAction.Enter -> enterLabel
        else -> key.label
    }

    // --- drawing ------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The middle of the window is deliberately NOT painted: only the two side strips get a
        // background, so the app behind the keyboard stays fully visible in between.
        drawColumnBackdrop(canvas, leftKeys, leftScroll)
        drawColumnBackdrop(canvas, rightKeys, rightScroll)
        drawColumn(canvas, leftKeys, leftScroll)
        drawColumn(canvas, rightKeys, rightScroll)
        drawScrollHints(canvas)
    }

    /** Paints only the strip behind one column (never the middle of the screen). */
    private fun drawColumnBackdrop(canvas: Canvas, keys: List<PlacedKey>, scroll: Float) {
        if (keys.isEmpty()) return
        val first = keys.first().rect
        val last = keys.last().rect
        val top = (first.top - scroll).coerceAtLeast(0f)
        val bottom = (last.bottom - scroll).coerceAtMost(height.toFloat())
        if (bottom <= top) return
        keyPaint.color = theme.keyboardBackground
        canvas.drawRoundRect(
            RectF(first.left - dp(3f), top, first.right + dp(3f), bottom),
            cornerRadius,
            cornerRadius,
            keyPaint,
        )
    }

    private fun drawColumn(canvas: Canvas, keys: List<PlacedKey>, scroll: Float) {
        for (placed in keys) {
            val top = placed.rect.top - scroll
            val bottom = placed.rect.bottom - scroll
            if (bottom < 0f || top > height.toFloat()) continue

            val rect = RectF(placed.rect.left, top, placed.rect.right, bottom)
            val pressed = placed.key === pressedKey
            keyPaint.color = when {
                pressed -> theme.keyBackgroundPressed
                placed.key.special -> theme.specialKeyBackground
                else -> theme.keyBackground
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)

            val labelColor = when {
                pressed -> theme.accent
                placed.key.action == KeyAction.Shift && shift != ShiftState.OFF -> theme.accent
                else -> theme.keyText
            }
            drawLabel(canvas, placed.label, rect, labelColor)
        }
    }

    private fun drawLabel(canvas: Canvas, label: String, rect: RectF, color: Int) {
        if (label.isBlank()) return
        labelPaint.color = color
        val nominal = sp(settings.keyTextSizeSp.toFloat())
        labelPaint.textSize = nominal
        val maxWidth = rect.width() - dp(10f)
        val measured = labelPaint.measureText(label)
        if (measured > maxWidth && measured > 0f) {
            labelPaint.textSize = nominal * (maxWidth / measured)
        }
        val metrics = labelPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, rect.centerX(), baseline, labelPaint)
    }

    /** Small arrows at the top/bottom of a column that still has hidden keys (the sketch's arrows). */
    private fun drawScrollHints(canvas: Canvas) {
        hintPaint.color = theme.keyText
        hintPaint.textSize = dp(14f)
        hintPaint.alpha = 150

        val colW = columnWidth()
        val leftX = sideMargin + colW / 2f
        val rightX = width - sideMargin - colW / 2f
        val topY = dp(14f)
        val bottomY = height - dp(8f)

        if (leftScroll > 0f) canvas.drawText(UP_ARROW, leftX, topY, hintPaint)
        if (leftScroll < maxScroll(leftContentHeight, height)) canvas.drawText(DOWN_ARROW, leftX, bottomY, hintPaint)
        if (rightScroll > 0f) canvas.drawText(UP_ARROW, rightX, topY, hintPaint)
        if (rightScroll < maxScroll(rightContentHeight, height)) canvas.drawText(DOWN_ARROW, rightX, bottomY, hintPaint)
        hintPaint.alpha = 255
    }

    // --- touch --------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Middle of the screen is NOT ours: do not consume, do not press anything.
                if (!isInsideStrip(event.x)) {
                    clearPress()
                    dragColumn = null
                    dragging = false
                    return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                val column = columnAt(event.x)
                dragColumn = column
                dragStartY = event.y
                dragStartScroll = if (column == Column.LEFT) leftScroll else rightScroll
                dragging = false
                updatePressed(column, event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val column = dragColumn ?: return true
                if (!dragging && kotlin.math.abs(event.y - dragStartY) > touchSlop) {
                    dragging = true
                    clearPress()
                }
                if (!isInsideStrip(event.x) && !dragging) {
                    clearPress()
                    return false
                }
                if (dragging) {
                    val delta = dragStartScroll - (event.y - dragStartY)
                    if (column == Column.LEFT) {
                        leftScroll = delta.coerceIn(0f, maxScroll(leftContentHeight, height))
                    } else {
                        rightScroll = delta.coerceIn(0f, maxScroll(rightContentHeight, height))
                    }
                    invalidate()
                } else {
                    updatePressed(column, event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val column = dragColumn
                val key = if (column != null && !dragging && isInsideStrip(event.x)) {
                    keyAt(column, event.x, event.y)
                } else {
                    null
                }
                val repeatsHandledPress = pressedKey?.action == KeyAction.Backspace && backspaceRepeatFired
                clearPress()
                dragColumn = null
                dragging = false
                if (key != null && !repeatsHandledPress) fire(key)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                clearPress()
                dragColumn = null
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun columnAt(x: Float): Column =
        if (x < width / 2f) Column.LEFT else Column.RIGHT

    private fun keysOf(column: Column): List<PlacedKey> =
        if (column == Column.LEFT) leftKeys else rightKeys

    private fun scrollOf(column: Column): Float =
        if (column == Column.LEFT) leftScroll else rightScroll

    /**
     * Hit test for one column. BOTH axes are checked: the middle of the screen belongs to the app,
     * so a tap outside the column's own x-range must never resolve to a key.
     */
    private fun keyAt(column: Column, x: Float, y: Float): KeyDef? {
        val scroll = scrollOf(column)
        return keysOf(column).firstOrNull {
            val top = it.rect.top - scroll
            val bottom = it.rect.bottom - scroll
            x >= it.rect.left && x <= it.rect.right && y >= top && y <= bottom
        }?.key
    }

    /** True when x falls inside one of the two key strips (the only touchable area). */
    fun isInsideStrip(x: Float): Boolean {
        val colW = columnWidth()
        return x <= sideMargin + colW || x >= width - sideMargin - colW
    }

    private fun updatePressed(column: Column, x: Float, y: Float) {
        val key = keyAt(column, x, y)
        if (key === pressedKey) return
        if (pressedKey?.action == KeyAction.Backspace) cancelBackspaceRepeat()
        pressedKey = key
        if (key != null) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                val text = if (action.letter && shift != ShiftState.OFF) action.value.uppercase() else action.value
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

    fun showPage(page: KeyboardPage) {
        if (this.page == page) return
        this.page = page
        shift = ShiftState.OFF
        leftScroll = 0f
        rightScroll = 0f
        rebuildGeometry()
        invalidate()
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

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val DEFAULT_ENTER_LABEL = "⏎"
        const val UP_ARROW = "▲"
        const val DOWN_ARROW = "▼"
        const val BACKSPACE_INITIAL_DELAY_MS = 400L
        const val BACKSPACE_REPEAT_INTERVAL_MS = 70L
        const val SHIFT_DOUBLE_TAP_MS = 350L
    }
}
