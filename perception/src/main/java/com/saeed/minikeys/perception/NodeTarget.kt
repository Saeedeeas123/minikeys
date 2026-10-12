package com.saeed.minikeys.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.saeed.minikeys.perception.uimap.Bounds
import kotlin.math.abs

/** What pressing a detected function should do. */
enum class TargetAction { CLICK, LONG_CLICK, FOCUS, SCROLL_FORWARD }

/**
 * A durable handle to one detected element, so an action can be performed on it later.
 *
 * `AccessibilityNodeInfo` objects go stale the moment the UI changes, so a node is never held: what
 * is kept is the **path** from the window root to the node (root → child index → …), plus enough
 * identity to confirm we landed on the same element before touching it. This is the difference
 * between "tap this button" working reliably and tapping whatever happens to be at those coordinates.
 */
data class NodeTarget(
    val index: Int,
    val path: List<Int>,
    val className: String,
    val text: String,
    val description: String,
    val viewId: String,
    val bounds: Bounds,
    val label: String,
    val onScreen: Boolean,
    val action: TargetAction,
)

/** Finding the node a [NodeTarget] points at, and nothing else. */
object NodeResolver {

    /** Walks the recorded child-index chain from the window root. Returns null if it no longer fits. */
    fun nodeAt(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo = root
        for (step in path) {
            if (step < 0 || step >= current.childCount) return null
            current = current.getChild(step) ?: return null
        }
        return current
    }

    /**
     * Is this still the same element?
     *
     * A path can survive while the element under it changes (a list re-rendered, a tab switched), so
     * acting on the path alone would silently press the wrong thing. Any one strong signal is enough:
     * same text, same content description, same view id, or the same place on screen.
     */
    fun sameIdentity(node: AccessibilityNodeInfo, target: NodeTarget, tolerance: Int = 24): Boolean {
        val className = node.className?.toString().orEmpty()
        if (className.isNotEmpty() && target.className.isNotEmpty() && className != target.className) return false

        val text = node.text?.toString()?.trim().orEmpty()
        if (target.text.isNotBlank() && text.equals(target.text, ignoreCase = true)) return true

        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (target.description.isNotBlank() && description.equals(target.description, ignoreCase = true)) return true

        val viewId = node.viewIdResourceName.orEmpty()
        if (target.viewId.isNotBlank() && viewId == target.viewId) return true

        val rect = Rect()
        node.getBoundsInScreen(rect)
        return abs(rect.left - target.bounds.left) <= tolerance && abs(rect.top - target.bounds.top) <= tolerance
    }

    /**
     * Searches the whole tree for the element again — used when the recorded path no longer resolves,
     * or when a crawl has to find an item that a virtualized list only materializes after scrolling.
     */
    fun findByIdentity(
        root: AccessibilityNodeInfo,
        target: NodeTarget,
        maxNodes: Int = 3000,
    ): AccessibilityNodeInfo? {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            if (sameIdentity(node, target)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    /** The nearest ancestor that can actually scroll — the thing to move when a target is out of sight. */
    fun scrollableAncestor(node: AccessibilityNodeInfo, maxLevels: Int = 14): AccessibilityNodeInfo? {
        var current = node.parent
        var level = 0
        while (current != null && level < maxLevels) {
            if (current.isScrollable) return current
            current = current.parent
            level++
        }
        return null
    }

    /** The biggest scrollable node in the window — the main list of the screen. */
    fun largestScrollable(root: AccessibilityNodeInfo, maxNodes: Int = 3000): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            if (node.isScrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height().toLong()
                if (area > bestArea) {
                    bestArea = area
                    best = node
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.addLast(it) }
            }
        }
        return best
    }
}