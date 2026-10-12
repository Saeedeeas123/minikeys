package com.saeed.minikeys.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.saeed.minikeys.core.settings.MiniKeysLog

/** Outcome of trying to run one function. */
data class ActionResult(
    val ok: Boolean,
    val message: String,
    val scrolledSteps: Int = 0,
)

/** Outcome of an enumeration crawl. */
data class CrawlResult(
    val steps: Int,
    val restored: Boolean,
    val discovered: Int,
    val message: String,
)

/**
 * Running a detected function, and reaching the ones that are not currently visible.
 *
 * This is the primitive the whole "remote control" idea rests on, and it is also the answer to the
 * scrolling question:
 *
 *  - **Acting**: the recorded path is resolved, the element's identity is confirmed, and only then is
 *    `performAction` called. Never coordinates, never a blind tap.
 *  - **Reaching**: an element that exists in the activity but is scrolled out of sight is brought into
 *    view first — `ACTION_SHOW_ON_SCREEN` if the container supports it, otherwise the scrollable
 *    ancestor is moved step by step until the element is really on screen. The user never has to
 *    scroll by hand to reach a function, which is exactly what was asked for.
 *  - **Crawling** (in [ScanService]): a *virtualized* list only materializes the items near the
 *    viewport, so those items cannot be read at all until the list is moved. The crawl walks the list,
 *    re-scans at every step, merges what it finds, and then puts the screen back where it was.
 */
class ScreenActions(
    private val rootSupplier: () -> AccessibilityNodeInfo?,
    private val screenBounds: () -> Rect,
) {

    /** Runs the function. Blocking (it may scroll and wait) — call it off the main thread. */
    fun perform(target: NodeTarget): ActionResult {
        val root = rootSupplier()
            ?: return ActionResult(false, "no window root — is the accessibility service still on?")

        var node = NodeResolver.nodeAt(root, target.path)
        if (node == null || !NodeResolver.sameIdentity(node, target)) {
            // The path no longer points at the same element (a list re-rendered, the screen moved on).
            node = NodeResolver.findByIdentity(root, target)
            if (node == null) {
                return ActionResult(
                    false,
                    "'${target.label}' is no longer in this window — the list has refreshed, try again",
                )
            }
            MiniKeysLog.line("action #${target.index}: path was stale for '${target.label}' — re-found by identity")
        }

        var scrolled = 0
        if (!isOnScreen(node)) {
            val reach = bringOnScreen(target, node)
            scrolled = reach.steps
            val reached = reach.node
            if (reached == null) {
                return ActionResult(
                    false,
                    "'${target.label}' could not be brought on screen after ${reach.steps} scroll steps",
                    reach.steps,
                )
            }
            node = reached
        }

        val performed = when (target.action) {
            TargetAction.CLICK -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            TargetAction.LONG_CLICK -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            TargetAction.FOCUS -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            TargetAction.SCROLL_FORWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }

        return if (performed) {
            ActionResult(true, "ran ${target.action.name.lowercase()} on '${target.label}'", scrolled)
        } else {
            ActionResult(
                false,
                "'${target.label}' refused the ${target.action.name.lowercase()} action " +
                    "(the app does not expose it as a node action)",
                scrolled,
            )
        }
    }

    private class Reach(val node: AccessibilityNodeInfo?, val steps: Int)

    /** Brings an off-screen element into view, without the user having to scroll anything. */
    private fun bringOnScreen(target: NodeTarget, node: AccessibilityNodeInfo): Reach {
        // 1. The polite way: ask the container to show this node (API 26+).
        runCatching {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        }
        if (isOnScreen(node)) return Reach(node, 0)

        // 2. Move the scrollable ancestor step by step, re-finding the element each time, because a
        //    virtualized list destroys and re-creates its children while it moves.
        var steps = 0
        var current = node
        while (steps < MAX_REACH_STEPS) {
            val container = NodeResolver.scrollableAncestor(current) ?: break
            val rect = Rect().also { current.getBoundsInScreen(it) }
            val viewport = screenBounds()
            val forward = rect.top >= viewport.top
            val moved = container.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            )
            if (!moved) break
            steps++
            Thread.sleep(SCROLL_SETTLE_MS)

            val root = rootSupplier() ?: break
            val refreshed = NodeResolver.findByIdentity(root, target) ?: break
            current = refreshed
            if (isOnScreen(current)) return Reach(current, steps)
        }
        return Reach(if (isOnScreen(current)) current else null, steps)
    }

    /** Moves the main scrollable area of the screen — the crawl's only tool. */
    fun scrollMain(forward: Boolean): Boolean {
        val root = rootSupplier() ?: return false
        val container = NodeResolver.largestScrollable(root) ?: return false
        return container.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        )
    }

    fun isOnScreen(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val viewport = screenBounds()
        return rect.right > viewport.left && rect.left < viewport.right &&
            rect.bottom > viewport.top && rect.top < viewport.bottom &&
            rect.width() > 0 && rect.height() > 0
    }

    private companion object {
        const val MAX_REACH_STEPS = 12
        const val SCROLL_SETTLE_MS = 320L
    }
}