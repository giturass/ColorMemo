package dev.colormemo.core.sync

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A random 256-bit recovery key. The key is never part of the remote document. */
class SyncEncryptionKey private constructor(private val bytes: ByteArray) {
    override fun toString(): String = "SyncEncryptionKey([REDACTED])"

    internal fun cipher(mode: Int, nonce: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(bytes, "AES"), GCMParameterSpec(128, nonce))
    }

    companion object {
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        fun generate(): String {
            val bytes = ByteArray(32).also(random::nextBytes)
            return try {
                encoder.encodeToString(bytes)
            } finally {
                bytes.fill(0)
            }
        }

        fun parse(encoded: String): SyncEncryptionKey {
            val value = encoded.trim()
            if (value.length != 43 || value.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) {
                throw invalidKey()
            }
            val bytes = try {
                Base64.getUrlDecoder().decode(value)
            } catch (_: IllegalArgumentException) {
                throw invalidKey()
            }
            if (bytes.size != 32 || encoder.encodeToString(bytes) != value) {
                bytes.fill(0)
                throw invalidKey()
            }
            return SyncEncryptionKey(bytes)
        }

        private fun invalidKey() = SyncConfigurationException("恢复密钥格式无效，请输入完整的 43 位恢复密钥。")
    }
}

/**
 * Mandatory version 2 envelope. AES-256-GCM authenticates the entire version 1 document,
 * including whiteboards, revision history and tombstones; only the envelope is public.
 * See docs/sync-protocol.md for the cross-client byte format.
 */
object EncryptedSyncJson {
    private const val TAG_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val MAX_CIPHERTEXT_BYTES = SyncJson.MAX_DOCUMENT_BYTES + TAG_BYTES
    private const val MAX_BASE64_CHARACTERS = ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4
    const val MAX_DOCUMENT_BYTES: Int = MAX_BASE64_CHARACTERS + 256

    private val random = SecureRandom()
    private val aad = "ColorMemo sync\n2\nAES-256-GCM".toByteArray(Charsets.UTF_8)
    private val encoder = Base64.getEncoder()

    fun encode(document: SyncDocument, key: SyncEncryptionKey): ByteArray {
        val plaintext = try {
            SyncJson.encodeDocument(document)
        } catch (_: Exception) {
            // Serialization exceptions can contain document text, including in their causes.
            throw SyncProtocolException("笔记数据无效或超过 16 MiB 同步上限，无法加密同步。")
        }
        try {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val ciphertext = try {
                key.cipher(Cipher.ENCRYPT_MODE, nonce).apply { updateAAD(aad) }.doFinal(plaintext)
            } catch (_: GeneralSecurityException) {
                throw SyncProtocolException("无法加密同步数据，请重试。")
            }
            return ("{\"schemaVersion\":2,\"encryption\":\"AES-256-GCM\",\"nonce\":\"" +
                encoder.encodeToString(nonce) + "\",\"ciphertext\":\"" +
                encoder.encodeToString(ciphertext) + "\"}").toByteArray(Charsets.US_ASCII)
        } finally {
            plaintext.fill(0)
        }
    }

    fun decode(bytes: ByteArray, key: SyncEncryptionKey): SyncDocument {
        if (bytes.size > MAX_DOCUMENT_BYTES) {
            throw SyncProtocolException("远端加密文件超过同步上限，未覆盖任何数据。")
        }
        val envelope = EnvelopeReader(bytes).read()
        val nonce = decodeBase64(envelope.nonce, NONCE_BYTES, NONCE_BYTES)
        val ciphertext = decodeBase64(envelope.ciphertext, TAG_BYTES, MAX_CIPHERTEXT_BYTES)
        val plaintext = try {
            key.cipher(Cipher.DECRYPT_MODE, nonce).apply { updateAAD(aad) }.doFinal(ciphertext)
        } catch (_: GeneralSecurityException) {
            throw SyncProtocolException("无法解密同步数据：恢复密钥不正确或远端数据已损坏，未覆盖任何数据。")
        }
        try {
            if (plaintext.size > SyncJson.MAX_DOCUMENT_BYTES) throw invalidEnvelope()
            return try {
                SyncJson.decodeDocument(plaintext)
            } catch (_: Exception) {
                throw SyncProtocolException("加密同步文档中的笔记格式无效，未覆盖任何数据。")
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decodeBase64(value: String, minBytes: Int, maxBytes: Int): ByteArray {
        if (value.isEmpty() || value.length % 4 != 0) throw invalidEnvelope()
        val padding = if (value.endsWith("==")) 2 else if (value.endsWith('=')) 1 else 0
        val decodedSize = value.length / 4 * 3 - padding
        if (decodedSize !in minBytes..maxBytes) throw invalidEnvelope()
        for (index in 0 until value.length - padding) {
            if (base64Digit(value[index]) == -1) throw invalidEnvelope()
        }
        // Java's decoder accepts nonzero unused bits; reject those noncanonical encodings.
        if (padding > 0 && (base64Digit(value[value.length - padding - 1]) and
                (if (padding == 2) 15 else 3)) != 0
        ) throw invalidEnvelope()
        return try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw invalidEnvelope()
        }
    }

    private fun base64Digit(character: Char): Int = when (character) {
        in 'A'..'Z' -> character - 'A'
        in 'a'..'z' -> character - 'a' + 26
        in '0'..'9' -> character - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    private fun invalidEnvelope() = SyncProtocolException("远端文件不是受支持的加密同步文档，未覆盖任何数据。")

    private data class Envelope(val nonce: String, val ciphertext: String)

    /**
     * The envelope is flat ASCII JSON with no escaped strings. Reading it directly avoids
     * recursive JSON parsing of untrusted input and catches duplicate fields as well.
     */
    private class EnvelopeReader(private val bytes: ByteArray) {
        private var position = 0

        fun read(): Envelope {
            expect('{')
            val fields = mutableSetOf<String>()
            var nonce: String? = null
            var ciphertext: String? = null
            while (true) {
                val field = readString(16)
                if (!fields.add(field)) throw invalidEnvelope()
                expect(':')
                when (field) {
                    "schemaVersion" -> expect('2')
                    "encryption" -> if (readString(11) != "AES-256-GCM") throw invalidEnvelope()
                    "nonce" -> nonce = readString(16)
                    "ciphertext" -> ciphertext = readString(MAX_BASE64_CHARACTERS)
                    else -> throw invalidEnvelope()
                }
                skipWhitespace()
                if (position < bytes.size && bytes[position] == '}'.code.toByte()) {
                    position++
                    break
                }
                expect(',')
            }
            skipWhitespace()
            if (position != bytes.size || fields.size != 4 || nonce == null || ciphertext == null) throw invalidEnvelope()
            return Envelope(nonce, ciphertext)
        }

        private fun readString(maxCharacters: Int): String {
            expect('"')
            val start = position
            while (position < bytes.size) {
                val character = bytes[position].toInt()
                if (character == '"'.code) {
                    val length = position - start
                    position++
                    return String(bytes, start, length, Charsets.US_ASCII)
                }
                if (position - start >= maxCharacters || character !in 32..126 || character == '\\'.code) {
                    throw invalidEnvelope()
                }
                position++
            }
            throw invalidEnvelope()
        }

        private fun expect(character: Char) {
            skipWhitespace()
            if (position >= bytes.size || bytes[position] != character.code.toByte()) throw invalidEnvelope()
            position++
        }

        private fun skipWhitespace() {
            while (position < bytes.size) {
                when (bytes[position].toInt()) {
                    9, 10, 13, 32 -> position++
                    else -> return
                }
            }
        }
    }
}
