package com.saeed.minikeys.core.llama

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** File format this engine can load: GGUF only (that is what llama.cpp consumes). */
object LlamaModelFormats {
    const val EXTENSION = ".gguf"
    fun isSupported(fileName: String): Boolean = fileName.lowercase().endsWith(EXTENSION)

    /** First 4 bytes of every GGUF file are the ASCII magic "GGUF". */
    fun hasGgufMagic(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val magic = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = stream.read(magic, read, 4 - read)
                if (n < 0) break
                read += n
            }
            read == 4 && magic[0] == 0x47.toByte() && magic[1] == 0x47.toByte() &&
                magic[2] == 0x55.toByte() && magic[3] == 0x46.toByte()
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Owns the on-device GGUF models: listing, SAF import and selection. The folder is shared with
 * the MediaPipe engine on purpose (`filesDir/models`) — one import serves both engines.
 */
class LlamaModelStore(context: Context) {

    private val appContext: Context = context.applicationContext

    val modelsDir: File = File(appContext.filesDir, "models").apply { mkdirs() }

    private val prefs = appContext.getSharedPreferences(SELECTION_PREFS, Context.MODE_PRIVATE)

    fun list(): List<File> = modelsDir.listFiles()
        ?.filter { it.isFile }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    fun supportedFiles(): List<File> = list().filter { LlamaModelFormats.isSupported(it.name) }

    fun currentSelection(): String = prefs.getString(KEY_SELECTED, "").orEmpty()

    fun saveSelection(path: String) {
        prefs.edit().putString(KEY_SELECTED, path).commit()
    }

    /** Materializes a SAF document into the models folder (.part first, then rename). */
    fun importFrom(uri: Uri, displayName: String?, progress: (copied: Long, total: Long?) -> Unit): File {
        val resolver = appContext.contentResolver
        val total = querySize(resolver, uri)
        val target = File(modelsDir, LlamaModelFileNames.sanitize(displayName ?: "picked-model"))
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
        const val SELECTION_PREFS = "minikeys_llama_cpp"
        const val KEY_SELECTED = "selected_model_path"
        const val BUFFER_SIZE_BYTES = 64 * 1024
    }
}

/** Neutralizes separators and path characters in model file names. */
object LlamaModelFileNames {
    fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model" }
}
