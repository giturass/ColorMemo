package dev.colormemo.app.data

import dev.colormemo.app.ui.SyncIntervalUi
import dev.colormemo.app.ui.SyncProviderUi
import dev.colormemo.app.ui.SyncSettingsUi
import dev.colormemo.core.sync.RemoteConfig
import kotlinx.serialization.Serializable

@Serializable
data class SyncPreferences(
    val enabled: Boolean = false,
    val provider: String = "WEBDAV",
    val endpoint: String = "",
    val username: String = "",
    val password: String = "",
    val bucket: String = "",
    val region: String = "us-east-1",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val sessionToken: String = "",
    val pathPrefix: String = "colormemo",
    val automaticSync: Boolean = true,
    val syncIntervalMinutes: Int = 15,
    val wifiOnly: Boolean = false,
) {
    fun remoteConfig(): RemoteConfig = when (provider) {
        "WEBDAV" -> RemoteConfig.WebDav(endpoint.trim(), username, password)
        "S3", "R2" -> RemoteConfig.S3(
            endpoint = endpoint.trim(),
            bucket = bucket.trim(),
            accessKeyId = accessKeyId.trim(),
            secretAccessKey = secretAccessKey,
            region = if (provider == "R2") "auto" else region.trim(),
            prefix = pathPrefix.trim().trim('/'),
            sessionToken = sessionToken.trim(),
        )
        else -> error("不支持的同步服务")
    }

    fun toUi(): SyncSettingsUi = SyncSettingsUi(
        enabled = enabled,
        provider = SyncProviderUi.valueOf(provider),
        endpoint = endpoint,
        username = username,
        password = password,
        bucket = bucket,
        region = region,
        accessKeyId = accessKeyId,
        secretAccessKey = secretAccessKey,
        sessionToken = sessionToken,
        pathPrefix = pathPrefix,
        automaticSync = automaticSync,
        syncInterval = SyncIntervalUi.entries.firstOrNull { it.minutes == syncIntervalMinutes }
            ?: SyncIntervalUi.FIFTEEN_MINUTES,
        wifiOnly = wifiOnly,
    )

    companion object {
        fun fromUi(value: SyncSettingsUi) = SyncPreferences(
            enabled = value.enabled,
            provider = value.provider.name,
            endpoint = value.endpoint.trim(),
            username = value.username,
            password = value.password,
            bucket = value.bucket.trim(),
            region = if (value.provider == SyncProviderUi.R2) "auto" else value.region.trim(),
            accessKeyId = value.accessKeyId.trim(),
            secretAccessKey = value.secretAccessKey,
            sessionToken = value.sessionToken.trim(),
            pathPrefix = value.pathPrefix.trim().trim('/'),
            automaticSync = value.automaticSync,
            syncIntervalMinutes = value.syncInterval.minutes,
            wifiOnly = value.wifiOnly,
        )
    }
}
