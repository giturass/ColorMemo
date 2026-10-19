package dev.colormemo.app.data

import dev.colormemo.app.sync.SyncInterval
import dev.colormemo.app.ui.MemoUiState
import dev.colormemo.app.ui.SyncProviderUi
import dev.colormemo.app.ui.SyncSettingsUi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncPreferencesTest {
    @Test
    fun syncStartsDisabledWithoutARecoveryKey() {
        val preferences = SyncPreferences()
        assertFalse(preferences.enabled)
        assertEquals("", preferences.encryptionKey)
        assertEquals("", preferences.toUi().encryptionKey)
    }

    @Test
    fun recoveryKeySurvivesUiAndSettingsSerialization() {
        val ui = SyncSettingsUi(
            enabled = true,
            provider = SyncProviderUi.R2,
            encryptionKey = "A".repeat(43), // Synthetic test key; not a credential.
        )
        val preferences = SyncPreferences.fromUi(ui)
        val restored = Json.decodeFromString<SyncPreferences>(Json.encodeToString(preferences))
        assertEquals(ui.encryptionKey, restored.encryptionKey)
        assertEquals(ui.encryptionKey, restored.toUi().encryptionKey)
    }

    @Test
    fun copyingARecoveryKeyWithSurroundingWhitespacePreservesItsValue() {
        val key = "A".repeat(43)
        assertEquals(key, SyncPreferences.fromUi(SyncSettingsUi(encryptionKey = " \n$key\n ")).encryptionKey)
    }

    @Test
    fun legacyIntervalAndSettingsWithoutAnIntervalRemainUsable() {
        val legacy = """{"enabled":true,"provider":"R2","syncIntervalMinutes":1440}"""
        val restored = SyncSettingsJson.decodeFromString<SyncPreferences>(legacy)
        assertEquals(true, restored.enabled)
        assertEquals("R2", restored.provider)
        assertEquals(1440L, restored.toUi().syncIntervalMinutes)
        val saved = SyncSettingsJson.decodeFromString<SyncPreferences>(SyncSettingsJson.encodeToString(restored))
        assertEquals(1440L, saved.syncIntervalMinutes)
        val missing = SyncSettingsJson.decodeFromString<SyncPreferences>("""{"enabled":false}""")
        assertEquals(15L, missing.syncIntervalMinutes)
    }

    @Test
    fun selectedIntervalsSurviveSerializationEvenWhenAutomaticSyncIsDisabled() {
        for (minutes in SyncInterval.choices) {
            val ui = SyncSettingsUi(automaticSync = false, syncIntervalMinutes = minutes)
            val encoded = SyncSettingsJson.encodeToString(SyncPreferences.fromUi(ui))
            val restored = SyncSettingsJson.decodeFromString<SyncPreferences>(encoded).toUi()
            assertEquals(minutes, restored.syncIntervalMinutes)
            assertFalse(restored.automaticSync)
        }
    }

    @Test
    fun invalidStoredIntervalsAreConstrainedForUiAndScheduling() {
        for ((stored, expected) in listOf(-1L to 15L, 0L to 15L, 14L to 15L, Long.MAX_VALUE to 1440L)) {
            val preferences = SyncPreferences(syncIntervalMinutes = stored)
            assertEquals(expected, preferences.toUi().syncIntervalMinutes)
            assertEquals(expected, SyncPreferences.fromUi(SyncSettingsUi(syncIntervalMinutes = stored)).syncIntervalMinutes)
            assertEquals(expected, SyncInterval.normalize(preferences.syncIntervalMinutes))
        }
    }

    @Test
    fun diagnosticsDoNotExposeCredentialsOrRecoveryKey() {
        val secrets = listOf(
            "private-endpoint", "private-username", "private-password", "private-access-id",
            "private-access-secret", "private-session-token", "private-recovery-key",
        )
        val ui = SyncSettingsUi(
            endpoint = secrets[0],
            username = secrets[1],
            password = secrets[2],
            accessKeyId = secrets[3],
            secretAccessKey = secrets[4],
            sessionToken = secrets[5],
            encryptionKey = secrets[6],
        )
        val diagnostics = listOf(ui.toString(), SyncPreferences.fromUi(ui).toString(), MemoUiState(syncSettings = ui).toString())
        diagnostics.forEach { message ->
            secrets.forEach { secret -> assertFalse("Diagnostic contains a secret", message.contains(secret)) }
        }
    }
}
