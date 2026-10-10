package com.saeed.minikeys

import android.content.Context
import com.saeed.minikeys.ui.MiniKeysLog
import java.io.File

/**
 * One-shot removal of the data left behind by the deleted offline LLM modules.
 *
 * The offline engines imported model files into `filesDir/models` (hundreds of megabytes to several
 * gigabytes). That feature was removed on 2026-10-10, so the folder can never be needed again; this
 * reclaims the space on the first launch of this build and reports exactly what it removed. Nothing
 * outside that one folder is touched, and a failed removal is reported instead of being hidden.
 */
object LegacyOfflineModels {

    fun removeOnce(context: Context) {
        try {
            val dir = File(context.applicationContext.filesDir, "models")
            if (!dir.exists()) return
            val files = dir.walkBottomUp().filter { it.isFile }.toList()
            val bytes = files.sumOf { it.length() }
            val names = files.joinToString(", ") { it.name }
            dir.deleteRecursively()
            MiniKeysLog.line(
                "legacy offline models: removed " + files.size + " file(s), " +
                    String.format("%.1f MB", bytes / 1_048_576.0) +
                    " - folder gone=" + !dir.exists() + " - [" + names + "]"
            )
        } catch (t: Throwable) {
            MiniKeysLog.line(
                "legacy offline models: cleanup failed " + t.javaClass.simpleName + ": " + t.message
            )
        }
    }
}
