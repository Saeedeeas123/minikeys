package com.saeed.minikeys

import android.app.Application
import com.saeed.minikeys.core.settings.MiniKeysLog
import com.saeed.minikeys.perception.ActivityShortcuts
import com.saeed.minikeys.perception.ScanService

/**
 * Registers a last-resort uncaught-exception handler that exports the stack trace + the
 * accumulated MiniKeysLog trace into shared Downloads (MediaStore), then hands control back
 * to the system default handler so the crash still surfaces normally.
 */
class CrashReportApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The activity-key bridge needs a Context for its persisted on/off switch, and the app is the
        // earliest place that has one.
        ActivityShortcuts.attach(this)
        MiniKeysLog.line("app onCreate")
        MiniKeysLog.line(
            "activity keys: enabled=" + ActivityShortcuts.enabled +
                " screen reader connected=" + ScanService.connected,
        )
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            MiniKeysLog.line("UNCAUGHT on thread " + thread.name)
            MiniKeysLog.writeCrash(this, "Uncaught exception on " + thread.name, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
