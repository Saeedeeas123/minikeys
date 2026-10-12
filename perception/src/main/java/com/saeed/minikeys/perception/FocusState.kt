package com.saeed.minikeys.perception

import com.saeed.minikeys.perception.uimap.FocusInfo
import com.saeed.minikeys.perception.uimap.UiFunction
import java.util.concurrent.CopyOnWriteArrayList

/** What one scan produced. Kept for the log and for the settings card. */
data class ScanReport(
    val packageName: String,
    val nodeCount: Int,
    val foreignNodeCount: Int,
    val functionCount: Int,
    val atMillis: Long,
    val reason: String,
    val durationMs: Long = 0,
    val error: String? = null,
    val truncated: Boolean = false,
)

/**
 * The latest perception result: which activity is in front, what it offers, and the runnable handle
 * for each of those functions.
 *
 * Deliberately **plain fields plus listeners, not `StateFlow`** — unlike the standalone FocusMap app,
 * nothing here is a Compose screen that collects a flow. Using coroutines would drag a dependency
 * into this module for no reader at all; the keyboard asks for what it needs when it needs it, and
 * subscribes only to be told that the answer changed.
 */
object FocusState {

    @Volatile
    var focus: FocusInfo? = null
        private set

    @Volatile
    var functions: List<UiFunction> = emptyList()
        private set

    /** The runnable handle of every function in [functions], aligned by index. */
    @Volatile
    var targets: List<NodeTarget> = emptyList()
        private set

    @Volatile
    var lastReport: ScanReport? = null
        private set

    @Volatile
    var lastSignature: String = ""
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) runCatching { listener() }
    }

    fun setFocus(info: FocusInfo) {
        focus = info
        notifyListeners()
    }

    fun setFunctions(
        functions: List<UiFunction>,
        targets: List<NodeTarget>,
        report: ScanReport,
        signature: String,
    ) {
        this.functions = functions
        this.targets = targets
        lastReport = report
        lastSignature = signature
        notifyListeners()
    }

    fun clear() {
        functions = emptyList()
        targets = emptyList()
        lastReport = null
        lastSignature = ""
        notifyListeners()
    }
}