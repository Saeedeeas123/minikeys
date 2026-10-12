package com.saeed.minikeys.perception.uimap

/**
 * Turns the flattened accessibility tree into the list of FUNCTIONS available on the current screen.
 *
 * This is the heart of the module: "which app am I in" is cheap, but "what can I do here" is what
 * every later layer (Cosmo's FIND step) needs. The rules below were chosen against real Compose and
 * classic-View hierarchies:
 *
 *  - an element is a function when it is actionable (clickable / long-clickable / editable /
 *    checkable / scrollable), enabled, visible, and big enough to be a real target;
 *  - a full-screen container is NOT a function — unless it scrolls (a list is a function: "scroll");
 *  - two elements describing the same rectangle are ONE function (Compose nests a clickable parent
 *    and its text child with identical bounds);
 *  - the label is taken from the most human source available: own text → content description →
 *    text of descendants → view id → class name. The descendant step is what makes Compose buttons
 *    readable at all, because a Compose button usually carries no text of its own.
 */
object FunctionExtractor {

    fun extract(elements: List<UiElement>, options: ExtractOptions = ExtractOptions()): List<UiFunction> {
        val candidates = elements.asSequence()
            .filter { it.enabled }
            .filter { !it.bounds.isEmpty }
            .filter { it.bounds.width >= options.minSizePx && it.bounds.height >= options.minSizePx }
            .filter { isActionable(it) }
            .filter { isPartOfTheScreen(it) }
            .filterNot { isContainerNoise(it, options) }
            .toList()

        val merged = mutableListOf<UiElement>()
        for (candidate in candidates) {
            val at = merged.indexOfFirst { it.bounds.sameAs(candidate.bounds, options.mergeTolerancePx) }
            if (at < 0) {
                merged.add(candidate)
            } else if (score(candidate) > score(merged[at])) {
                merged[at] = candidate
            }
        }

        return merged
            .sortedWith(compareBy({ it.bounds.top / options.rowBucketPx }, { it.bounds.left }))
            .take(options.maxFunctions)
            .mapIndexed { position, element ->
                UiFunction(
                    index = position + 1,
                    label = label(element, options.maxLabelChars),
                    category = category(element),
                    bounds = element.bounds,
                    detail = detail(element),
                    path = element.path,
                    onScreen = isOnScreen(element),
                )
            }
    }

    /** On screen = visible to the user AND inside the display rectangle. */
    fun isOnScreen(element: UiElement): Boolean = element.visible && element.inViewport

    /**
     * Everything that is genuinely part of the current activity.
     *
     * The first version required `isVisibleToUser`, which is why Saeed could not tell whether the app
     * knew about the parts of a screen he had to scroll to see — it did not: every scrolled-out
     * element was being thrown away. Two different situations hide behind that flag:
     *
     *  - **scrolled out of the viewport** — the element IS in the activity, keeps real coordinates and
     *    can be reached; it must be listed, marked off-screen;
     *  - **inside the viewport but not visible to the user** — covered by a dialog, zero alpha, a
     *    collapsed section. That is noise and stays dropped.
     *
     * Note the honest limit: this works because a normal view hierarchy (ScrollView, NestedScrollView,
     * ConstraintLayout …) keeps its off-screen children in the accessibility tree. A *virtualized* list
     * (RecyclerView, Compose LazyColumn) never materializes the items it has not scrolled to, so those
     * genuinely are not there to be read — they need a scroll-and-rescan crawl, which the app provides
     * as an explicit action rather than pretending they were found.
     */
    fun isPartOfTheScreen(element: UiElement): Boolean =
        if (element.inViewport) element.visible else true

    /** A stable fingerprint of a screen's functions — used to log only when the screen really changed. */
    fun signature(functions: List<UiFunction>): String {
        val builder = StringBuilder()
        for (function in functions) {
            // The off-screen marker is part of the fingerprint: scrolling changes what is visible and
            // therefore what the user is being told, even when the element list itself is unchanged.
            if (!function.onScreen) builder.append('!')
            builder.append(function.category.label).append(':')
                .append(function.label).append('@')
                .append(function.bounds.left).append(',').append(function.bounds.top)
                .append(';')
        }
        return builder.toString()
    }

    fun onScreenCount(functions: List<UiFunction>): Int = functions.count { it.onScreen }

    fun offScreenCount(functions: List<UiFunction>): Int = functions.count { !it.onScreen }

    fun isActionable(element: UiElement): Boolean =
        element.clickable || element.longClickable || element.editable || element.checkable || element.scrollable

    /**
     * Whether the element carries a label a human wrote, as opposed to the class-name fallback of
     * [label]. Containers must be judged on this: every view has a class name, so the fallback would
     * make the "is this just an empty container?" question unanswerable.
     */
    fun hasHumanLabel(element: UiElement): Boolean =
        element.text.isNotBlank() ||
            element.description.isNotBlank() ||
            element.descendantTexts.any { it.isNotBlank() }

    /**
     * A rectangle covering nearly the whole screen that does nothing but contain other views. Keeping
     * it would draw one meaningless box around the entire display on every single screen.
     */
    private fun isContainerNoise(element: UiElement, options: ExtractOptions): Boolean {
        if (element.scrollable) return false
        val coverage = element.bounds.coverageOf(options.screenWidth, options.screenHeight)
        if (coverage < options.containerCoverage) return false
        return !hasHumanLabel(element)
    }

    private fun score(element: UiElement): Double {
        var score = 0.0
        if (hasHumanLabel(element)) score += 4.0
        if (element.clickable) score += 2.0
        if (element.editable) score += 2.0
        if (element.checkable) score += 1.0
        if (element.scrollable) score += 1.0
        // When a rectangle is claimed twice, the one the user can actually see wins.
        if (isOnScreen(element)) score += 1.0
        // Prefer the outer element when everything else ties: it is the real touch target.
        score -= element.depth * 0.001
        return score
    }

    fun category(element: UiElement): FunctionCategory = when {
        element.editable -> FunctionCategory.INPUT
        element.checkable -> FunctionCategory.TOGGLE
        element.clickable -> FunctionCategory.TAP
        element.longClickable -> FunctionCategory.LONG_PRESS
        element.scrollable -> FunctionCategory.SCROLL
        else -> FunctionCategory.TAP
    }

    fun label(element: UiElement, maxChars: Int = 64): String {
        val own = element.text.trim()
        if (own.isNotEmpty()) return cap(own, maxChars)

        val described = element.description.trim()
        if (described.isNotEmpty()) return cap(described, maxChars)

        val fromDescendants = element.descendantTexts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
        if (fromDescendants.isNotEmpty()) return cap(fromDescendants.joinToString(" · "), maxChars)

        val id = shortViewId(element.viewId)
        if (id.isNotEmpty()) return id

        return shortClassName(element.className)
    }

    fun detail(element: UiElement): String {
        val flags = buildList {
            if (element.clickable) add("tap")
            if (element.longClickable) add("long")
            if (element.editable) add("edit")
            if (element.checkable) add(if (element.checked) "checked" else "checkable")
            if (element.scrollable) add("scroll")
        }.joinToString(",")
        val id = shortViewId(element.viewId)
        return buildString {
            append("class=").append(shortClassName(element.className))
            if (id.isNotEmpty()) append(" id=").append(id)
            append(" flags=").append(flags.ifEmpty { "-" })
            append(" depth=").append(element.depth)
        }
    }

    /** `com.pkg:id/send_button` → `send_button`; a bare id is returned unchanged. */
    fun shortViewId(viewId: String): String {
        if (viewId.isBlank()) return ""
        return viewId.substringAfterLast('/').ifBlank { viewId }
    }

    fun shortClassName(className: String): String {
        if (className.isBlank()) return "View"
        return className.substringAfterLast('.')
    }

    private fun cap(value: String, maxChars: Int): String =
        if (value.length <= maxChars) value else value.take(maxChars - 1) + "…"
}
