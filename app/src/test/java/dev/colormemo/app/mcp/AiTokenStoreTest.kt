package dev.colormemo.app.mcp

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AiTokenStoreTest {
    @Test
    fun reopeningTheStoreKeepsTheTokenWithoutRewritingIt() {
        val storage = Storage()
        val store = storage.open()
        val token = store.getOrCreate()
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(token, store.getOrCreate())
        assertEquals(token, storage.open().getOrCreate())
        assertEquals(1, storage.writes)
        assertFalse(storage.encoded!!.contains(token))
        assertFalse(Base64.getDecoder().decode(storage.encoded).toString(Charsets.UTF_8).contains(token))
    }

    @Test
    fun manualResetPersistsTheReplacementAcrossStoreInstances() {
        val storage = Storage()
        val old = storage.open().getOrCreate()
        val replacement = storage.open().reset()
        assertNotEquals(old, replacement)
        assertEquals(replacement, storage.open().getOrCreate())
        assertEquals(2, storage.writes)
    }

    @Test
    fun corruptDataIsNeverSilentlyReplaced() {
        val storage = Storage()
        val token = storage.open().getOrCreate()
        val original = storage.encoded!!
        val tampered = Base64.getDecoder().decode(original).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (invalid in listOf("invalid!", "", Base64.getEncoder().encodeToString(tampered))) {
            storage.encoded = invalid
            assertThrows(Exception::class.java) { storage.open().getOrCreate() }
            assertEquals(invalid, storage.encoded)
            assertEquals(1, storage.writes)
        }
        storage.encoded = original
        assertEquals(token, storage.open().getOrCreate())
    }

    @Test
    fun missingKeyRequiresExplicitResetAndThenRecovers() {
        val storage = Storage()
        val old = storage.open().getOrCreate()
        storage.key = null
        assertThrows(IllegalStateException::class.java) { storage.open().getOrCreate() }
        assertEquals(1, storage.writes)
        assertNull(storage.key)
        val replacement = storage.open().reset()
        assertNotEquals(old, replacement)
        assertEquals(replacement, storage.open().getOrCreate())
    }

    @Test
    fun failedCommitCannotExposeAnUndurableTokenOnRetry() {
        val storage = Storage()
        storage.failWrites = true
        val store = storage.open()
        assertThrows(IOException::class.java) { store.getOrCreate() }
        val pending = storage.encoded
        assertThrows(IOException::class.java) { store.getOrCreate() }
        assertEquals(pending, storage.encoded)
        storage.failWrites = false
        val token = store.getOrCreate()
        assertEquals(3, storage.writes)
        assertEquals(pending, storage.encoded)
        assertEquals(token, storage.open().getOrCreate())
    }

    @Test
    fun concurrentFirstLoadsPersistOneCredential() {
        val storage = Storage()
        val store = storage.open()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..12).map { executor.submit<String> { store.getOrCreate() } }
            assertEquals(1, results.map { it.get(5, TimeUnit.SECONDS) }.distinct().size)
            assertEquals(1, storage.writes)
        } finally {
            executor.shutdownNow()
        }
    }

    private class Storage {
        var encoded: String? = null
        var key: SecretKey? = null
        var writes = 0
        var failWrites = false

        fun open() = AiTokenStore(
            read = { encoded },
            write = {
                // Match SharedPreferences, which mutates memory before reporting a failed commit.
                encoded = it
                writes++
                if (failWrites) throw IOException("Simulated storage failure")
            },
            key = { create ->
                key ?: run {
                    check(create) { "Missing test key" }
                    KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
                }
            },
        )
    }
}
