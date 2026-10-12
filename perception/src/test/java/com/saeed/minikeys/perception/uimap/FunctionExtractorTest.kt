package com.saeed.minikeys.perception.uimap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what counts as a "function" on screen. Every case here corresponds to a
 * shape that really occurs in Android view hierarchies (classic Views and Compose alike), so a green
 * run means the classifier is trustworthy before anything is installed on the device.
 */
class FunctionExtractorTest {

    private val screen = ExtractOptions(screenWidth = 1080, screenHeight = 2400)

    private fun element(
        path: List<Int> = listOf(0),
        depth: Int = 1,
        className: String = "android.view.View",
        text: String = "",
        description: String = "",
        viewId: String = "",
        bounds: Bounds = Bounds(0, 0, 100, 100),
        clickable: Boolean = false,
        longClickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        inViewport: Boolean = true,
        descendantTexts: List<String> = emptyList(),
        packageName: String = "com.example.app",
    ) = UiElement(
        path = path,
        depth = depth,
        className = className,
        text = text,
        description = description,
        viewId = viewId,
        bounds = bounds,
        clickable = clickable,
        longClickable = longClickable,
        editable = editable,
        scrollable = scrollable,
        checkable = checkable,
        checked = checked,
        enabled = enabled,
        visible = visible,
        inViewport = inViewport,
        descendantTexts = descendantTexts,
        packageName = packageName,
    )

    // --- the Compose shape ----------------------------------------------------------------------

    @Test
    fun `compose button with no text of its own is named after its child text`() {
        val functions = FunctionExtractor.extract(
            listOf(
                element(
                    path = listOf(0, 3),
                    depth = 6,
                    className = "android.view.View",
                    bounds = Bounds(40, 300, 1040, 420),
                    clickable = true,
                    descendantTexts = listOf("Send message"),
                ),
            ),
            screen,
        )

        assertEquals(1, functions.size)
        assertEquals("Send message", functions[0].label)
        assertEquals(FunctionCategory.TAP, functions[0].category)
        assertEquals(1, functions[0].index)
    }

    @Test
    fun `clickable parent and its text child are one function, not two`() {
        val functions = FunctionExtractor.extract(
            listOf(
                element(
                    path = listOf(0, 1),
                    depth = 5,
                    bounds = Bounds(40, 300, 1040, 420),
                    clickable = true,
                    descendantTexts = listOf("Switch model"),
                ),
                element(
                    path = listOf(0, 1, 0),
                    depth = 6,
                    className = "android.widget.TextView",
                    text = "Switch model",
                    bounds = Bounds(42, 302, 1038, 418),
                ),
            ),
            screen,
        )

        assertEquals(1, functions.size)
        assertEquals("Switch model", functions[0].label)
    }

    @Test
    fun `identical rectangles merge and the labelled one wins`() {
        val functions = FunctionExtractor.extract(
            listOf(
                element(path = listOf(0, 0), depth = 4, bounds = Bounds(10, 10, 300, 90), clickable = true),
                element(
                    path = listOf(0, 1),
                    depth = 5,
                    bounds = Bounds(10, 10, 300, 90),
                    clickable = true,
                    text = "Chat",
                ),
            ),
            screen,
        )

        assertEquals(1, functions.size)
        assertEquals("Chat", functions[0].label)
    }

    // --- labels ---------------------------------------------------------------------------------

    @Test
    fun `label prefers own text, then description, then descendant text, then view id, then class`() {
        assertEquals("Own text", FunctionExtractor.label(element(text = "Own text", description = "Desc")))
        assertEquals("Desc", FunctionExtractor.label(element(description = "Desc", descendantTexts = listOf("Child"))))
        assertEquals("Child", FunctionExtractor.label(element(descendantTexts = listOf("Child"), viewId = "pkg:id/thing")))
        assertEquals("thing", FunctionExtractor.label(element(viewId = "pkg:id/thing")))
        assertEquals("FrameLayout", FunctionExtractor.label(element(className = "android.widget.FrameLayout")))
    }

    @Test
    fun `descendant labels are capped and deduplicated`() {
        val label = FunctionExtractor.label(
            element(descendantTexts = listOf("One", "One", "Two", "Three", "Four")),
        )
        assertEquals("One · Two · Three", label)
    }

    @Test
    fun `overlong labels are truncated`() {
        val label = FunctionExtractor.label(element(text = "x".repeat(200)), maxChars = 20)
        assertEquals(20, label.length)
        assertTrue(label.endsWith("…"))
    }

    // --- categories -----------------------------------------------------------------------------

    @Test
    fun `category is inferred from the element flags`() {
        assertEquals(FunctionCategory.INPUT, FunctionExtractor.category(element(editable = true, clickable = true)))
        assertEquals(FunctionCategory.TOGGLE, FunctionExtractor.category(element(checkable = true, clickable = true)))
        assertEquals(FunctionCategory.TAP, FunctionExtractor.category(element(clickable = true)))
        assertEquals(FunctionCategory.LONG_PRESS, FunctionExtractor.category(element(longClickable = true)))
        assertEquals(FunctionCategory.SCROLL, FunctionExtractor.category(element(scrollable = true)))
    }

    // --- filtering ------------------------------------------------------------------------------

    @Test
    fun `disabled, tiny, non-actionable and covered elements are dropped`() {
        val functions = FunctionExtractor.extract(
            listOf(
                element(bounds = Bounds(0, 0, 200, 200), clickable = true, enabled = false),
                element(bounds = Bounds(0, 0, 4, 4), clickable = true),
                element(bounds = Bounds(0, 0, 200, 200), text = "just a label"),
                // Inside the viewport but not visible to the user: covered by a dialog, zero alpha,
                // collapsed. Real noise — unlike an element that is merely scrolled out of sight.
                element(bounds = Bounds(0, 100, 200, 200), clickable = true, visible = false, inViewport = true),
            ),
            screen,
        )

        assertTrue(functions.isEmpty())
    }

    // --- off-screen: the first field test could not tell, this is the answer ---------------------

    @Test
    fun `an element scrolled out of the viewport is still listed, and marked off-screen`() {
        val functions = FunctionExtractor.extract(
            listOf(
                element(
                    path = listOf(0),
                    bounds = Bounds(40, 2600, 1040, 2700), // below the bottom edge (screen is 2400)
                    clickable = true,
                    text = "Advanced settings",
                    visible = false,
                    inViewport = false,
                ),
                element(
                    path = listOf(1),
                    bounds = Bounds(40, -300, 1040, -200), // above the top edge
                    clickable = true,
                    text = "Account header",
                    visible = false,
                    inViewport = false,
                ),
                element(
                    path = listOf(2),
                    bounds = Bounds(40, 300, 1040, 400),
                    clickable = true,
                    text = "Visible button",
                ),
            ),
            screen,
        )

        assertEquals(3, functions.size)
        assertEquals(listOf("Account header", "Visible button", "Advanced settings"), functions.map { it.label })
        assertEquals(listOf(false, true, false), functions.map { it.onScreen })
        assertTrue(FunctionFormat.line(functions[0]).contains(FunctionFormat.OFF_SCREEN_MARK))
        assertFalse(FunctionFormat.line(functions[1]).contains(FunctionFormat.OFF_SCREEN_MARK))
    }

    @Test
    fun `counts separate what is visible from what is merely present`() {
        val functions = FunctionExtractor.extract(
            listOf(
                element(bounds = Bounds(40, 300, 400, 400), clickable = true, text = "A"),
                element(bounds = Bounds(40, 2600, 400, 2700), clickable = true, text = "B", visible = false, inViewport = false),
                element(bounds = Bounds(40, 2800, 400, 2900), clickable = true, text = "C", visible = false, inViewport = false),
            ),
            screen,
        )

        assertEquals(1, FunctionExtractor.onScreenCount(functions))
        assertEquals(2, FunctionExtractor.offScreenCount(functions))
        assertEquals("1 on screen · 2 off screen", FunctionFormat.counts(functions))
    }

    @Test
    fun `the list toast states the counts and marks the off-screen entries`() {
        val functions = FunctionExtractor.extract(
            listOf(
                element(bounds = Bounds(40, 300, 400, 400), clickable = true, text = "Chat"),
                element(bounds = Bounds(40, 2600, 400, 2700), clickable = true, text = "Terminal", visible = false, inViewport = false),
            ),
            screen,
        )

        val text = FunctionFormat.listText(null, functions)
        assertTrue(text.contains("(1 on screen · 1 off screen)"))
        assertTrue(text.contains("#1 [tap] Chat"))
        assertTrue(text.contains("#2 [tap] Terminal  · off-screen"))
    }

    @Test
    fun `scrolling an element into view changes the fingerprint`() {
        val offScreen = FunctionExtractor.extract(
            listOf(element(bounds = Bounds(40, 2600, 400, 2700), clickable = true, text = "B", visible = false, inViewport = false)),
            screen,
        )
        val onScreen = FunctionExtractor.extract(
            listOf(element(bounds = Bounds(40, 2600, 400, 2700), clickable = true, text = "B")),
            screen,
        )

        assertNotEquals(FunctionExtractor.signature(offScreen), FunctionExtractor.signature(onScreen))
    }

    @Test
    fun `a blank full-screen container is dropped but a scrollable one is kept`() {
        val fullScreen = Bounds(0, 0, 1080, 2400)
        val functions = FunctionExtractor.extract(
            listOf(
                element(
                    path = listOf(0),
                    depth = 1,
                    className = "android.widget.FrameLayout",
                    bounds = fullScreen,
                    clickable = true,
                ),
                element(
                    path = listOf(1),
                    depth = 2,
                    className = "androidx.recyclerview.widget.RecyclerView",
                    bounds = fullScreen,
                    scrollable = true,
                ),
            ),
            screen,
        )

        assertEquals(1, functions.size)
        assertEquals(FunctionCategory.SCROLL, functions[0].category)
    }

    @Test
    fun `a full-screen element that carries a real label is kept`() {
        val functions = FunctionExtractor.extract(
            listOf(element(bounds = Bounds(0, 0, 1080, 2400), clickable = true, text = "Dismiss")),
            screen,
        )

        assertEquals(1, functions.size)
        assertEquals("Dismiss", functions[0].label)
    }

    // --- ordering and limits --------------------------------------------------------------------

    @Test
    fun `functions are numbered in reading order, top to bottom then left to right`() {
        val functions = FunctionExtractor.extract(
            listOf(
                element(path = listOf(0), bounds = Bounds(40, 400, 300, 480), clickable = true, text = "Third"),
                element(path = listOf(1), bounds = Bounds(40, 100, 300, 180), clickable = true, text = "First"),
                element(path = listOf(2), bounds = Bounds(600, 100, 900, 180), clickable = true, text = "Second"),
            ),
            screen,
        )

        assertEquals(listOf("First", "Second", "Third"), functions.map { it.label })
        assertEquals(listOf(1, 2, 3), functions.map { it.index })
    }

    @Test
    fun `the function list is capped at maxFunctions`() {
        val many = (0 until 5).map { index ->
            element(
                path = listOf(index),
                bounds = Bounds(40, 100 + index * 100, 300, 180 + index * 100),
                clickable = true,
                text = "F$index",
            )
        }

        val functions = FunctionExtractor.extract(many, screen.copy(maxFunctions = 3))

        assertEquals(3, functions.size)
        assertEquals(listOf(1, 2, 3), functions.map { it.index })
    }

    @Test
    fun `signature is stable for the same screen and changes for a different one`() {
        val first = FunctionExtractor.extract(
            listOf(element(bounds = Bounds(40, 100, 300, 180), clickable = true, text = "Chat")),
            screen,
        )
        val same = FunctionExtractor.extract(
            listOf(element(bounds = Bounds(40, 100, 300, 180), clickable = true, text = "Chat")),
            screen,
        )
        val other = FunctionExtractor.extract(
            listOf(element(bounds = Bounds(40, 100, 300, 180), clickable = true, text = "Terminal")),
            screen,
        )

        assertEquals(FunctionExtractor.signature(first), FunctionExtractor.signature(same))
        assertNotEquals(FunctionExtractor.signature(first), FunctionExtractor.signature(other))
    }

    // --- bounds ---------------------------------------------------------------------------------

    @Test
    fun `bounds comparison tolerates a couple of pixels but not a real difference`() {
        assertTrue(Bounds(10, 10, 300, 90).sameAs(Bounds(12, 11, 298, 88)))
        assertFalse(Bounds(10, 10, 300, 90).sameAs(Bounds(10, 10, 400, 90)))
        assertTrue(Bounds(0, 0, 0, 0).isEmpty)
    }

    // --- the two verification views --------------------------------------------------------------

    @Test
    fun `focus toast names the app, the package and the activity`() {
        val focus = FocusInfo(
            packageName = "com.ai.assistance.operit",
            activityName = "com.ai.assistance.operit.MainActivity",
            appLabel = "Operit",
            atMillis = 0L,
            source = "event",
        )

        assertEquals("Operit · com.ai.assistance.operit/.MainActivity", focus.display())
        assertEquals("FocusMap ▸ Operit\ncom.ai.assistance.operit/.MainActivity", FunctionFormat.focusToast(focus))
    }

    @Test
    fun `activity shortening survives odd class names`() {
        assertEquals("", FunctionFormat.activityShort("com.a", ""))
        assertEquals("", FunctionFormat.activityShort("com.a", "com.a.Main$1"))
        assertEquals("", FunctionFormat.activityShort("com.a", "android.widget.FrameLayout"))
        assertEquals("com.a", FunctionFormat.activityShort("com.a", "com.a"))
        assertEquals("com.other.Screen", FunctionFormat.activityShort("com.a", "com.other.Screen"))
    }

    @Test
    fun `the list toast contains every function, and an honest empty state`() {
        val functions = FunctionExtractor.extract(
            listOf(
                element(bounds = Bounds(40, 100, 300, 180), clickable = true, text = "Chat"),
                element(bounds = Bounds(40, 200, 300, 280), editable = true, text = "Type a message"),
            ),
            screen,
        )

        val text = FunctionFormat.listText(null, functions)
        assertTrue(text.contains("#1 [tap] Chat"))
        assertTrue(text.contains("#2 [input] Type a message"))

        val empty = FunctionFormat.listText(null, emptyList())
        assertTrue(empty.contains("No actionable element found."))
    }
}