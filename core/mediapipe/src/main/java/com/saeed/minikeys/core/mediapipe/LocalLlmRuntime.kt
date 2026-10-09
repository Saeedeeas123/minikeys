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
        engine?.let { current ->
            if (current.modelPath == modelPath) return current
            runCatching { current.close() }
        }
        synchronized(this) {
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