package com.saeed.minikeys.core.llama

/**
 * Process-wide holder for the loaded llama.cpp session. Loading a model costs seconds and keeps
 * native memory alive, so the engine outlives any single Compose state object.
 * Properly double-checked: unlike the MediaPipe runtime, close+reload cannot race.
 */
object LocalLlamaRuntime {

    @Volatile
    private var engine: LlamaCppEngine? = null

    val isLoaded: Boolean get() = engine != null

    fun get(modelPath: String, options: LlamaCppOptions = LlamaCppOptions()): LlamaCppEngine {
        engine?.let { current -> if (current.modelPath == modelPath) return current }
        synchronized(this) {
            engine?.let { current ->
                if (current.modelPath == modelPath) return current
                runCatching { current.close() }
            }
            val fresh = LlamaCppEngine(modelPath, options)
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
