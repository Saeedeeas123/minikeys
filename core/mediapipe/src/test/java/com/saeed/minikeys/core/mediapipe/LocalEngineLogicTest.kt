package com.saeed.minikeys.core.mediapipe

import com.saeed.minikeys.core.llm.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEngineLogicTest {

    private val hf = HuggingFaceModelDownloader(modelsDir = java.io.File("/tmp/nonexistent"))

    @Test
    fun `hugging face url for a public repository`() {
        assertEquals(
            "https://huggingface.co/litert-community/gemma-3-1b-it/resolve/main/gemma-3-1b-it.task",
            HuggingFaceModelDownloader.modelUrl("litert-community/gemma-3-1b-it", "gemma-3-1b-it.task"),
        )
    }

    @Test
    fun `hugging face url trims slashes and encodes unusual names`() {
        assertEquals(
            "https://huggingface.co/user/model/resolve/main/the%20model.task",
            HuggingFaceModelDownloader.modelUrl("/user/model/", "the model.task"),
        )
    }

    @Test
    fun `gated http errors read as actionable messages`() {
        assertEquals(
            "HTTP 401 — this repository is gated or private: a Hugging Face token with access is required",
            HuggingFaceModelDownloader.hfErrorMessage(401),
        )
        assertTrue(HuggingFaceModelDownloader.hfErrorMessage(404).contains("not found"))
    }

    @Test
    fun `file names are sanitized for the models folder`() {
        assertEquals("gemma-3-1b-it.task", LocalModelFileNames.sanitize("gemma-3-1b-it.task"))
        assertEquals("weird___name.bin", LocalModelFileNames.sanitize("weird://name.bin"))
        assertEquals("___", LocalModelFileNames.sanitize("///"))
    }

    @Test
    fun `supported format detection is case-insensitive`() {
        assertTrue(LocalModelFormats.isSupported("Gemma-3-1B-IT.TASK"))
        assertTrue(LocalModelFormats.isSupported("qwen.litertlm"))
        assertTrue(LocalModelFormats.isSupported("tiny.bin"))
        assertFalse(LocalModelFormats.isSupported("notes.txt"))
        assertFalse(LocalModelFormats.isSupported("gguf-model.gguf"))
    }

    @Test
    fun `prompt builder exposes roles and ends with the assistant turn`() {
        val prompt = LocalPromptBuilder.toPrompt(
            listOf(
                ChatMessage("system", "Be brief."),
                ChatMessage("user", "Say OK"),
            )
        )

        assertTrue(prompt.contains("System: Be brief."))
        assertTrue(prompt.contains("User: Say OK"))
        assertTrue(prompt.trimEnd().endsWith("Assistant:"))
    }

    @Test
    fun `task bundles are recognized with and without a leading prefix`() {
        // Real bundles were byte-inspected on device (2026-10-10): a vision bundle puts the ZIP
        // header at offset 2, an LLM bundle at offset 4. "PK at byte 0" would reject both.
        val plain = tempFile(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        val prefix2 = tempFile(0x00, 0x00, 0x50, 0x4B, 0x03, 0x04, 0x14)
        val prefix4 = tempFile(0x00, 0x00, 0x00, 0x00, 0x50, 0x4B, 0x03, 0x04, 0x14)
        val notZip = tempFile(0x47, 0x47, 0x55, 0x46, 0x03)
        val empty = tempFile()
        try {
            assertTrue(LocalModelFormats.hasZipMagic(plain))
            assertTrue(LocalModelFormats.hasZipMagic(prefix2))
            assertTrue(LocalModelFormats.hasZipMagic(prefix4))
            assertFalse(LocalModelFormats.hasZipMagic(notZip))
            assertFalse(LocalModelFormats.hasZipMagic(empty))
        } finally {
            listOf(plain, prefix2, prefix4, notZip, empty).forEach { it.delete() }
        }
    }

    @Test
    fun `litertlm bundles are recognized by their ascii magic`() {
        val real = tempFile(0x4C, 0x49, 0x54, 0x45, 0x52, 0x54, 0x4C, 0x4D, 0x01, 0x00, 0x00, 0x00)
        val zip = tempFile(0x50, 0x4B, 0x03, 0x04)
        val empty = tempFile()
        try {
            assertTrue(LocalModelFormats.hasLitertlmMagic(real))
            assertFalse(LocalModelFormats.hasLitertlmMagic(zip))
            assertFalse(LocalModelFormats.hasLitertlmMagic(empty))
        } finally {
            listOf(real, zip, empty).forEach { it.delete() }
        }
    }

    private fun tempFile(vararg bytes: Int): java.io.File {
        val file = java.io.File.createTempFile("model", ".bundle")
        file.writeBytes(ByteArray(bytes.size) { bytes[it].toByte() })
        return file
    }
}
