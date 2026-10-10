package com.saeed.minikeys.core.mediapipe

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** File formats the offline engine can plausibly load. */
object LocalModelFormats {
    val SUPPORTED_EXTENSIONS = listOf(".task", ".litertlm", ".bin")

    fun isSupported(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return SUPPORTED_EXTENSIONS.any { lower.endsWith(it) }
    }

    /** .task bundles are ZIP archives ("PK" magic). Other extensions are passed through unchecked. */
    fun hasZipMagic(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val magic = ByteArray(2)
            var read = 0
            while (read < 2) {
                val n = stream.read(magic, read, 2 - read)
                if (n < 0) break
                read += n
            }
            read == 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Owns the on-device model folder (`filesDir/models`): listing, importing files picked through the
 * storage picker, remembering the selected model.
 */
class LocalModelStore(context: Context) {

    private val appContext: Context = context.applicationContext

    val modelsDir: File =
        File(appContext.filesDir, "models").apply { mkdirs() }

    private val prefs: android.content.SharedPreferences =
        appContext.getSharedPreferences(SELECTION_PREFS, Context.MODE_PRIVATE)

    fun list(): List<File> =
        modelsDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun supportedFiles(): List<File> = list().filter { LocalModelFormats.isSupported(it.name) }

    fun currentSelection(): String = prefs.getString(KEY_SELECTED, "").orEmpty()

    fun saveSelection(path: String) {
        prefs.edit().putString(KEY_SELECTED, path).commit()
    }

    /**
     * Materializes a storage-access-framework document into the models folder (the engine needs a
     * real file path, not a content URI). Writes to a .part file first, then renames.
     */
    fun importFrom(uri: Uri, displayName: String?, progress: (copied: Long, total: Long?) -> Unit): File {
        val resolver = appContext.contentResolver
        val total = querySize(resolver, uri)
        val target = File(modelsDir, LocalModelFileNames.sanitize(displayName ?: "picked-model"))
        val partial = File(modelsDir, target.name + ".part")

        val input = resolver.openInputStream(uri) ?: throw IOException("cannot open the picked file")
        input.use { stream ->
            FileOutputStream(partial).use { out ->
                var copied = 0L
                val buffer = ByteArray(BUFFER_SIZE_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    progress(copied, total)
                }
                out.flush()
            }
        }
        val received = partial.length()
        if (total != null && received < total) {
            partial.delete()
            throw IOException("incomplete file: received $received of $total bytes — retry picking the file")
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("could not finalize the imported file")
        }
        return target
    }

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private companion object {
        const val SELECTION_PREFS = "minikeys_local_llm"
        const val KEY_SELECTED = "selected_model_path"
        const val BUFFER_SIZE_BYTES = 64 * 1024
    }
}

/** Shared naming rules for model files on disk: neutralizes separators and path characters. */
object LocalModelFileNames {
    fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "model" }
}