package com.saeed.minikeys.core.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppOptionsTest {

    @Test
    fun `defaults are already normalized`() {
        val o = LlamaCppOptions()
        assertEquals(o, o.normalized())
    }

    @Test
    fun `wild values clamp into supported ranges`() {
        val o = LlamaCppOptions(
            context = 100_000,
            threads = 99,
            maxTokens = 0,
            temp = -3f,
            topK = -1,
            topP = 0f,
            penaltyLastN = -5,
            repeatPenalty = 9f,
        ).normalized()

        assertEquals(32_768, o.context)
        assertEquals(8, o.threads)
        assertEquals(1, o.maxTokens)
        assertEquals(0f, o.temp)
        assertEquals(0, o.topK)
        assertEquals(0.05f, o.topP)
        assertEquals(0, o.penaltyLastN)
        assertEquals(2.0f, o.repeatPenalty)
    }

    @Test
    fun `low floors are respected`() {
        val o = LlamaCppOptions(context = 100, threads = 0, maxTokens = 1, temp = 7f, topP = 2f).normalized()
        assertEquals(512, o.context)
        assertEquals(1, o.threads)
        assertEquals(1, o.maxTokens)
        assertEquals(2.0f, o.temp)
        assertEquals(1.0f, o.topP)
    }

    @Test
    fun `greedy path requires non-positive temp`() {
        assertTrue(LlamaCppOptions(temp = 0f).normalized().greedy)
        assertTrue(LlamaCppOptions(temp = -0.5f).normalized().greedy)
        assertFalse(LlamaCppOptions(temp = 0.8f).greedy)
    }

    @Test
    fun `negative seed is the random sentinel`() {
        assertEquals(-1L, LlamaCppOptions().seed)
    }
}
