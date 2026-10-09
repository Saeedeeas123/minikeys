package com.saeed.minikeys.core.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaModelFormatsTest {

    @Test
    fun `gguf accepted case-insensitively`() {
        assertTrue(LlamaModelFormats.isSupported("gemma-3-270m-it-qat-Q4_0.gguf"))
        assertTrue(LlamaModelFormats.isSupported("MODEL.GGUF"))
    }

    @Test
    fun `non-gguf rejected`() {
        assertFalse(LlamaModelFormats.isSupported("gemma-3-1b-it.task"))
        assertFalse(LlamaModelFormats.isSupported("model.litertlm"))
        assertFalse(LlamaModelFormats.isSupported("gguf.txt"))
        assertFalse(LlamaModelFormats.isSupported("model.part"))
    }

    @Test
    fun `sanitize neutralizes path characters and blanks`() {
        assertEquals("a_b_c.gguf", LlamaModelFileNames.sanitize("a/b\\c.gguf"))
        assertEquals("___", LlamaModelFileNames.sanitize("///"))
        assertEquals("gemma_3_270m.gguf", LlamaModelFileNames.sanitize("gemma 3 270m.gguf"))
    }
}
