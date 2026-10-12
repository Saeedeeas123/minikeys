package com.saeed.minikeys.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.saeed.minikeys.perception.uimap.Bounds
import com.saeed.minikeys.perception.uimap.UiElement

/**
 * Flattens the live `AccessibilityNodeInfo` tree into [UiElement]s.
 *
 * Two things here matter more than they look:
 *
 *  1. **Descendant texts are aggregated bottom-up.** A Compose button carries no text of its own —
 *     the label lives in a child node. Without this step every Compose control would be listed as
 *     "View", which is exactly the failure mode that makes a detection tool useless.
 *  2. **Foreign packages are cut out.** The overlay windows of this app, the IME and system windows
 *     can appear inside the same tree; they are counted (so the log can show it happened) and then
 *     skipped, never reported as a function of the app being looked at.
 *
 * Nodes are walked with an explicit stack and never recycled: recycling a node that the framework
 * still holds is a crash waiting to happen, and modern Android deprecates the whole recycle dance.
 */
class NodeReader {

    data class Result(
        val elements: List<UiElement>,
        val foreignNodes: Int,
        val truncated: Boolean,
    )

    private class Record(
        val node: AccessibilityNodeInfo,
        val path: List<Int>,
        val depth: Int,
        val parentIndex: Int,
        val texts: MutableList<String> = mutableListOf(),
    )

    private class Frame(
        val node: AccessibilityNodeInfo,
        val path: List<Int>,
        val depth: Int,
        val parentIndex: Int,
    )

    fun read(
        root: AccessibilityNodeInfo,
        targetPackage: String,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        maxNodes: Int = 3000,
        maxDepth: Int = 40,
    ): Result {
        val records = ArrayList<Record>()
        val childrenOf = ArrayList<MutableList<Int>>()
        var foreign = 0
        var truncated = false

        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, emptyList(), 0, -1))

        while (stack.isNotEmpty()) {
            if (records.size >= maxNodes) {
                truncated = true
                break
            }
            val frame = stack.removeLast()
            val node = frame.node
            val nodePackage = node.packageName?.toString().orEmpty()

            if (targetPackage.isNotBlank() && nodePackage.isNotBlank() && nodePackage != targetPackage) {
                foreign++
                continue
            }

            val index = records.size
            val record = Record(node, frame.path, frame.depth, frame.parentIndex)
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { record.texts.add(it) }
            records.add(record)
            childrenOf.add(mutableListOf())
            if (frame.parentIndex >= 0) childrenOf[frame.parentIndex].add(index)

            if (frame.depth >= maxDepth) {
                truncated = true
                continue
            }

            // Pushed in reverse so children pop in their natural left-to-right order.
            for (childIndex in node.childCount - 1 downTo 0) {
                val child = node.getChild(childIndex) ?: continue
                stack.addLast(Frame(child, frame.path + childIndex, frame.depth + 1, index))
            }
        }

        for (index in records.indices) {
            if (records[index].parentIndex < 0) aggregate(records, childrenOf, index, MAX_LABEL_TEXTS)
        }

        val elements = records.map { record -> toElement(record, screenWidth, screenHeight) }
        return Result(elements = elements, foreignNodes = foreign, truncated = truncated)
    }

    /** Merges each node's own text and its descendants' texts upward, nearest first, capped. */
    private fun aggregate(
        records: List<Record>,
        childrenOf: List<MutableList<Int>>,
        index: Int,
        cap: Int,
    ) {
        for (child in childrenOf[index]) {
            aggregate(records, childrenOf, child, cap)
            for (text in records[child].texts) {
                if (records[index].texts.size >= cap) return
                if (records[index].texts.none { it.equals(text, ignoreCase = true) }) {
                    records[index].texts.add(text)
                }
            }
        }
    }

    private fun toElement(record: Record, screenWidth: Int, screenHeight: Int): UiElement {
        val node = record.node
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val className = node.className?.toString().orEmpty()
        val ownText = node.text?.toString()?.trim().orEmpty()

        return UiElement(
            path = record.path,
            depth = record.depth,
            className = className,
            text = ownText,
            description = node.contentDescription?.toString()?.trim().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = isEditable(node, className),
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            inViewport = intersectsScreen(rect, screenWidth, screenHeight),
            descendantTexts = record.texts
                .filterNot { it.equals(ownText, ignoreCase = true) }
                .take(MAX_LABEL_TEXTS),
            packageName = node.packageName?.toString().orEmpty(),
        )
    }

    /**
     * Whether the element's rectangle overlaps the display.
     *
     * A scrolled-out element keeps its real coordinates (negative above the top edge, beyond the
     * height below the bottom edge) — this is exactly what tells "it exists in the activity, you just
     * cannot see it" apart from "it is not on this screen at all".
     */
    private fun intersectsScreen(rect: Rect, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return true
        return rect.right > 0 && rect.left < screenWidth && rect.bottom > 0 && rect.top < screenHeight
    }

    /**
     * A text field. `isEditable` is the authoritative flag, but not every app sets it — plenty of
     * custom text fields only look like one — so the class name is used as a second signal.
     */
    private fun isEditable(node: AccessibilityNodeInfo, className: String): Boolean =
        node.isEditable ||
            className.contains("EditText") ||
            className.contains("AutoCompleteTextView") ||
            className.contains("SearchView")

    private companion object {
        const val MAX_LABEL_TEXTS = 3
    }
}