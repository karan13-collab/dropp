package com.dropp.dropp.core.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionHelper {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val NONCE_LENGTH_BYTE = 12

    fun encrypt(plainText: String, base64Key: String): String {
        val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val nonce = ByteArray(NONCE_LENGTH_BYTE)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, nonce))

        // Android automatically appends the 16-byte Tag to the end of the CipherText
        val cipherTextWithTag = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Combine: [Nonce (12)] + [CipherTextWithTag]
        val result = ByteArray(nonce.size + cipherTextWithTag.size)
        System.arraycopy(nonce, 0, result, 0, nonce.size)
        System.arraycopy(cipherTextWithTag, 0, result, nonce.size, cipherTextWithTag.size)

        // NO_WRAP is strictly required so we don't accidentally send newline characters over TCP!
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    fun decrypt(base64Payload: String, base64Key: String): String {
        val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val payload = Base64.decode(base64Payload, Base64.NO_WRAP)

        val nonce = payload.copyOfRange(0, NONCE_LENGTH_BYTE)
        val cipherTextWithTag = payload.copyOfRange(NONCE_LENGTH_BYTE, payload.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, nonce))

        val plainTextBytes = cipher.doFinal(cipherTextWithTag)
        return String(plainTextBytes, Charsets.UTF_8)
    }
}