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
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.saeed.minikeys.core.settings.MiniKeysLog
import com.saeed.minikeys.core.settings.MiniKeysRuntime
import com.saeed.minikeys.core.settings.OverlaySettings
import com.saeed.minikeys.core.settings.OverlaySettingsStore
import com.saeed.minikeys.keyboard.KeyDispatcher
import com.saeed.minikeys.keyboard.KeyboardPage
import com.saeed.minikeys.perception.ActivityShortcuts

/**
 * Always-on EDGE OVERLAY — the two vertical key strips, and the ON/OFF control in the notification.
 *
 * Two independent overlay windows, one per screen edge, each exactly as wide as its key strip. Because
 * neither window covers the middle of the screen, touches in the middle reach the app behind it
 * natively: no touchable-region tricks, no IME window, nothing to fight the system about.
 *
 * THREE THINGS THIS SERVICE IS RESPONSIBLE FOR
 *
 *  1. The strips. Attached or detached, never half-attached: [attachStrips] only reports success after
 *     both windows are really on screen.
 *  2. The keyboard handover. While the strips are attached, `MiniKeysRuntime.overlayAttached` is true,
 *     and the IME ([com.saeed.minikeys.keyboard.KeyboardService]) answers with a 1px invisible input
 *     view: the strips ARE the keyboard, so no second keyboard may slide up. While they are detached
 *     the IME shows its normal keyboard again. That flag is the single source of truth for the handover.
 *  3. The ON/OFF switch in the notification. The service therefore stays alive while the strips are
 *     OFF: a notification that disappears when it is switched off could never switch it back on.
 *     The persisted `enabled` flag — not the service lifetime — decides whether the strips are shown.
 *
 * Typing itself is not done here: a key press is handed to [KeyDispatcher], which pushes it through the
 * IME's `InputConnection` (the only door an app has to the focused field of another app).
 */
class EdgeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var store: OverlaySettingsStore

    private var leftView: EdgeColumnView? = null
    private var rightView: EdgeColumnView? = null
    private var leftParams: WindowManager.LayoutParams? = null
    private var rightParams: WindowManager.LayoutParams? = null

    /** Page and caps are shared by both strips, so they live here and are pushed into both views. */
    private var page: KeyboardPage = KeyboardPage.LETTERS
    private var caps: Boolean = false

    /** Last typing-readiness shown in the notification, so we only refresh it when it really changed. */
    private var shownTypingReady: Boolean? = null
    private var foregroundStarted = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (leftView != null) applySettings()
    }

    /** The IME opens and closes its connection as text fields gain and lose focus. */
    private val connectionListener: () -> Unit = { refreshNotificationIfNeeded() }

    /**
     * The activity in front changed → the shortcut page's key set is different → both strips are
     * rebuilt from the new keys (this is what makes the ⌃ page per-activity on the edge overlay too).
     */
    private val activityKeysListener: () -> Unit = {
        leftView?.refreshKeys()
        rightView?.refreshKeys()
    }

    override fun onCreate() {
        super.onCreate()
        MiniKeysLog.attach(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        store = OverlaySettingsStore.from(this)
        store.rawValues() // touch the store so the preferences file exists for the listener
        store.addListener(prefsListener)
        KeyDispatcher.addConnectionListener(connectionListener)
        ActivityShortcuts.addListener(activityKeysListener)
        createChannel()
        MiniKeysLog.line("EDGE overlay: service created (enabled=" + store.load().enabled + ")")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification must exist before anything else: it is the ON/OFF control and the service
        // has only a few seconds to go foreground after startForegroundService.
        startForegroundSafely()

        when (intent?.action) {
            ACTION_START -> setEnabled(true, "start request")
            ACTION_STOP -> setEnabled(false, "stop request")
            ACTION_TOGGLE -> setEnabled(!store.load().enabled, "notification toggle")
            else -> {
                // Sync (boot, app update, sticky restart): the persisted flag is the only truth.
                val enabled = store.load().enabled
                MiniKeysLog.line("EDGE overlay: sync -> enabled=" + enabled)
                if (enabled) attachStrips("sync") else detachStrips("sync")
            }
        }

        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        KeyDispatcher.removeConnectionListener(connectionListener)
        ActivityShortcuts.removeListener(activityKeysListener)
        runCatching { store.removeListener(prefsListener) }
        detachStrips("service destroyed")
        MiniKeysLog.line("EDGE overlay: service destroyed")
        super.onDestroy()
    }

    // --- on / off ---------------------------------------------------------------------------------

    /**
     * The one place that flips the switch. Writes the flag to disk FIRST, then makes the screen match
     * it, so a crash in between leaves the stored truth and the next sync repairs the screen.
     */
    private fun setEnabled(enabled: Boolean, source: String) {
        store.setEnabled(enabled)
        MiniKeysLog.line("EDGE overlay: enabled=" + enabled + " (source: " + source + ")")
        if (enabled) attachStrips(source) else detachStrips(source)
    }

    // --- overlay windows --------------------------------------------------------------------------

    private fun attachStrips(source: String) {
        if (leftView != null) {
            MiniKeysRuntime.overlayAttached = true
            return
        }

        val left = EdgeColumnView(this).apply {
            side = EdgeColumnView.Side.LEFT
            onPageRequest = ::requestPage
            onCapsRequest = ::requestCaps
        }
        val right = EdgeColumnView(this).apply {
            side = EdgeColumnView.Side.RIGHT
            onPageRequest = ::requestPage
            onCapsRequest = ::requestCaps
        }

        val lp = buildParams(Gravity.START)
        val rp = buildParams(Gravity.END)

        val added = runCatching {
            windowManager.addView(left, lp)
            windowManager.addView(right, rp)
        }

        if (added.isFailure) {
            val error = added.exceptionOrNull()
            MiniKeysLog.line(
                "EDGE overlay: addView FAILED " + error?.javaClass?.simpleName + ": " + error?.message +
                    " — is the draw-over-other-apps permission granted?",
            )
            runCatching { windowManager.removeView(left) }
            runCatching { windowManager.removeView(right) }
            running = false
            MiniKeysRuntime.overlayAttached = false
            return
        }

        leftView = left
        rightView = right
        leftParams = lp
        rightParams = rp
        running = true

        left.setState(page, caps)
        right.setState(page, caps)
        applySettings()

        // Only now, with both windows really on screen, is the IME told to go silent — the strips have
        // taken over typing, so no second keyboard may appear under them.
        MiniKeysRuntime.overlayAttached = true

        MiniKeysLog.line(
            "EDGE overlay: strips attached (" + source + ", width=" + lp.width + "px, page=" + page +
                ", caps=" + caps + ", IME silent=" + MiniKeysRuntime.overlayAttached + ")",
        )
    }

    private fun detachStrips(source: String) {
        val hadStrips = leftView != null
        removeOverlays()
        running = false
        MiniKeysRuntime.overlayAttached = false
        if (hadStrips) {
            MiniKeysLog.line("EDGE overlay: strips detached (" + source + ") — the normal keyboard is back")
        }
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
        leftParams?.let { params ->
            params.width = width
            leftView?.let { view -> runCatching { windowManager.updateViewLayout(view, params) } }
        }
        rightParams?.let { params ->
            params.width = width
            rightView?.let { view -> runCatching { windowManager.updateViewLayout(view, params) } }
        }
        leftView?.applySettings(settings)
        rightView?.applySettings(settings)
    }

    private fun removeOverlays() {
        leftView?.let { view -> runCatching { windowManager.removeView(view) } }
        rightView?.let { view -> runCatching { windowManager.removeView(view) } }
        leftView = null
        rightView = null
        leftParams = null
        rightParams = null
    }

    // --- shared page state ------------------------------------------------------------------------

    /** A page key (numbers/symbols, emoji, shortcuts, abc) rewrites BOTH strips from the top-left. */
    private fun requestPage(requested: KeyboardPage) {
        if (page == requested) return
        page = requested
        pushState()
        MiniKeysLog.line("EDGE overlay: page -> " + page)
    }

    private fun requestCaps() {
        caps = !caps
        pushState()
        MiniKeysLog.line("EDGE overlay: caps -> " + caps)
    }

    private fun pushState() {
        leftView?.setState(page, caps)
        rightView?.setState(page, caps)
    }

    // --- notification (the ON/OFF control) --------------------------------------------------------

    private fun startForegroundSafely() {
        if (foregroundStarted) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            foregroundStarted = true
        } catch (t: Throwable) {
            MiniKeysLog.line(
                "EDGE overlay: startForeground FAILED " + t.javaClass.simpleName + ": " + t.message,
            )
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Edge keys overlay",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "ON/OFF switch for the always-on edge key strips"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val enabled = store.load().enabled
        val typingReady = KeyDispatcher.hasConnection
        val imeSelected = isMiniKeysTheActiveKeyboard()

        val title = if (enabled) "Edge keys ON" else "Edge keys OFF"
        val text = when {
            !enabled -> "Turn on to type from the screen edges."
            // The strips can only type through the MiniKeys IME's connection, so say it plainly when
            // another keyboard is selected instead of leaving the user with silent keys.
            !imeSelected -> "Typing needs MiniKeys as the keyboard — select it in Settings."
            typingReady -> "Typing ready — the focused text field is connected."
            else -> "Waiting for a text field: tap a text box, then the strips type into it."
        }

        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, EdgeOverlayService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(0, if (enabled) "Turn off" else "Turn on", toggleIntent)

        appLaunchIntent()?.let { launch ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    2,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        shownTypingReady = KeyDispatcher.hasConnection
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
            .onFailure { MiniKeysLog.line("EDGE overlay: notification update failed " + it.message) }
    }

    /** Refreshes the notification only when the typing-readiness line would actually change. */
    private fun refreshNotificationIfNeeded() {
        val ready = KeyDispatcher.hasConnection
        if (shownTypingReady == ready) return
        MiniKeysLog.line("EDGE overlay: typing readiness -> " + ready)
        updateNotification()
    }

    private fun appLaunchIntent(): Intent? =
        packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }

    /**
     * The strips type through the MiniKeys IME, so they are only useful while that IME is the selected
     * one. `DEFAULT_INPUT_METHOD` looks like `com.saeed.minikeys/.keyboard.KeyboardService`.
     */
    private fun isMiniKeysTheActiveKeyboard(): Boolean {
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return current != null && current.startsWith(packageName)
    }

    companion object {
        const val ACTION_START = "com.saeed.minikeys.edgeoverlay.action.START"
        const val ACTION_STOP = "com.saeed.minikeys.edgeoverlay.action.STOP"
        const val ACTION_TOGGLE = "com.saeed.minikeys.edgeoverlay.action.TOGGLE"

        private const val CHANNEL_ID = "minikeys_edge_overlay"
        private const val NOTIFICATION_ID = 4711

        /** True while the strips are really attached; the app uses it to label its toggle honestly. */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) = send(context, ACTION_START)

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun toggle(context: Context) = send(context, ACTION_TOGGLE)

        /**
         * Boot / app-update entry point: brings the service (and therefore the ON/OFF control) back,
         * and re-attaches the strips only if they were on. Nothing is decided here — the stored flag is.
         */
        fun sync(context: Context) = send(context, null)

        private fun send(context: Context, action: String?) {
            val intent = Intent(context, EdgeOverlayService::class.java)
            if (action != null) intent.action = action
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    MiniKeysLog.line("EDGE overlay: start failed " + it.javaClass.simpleName + ": " + it.message)
                }
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
