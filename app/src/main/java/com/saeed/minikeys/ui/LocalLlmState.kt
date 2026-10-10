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
        val problem = modelProblem(path)
        if (problem != null) {
            operationStatus = problem
            return
        }
        if (testing) return
        testing = true
        operationStatus = "Loading model (this can take a few seconds)"
        Thread {
            MiniKeysLog.line("MEDIAPIPE test start path=" + path + " size=" + File(path).length())
            val result = try {
                MiniKeysLog.line("MEDIAPIPE loading model")
                val engine = LocalLlmRuntime.get(appContext, path)
                MiniKeysLog.line("MEDIAPIPE loaded, chatting")
                val reply = engine.chat(listOf(ChatMessage("user", TEST_PROMPT)))
                MiniKeysLog.line("MEDIAPIPE reply latencyMs=" + reply.latencyMs + " len=" + reply.text.length)
                "LOCAL OK - " + reply.model + " answered in " + reply.latencyMs + " ms: " +
                    (34).toChar() + reply.text.trim().take(120) + (34).toChar()
            } catch (t: Throwable) {
                MiniKeysLog.line("MEDIAPIPE FAILED " + t.javaClass.name + ": " + t.message)
                LocalLlmRuntime.close()
                "LOCAL FAILED - " + t.javaClass.simpleName + ": " + (t.message ?: "no message") +
                    " - tap Export log, the trace is saved."
            }
            operationStatus = result
            testing = false
        }.start()
    }

    /**
     * Acceptance test for repeated generation on the MediaPipe engine: [repeats] sequential
     * generateResponse() calls on ONE loaded engine. Catches a runtime that degrades or stops
     * answering after the first calls, and measures the per-call cost.
     */
    fun stressTest(repeats: Int = STRESS_REPEATS) {
        val path = selectedModelPath
        val problem = modelProblem(path)
        if (problem != null) {
            operationStatus = problem
            return
        }
        if (testing) return
        testing = true
        operationStatus = "Stress test: loading the model"
        Thread {
            MiniKeysLog.line("MEDIAPIPE stress start path=$path repeats=$repeats")
            var ok = 0
            var empty = 0
            var failed = 0
            var firstError = ""
            var totalMs = 0L
            var slowestMs = 0L
            var fastestMs = Long.MAX_VALUE
            var lastText = ""
            val result = try {
                val engine = LocalLlmRuntime.get(appContext, path)
                MiniKeysLog.line("MEDIAPIPE stress loaded")
                for (i in 1..repeats) {
                    try {
                        val reply = engine.chat(listOf(ChatMessage("user", TEST_PROMPT)))
                        totalMs += reply.latencyMs
                        slowestMs = maxOf(slowestMs, reply.latencyMs)
                        fastestMs = minOf(fastestMs, reply.latencyMs)
                        lastText = reply.text.trim()
                        if (lastText.isEmpty()) empty++ else ok++
                        MiniKeysLog.line(
                            "MEDIAPIPE stress $i/$repeats latencyMs=" + reply.latencyMs +
                                " len=" + reply.text.length + " text=" + lastText.take(40)
                        )
                    } catch (t: Throwable) {
                        failed++
                        if (firstError.isEmpty()) {
                            firstError = t.javaClass.simpleName + ": " + (t.message ?: "no message")
                        }
                        MiniKeysLog.line(
                            "MEDIAPIPE stress $i/$repeats FAILED " + t.javaClass.name + ": " + t.message
                        )
                    }
                    operationStatus = "Stress test $i/$repeats - ok=$ok empty=$empty failed=$failed"
                }
                val avg = if (repeats > 0) totalMs / repeats else 0L
                val fastest = if (fastestMs == Long.MAX_VALUE) "n/a" else "$fastestMs ms"
                val verdict = if (failed == 0 && empty == 0) "LOCAL STRESS PASS" else "LOCAL STRESS FAIL"
                verdict + " - " + repeats + " calls on one loaded session: ok=" + ok +
                    " empty=" + empty + " failed=" + failed + " | avg=" + avg + " ms, fastest=" +
                    fastest + ", slowest=" + slowestMs + " ms | last reply: " +
                    (34).toChar() + lastText.take(60) + (34).toChar() +
                    (if (firstError.isEmpty()) "" else " | first error: " + firstError)
            } catch (t: Throwable) {
                MiniKeysLog.line("MEDIAPIPE stress setup FAILED " + t.javaClass.name + ": " + t.message)
                LocalLlmRuntime.close()
                "LOCAL STRESS FAIL - " + t.javaClass.simpleName + ": " + (t.message ?: "no message")
            }
            operationStatus = result
            testing = false
        }.start()
    }

    /** Shared validation so Test and the stress test reject the same bad inputs identically. */
    private fun modelProblem(path: String): String? {
        if (path.isBlank() || !path.startsWith(store.modelsDir.absolutePath)) {
            return "Select or download a supported model first."
        }
        if (!LocalModelFormats.isSupported(File(path).name)) {
            return "Unsupported format: " + File(path).name + " (expected .task / .litertlm / .bin)."
        }
        val modelFile = File(path)
        if (!modelFile.isFile || modelFile.length() == 0L) {
            return "Model file is empty or missing - re-import or re-download it."
        }
        if (modelFile.name.lowercase().endsWith(".task") && !LocalModelFormats.hasZipMagic(modelFile)) {
            return "Not a .task bundle (bad header) - the file is corrupt; re-import or re-download it."
        }
        if (modelFile.name.lowercase().endsWith(".litertlm") && !LocalModelFormats.hasLitertlmMagic(modelFile)) {
            return "Not a .litertlm bundle (bad header) - the file is corrupt; re-import or re-download it."
        }
        return null
    }

    fun exportLog() {
        operationStatus = "Log exported: " + MiniKeysLog.flush(appContext, "mediapipe")
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
        /** Exactly the same prompt for every call, so the stress loop is comparable. */
        const val TEST_PROMPT = "Reply to this message with exactly: OK"
        const val STRESS_REPEATS = 20

        /** A known LiteRT-community bundle; both fields stay fully editable. */
        const val DEFAULT_HF_REPO = "litert-community/gemma-3-1b-it"
        const val DEFAULT_HF_FILE = "gemma-3-1b-it.task"
    }
}