package com.saeed.minikeys.core.mediapipe

import com.saeed.minikeys.core.llm.ChatMessage
import com.saeed.minikeys.core.llm.LlmEngine

/**
 * Process-wide holder for the loaded offline engine. Loading a model costs seconds and keeps native
 * memory alive, so the engine outlives any single Compose state object (activity recreation).
 */
object LocalLlmRuntime {

    @Volatile
    private var engine: MediapipeLlmEngine? = null

    val isLoaded: Boolean get() = engine != null

    fun get(context: android.content.Context, modelPath: String): MediapipeLlmEngine {
        engine?.let { current -> if (current.modelPath == modelPath) return current }
        synchronized(this) {
            // Re-check under the lock: two concurrent get() calls with different paths must
            // not both load, and the old engine must not be closed while a chat() on it is
            // still in flight (close+reload race -> native crash in the MediaPipe runtime).
            engine?.let { current ->
                if (current.modelPath == modelPath) return current
                runCatching { current.close() }
            }
            val fresh = MediapipeLlmEngine(context, modelPath)
            engine = fresh
            return fresh
        }
    }

    fun close() {
        synchronized(this) {
            engine?.let { current -> runCatching { current.close() } }
            engine = null
        }
    }
}