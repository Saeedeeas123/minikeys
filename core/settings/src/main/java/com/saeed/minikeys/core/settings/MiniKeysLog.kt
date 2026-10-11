package com.saeed.minikeys.core.settings

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trace + crash exporter that survives the process.
 *
 * Two sinks, on purpose:
 *
 *  1. a DURABLE per-run file under the app's external files dir
 *     (`/sdcard/Android/data/com.saeed.minikeys/files/logs/`), appended and flushed on EVERY line, so
 *     a process that dies mid-action still leaves everything up to that instant on disk. This is the
 *     only diagnostic channel on this device: logcat, tombstones and dropbox are unreachable.
 *  2. an in-memory queue that the "Export log" buttons and the crash handler copy into shared
 *     Downloads (MediaStore) — the copy a human can pick up without a PC.
 *
 * One file per run, previous runs retained (see [KEEP_RUNS]); nothing is overwritten on exit.
 */
object MiniKeysLog {

    private const val TAG = "minikeys"
    private const val KEEP_RUNS = 10

    private val queue = StringBuilder()

    @Volatile
    private var runFile: File? = null

    /**
     * Opens this run's log file. Called from Application.onCreate and from every service, so whichever
     * component starts first creates it; later calls are no-ops.
     */
    @Synchronized
    fun attach(context: Context) {
        if (runFile != null) return
        try {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(root, "logs")
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("cannot create " + dir)
            val target = File(dir, "minikeys-" + stamp() + ".log")
            target.createNewFile()
            runFile = target
            prune(dir)
            line("=== log opened: " + target.absolutePath + " ===")
            line(
                "device=" + Build.MANUFACTURER + " " + Build.MODEL +
                    " android=" + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")" +
                    " abi=" + Build.SUPPORTED_ABIS.firstOrNull(),
            )
        } catch (t: Throwable) {
            runFile = null
            line("LOG FILE UNAVAILABLE (" + t.javaClass.simpleName + ": " + t.message + ") — trace is memory-only")
        }
    }

    /** Absolute path of this run's file, or null when the file could not be opened. */
    fun runLogPath(): String? = runFile?.absolutePath

    /** Previous runs, newest first. Basis of the in-app log viewer. */
    fun sessions(context: Context): List<File> {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "logs")
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { file ->
            file.isFile && file.name.startsWith("minikeys-") && file.name.endsWith(".log")
        }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP_RUNS).forEach { runCatching { it.delete() } }
    }

    @Synchronized
    fun line(msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i(TAG, msg)
        val entry = ts + " " + msg + "\n"
        queue.append(entry)
        val file = runFile ?: return
        // appendText opens + closes the stream, which flushes: a crash right after this line keeps it.
        runCatching { file.appendText(entry, Charsets.UTF_8) }
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
        val fileName = "MiniKeys-$name-${stamp()}.log.txt"
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