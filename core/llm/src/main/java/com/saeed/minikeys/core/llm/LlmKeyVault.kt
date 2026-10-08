package com.saeed.minikeys.core.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the API key encrypted at rest with an Android-Keystore AES/GCM key, so the plaintext key
 * never sits in a preferences file. Pure framework crypto — no third-party dependency.
 *
 * The stored bundle is `iv(12) || ciphertext+tag`, Base64-encoded.
 */
class LlmKeyVault(context: Context) {

    private val prefs: android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Blank input clears the stored key. */
    fun save(plainKey: String) {
        if (plainKey.isBlank()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val ciphertext = cipher.doFinal(plainKey.toByteArray(Charsets.UTF_8))
        val bundle = cipher.iv + ciphertext
        prefs.edit().putString(KEY_STORED, Base64.encodeToString(bundle, Base64.NO_WRAP)).commit()
    }

    /** Returns "" when no key is stored; throws only if the Keystore itself is broken. */
    fun load(): String {
        val stored = prefs.getString(KEY_STORED, null) ?: return ""
        val bundle = Base64.decode(stored, Base64.NO_WRAP)
        if (bundle.size <= GCM_IV_LENGTH_BYTES) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, bundle.copyOfRange(0, GCM_IV_LENGTH_BYTES)),
            )
        }
        return String(cipher.doFinal(bundle.copyOfRange(GCM_IV_LENGTH_BYTES, bundle.size)), Charsets.UTF_8)
    }

    fun clear() {
        prefs.edit().remove(KEY_STORED).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "minikeys_llm_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "minikeys_llm_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEY_STORED = "api_key_encrypted"
    }
}