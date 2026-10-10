package com.saeed.minikeys.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trace + crash exporter that survives the process.
 * Writes into shared Downloads THROUGH MediaStore (no storage permission needed on API 29+).
 * Files appear as MiniKeys-*.log|txt inside /sdcard/Download — readable by any file manager
 * and by the coding agent directly. App-private logging is a proven dead end on this device.
 */
object MiniKeysLog {

    private const val TAG = "minikeys"
    private val queue = StringBuilder()

    @Synchronized
    fun line(msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i(TAG, msg)
        queue.append(ts).append(' ').append(msg).append('\n')
    }

    @Synchronized
    fun snapshot(): String = queue.toString()

    @Synchronized
    fun clear() {
        queue.setLength(0)
    }

    private fun newValues(fileName: String) = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }

    private fun finalize(context: Context, uri: Uri, values: ContentValues) {
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** Flush the accumulated trace into a timestamped file in shared Downloads. */
    @Synchronized
    fun flush(context: Context, name: String): String {
        val fileName = "MiniKeys-$name-${stamp()}.log"
        return try {
            val values = newValues(fileName)
            val uri: Uri? = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                "(MediaStore refused the insert)"
            } else {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(queue.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                finalize(context, uri, values)
                "Download/$fileName"
            }
        } catch (t: Throwable) {
            "(export failed: " + t.javaClass.simpleName + ": " + t.message + ")"
        }
    }

    private fun traceOf(t: Throwable): String = buildString {
        var cur: Throwable? = t
        while (cur != null) {
            append(cur.javaClass.name).append(": ").append(cur.message).append('\n')
            cur.stackTrace.take(24).forEach { append("    at ").append(it).append('\n') }
            cur = cur.cause
        }
    }

    /** Writes a standalone crash report file (used by the crash handler). */
    fun writeCrash(context: Context, headline: String, t: Throwable) {
        val fileName = "MiniKeys-crash-${stamp()}.txt"
        try {
            val values = newValues(fileName)
            val uri: Uri? = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write((headline + "\n" + traceOf(t) + "\nTrace so far:\n").toByteArray())
                    out.write(snapshot().toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                finalize(context, uri, values)
            }
        } catch (ignored: Throwable) {
            // nothing more can be done in a dying process
        }
    }
}
