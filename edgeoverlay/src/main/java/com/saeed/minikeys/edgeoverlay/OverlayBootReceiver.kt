package com.saeed.minikeys.edgeoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saeed.minikeys.core.settings.MiniKeysLog

/**
 * Brings the always-on overlay back after a reboot or after the app is updated.
 *
 * The service is started in SYNC mode: the strips are re-attached only if they were on when the phone
 * went down, while the notification — and therefore the ON/OFF control — is available again either
 * way. Nothing here decides anything by itself; the persisted setting is the truth.
 */
class OverlayBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        MiniKeysLog.attach(context)
        MiniKeysLog.line("boot: restarting edge overlay service (" + action + ")")
        EdgeOverlayService.sync(context)
    }
}