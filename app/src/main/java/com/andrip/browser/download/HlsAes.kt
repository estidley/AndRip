package com.andrip.browser.download

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS AES-128 (RFC 8216): one 16-byte key served by the playlist, CBC, IV from the
 * EXT-X-KEY tag or the media sequence number. This is ordinary encryption the stream
 * already gives the browser. It is not a DRM license.
 */
internal object HlsAes {
    fun sequenceIv(mediaSequence: Long): String {
        val bytes = ByteArray(16)
        var value = mediaSequence
        for (index in 15 downTo 0) {
            bytes[index] = (value and 0xff).toByte()
            value = value ushr 8
        }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun decryptFile(source: File, target: File, key: ByteArray, ivHex: String) {
        if (key.size != 16) throw IOException("HLS key must be 16 bytes")
        val iv = ivBytes(ivHex)
        target.delete()
        try {
            transform(source, target, key, iv, "AES/CBC/PKCS5Padding")
        } catch (e: GeneralSecurityException) {
            target.delete()
            if (source.length() == 0L || source.length() % 16L != 0L) {
                throw IOException("Could not decrypt an HLS segment", e)
            }
            transform(source, target, key, iv, "AES/CBC/NoPadding")
        }
        if (!target.isFile || target.length() == 0L) throw IOException("Decrypted HLS segment is empty")
    }

    internal fun ivBytes(ivHex: String): ByteArray {
        val hex = ivHex.trim().removePrefix("0x").removePrefix("0X")
        if (hex.length != 32 || hex.any { it.lowercaseChar() !in "0123456789abcdef" }) {
            throw IOException("Invalid HLS IV")
        }
        return ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun transform(source: File, target: File, key: ByteArray, iv: ByteArray, transformation: String) {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val updated = cipher.update(buffer, 0, count)
                    if (updated != null) output.write(updated)
                }
                output.write(cipher.doFinal())
            }
        }
    }
}
