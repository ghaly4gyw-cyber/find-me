package com.ghaly.findme.mesh

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AES-GCM helper for private-group messages. Every group has one shared
 * 256-bit key (generated on creation, shared out-of-band — e.g. read aloud or typed
 * in by whoever's joining, since everyone's standing in the same spot at the mine).
 * Only people with the key can decrypt a group's messages; everyone else still
 * relays the (unreadable) bytes along like any other mesh message.
 */
object Crypto {
    private const val ALGO = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun newKeyBase64(): String {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    /** Returns base64(iv || ciphertext). */
    fun encrypt(plaintext: String, keyBase64: String): String {
        val key = Base64.decode(keyBase64, Base64.NO_WRAP)
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    /** Returns null if the key is wrong or the data is corrupt, rather than throwing —
     *  callers use this to tell "not for this group" apart from "broken message". */
    fun decrypt(payloadBase64: String, keyBase64: String): String? {
        return try {
            val key = Base64.decode(keyBase64, Base64.NO_WRAP)
            val combined = Base64.decode(payloadBase64, Base64.NO_WRAP)
            if (combined.size <= IV_BYTES) return null
            val iv = combined.copyOfRange(0, IV_BYTES)
            val ciphertext = combined.copyOfRange(IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(ALGO)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
