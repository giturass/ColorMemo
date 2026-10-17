package dev.colormemo.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class RemoteConfig {
    /** URL of an existing WebDAV collection, not a public share page. */
    @Serializable
    @SerialName("webdav")
    data class WebDav(
        val url: String,
        val username: String = "",
        val password: String = "",
    ) : RemoteConfig() {
        override fun toString(): String = "RemoteConfig.WebDav(credentials=redacted)"
    }

    /** Path-style S3 endpoint. R2 uses https://ACCOUNT_ID.r2.cloudflarestorage.com and auto. */
    @Serializable
    @SerialName("s3")
    data class S3(
        val endpoint: String,
        val bucket: String,
        val accessKeyId: String,
        val secretAccessKey: String,
        val region: String = "auto",
        val prefix: String = "colormemo",
        val sessionToken: String = "",
    ) : RemoteConfig() {
        override fun toString(): String = "RemoteConfig.S3(credentials=redacted)"
    }
}
