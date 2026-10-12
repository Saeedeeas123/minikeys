package com.saeed.minikeys.perception

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.saeed.minikeys.core.settings.MiniKeysLog
import com.saeed.minikeys.perception.uimap.ExtractOptions
import com.saeed.minikeys.perception.uimap.FocusInfo
import com.saeed.minikeys.perception.uimap.FunctionCategory
import com.saeed.minikeys.perception.uimap.FunctionExtractor
import com.saeed.minikeys.perception.uimap.FunctionFormat
import com.saeed.minikeys.perception.uimap.UiElement
import com.saeed.minikeys.perception.uimap.UiFunction
import java.util.concurrent.Executors

/**
 * The screen reader behind the activity keys: *which app + activity is in front*, *what that screen
 * offers*, and *running* any of it.
 *
 * It answers the first two questions for the keyboard:
 *  - **Which app**: the active **application window** from [getWindows] (hence
 *    `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`) — with several apps open only one is `isActive`. The window
 *    root gives the package, `TYPE_WINDOW_STATE_CHANGED` adds the activity class.
 *  - **What it offers**: [NodeReader] flattens the root and [FunctionExtractor] classifies it, with
 *    off-screen elements included, not just the ones on the visible part of the screen.
 *  - **Running it**: every function carries a [NodeTarget]; [performFunction] resolves it, checks its
 *    identity and presses it through the accessibility API.
 *
 * What it does NOT do here (it does in the standalone FocusMap app): show boxes, show toasts, switch
 * itself on and off, or crawl a virtualized list. MiniKeys has its own overlay and its own switches;
 * this file is only the perception + action half.
 */
class ScanService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val reader = NodeReader()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "minikeys-perception")
    }
    private val actions = ScreenActions(
        rootSupplier = { currentRoot() },
        screenBounds = { screenRect() },
    )

    private var pendingScan: Runnable? = null
    private var lastFocusKey = ""
    private var lastFocusAt = 0L
    private val lastActivityOf = HashMap<String, String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // The same flags the standalone app proved necessary; set here too so a stale XML can never
        // silently degrade perception.
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 150
        }

        MiniKeysLog.line("perception: screen reader connected — activity keys are live")
        startPolling()
        scheduleScan("service-connected", 400)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val received = event ?: return
        when (received.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = received.packageName?.toString().orEmpty()
                if (pkg.isEmpty()) return
                val activity = received.className?.toString().orEmpty()
                if (activity.isNotBlank()) lastActivityOf[pkg] = activity
                considerFocus(pkg, activity, "event")
                scheduleScan("window-state", SCAN_AFTER_STATE_MS)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = received.packageName?.toString().orEmpty()
                val current = FocusState.focus?.packageName
                if (current == null || pkg.isEmpty() || pkg == current) {
                    scheduleScan("content-changed", CONTENT_DEBOUNCE_MS)
                }
            }
        }
    }

    override fun onInterrupt() {
        MiniKeysLog.line("perception: screen reader interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        MiniKeysLog.line("perception: screen reader unbound — activity keys off")
        if (instance === this) instance = null
        ActivityShortcuts.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        MiniKeysLog.line("perception: screen reader destroyed")
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        ActivityShortcuts.clear()
        super.onDestroy()
    }

    // --- focus ------------------------------------------------------------------------------------

    /** Records the activity in front and points the shortcut page at *that* activity's keys. */
    private fun considerFocus(pkg: String, activity: String, source: String) {
        val now = System.currentTimeMillis()
        val key = "$pkg/$activity"
        if (key == lastFocusKey && now - lastFocusAt < FOCUS_DEDUPE_MS) return
        lastFocusKey = key
        lastFocusAt = now
        if (activity.isNotBlank()) lastActivityOf[pkg] = activity

        val label = appLabel(pkg)
        val info = FocusInfo(
            packageName = pkg,
            activityName = activity,
            appLabel = label,
            atMillis = now,
            source = source,
        )
        FocusState.setFocus(info)
        MiniKeysLog.line("perception FOCUS[$source] ${info.display()}")
        ActivityShortcuts.select(key, info.display())
    }

    private fun appLabel(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    // --- the window the user is looking at ----------------------------------------------------------

    private fun activeApplicationWindow(): AccessibilityWindowInfo? {
        val all = runCatching { windows }.getOrNull() ?: return null
        val application = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (application.isEmpty()) return null
        return application.firstOrNull { it.isActive && packageOf(it) != packageName }
            ?: application.firstOrNull { it.isActive }
            ?: application.firstOrNull { it.isFocused }
    }

    private fun packageOf(window: AccessibilityWindowInfo): String? =
        runCatching { window.root?.packageName?.toString() }.getOrNull()

    private fun currentRoot(): AccessibilityNodeInfo? =
        activeApplicationWindow()?.root ?: runCatching { rootInActiveWindow }.getOrNull()

    private fun screenRect(): Rect {
        val metrics = resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun startPolling() {
        val task = object : Runnable {
            override fun run() {
                runCatching { pollActiveWindow() }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        handler.postDelayed(task, POLL_INTERVAL_MS)
    }

    private fun pollActiveWindow() {
        val pkg = packageOf(activeApplicationWindow() ?: return) ?: return
        if (pkg.isEmpty()) return
        val current = FocusState.focus
        if (current == null || current.packageName != pkg) {
            considerFocus(pkg, lastActivityOf[pkg].orEmpty(), "poll")
            scheduleScan("poll-app-change", 0)
        }
    }

    // --- scanning ---------------------------------------------------------------------------------

    fun scheduleScan(reason: String, delayMs: Long) {
        pendingScan?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingScan = null
            runCatching { scanNow(reason) }
                .onFailure { MiniKeysLog.line("perception: scan($reason) failed: ${it.message}") }
        }
        pendingScan = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /** Reads the screen, publishes the function list to [FocusState] and to the keyboard's key set. */
    fun scanNow(reason: String) {
        val started = System.currentTimeMillis()
        val root = currentRoot()
        if (root == null) {
            MiniKeysLog.line("perception: scan($reason) — no window root available")
            return
        }

        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != FocusState.focus?.packageName) {
            considerFocus(pkg, lastActivityOf[pkg].orEmpty(), "scan")
        }

        val screen = screenRect()
        val result = reader.read(
            root = root,
            targetPackage = pkg,
            screenWidth = screen.width(),
            screenHeight = screen.height(),
        )
        val functions = FunctionExtractor.extract(
            result.elements,
            ExtractOptions(screenWidth = screen.width(), screenHeight = screen.height()),
        )
        val targets = buildTargets(functions, result.elements)
        val signature = FunctionExtractor.signature(functions)
        val report = ScanReport(
            packageName = pkg,
            nodeCount = result.elements.size,
            foreignNodeCount = result.foreignNodes,
            functionCount = functions.size,
            atMillis = System.currentTimeMillis(),
            reason = reason,
            durationMs = System.currentTimeMillis() - started,
            truncated = result.truncated,
        )
        FocusState.setFunctions(functions, targets, report, signature)

        // The keyboard's half: this activity's keys, published for the shortcut page.
        val activity = lastActivityOf[pkg].orEmpty()
        ActivityShortcuts.publish(
            key = "$pkg/$activity",
            label = "${appLabel(pkg)} · $pkg" + if (activity.isBlank()) "" else "/$activity",
            items = functions.map { ActivityShortcuts.Item(it.index, it.label, it.onScreen) },
        )

        if (signature != lastLoggedSignature) {
            lastLoggedSignature = signature
            MiniKeysLog.line(
                "perception scan($reason) pkg=$pkg functions=${functions.size}" +
                    " (${FunctionFormat.counts(functions)}) in ${report.durationMs}ms",
            )
            functions.take(MAX_LOGGED_FUNCTIONS).forEach { MiniKeysLog.line("   " + FunctionFormat.detailRow(it)) }
        }
    }

    @Volatile
    private var lastLoggedSignature: String = ""

    /** Turns each function into a handle that can be run later. */
    private fun buildTargets(functions: List<UiFunction>, elements: List<UiElement>): List<NodeTarget> {
        val byPath = elements.associateBy { it.path }
        return functions.map { function ->
            val element = byPath[function.path]
            NodeTarget(
                index = function.index,
                path = function.path,
                className = element?.className.orEmpty(),
                text = element?.text.orEmpty(),
                description = element?.description.orEmpty(),
                viewId = element?.viewId.orEmpty(),
                bounds = function.bounds,
                label = function.label,
                onScreen = function.onScreen,
                action = actionFor(function.category),
            )
        }
    }

    private fun actionFor(category: FunctionCategory): TargetAction = when (category) {
        FunctionCategory.INPUT -> TargetAction.FOCUS
        FunctionCategory.LONG_PRESS -> TargetAction.LONG_CLICK
        FunctionCategory.SCROLL -> TargetAction.SCROLL_FORWARD
        FunctionCategory.TOGGLE, FunctionCategory.TAP -> TargetAction.CLICK
    }

    // --- running a function ------------------------------------------------------------------------

    /** Runs function #index by pressing its real element. The callback fires on the main thread. */
    fun performFunction(index: Int, onDone: (ActionResult) -> Unit) {
        val target = FocusState.targets.firstOrNull { it.index == index }
        if (target == null) {
            onDone(ActionResult(false, "no element #$index in the current list"))
            return
        }
        worker.execute {
            val result = runCatching { actions.perform(target) }.getOrElse { failure ->
                ActionResult(false, "action crashed: ${failure.javaClass.simpleName}: ${failure.message}")
            }
            handler.post {
                onDone(result)
                scheduleScan("after-action", AFTER_ACTION_SCAN_MS)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: ScanService? = null

        /** True while the system has the screen reader bound. */
        val connected: Boolean get() = instance != null

        fun requestScan(reason: String) {
            instance?.scheduleScan(reason, 0L)
        }

        /** Runs a detected function by index; false when the screen reader is not enabled. */
        fun runFunction(index: Int, onDone: (ActionResult) -> Unit): Boolean {
            val service = instance ?: return false
            service.performFunction(index, onDone)
            return true
        }

        private const val SCAN_AFTER_STATE_MS = 250L
        private const val CONTENT_DEBOUNCE_MS = 500L
        private const val AFTER_ACTION_SCAN_MS = 700L
        private const val POLL_INTERVAL_MS = 1_200L
        private const val FOCUS_DEDUPE_MS = 1_500L
        private const val MAX_LOGGED_FUNCTIONS = 60
    }
}