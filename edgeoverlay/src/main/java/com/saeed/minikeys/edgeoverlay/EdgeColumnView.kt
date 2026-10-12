package com.saeed.minikeys.edgeoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import com.saeed.minikeys.core.settings.KeyboardTheme
import com.saeed.minikeys.core.settings.MiniKeysLog
import com.saeed.minikeys.core.settings.OverlaySettings
import com.saeed.minikeys.keyboard.KeyAction
import com.saeed.minikeys.keyboard.KeyDef
import com.saeed.minikeys.keyboard.KeyDispatcher
import com.saeed.minikeys.keyboard.KeyboardLayouts
import com.saeed.minikeys.keyboard.KeyboardPage
import kotlin.math.abs

/**
 * ONE vertical key strip of the edge overlay (left or right).
 *
 * The window is exactly as wide as the strip, so the middle of the screen is never covered and stays
 * fully interactive for the app underneath.
 *
 * The keyboard is laid out the way it was asked for: the whole page is ONE sequence and the two
 * edges deal it out alternately — first key top-left, second key top-right, third key left again …
 * so the pair of strips together reproduce the horizontal keyboard vertically. Pressing a page key
 * (numbers/symbols, emoji, shortcuts) or caps rewrites the strips from the very top-left.
 */
class EdgeColumnView(context: Context) : View(context) {

    /** Which edge this strip is attached to; only affects the rounded corner side. */
    var side: Side = Side.LEFT

    /**
     * Page and caps are shared by both strips, so a strip never changes them itself — it asks the
     * service, which then pushes the new state into both views. Otherwise the two halves would drift
     * apart on the first page change.
     */
    var onPageRequest: ((KeyboardPage) -> Unit)? = null
    var onCapsRequest: (() -> Unit)? = null

    enum class Side { LEFT, RIGHT }

    private data class PlacedKey(val key: KeyDef, val rect: RectF, val label: String)

    private var settings: OverlaySettings = OverlaySettings.DEFAULT
    private var theme: KeyboardTheme = KeyboardTheme.DEFAULT
    private var page: KeyboardPage = KeyboardPage.LETTERS
    private var caps: Boolean = false
    private var scroll = 0f
    private var contentHeight = 0f
    private var placed: List<PlacedKey> = emptyList()
    private var pressedKey: KeyDef? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val sideMargin = dp(4f)
    private val keyGap = dp(4f)
    private val topPadding = dp(6f)
    private val bottomPadding = dp(6f)
    private val cornerRadius = dp(10f)

    private var systemTopPadding = 0
    private var systemBottomPadding = 0

    private val statusBarFallback: Int = run {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private val navBarFallback: Int = run {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private var dragStartY = 0f
    private var dragStartScroll = 0f
    private var dragging = false

    fun applySettings(settings: OverlaySettings) {
        this.settings = settings.normalized()
        this.theme = KeyboardTheme.DEFAULT
        rebuild()
        invalidate()
    }

    /**
     * New shared state from the service. A page or caps change always rewinds the strip to its first
     * key, which is what makes the switch start from the top-left of the screen.
     */
    fun setState(page: KeyboardPage, caps: Boolean) {
        if (this.page == page && this.caps == caps) return
        val pageChanged = this.page != page
        this.page = page
        this.caps = caps
        if (pageChanged) scroll = 0f
        rebuild()
        invalidate()
    }

    /**
     * The activity in front changed, so the shortcut page has a different key set: rebuild the strip
     * from the new keys. Called through `ActivityShortcuts`' listener, from the overlay service.
     */
    fun refreshKeys() {
        rebuild()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                top = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
                    .coerceAtLeast(statusBarFallback)
                bottom = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom
                    .coerceAtLeast(navBarFallback)
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop.coerceAtLeast(statusBarFallback)
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom.coerceAtLeast(navBarFallback)
            }
            if (top != systemTopPadding || bottom != systemBottomPadding) {
                systemTopPadding = top
                systemBottomPadding = bottom
                rebuild()
                invalidate()
            }
            insets
        }
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        setOnApplyWindowInsetsListener(null)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    /**
     * This strip's half of the page: the page is one flat sequence and this strip takes every other
     * key — left takes the even positions (0, 2, 4 …), right the odd ones (1, 3, 5 …).
     */
    private fun keysForStrip(): List<KeyDef> {
        val sequence = KeyboardLayouts.verticalKeys(page, caps)
        val firstIndex = if (side == Side.LEFT) 0 else 1
        return sequence.filterIndexed { index, _ -> index % 2 == firstIndex }
    }

    private fun keyHeightPx(): Float = (settings.keyHeightDp * density).coerceAtLeast(dp(24f))

    private fun rebuild() {
        if (width <= 0 || height <= 0) {
            placed = emptyList()
            return
        }
        val keys = keysForStrip()
        val keyH = keyHeightPx()
        val left = sideMargin
        val right = width - sideMargin
        var top = topPadding + systemTopPadding
        val list = ArrayList<PlacedKey>(keys.size)
        for (key in keys) {
            list += PlacedKey(key, RectF(left, top, right, top + keyH), labelOf(key))
            top += keyH + keyGap
        }
        placed = list
        contentHeight = top + bottomPadding + systemBottomPadding
        scroll = scroll.coerceIn(0f, (contentHeight - height).coerceAtLeast(0f))
    }

    private fun labelOf(key: KeyDef): String = when (key.action) {
        is KeyAction.Text -> key.label
        KeyAction.CapsToggle -> if (caps) "⇪" else "⇧"
        else -> key.label
    }

    /** The caps key stays highlighted while caps lock is on, like a real keyboard's LED. */
    private fun isActive(key: KeyDef): Boolean =
        key.action == KeyAction.CapsToggle && caps

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The strip paints its own keys only — the window is already strip-sized, so nothing else
        // needs painting (and the middle of the screen is not part of this window at all).
        for (item in placed) {
            val top = item.rect.top - scroll
            val bottom = item.rect.bottom - scroll
            if (bottom < 0f || top > height.toFloat()) continue

            val rect = RectF(item.rect.left, top, item.rect.right, bottom)
            val pressed = item.key === pressedKey
            keyPaint.color = when {
                pressed -> theme.keyBackgroundPressed
                item.key.special -> theme.specialKeyBackground
                else -> theme.keyBackground
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)
            drawLabel(canvas, item.label, rect, if (pressed || isActive(item.key)) theme.accent else theme.keyText)
        }
        drawScrollHints(canvas)
    }

    private fun drawLabel(canvas: Canvas, label: String, rect: RectF, color: Int) {
        if (label.isBlank()) return
        labelPaint.color = color
        val nominal = sp(settings.keyTextSizeSp.toFloat())
        labelPaint.textSize = nominal
        val maxWidth = rect.width() - dp(6f)
        val measured = labelPaint.measureText(label)
        if (measured > maxWidth && measured > 0f) labelPaint.textSize = nominal * (maxWidth / measured)
        val metrics = labelPaint.fontMetrics
        canvas.drawText(label, rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, labelPaint)
    }

    private fun drawScrollHints(canvas: Canvas) {
        hintPaint.color = theme.keyText
        hintPaint.alpha = 140
        hintPaint.textSize = dp(12f)
        val x = width / 2f
        if (scroll > 0f) canvas.drawText("▲", x, dp(12f), hintPaint)
        if (scroll < (contentHeight - height).coerceAtLeast(0f)) canvas.drawText("▼", x, height - dp(4f), hintPaint)
        hintPaint.alpha = 255
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartY = event.y
                dragStartScroll = scroll
                dragging = false
                updatePressed(event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - dragStartY) > touchSlop) {
                    dragging = true
                    pressedKey = null
                    invalidate()
                }
                if (dragging) {
                    scroll = (dragStartScroll - (event.y - dragStartY))
                        .coerceIn(0f, (contentHeight - height).coerceAtLeast(0f))
                    invalidate()
                } else {
                    updatePressed(event.y)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val key = if (!dragging) keyAt(event.y) else null
                pressedKey = null
                invalidate()
                if (key != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    activate(key)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** What a press does: page and caps keys change the strips, everything else goes to the editor. */
    private fun activate(key: KeyDef) {
        when (val action = key.action) {
            KeyAction.CapsToggle -> onCapsRequest?.invoke()
            KeyAction.ShowSymbols -> onPageRequest?.invoke(KeyboardPage.SYMBOLS)
            KeyAction.ShowLetters -> onPageRequest?.invoke(KeyboardPage.LETTERS)
            KeyAction.ShowEmoji -> onPageRequest?.invoke(KeyboardPage.EMOJI)
            KeyAction.ShowShortcuts -> onPageRequest?.invoke(KeyboardPage.SHORTCUTS)
            else -> {
                val result = KeyDispatcher.dispatch(action)
                MiniKeysLog.line("EDGE " + side + " '" + key.label + "' -> " + result)
            }
        }
    }

    private fun updatePressed(y: Float) {
        val key = keyAt(y)
        if (key === pressedKey) return
        pressedKey = key
        invalidate()
    }

    private fun keyAt(y: Float): KeyDef? = placed.firstOrNull {
        val top = it.rect.top - scroll
        val bottom = it.rect.bottom - scroll
        y >= top && y <= bottom
    }?.key

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}