package com.saeed.minikeys.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saeed.minikeys.core.settings.MiniKeysLog
import com.saeed.minikeys.core.settings.OverlaySettings
import com.saeed.minikeys.core.settings.OverlaySettingsStore
import com.saeed.minikeys.edgeoverlay.EdgeOverlayService

/**
 * State of the always-on edge overlay card: permission, run state and the strip sizing.
 * Every write goes to disk first, then the screen re-reads it — never an optimistic guess.
 */
class OverlayState(context: Context) {

    private val appContext: Context = context.applicationContext
    private val store = OverlaySettingsStore.from(appContext)

    var settings: OverlaySettings by mutableStateOf(store.load())
        private set

    var hasOverlayPermission: Boolean by mutableStateOf(EdgeOverlayService.canDrawOverlays(appContext))
        private set

    var running: Boolean by mutableStateOf(EdgeOverlayService.running)
        private set

    fun refresh() {
        settings = store.load()
        hasOverlayPermission = EdgeOverlayService.canDrawOverlays(appContext)
        running = EdgeOverlayService.running
    }

    fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled)
        if (enabled) {
            EdgeOverlayService.start(appContext)
        } else {
            EdgeOverlayService.stop(appContext)
        }
        MiniKeysLog.line("EDGE overlay toggle: enabled=" + enabled)
        refresh()
    }

    fun setColumnWidthPercent(percent: Int) = persist(settings.copy(columnWidthPercent = percent))

    fun setKeyHeightDp(heightDp: Int) = persist(settings.copy(keyHeightDp = heightDp))

    fun setKeyTextSizeSp(textSizeSp: Int) = persist(settings.copy(keyTextSizeSp = textSizeSp))

    fun openOverlayPermissionSettings() {
        runCatching { appContext.startActivity(EdgeOverlayService.overlayPermissionIntent(appContext)) }
    }

    fun exportLog() {
        MiniKeysLog.flush(appContext, "edge-overlay")
    }

    private fun persist(updated: OverlaySettings) {
        store.save(updated)
        refresh()
    }
}
