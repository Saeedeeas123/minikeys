package com.saeed.minikeys.edgeoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.saeed.minikeys.core.settings.MiniKeysLog
import com.saeed.minikeys.core.settings.OverlaySettings
import com.saeed.minikeys.core.settings.OverlaySettingsStore

/**
 * Always-on EDGE OVERLAY.
 *
 * Two independent overlay windows — one per screen edge — each exactly as wide as its key strip.
 * Because neither window covers the middle of the screen, touches in the middle reach the app behind
 * it natively: no touchable-region tricks, no IME window, nothing to fight the system about.
 *
 * Lifecycle: started from the app (or its notification action), stays alive as a foreground service
 * until the user turns it off from the notification or from the app.
 */
class EdgeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var store: OverlaySettingsStore

    private var leftView: EdgeColumnView? = null
    private var rightView: EdgeColumnView? = null
    private var leftParams: WindowManager.LayoutParams? = null
    private var rightParams: WindowManager.LayoutParams? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (running) applySettings()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        store = OverlaySettingsStore.from(this)
        store.rawValues() // touch the store so the prefs file exists for the listener
        store.addListener(prefsListener)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                MiniKeysLog.line("EDGE overlay: stop requested")
                store.setEnabled(false)
                stopEverything()
                return START_NOT_STICKY
            }

            else -> {
                val settings = store.load()
                if (intent?.action == ACTION_START) store.setEnabled(true)
                if (!settings.enabled && intent?.action != ACTION_START) {
                    stopEverything()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                showOverlays()
                applySettings()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        runCatching { store.removeListener(prefsListener) }
        removeOverlays()
        super.onDestroy()
    }

    // --- overlay windows ----------------------------------------------------------------------

    private fun showOverlays() {
        if (leftView != null) return
        val left = EdgeColumnView(this).apply { side = EdgeColumnView.Side.LEFT }
        val right = EdgeColumnView(this).apply { side = EdgeColumnView.Side.RIGHT }
        left.onKeyPressed = ::onKeyPressed
        right.onKeyPressed = ::onKeyPressed

        val lp = buildParams(Gravity.START)
        val rp = buildParams(Gravity.END)

        leftParams = lp
        rightParams = rp
        leftView = left
        rightView = right

        runCatching {
            windowManager.addView(left, lp)
            windowManager.addView(right, rp)
        }.onFailure { error ->
            MiniKeysLog.line("EDGE overlay: addView failed " + error.javaClass.simpleName + ": " + error.message)
            leftView = null
            rightView = null
        }
        running = true
        MiniKeysLog.line("EDGE overlay: windows added")
    }

    private fun buildParams(gravity: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            stripWidthPx(),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable (never steals typing), not touch-modal, laid out over the whole screen so
            // the strips can reach the top and bottom edges of the display.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = Gravity.TOP or gravity
        }

    private fun stripWidthPx(): Int {
        val metrics = resources.displayMetrics
        val settings = store.load()
        return (metrics.widthPixels * settings.columnWidthPercent / 100f).toInt()
            .coerceAtLeast((24f * metrics.density).toInt())
    }

    private fun applySettings() {
        val settings = store.load()
        val width = stripWidthPx()
        leftParams?.let { it.width = width; leftView?.let { view -> windowManager.updateViewLayout(view, it) } }
        rightParams?.let { it.width = width; rightView?.let { view -> windowManager.updateViewLayout(view, it) } }
        leftView?.applySettings(settings)
        rightView?.applySettings(settings)
        updateNotification()
    }

    private fun removeOverlays() {
        leftView?.let { runCatching { windowManager.removeView(it) } }
        rightView?.let { runCatching { windowManager.removeView(it) } }
        leftView = null
        rightView = null
        leftParams = null
        rightParams = null
    }

    /** Pressed key: visual for now — the seam for per-app shortcut actions later. */
    private fun onKeyPressed(key: com.saeed.minikeys.keyboard.KeyDef) {
        MiniKeysLog.line("EDGE key pressed: label=" + key.label + " action=" + key.action)
    }

    private fun stopEverything() {
        removeOverlays()
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // --- notification -------------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Edge keys overlay",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the always-on edge shortcut strips running"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = appLaunchIntent()
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, EdgeOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Edge keys are ON")
            .setContentText("Two key strips on the screen edges. Turn off here or in MiniKeys.")
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(0, "Turn off", stopIntent)

        if (openApp != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    2,
                    openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun appLaunchIntent(): Intent? =
        packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }

    companion object {
        const val ACTION_START = "com.saeed.minikeys.edgeoverlay.action.START"
        const val ACTION_STOP = "com.saeed.minikeys.edgeoverlay.action.STOP"
        private const val CHANNEL_ID = "minikeys_edge_overlay"
        private const val NOTIFICATION_ID = 4711

        /** True while the strips are attached; the app uses it to label its toggle honestly. */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, EdgeOverlayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EdgeOverlayService::class.java).setAction(ACTION_STOP))
        }

        fun canDrawOverlays(context: Context): Boolean =
            android.provider.Settings.canDrawOverlays(context)

        fun overlayPermissionIntent(context: Context): Intent =
            Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
