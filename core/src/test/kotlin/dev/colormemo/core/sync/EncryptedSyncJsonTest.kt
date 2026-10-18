package dev.colormemo.core.sync

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteColor
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedSyncJsonTest {
    // Public interoperability fixtures; these keys must never be used for real notes.
    private val recoveryKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val key = SyncEncryptionKey.parse(recoveryKey)
    private val referenceEnvelope = """{"schemaVersion":2,"encryption":"AES-256-GCM","nonce":"AAECAwQFBgcICQoL","ciphertext":"PCCleK2Ar3rbJOX42IYWT7nnqxaeFCsZS0Xf3kAUeXJUSF7PZwTt89WhG0qbyA=="}"""
    private val note = Note(id = "note", revision = "revision", deviceId = "device", createdAt = 1, updatedAt = 2)

    @Test
    fun generatedRecoveryKeysHaveFullEntropyLengthAndSafeDiagnostics() {
        val generated = List(32) { SyncEncryptionKey.generate() }
        assertEquals(32, generated.toSet().size)
        for (encoded in generated) {
            assertEquals(43, encoded.length)
            assertEquals(32, Base64.getUrlDecoder().decode(encoded).size)
            assertFalse(SyncEncryptionKey.parse(" \n$encoded\t ").toString().contains(encoded))
        }
    }

    @Test
    fun recoveryKeysRejectWrongLengthsAlphabetsPaddingAndNoncanonicalBits() {
        val zeroKey = "A".repeat(43)
        listOf("", "short-private-value", zeroKey.dropLast(1), zeroKey + "A", zeroKey + "=",
            zeroKey.dropLast(1) + "B", zeroKey.replaceRange(4, 5, "+"), zeroKey.replaceRange(4, 5, "/"),
            zeroKey.replaceRange(4, 5, " "), zeroKey.replaceRange(4, 5, "彩"),
        ).forEach { encoded ->
            val error = assertThrows(SyncConfigurationException::class.java) { SyncEncryptionKey.parse(encoded) }
            assertNull(error.cause)
            if (encoded.isNotEmpty()) assertFalse(error.message.orEmpty().contains(encoded))
        }
    }

    @Test
    fun aes256GcmMatchesNistKnownAnswerVector() {
        // NIST SP 800-38D AES-256-GCM: zero key/96-bit IV, one zero block, no AAD.
        val zeroKey = SyncEncryptionKey.parse("A".repeat(43))
        val result = zeroKey.cipher(Cipher.ENCRYPT_MODE, ByteArray(12)).doFinal(ByteArray(16))
        assertArrayEquals(hex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919"), result)
    }

    @Test
    fun readsIndependentOpenSslEnvelopeWithVersionAndDomainAad() {
        // Generated with OpenSSL EVP_aes_256_gcm, key 00..1f and nonce 00..0b.
        assertEquals(SyncDocument(), EncryptedSyncJson.decode(referenceEnvelope.toByteArray(), key))
    }

    @Test
    fun unicodeWhiteboardAndAllRevisionAndDeletionMetadataRoundTrip() {
        val source = SyncDocument(notes = listOf(
            note.copy(
                id = "a-drawing", title = "端到端秘密标题", content = "# 彩笺\n\n- [x] **秘密** 🍊",
                color = NoteColor.CORAL, isPinned = true, isArchived = true, reminderAt = 8,
                parentRevision = "parent", ancestorRevisions = setOf("grandparent", "parent"),
                conflictOf = "original", conflictRevision = "conflicting",
                whiteboard = Whiteboard(listOf(WhiteboardStroke(points = listOf(WhiteboardPoint(2f, 3f))))),
            ),
            note.copy(id = "b-trashed", deletedAt = 4, content = "回收站秘密"),
            note.copy(id = "c-purged", deletedAt = 5, isPurged = true),
        ))
        val encrypted = EncryptedSyncJson.encode(source, key)
        assertEquals(source, EncryptedSyncJson.decode(encrypted, key))
        for (secret in listOf("端到端秘密标题", "回收站秘密", "ancestorRevisions", "whiteboard", "a-drawing", recoveryKey)) {
            assertFalse(encrypted.toString(Charsets.US_ASCII).contains(secret))
        }
    }

    @Test
    fun eachUploadUsesAFreshRandomNonceAndCiphertext() {
        val encoded = List(20) { EncryptedSyncJson.encode(SyncDocument(), key).toString(Charsets.US_ASCII) }
        assertEquals(20, encoded.toSet().size)
        val nonces = encoded.map { it.substringAfter("\"nonce\":\"").substringBefore('"') }
        assertEquals(20, nonces.toSet().size)
    }

    @Test
    fun wrongKeysAndTamperedCiphertextOrNonceFailWithTheSameSafeMessage() {
        val wrongKey = SyncEncryptionKey.parse(SyncEncryptionKey.generate())
        val wrongKeyError = assertThrows(SyncProtocolException::class.java) {
            EncryptedSyncJson.decode(referenceEnvelope.toByteArray(), wrongKey)
        }
        listOf(referenceEnvelope.replace("AAECAwQFBgcICQoL", "BAECAwQFBgcICQoL"),
            referenceEnvelope.replace("PCCleK2A", "QCCleK2A"),
        ).forEach { changed ->
            val error = assertThrows(SyncProtocolException::class.java) { EncryptedSyncJson.decode(changed.toByteArray(), key) }
            assertEquals(wrongKeyError.message, error.message)
            assertNull(error.cause)
        }
        assertNull(wrongKeyError.cause)
        assertTrue(wrongKeyError.message.orEmpty().contains("恢复密钥不正确"))
    }

    @Test
    fun rejectsCiphertextProducedForAnotherDomainOrWithoutAad() {
        listOf("", "ColorMemo sync\n1\nAES-256-GCM", "another application").forEach { aad ->
            val envelope = seal(SyncJson.encodeDocument(SyncDocument()), aad)
            assertThrows(SyncProtocolException::class.java) { EncryptedSyncJson.decode(envelope, key) }
        }
    }

    @Test
    fun plaintextAndUnknownOrAmbiguousEnvelopeFormatsFailClosed() {
        listOf(
            SyncJson.encode(SyncDocument()), "{}", "[]",
            referenceEnvelope.replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
            referenceEnvelope.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
            referenceEnvelope.replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\""),
            referenceEnvelope.replace("\"schemaVersion\":2", "\"schemaVersion\":2e0"),
            referenceEnvelope.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"),
            referenceEnvelope.replace("\"schemaVersion\":2,", ""),
            referenceEnvelope.replace("AES-256-GCM", "AES-128-GCM"),
            referenceEnvelope.replace("AES-256-GCM", "AES-256-CBC"),
            referenceEnvelope.replace("\"encryption\"", "\"futureData\""),
            referenceEnvelope.replace("\"nonce\"", "\"no\\u006ece\""),
            referenceEnvelope.dropLast(1) + ",\"futureData\":{}}",
            referenceEnvelope.dropLast(1) + ",}",
            referenceEnvelope + "{}", referenceEnvelope.dropLast(1),
            "{\"schemaVersion\":2,\"ciphertext\":" + "[".repeat(100_000),
        ).forEach { encoded ->
            val error = assertThrows(SyncProtocolException::class.java) { EncryptedSyncJson.decode(encoded.toByteArray(), key) }
            assertNull(error.cause)
        }
    }

    @Test
    fun fieldOrderingAndJsonWhitespaceDoNotAlterTheAuthenticatedFormat() {
        val reordered = """
            { "ciphertext" : "PCCleK2Ar3rbJOX42IYWT7nnqxaeFCsZS0Xf3kAUeXJUSF7PZwTt89WhG0qbyA==",
              "nonce" : "AAECAwQFBgcICQoL", "encryption" : "AES-256-GCM", "schemaVersion" : 2 }
        """.trimIndent()
        assertEquals(SyncDocument(), EncryptedSyncJson.decode(reordered.toByteArray(), key))
    }

    @Test
    fun noncanonicalBase64TruncatedTagsAndWrongNonceSizesAreRejected() {
        listOf("", "AAECAwQFBgcICQo=", "AAECAwQFBgcICQoLAA==", "AAECAwQFBgcICQo_", "AAECAwQFBgcICQ==")
            .forEach { nonce ->
                assertThrows(SyncProtocolException::class.java) {
                    EncryptedSyncJson.decode(referenceEnvelope.replace("AAECAwQFBgcICQoL", nonce).toByteArray(), key)
                }
            }
        val ciphertext = "PCCleK2Ar3rbJOX42IYWT7nnqxaeFCsZS0Xf3kAUeXJUSF7PZwTt89WhG0qbyA=="
        listOf("", "AA==", Base64.getEncoder().encodeToString(ByteArray(15)), ciphertext.dropLast(2),
            ciphertext.dropLast(3) + "B==", ciphertext.replaceRange(3, 4, "="),
        ).forEach { value ->
            assertThrows(SyncProtocolException::class.java) {
                EncryptedSyncJson.decode(referenceEnvelope.replace(ciphertext, value).toByteArray(), key)
            }
        }
    }

    @Test
    fun invalidDecryptedJsonAndUtf8NeverEscapeThroughExceptionCauses() {
        val privateText = "private-note-content-never-log"
        listOf("{\"schemaVersion\":1,\"notes\":[],\"$privateText\":1}".toByteArray(),
            "{\"schemaVersion\":1,\"notes\":[{\"title\":\"$privateText\"}]}".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28),
        ).forEach { plaintext ->
            val error = assertThrows(SyncProtocolException::class.java) { EncryptedSyncJson.decode(seal(plaintext), key) }
            assertNull(error.cause)
            assertFalse(error.toString().contains(privateText))
        }
    }

    @Test
    fun oversizedWireAndCorruptEnvelopeUtf8AreRejectedBeforeDecryption() {
        assertThrows(SyncProtocolException::class.java) {
            EncryptedSyncJson.decode(ByteArray(EncryptedSyncJson.MAX_DOCUMENT_BYTES + 1), key)
        }
        assertThrows(SyncProtocolException::class.java) {
            EncryptedSyncJson.decode(referenceEnvelope.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28), key)
        }
    }

    @Test
    fun fullSixteenMiBPlaintextFitsTheEncryptedWireLimitAndRoundTrips() {
        val emptyNotes = List(170) { note.copy(id = "size-$it") }
        var remaining = SyncJson.MAX_DOCUMENT_BYTES - SyncJson.encodeDocument(SyncDocument(notes = emptyNotes)).size
        val notes = emptyNotes.map { source ->
            val length = minOf(remaining, SyncJson.MAX_CONTENT_CHARACTERS)
            remaining -= length
            source.copy(content = "x".repeat(length))
        }.sortedBy { it.id }
        assertEquals(0, remaining)
        val source = SyncDocument(notes = notes)
        assertEquals(SyncJson.MAX_DOCUMENT_BYTES, SyncJson.encodeDocument(source).size)
        val encrypted = EncryptedSyncJson.encode(source, key)
        assertTrue(encrypted.size > SyncJson.MAX_DOCUMENT_BYTES)
        assertTrue(encrypted.size <= EncryptedSyncJson.MAX_DOCUMENT_BYTES)
        assertEquals(source, EncryptedSyncJson.decode(encrypted, key))
    }

    private fun seal(plaintext: ByteArray, aad: String = "ColorMemo sync\n2\nAES-256-GCM"): ByteArray {
        val nonce = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
        return """{"schemaVersion":2,"encryption":"AES-256-GCM","nonce":"AAECAwQFBgcICQoL","ciphertext":"$ciphertext"}""".toByteArray()
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
