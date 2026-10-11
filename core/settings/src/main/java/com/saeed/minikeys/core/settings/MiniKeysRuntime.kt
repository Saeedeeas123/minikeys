package com.saeed.minikeys.core.settings

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny process-wide state shared by the two surfaces that have to agree with each other:
 *
 *  - `:edgeoverlay` — the always-on key strips: while they are attached they ARE the visible
 *    keyboard, and they are what the user presses.
 *  - `:keyboard`    — the IME: while the strips are attached it must show nothing at all, but it has
 *    to stay bound to the focused field because it owns the `InputConnection` that actually types.
 *
 * It lives here, in the leaf module, so the one-way dependency flow is preserved: both modules
 * already depend on `:core:settings` and neither has to know the other exists.
 */
object MiniKeysRuntime {

    /** True while the edge strips are attached to the screen. */
    @Volatile
    var overlayAttached: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            listeners.toList().forEach { runCatching { it() } }
        }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The IME uses this to swap its input view the moment the strips appear or disappear. */
    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
