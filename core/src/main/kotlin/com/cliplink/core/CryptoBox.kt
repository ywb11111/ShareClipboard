package com.cliplink.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoBox {
    private const val ITERATIONS = 150_000
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val context = "ClipLink LAN sync v1".toByteArray(StandardCharsets.UTF_8)
    private val random = SecureRandom()

    fun deriveKey(pairingCode: String): ByteArray {
        val normalized = pairingCode.trim().uppercase().toCharArray()
        require(normalized.size >= 8) { "配对码至少需要 8 位" }
        val spec = PBEKeySpec(normalized, context, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
            .also { spec.clearPassword() }
    }

    fun fingerprint(key: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(key)
        .take(8)
        .joinToString("") { "%02x".format(it) }

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(context)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(iv.size + ciphertext.size).put(iv).put(ciphertext).array()
    }

    fun decrypt(key: ByteArray, payload: ByteArray): ByteArray {
        require(payload.size > IV_BYTES) { "无效的加密消息" }
        val iv = payload.copyOfRange(0, IV_BYTES)
        val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(context)
        return cipher.doFinal(ciphertext)
    }
}
