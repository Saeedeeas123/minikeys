package com.saeed.minikeys.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saeed.minikeys.core.llm.ChatMessage
import com.saeed.minikeys.core.mediapipe.HuggingFaceModelDownloader
import com.saeed.minikeys.core.mediapipe.LocalLlmRuntime
import com.saeed.minikeys.core.mediapipe.LocalModelFormats
import com.saeed.minikeys.core.mediapipe.LocalModelStore
import java.io.File

/**
 * State of the offline (local) model card. Every heavy operation — storage import, HF download,
 * model load, inference — runs on a background thread; progress lands in Compose state.
 */
class LocalLlmState(context: Context) {

    private val appContext: Context = context.applicationContext
    private val store = LocalModelStore(appContext)

    var modelFiles: List<File> by mutableStateOf(store.list())
        private set

    var selectedModelPath: String by mutableStateOf(store.currentSelection())

    var operationStatus: String by mutableStateOf(defaultStatus())

    var downloadProgress: String by mutableStateOf("")
        private set

    var testing: Boolean by mutableStateOf(false)
        private set

    var hfRepo: String by mutableStateOf(DEFAULT_HF_REPO)
    var hfFileName: String by mutableStateOf(DEFAULT_HF_FILE)
    var hfToken: String by mutableStateOf("")

    fun refresh() {
        modelFiles = store.list()
    }

    fun select(path: String) {
        selectedModelPath = path
        store.saveSelection(path)
    }

    fun copyPickedDocument(uri: Uri) {
        operationStatus = "Importing picked file…"
        downloadProgress = ""
        Thread {
            try {
                val target = store.importFrom(
                    uri = uri,
                    displayName = displayName(appContext, uri),
                    progress = { copied, total ->
                        downloadProgress = progressText(copied, total)
                    },
                )
                refresh()
                select(target.absolutePath)
                unloadModel()
                operationStatus = "Imported ${target.name} — tap Test to load it."
            } catch (e: Exception) {
                operationStatus = "Import failed — ${e.message ?: e.javaClass.simpleName}"
            } finally {
                downloadProgress = ""
            }
        }.start()
    }

    fun downloadFromHuggingFace() {
        val repo = hfRepo.trim()
        val fileName = hfFileName.trim()
        if (repo.isBlank() || fileName.isBlank()) {
            operationStatus = "Repository path and file name are both required."
            return
        }
        operationStatus = "Downloading $repo / $fileName …"
        downloadProgress = ""
        Thread {
            try {
                val target = HuggingFaceModelDownloader(store.modelsDir)
                    .download(
                        repoId = repo,
                        fileName = fileName,
                        token = hfToken.trim().ifBlank { null },
                        progress = { copied, total ->
                            downloadProgress = progressText(copied, total)
                        },
                    )
                refresh()
                select(target.absolutePath)
                unloadModel()
                operationStatus = "Downloaded ${target.name} — tap Test to load it."
            } catch (e: Exception) {
                operationStatus = "Download failed — ${e.message ?: e.javaClass.simpleName}"
            } finally {
                downloadProgress = ""
            }
        }.start()
    }

    fun testLocal() {
        val path = selectedModelPath
        if (path.isBlank() || !path.startsWith(store.modelsDir.absolutePath)) {
            operationStatus = "Select or download a supported model first."
            return
        }
        if (!LocalModelFormats.isSupported(File(path).name)) {
            operationStatus = "Unsupported format: ${File(path).name} (expected .task / .litertlm / .bin)."
            return
        }
        if (testing) return
        testing = true
        operationStatus = "Loading model (this can take a few seconds)…"
        Thread {
            val result = try {
                val reply = LocalLlmRuntime.get(appContext, path)
                    .chat(listOf(ChatMessage("user", "Reply to this message with exactly: OK")))
                "LOCAL OK — ${reply.model} answered in ${reply.latencyMs} ms: " +
                    "\"${reply.text.trim().take(120)}\""
            } catch (e: Exception) {
                LocalLlmRuntime.close()
                "LOCAL FAILED — ${e.message ?: e.javaClass.simpleName}"
            }
            operationStatus = result
            testing = false
        }.start()
    }

    fun unloadModel() {
        LocalLlmRuntime.close()
        if (operationStatus.startsWith("LOCAL OK")) {
            operationStatus = "Model unloaded from memory."
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun progressText(copied: Long, total: Long?): String {
        val done = humanBytes(copied)
        val of = total?.let { " of ${humanBytes(it)}" }.orEmpty()
        return "$done$of …"
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.1f KB".format(bytes / 1024.0)
    }

    private fun defaultStatus(): String =
        "No local model yet — pick one from device storage or download from Hugging Face."

    private companion object {
        /** A known LiteRT-community bundle; both fields stay fully editable. */
        const val DEFAULT_HF_REPO = "litert-community/gemma-3-1b-it"
        const val DEFAULT_HF_FILE = "gemma-3-1b-it.task"
    }
}