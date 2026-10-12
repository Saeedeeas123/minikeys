package com.saeed.minikeys.perception.uimap

import kotlin.math.abs

/** Rectangle in screen pixels, mirroring Android's `boundsInScreen` (origin = top-left of display). */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /**
     * True when both rectangles describe the same place on screen.
     *
     * A tolerance is required: Compose reports a clickable parent and the text node inside it with
     * bounds that differ by a pixel or two, and they are one and the same button to the user.
     */
    fun sameAs(other: Bounds, tolerance: Int = 6): Boolean =
        abs(left - other.left) <= tolerance &&
            abs(top - other.top) <= tolerance &&
            abs(right - other.right) <= tolerance &&
            abs(bottom - other.bottom) <= tolerance

    fun coverageOf(screenWidth: Int, screenHeight: Int): Double {
        if (screenWidth <= 0 || screenHeight <= 0) return 0.0
        val area = width.toDouble() * height.toDouble()
        return area / (screenWidth.toDouble() * screenHeight.toDouble())
    }

    override fun toString(): String = "[$left,$top][$right,$bottom]"
}

/**
 * One node of the accessibility tree, flattened.
 *
 * Deliberately free of Android types: the accessibility service converts `AccessibilityNodeInfo`
 * into this shape, which keeps every decision below (filtering, merging, labelling, ordering)
 * testable on the JVM without a device.
 */
data class UiElement(
    val path: List<Int>,
    val depth: Int,
    val className: String,
    val text: String,
    val description: String,
    val viewId: String,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    /**
     * Whether the element's rectangle intersects the screen at all.
     *
     * This is what separates "scrolled out of sight but present in the activity" (kept, and marked
     * off-screen) from "not really on the screen" — the distinction the first test round could not
     * see, because the old code dropped everything the user could not see.
     */
    val inViewport: Boolean = true,
    /** Non-blank texts found in this node's descendants, nearest first (max 3). */
    val descendantTexts: List<String> = emptyList(),
    val packageName: String = "",
)

/** What kind of action a function performs. Drives the box colour and the list tag. */
enum class FunctionCategory(val label: String) {
    TAP("tap"),
    LONG_PRESS("long"),
    INPUT("input"),
    SCROLL("scroll"),
    TOGGLE("toggle"),
}

/** A function the user can invoke on the screen they are looking at right now. */
data class UiFunction(
    /** 1-based reading order (top-to-bottom, left-to-right). Shown as "#3" in both views. */
    val index: Int,
    val label: String,
    val category: FunctionCategory,
    val bounds: Bounds,
    /** View id / class / raw flags — for the log and the in-app table, not for the toast. */
    val detail: String,
    val path: List<Int>,
    /**
     * False when the element exists in the current activity but is scrolled out of the visible area.
     * Such a function is still listed (and can be reached: the app scrolls to it before acting).
     */
    val onScreen: Boolean = true,
)

/** Which app + activity the user is looking at. */
data class FocusInfo(
    val packageName: String,
    val activityName: String,
    val appLabel: String,
    val atMillis: Long,
    /** How the detection arrived: "event" (window state change) or "poll" (backstop sweep). */
    val source: String,
) {
    val key: String get() = "$packageName/$activityName"

    /** "Operit · com.ai.assistance.operit/.MainActivity" — the honest, complete answer. */
    fun display(): String {
        val where = FunctionFormat.activityShort(packageName, activityName)
        return if (where.isBlank()) "$appLabel · $packageName" else "$appLabel · $where"
    }
}

/** Tunables for [FunctionExtractor.extract]. Defaults are the ones proven useful on the device. */
data class ExtractOptions(
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    /**
     * Off-screen elements are included now, so a long screen legitimately produces more functions
     * than the visible area suggests; the cap is a safety valve, not a viewport.
     */
    val maxFunctions: Int = 120,
    val minSizePx: Int = 8,
    val mergeTolerancePx: Int = 6,
    val rowBucketPx: Int = 16,
    val maxLabelChars: Int = 64,
    /** Above this fraction of the screen a container is noise unless it scrolls. */
    val containerCoverage: Double = 0.94,
)
