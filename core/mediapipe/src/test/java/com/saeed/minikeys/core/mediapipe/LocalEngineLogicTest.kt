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
    fun `task bundles are recognized by zip magic`() {
        val task = java.io.File.createTempFile("model", ".task")
        task.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        val garbage = java.io.File.createTempFile("model", ".task")
        garbage.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        val empty = java.io.File.createTempFile("model", ".task")
        try {
            assertTrue(LocalModelFormats.hasZipMagic(task))
            assertFalse(LocalModelFormats.hasZipMagic(garbage))
            assertFalse(LocalModelFormats.hasZipMagic(empty))
        } finally {
            task.delete()
            garbage.delete()
            empty.delete()
        }
    }
}