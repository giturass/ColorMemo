package dev.colormemo.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The whole configuration, including endpoint and credentials, is encrypted at rest. */
class SecureSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(SyncPreferences())
    val settings = mutableSettings.asStateFlow()

    val deviceId: String = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        check(preferences.edit().putString("device_id", it).commit()) { "无法保存设备标识" }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        val encoded = preferences.getString("sync_encrypted_v1", null) ?: return@withContext
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > IV_BYTES + 16) { "同步设置损坏，请重新保存设置" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(AAD)
        val json = cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)).toString(Charsets.UTF_8)
        mutableSettings.value = SyncSettingsJson.decodeFromString<SyncPreferences>(json).also {
            require(it.provider in setOf("WEBDAV", "S3", "R2")) { "不支持的同步设置版本" }
        }
    }

    suspend fun save(value: SyncPreferences) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(AAD)
        check(cipher.iv.size == IV_BYTES)
        val encrypted = cipher.doFinal(SyncSettingsJson.encodeToString(value).toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString("sync_encrypted_v1", packed).commit()) { "同步设置保存失败" }
        mutableSettings.value = value
    }

    fun lastSyncTime(): Long = preferences.getLong("last_sync_ms", 0)

    fun recordSync(time: Long) {
        preferences.edit().putLong("last_sync_ms", time).apply()
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "找不到设备密钥，请重新填写同步设置" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "colormemo.sync.v1"
        const val IV_BYTES = 12
        val AAD = "dev.colormemo.app:sync:v1".toByteArray(Charsets.UTF_8)
    }
}
