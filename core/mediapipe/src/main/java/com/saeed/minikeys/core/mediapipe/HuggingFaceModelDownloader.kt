package com.saeed.minikeys.core.mediapipe

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Downloads a model file straight from Hugging Face into the local models folder.
 *
 * Public repositories need NO token; gated/private ones need one (sent as a Bearer header). The
 * failure message tells the user that, instead of surfacing a bare HTTP code.
 */
class HuggingFaceModelDownloader(private val modelsDir: File) {

    init {
        modelsDir.mkdirs()
    }

    fun download(
        repoId: String,
        fileName: String,
        token: String?,
        progress: (copied: Long, total: Long?) -> Unit,
    ): File {
        val url = modelUrl(repoId, fileName)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            token?.trim()?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException(hfErrorMessage(code))
            }

            val total = connection.contentLengthLong.takeIf { it > 0 }
            val target = File(modelsDir, LocalModelFileNames.sanitize(fileName))
            val partial = File(modelsDir, target.name + ".part")

            connection.inputStream.use { stream ->
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
                throw IOException("incomplete download: received $received of $total bytes — retry the download")
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                throw IOException("could not finalize the downloaded file")
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BUFFER_SIZE_BYTES = 64 * 1024

        fun modelUrl(repoId: String, fileName: String): String {
            val repoPath = repoId.trim().trim('/')
                .split('/')
                .filter { it.isNotBlank() }
                .joinToString("/") { pathEncode(it) }
            val encodedFile = pathEncode(fileName.trim())
            return "https://huggingface.co/$repoPath/resolve/main/$encodedFile"
        }

        /**
         * Percent-encoding for URL *path* segments. URLEncoder is form-encoding and maps a space to
         * '+' in a path, which Hugging Face reads as a literal plus — not the same file.
         */
        private fun pathEncode(segment: String): String =
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

        fun hfErrorMessage(code: Int): String = when (code) {
            401, 403 -> "HTTP $code — this repository is gated or private: a Hugging Face token with access is required"
            404 -> "HTTP 404 — repository or file name not found; check the repo path and exact file name"
            429 -> "HTTP 429 — rate limited by Hugging Face; try again shortly"
            else -> "HTTP $code"
        }
    }
}