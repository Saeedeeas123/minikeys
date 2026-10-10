package com.saeed.minikeys.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saeed.minikeys.core.llama.LlamaCppOptions
import com.saeed.minikeys.core.llama.LlamaModelFormats
import com.saeed.minikeys.core.llama.LocalLlamaRuntime
import com.saeed.minikeys.core.llama.LlamaModelStore
import com.saeed.minikeys.core.llm.ChatMessage
import com.saeed.minikeys.core.mediapipe.HuggingFaceModelDownloader
import java.io.File

/**
 * State of the GGUF (llama.cpp) card: pick a GGUF -> Test loads it in-process and asks for a
 * deterministic reply. Inference is fully offline; downloads come from Hugging Face when needed.
 */
class LlamaCppState(context: Context) {

    private val appContext: Context = context.applicationContext
    private val store = LlamaModelStore(appContext)

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
                    progress = { copied, total -> downloadProgress = progressText(copied, total) },
                )
                refresh()
                select(target.absolutePath)
                unloadModel()
                operationStatus = "Imported " + target.name + " — tap Test to load it."
            } catch (e: Exception) {
                operationStatus = "Import failed — " + (e.message ?: e.javaClass.simpleName)
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
        operationStatus = "Downloading " + repo + " / " + fileName + " …"
        downloadProgress = ""
        Thread {
            try {
                val target = HuggingFaceModelDownloader(store.modelsDir)
                    .download(
                        repoId = repo,
                        fileName = fileName,
                        token = hfToken.trim().ifBlank { null },
                        progress = { copied, total -> downloadProgress = progressText(copied, total) },
                    )
                refresh()
                select(target.absolutePath)
                unloadModel()
                operationStatus = "Downloaded " + target.name + " — tap Test to load it."
            } catch (e: Exception) {
                operationStatus = "Download failed — " + (e.message ?: e.javaClass.simpleName)
            } finally {
                downloadProgress = ""
            }
        }.start()
    }

    fun testModel() {
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
            MiniKeysLog.line("LLAMA test start path=" + path + " size=" + File(path).length())
            val result = try {
                MiniKeysLog.line("LLAMA loading model")
                val engine = LocalLlamaRuntime.get(path, TEST_OPTIONS)
                MiniKeysLog.line("LLAMA loaded: " + engine.nativeDescription)
                MiniKeysLog.line("LLAMA chatting")
                val reply = engine.chat(listOf(ChatMessage("user", TEST_PROMPT)))
                MiniKeysLog.line("LLAMA reply latencyMs=" + reply.latencyMs + " len=" + reply.text.length)
                "LLAMA OK - " + reply.model + " answered in " + reply.latencyMs + " ms: " +
                    (34).toChar() + reply.text.trim().take(120) + (34).toChar()
            } catch (t: Throwable) {
                MiniKeysLog.line("LLAMA FAILED " + t.javaClass.name + ": " + t.message)
                LocalLlamaRuntime.close()
                "LLAMA FAILED - " + t.javaClass.simpleName + ": " + (t.message ?: "no message") +
                    " - tap Export log, the trace is saved."
            }
            operationStatus = result
            testing = false
        }.start()
    }

    /**
     * Acceptance test for the "works, then suddenly stops" failure mode: [repeats] sequential
     * chat() calls on ONE loaded engine (no reload in between). A session whose KV cache is not
     * reset between calls dies part-way through this loop; this reports exactly how far it got.
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
            MiniKeysLog.line("LLAMA stress start path=$path repeats=$repeats")
            var ok = 0
            var empty = 0
            var failed = 0
            var firstError = ""
            var totalMs = 0L
            var slowestMs = 0L
            var fastestMs = Long.MAX_VALUE
            var lastText = ""
            val result = try {
                // A fresh session with the stress options: the runtime cache keys on the model
                // path alone, so without this the loop could inherit Test's 64-token settings.
                LocalLlamaRuntime.close()
                val engine = LocalLlamaRuntime.get(path, STRESS_OPTIONS)
                MiniKeysLog.line("LLAMA stress loaded: " + engine.nativeDescription)
                for (i in 1..repeats) {
                    try {
                        val reply = engine.chat(listOf(ChatMessage("user", STRESS_PROMPT)))
                        totalMs += reply.latencyMs
                        slowestMs = maxOf(slowestMs, reply.latencyMs)
                        fastestMs = minOf(fastestMs, reply.latencyMs)
                        lastText = reply.text.trim()
                        if (lastText.isEmpty()) empty++ else ok++
                        MiniKeysLog.line(
                            "LLAMA stress $i/$repeats latencyMs=" + reply.latencyMs +
                                " len=" + reply.text.length + " text=" + lastText.take(40)
                        )
                    } catch (t: Throwable) {
                        failed++
                        if (firstError.isEmpty()) {
                            firstError = t.javaClass.simpleName + ": " + (t.message ?: "no message")
                        }
                        MiniKeysLog.line(
                            "LLAMA stress $i/$repeats FAILED " + t.javaClass.name + ": " + t.message
                        )
                    }
                    operationStatus = "Stress test $i/$repeats - ok=$ok empty=$empty failed=$failed"
                }
                val avg = if (repeats > 0) totalMs / repeats else 0L
                val fastest = if (fastestMs == Long.MAX_VALUE) "n/a" else "$fastestMs ms"
                val verdict = if (failed == 0 && empty == 0) "LLAMA STRESS PASS" else "LLAMA STRESS FAIL"
                verdict + " - " + repeats + " calls on one loaded session (ctx=" +
                    STRESS_OPTIONS.normalized().context + ", maxTokens=" +
                    STRESS_OPTIONS.normalized().maxTokens + "): ok=" + ok +
                    " empty=" + empty + " failed=" + failed + " | avg=" + avg + " ms, fastest=" +
                    fastest + ", slowest=" + slowestMs + " ms | last reply: " +
                    (34).toChar() + lastText.take(60) + (34).toChar() +
                    (if (firstError.isEmpty()) "" else " | first error: " + firstError)
            } catch (t: Throwable) {
                MiniKeysLog.line("LLAMA stress setup FAILED " + t.javaClass.name + ": " + t.message)
                LocalLlamaRuntime.close()
                "LLAMA STRESS FAIL - " + t.javaClass.simpleName + ": " + (t.message ?: "no message")
            }
            operationStatus = result
            testing = false
        }.start()
    }

    /** Shared validation so Test and the stress test reject the same bad inputs identically. */
    private fun modelProblem(path: String): String? {
        if (path.isBlank() || !path.startsWith(store.modelsDir.absolutePath)) {
            return "Select or download a GGUF model first."
        }
        if (!LlamaModelFormats.isSupported(File(path).name)) {
            return "Unsupported format: " + File(path).name + " (expected .gguf)."
        }
        val modelFile = File(path)
        if (!modelFile.isFile || modelFile.length() == 0L) {
            return "Model file is empty or missing - re-import or re-download it."
        }
        if (!LlamaModelFormats.hasGgufMagic(modelFile)) {
            return "Not a GGUF file (bad header) - the file is corrupt; re-import or re-download it."
        }
        return null
    }

    fun exportLog() {
        operationStatus = "Log exported: " + MiniKeysLog.flush(appContext, "llama")
    }
    fun unloadModel() {
        LocalLlamaRuntime.close()
        if (operationStatus.startsWith("LLAMA OK")) {
            operationStatus = "Model unloaded from memory."
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun progressText(copied: Long, total: Long?): String {
        val done = humanBytes(copied)
        val of = total?.let { " of " + humanBytes(it) }.orEmpty()
        return done + of + " …"
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.1f KB".format(bytes / 1024.0)
    }

    private fun defaultStatus(): String =
        "No GGUF model yet — pick one from device storage or download from Hugging Face."

    private companion object {
        /** Greedy decoding: the acceptance test must be deterministic. */
        val TEST_OPTIONS = LlamaCppOptions(maxTokens = 64, temp = 0.0f)

        /** Exactly the same prompt for every call, so the acceptance test is comparable. */
        const val TEST_PROMPT = "Reply to this message with exactly: OK"

        /**
         * Stress test settings, chosen so the loop can actually fail:
         * a small context (512) plus a prompt whose greedy answer is long (~150 positions per
         * call) exhausts a session that never releases its KV cache within a few calls, while a
         * healthy session finishes all [STRESS_REPEATS] calls.
         */
        val STRESS_OPTIONS = LlamaCppOptions(context = 512, maxTokens = 128, temp = 0.0f)
        const val STRESS_PROMPT = "Count from 1 to 40, one number per line."
        const val STRESS_REPEATS = 20

        /** Editable example defaults; a public GGUF mirror of the 270M model. */
        const val DEFAULT_HF_REPO = "unsloth/gemma-3-270m-it-GGUF"
        const val DEFAULT_HF_FILE = "gemma-3-270m-it-Q4_K_M.gguf"
    }
}
