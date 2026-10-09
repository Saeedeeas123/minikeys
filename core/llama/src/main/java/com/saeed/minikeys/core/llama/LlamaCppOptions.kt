package com.saeed.minikeys.core.llama

/**
 * Tunables for a llama.cpp session. [normalized] is pure and unit-tested: clamping here means
 * the JNI surface can assume sane values and never has to defend itself.
 */
data class LlamaCppOptions(
    val context: Int = 2048,
    val threads: Int = defaultThreads(),
    val maxTokens: Int = 512,
    val temp: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val penaltyLastN: Int = 64,
    val repeatPenalty: Float = 1.1f,
    /** -1 = randomize (JNI converts to the default-seed sentinel). */
    val seed: Long = -1L,
) {
    /** Greedy decoding (temp <= 0) is the deterministic path used by tests/diagnostics. */
    val greedy: Boolean get() = temp <= 0.0f

    fun normalized(): LlamaCppOptions = copy(
        context = context.coerceIn(512, 32_768),
        threads = threads.coerceIn(1, 8),
        maxTokens = maxTokens.coerceIn(1, 4_096),
        temp = temp.coerceIn(0.0f, 2.0f),
        topK = topK.coerceIn(0, 200),
        topP = topP.coerceIn(0.05f, 1.0f),
        penaltyLastN = penaltyLastN.coerceIn(0, 1_024),
        repeatPenalty = repeatPenalty.coerceIn(1.0f, 2.0f),
    )

    companion object {
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}
