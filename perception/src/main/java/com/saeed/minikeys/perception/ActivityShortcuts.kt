package com.saeed.minikeys.perception

import android.content.Context
import android.content.SharedPreferences
import com.saeed.minikeys.core.settings.MiniKeysLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The bridge between perception and the keyboard — the one place the two halves meet.
 *
 * The accessibility service walks the screen and publishes here; the keyboard and the edge overlay
 * read here and draw the shortcut page from it. Both live in the same process, so this is a plain
 * in-memory hand-off: no binder, no IPC, no serialisation.
 *
 * **Per-activity keys.** Every activity gets its own list, keyed by `package/activity` and kept in
 * [cache]. Moving to another app replaces the keys; coming back restores that app's keys instantly,
 * even before the first scan of the new screen has finished.
 */
object ActivityShortcuts {

    /** One runnable function of the activity in front, as the keyboard needs to see it. */
    data class Item(val index: Int, val label: String, val onScreen: Boolean)

    private const val FILE = "minikeys_activity_keys"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** `package/Activity` of the screen the keys currently belong to. */
    @Volatile
    var activityKey: String = ""
        private set

    /** Human-readable name of that activity ("Operit · .MainActivity") for the settings card. */
    @Volatile
    var activityLabel: String = ""
        private set

    @Volatile
    private var current: List<Item> = emptyList()

    private val cache = ConcurrentHashMap<String, List<Item>>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The keys the keyboard should show right now (empty when the feature is switched off). */
    val keys: List<Item> get() = if (enabled) current else emptyList()

    val count: Int get() = keys.size

    /** Whether the shortcut page offers activity keys above the fixed Windows combinations. */
    var enabled: Boolean
        get() = prefs?.getBoolean(KEY_ENABLED, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.commit()
            MiniKeysLog.line("activity keys: enabled=$value")
            notifyListeners()
        }

    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        MiniKeysLog.line("activity keys: attached (enabled=$enabled)")
    }

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) runCatching { listener() }
    }

    /**
     * The scanner noticed a new activity. Its cached keys appear immediately, so the shortcut page is
     * never briefly wrong or empty while the first scan of the new screen is still running.
     */
    fun select(key: String, label: String) {
        activityKey = key
        activityLabel = label
        val cached = cache[key] ?: emptyList()
        if (cached.size != current.size || cached != current) {
            current = cached
            notifyListeners()
        }
    }

    /** A finished scan of [key]: this is that activity's shortcut set from now on. */
    fun publish(key: String, label: String, items: List<Item>) {
        cache[key] = items
        activityKey = key
        activityLabel = label
        current = items
        MiniKeysLog.line(
            "activity keys: ${items.count { it.onScreen }} on screen · " +
                "${items.count { !it.onScreen }} off screen for $key",
        )
        notifyListeners()
    }

    fun clear() {
        current = emptyList()
        notifyListeners()
    }

    /**
     * Runs one activity key through the accessibility service — the real element is pressed, no
     * coordinates and no typing. Returns immediately with a status line for the caller's log; the
     * outcome is logged when it arrives, because reaching an off-screen element takes a moment.
     */
    fun run(index: Int): String {
        val item = current.firstOrNull { it.index == index }
            ?: return "activity key #$index is gone — the screen changed"
        val started = ScanService.runFunction(index) { result ->
            MiniKeysLog.line(
                "activity key #$index '${item.label}' -> " +
                    (if (result.ok) "OK" else "FAILED") + ": " + result.message,
            )
        }
        return if (started) "running '${item.label}'…" else "screen reader is not enabled"
    }
}