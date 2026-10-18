package dev.colormemo.app.data

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
    fun legacySyncIntervalSettingIsIgnoredAndNoLongerSaved() {
        val legacy = """{"enabled":true,"provider":"R2","syncIntervalMinutes":1440}"""
        val restored = SyncSettingsJson.decodeFromString<SyncPreferences>(legacy)
        assertEquals(true, restored.enabled)
        assertEquals("R2", restored.provider)
        assertFalse(SyncSettingsJson.encodeToString(restored).contains("syncIntervalMinutes"))
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
