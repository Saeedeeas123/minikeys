package com.saeed.minikeys

import android.app.Application
import com.saeed.minikeys.core.settings.MiniKeysLog

/**
 * Registers a last-resort uncaught-exception handler that exports the stack trace + the
 * accumulated MiniKeysLog trace into shared Downloads (MediaStore), then hands control back
 * to the system default handler so the crash still surfaces normally.
 */
class CrashReportApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MiniKeysLog.line("app onCreate")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            MiniKeysLog.line("UNCAUGHT on thread " + thread.name)
            MiniKeysLog.writeCrash(this, "Uncaught exception on " + thread.name, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
