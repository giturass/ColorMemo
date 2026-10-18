package dev.colormemo.core.sync

import dev.colormemo.core.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SyncResult(val notes: List<Note>, val conflictCount: Int, val uploaded: Boolean)
data class ConnectionTestResult(val noteCount: Int, val remoteExists: Boolean)

/**
 * A sync returns the merged snapshot that was downloaded or conditionally uploaded.
 * The repository must merge it with its latest local notes atomically after this call;
 * edits can arrive while the network is in flight. See docs/sync-protocol.md.
 */
class SyncEngine(
    private val config: RemoteConfig,
    private val encryptionKey: SyncEncryptionKey,
    client: OkHttpClient = defaultClient(),
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
        .build()
    private val target: HttpUrl = targetUrl(config)

    /** Read-only: missing snapshot is reported; successful GET does not prove PUT permission. */
    suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val remote = readRemote()
        ConnectionTestResult(
            noteCount = remote.document.notes.count { it.deletedAt == null && !it.isPurged },
            remoteExists = remote.exists,
        )
    }

    suspend fun sync(localNotes: List<Note>): SyncResult = withContext(Dispatchers.IO) {
        var accumulated = MergeEngine.merge(localNotes, emptyList()).notes
        var conflictCount = 0
        for (attempt in 0 until MAX_WRITE_ATTEMPTS) {
            val remote = readRemote()
            val merged = MergeEngine.merge(accumulated, remote.document.notes)
            accumulated = merged.notes
            conflictCount += merged.conflictCount
            if (remote.exists && accumulated == remote.document.notes.sortedBy { it.id }) {
                return@withContext SyncResult(accumulated, conflictCount, uploaded = false)
            }
            // Authenticate the full snapshot, including revisions, deletion markers and whiteboards.
            // Even an empty first sync creates an encrypted object to establish the shared key.
            val bytes = EncryptedSyncJson.encode(SyncDocument(notes = accumulated), encryptionKey)
            val builder = baseRequest().header("Content-Type", JSON_MEDIA_TYPE.toString())
                .put(bytes.toRequestBody(JSON_MEDIA_TYPE))
            if (remote.exists) builder.header("If-Match", requireNotNull(remote.etag))
            else builder.header("If-None-Match", "*")
            val response = execute(authenticate(builder.build(), bytes), readBody = false)
            if (response.code in setOf(200, 201, 204)) {
                return@withContext SyncResult(accumulated, conflictCount, uploaded = true)
            }
            // S3 also documents 404 when a concurrent delete wins before an If-Match PUT.
            val concurrentChange = response.code == 409 || response.code == 412 ||
                (response.code == 404 && remote.exists && config is RemoteConfig.S3)
            if (!concurrentChange) throw httpFailure(response.code)
            if (attempt + 1 < MAX_WRITE_ATTEMPTS) delay(100L * (attempt + 1))
        }
        throw SyncException("远端持续发生更改，或 WebDAV 目录不存在，请稍后重试。", isRetryable = true)
    }

    private suspend fun readRemote(): RemoteSnapshot {
        val response = execute(authenticate(baseRequest().get().build()), readBody = true)
        if (response.code == 404) return RemoteSnapshot(SyncDocument(), null, exists = false)
        if (response.code != 200) throw httpFailure(response.code)
        val etag = response.etag
        if (etag == null || !STRONG_ETAG.matches(etag)) {
            throw SyncProtocolException("服务器未返回有效的强 ETag，无法安全同步；请启用 ETag 和条件写入。")
        }
        // There is deliberately no plaintext fallback, including for legacy v1 sync files.
        return RemoteSnapshot(EncryptedSyncJson.decode(response.body, encryptionKey), etag, exists = true)
    }

    private fun baseRequest(): Request.Builder = Request.Builder()
        .url(target)
        .header("Accept", "application/json")
        .header("Cache-Control", "no-cache")
        .header("Accept-Encoding", "identity")

    private fun authenticate(request: Request, payload: ByteArray = byteArrayOf()): Request = when (val provider = config) {
        is RemoteConfig.WebDav -> if (provider.username.isNotEmpty() || provider.password.isNotEmpty()) {
            request.newBuilder().header(
                "Authorization", Credentials.basic(provider.username, provider.password, Charsets.UTF_8),
            ).build()
        } else request
        is RemoteConfig.S3 -> AwsV4Signer.sign(
            request = request,
            accessKeyId = provider.accessKeyId,
            secretAccessKey = provider.secretAccessKey,
            region = provider.region,
            sessionToken = provider.sessionToken,
            payload = payload,
        )
    }

    /** Reading happens on OkHttp's dispatcher; cancellation cancels the socket and closes its body. */
    private suspend fun execute(request: Request, readBody: Boolean): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(SyncException("无法连接同步服务，请检查网络和服务地址。", true, error))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        HttpResult(
                            code = response.code,
                            etag = response.header("ETag")?.trim(),
                            body = if (readBody && response.code == 200) readLimited(response.body) else byteArrayOf(),
                        )
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            if (error is SyncException) error
                            else SyncException("读取同步数据失败，请检查网络后重试。", true, error),
                        )
                    }
                }
            }
        })
    }

    private fun readLimited(body: ResponseBody?): ByteArray {
        if (body == null) throw SyncProtocolException("服务器返回了空的同步响应。")
        if (body.contentLength() > EncryptedSyncJson.MAX_DOCUMENT_BYTES) {
            throw SyncProtocolException("远端加密文件超过大小上限，未覆盖任何数据。")
        }
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (output.size().toLong() + count > EncryptedSyncJson.MAX_DOCUMENT_BYTES) {
                    throw SyncProtocolException("远端加密文件超过大小上限，未覆盖任何数据。")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private data class HttpResult(val code: Int, val etag: String?, val body: ByteArray)
    private data class RemoteSnapshot(val document: SyncDocument, val etag: String?, val exists: Boolean)

    companion object {
        // Retain the object location so both new and old clients reject incompatible formats.
        const val FILE_NAME: String = "colormemo-v1.json"
        private const val MAX_WRITE_ATTEMPTS = 4
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val STRONG_ETAG = Regex("\"[\\x21\\x23-\\x7E]*\"")

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        private fun targetUrl(config: RemoteConfig): HttpUrl = when (config) {
            is RemoteConfig.WebDav -> {
                requireSyncConfiguration(
                    ':' !in config.username && config.username.none { it.isISOControl() },
                    "WebDAV 用户名不能包含冒号或控制字符。",
                )
                secureUrl(config.url).newBuilder().addPathSegment(FILE_NAME).build()
            }
            is RemoteConfig.S3 -> {
                requireSyncConfiguration(
                    config.bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")), "S3 存储桶名称格式无效。",
                )
                requireSyncConfiguration(
                    config.accessKeyId.isNotBlank() && config.secretAccessKey.isNotEmpty(),
                    "S3 Access Key ID 和 Secret Access Key 不能为空。",
                )
                requireSyncConfiguration(config.accessKeyId.all { it.code in 33..126 && it != '/' }, "S3 Access Key ID 格式无效。")
                requireSyncConfiguration(config.sessionToken.all { it.code in 33..126 }, "S3 session token 格式无效。")
                requireSyncConfiguration(config.region.matches(Regex("[a-z0-9-]{1,64}")), "S3 区域格式无效。")
                val segments = config.prefix.trim('/').split('/').filter { it.isNotEmpty() }
                requireSyncConfiguration(
                    segments.none { it == "." || it == ".." || it.any(Char::isISOControl) },
                    "S3 路径前缀不能包含相对目录或控制字符。",
                )
                val url = secureUrl(config.endpoint).newBuilder().addPathSegment(config.bucket).apply {
                    segments.forEach(::addPathSegment)
                    addPathSegment(FILE_NAME)
                }.build()
                // Match the path transmitted by OkHttp to the exact S3 URI-encoding rules.
                url.newBuilder().encodedPath(AwsV4Signer.canonicalUri(url)).build()
            }
        }

        private fun secureUrl(value: String): HttpUrl {
            val url = value.trim().toHttpUrlOrNull() ?: throw SyncConfigurationException("请输入有效的 HTTPS 同步地址。")
            requireSyncConfiguration(
                url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1"),
                "同步地址必须使用 HTTPS，防止笔记和凭据明文传输。",
            )
            requireSyncConfiguration(
                url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null,
                "同步地址不能包含登录凭据、查询参数或片段，请使用独立凭据字段。",
            )
            return url
        }

        private fun httpFailure(code: Int): SyncException = SyncException(
            message = when (code) {
                401, 403 -> "同步服务拒绝访问（HTTP $code），请检查凭据和存储权限。"
                404 -> "同步目录或存储桶不存在（HTTP 404）。"
                423 -> "远端文件已锁定（HTTP 423），请稍后重试。"
                507 -> "同步服务存储空间不足（HTTP 507）。"
                in 300..399 -> "同步地址发生重定向（HTTP $code），请填写最终 HTTPS 地址。"
                else -> "同步服务返回 HTTP $code，未覆盖本地数据。"
            },
            isRetryable = code == 408 || code == 423 || code == 429 || code in 500..599,
        )
    }
}
