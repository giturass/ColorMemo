package dev.colormemo.core.sync

import okhttp3.HttpUrl
import okhttp3.Request
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** AWS Signature Version 4, with the S3 single-encoding rule for object paths. */
object AwsV4Signer {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)
    private val whitespace = Regex("[\\t\\n\\r ]+")
    private val unsignedHeaders = setOf(
        "authorization", "connection", "transfer-encoding", "user-agent", "accept-encoding",
        "content-length", "expect", "x-amzn-trace-id",
    )

    fun sign(
        request: Request,
        accessKeyId: String,
        secretAccessKey: String,
        region: String,
        sessionToken: String = "",
        instant: Instant = Instant.now(),
        payload: ByteArray = byteArrayOf(),
    ): Request {
        requireSyncConfiguration(accessKeyId.isNotBlank() && secretAccessKey.isNotEmpty(), "S3 访问密钥不能为空。")
        requireSyncConfiguration(region.matches(Regex("[a-z0-9-]{1,64}")), "S3 区域格式无效。")
        requireSyncConfiguration(accessKeyId.all { it.code in 33..126 && it != '/' }, "S3 Access Key ID 格式无效。")
        requireSyncConfiguration(sessionToken.all { it.code in 33..126 }, "S3 session token 格式无效。")
        val timestamp = timestampFormat.format(instant)
        val day = timestamp.take(8)
        val payloadHash = sha256Hex(payload)
        val prepared = request.newBuilder()
            .header("Host", hostHeader(request.url))
            .header("x-amz-date", timestamp)
            .header("x-amz-content-sha256", payloadHash)
            .removeHeader("Authorization")
            .apply {
                if (sessionToken.isNotEmpty()) header("x-amz-security-token", sessionToken)
                else removeHeader("x-amz-security-token")
            }
            .build()
        val names = prepared.headers.names().map { it.lowercase(Locale.ROOT) }
            .filterNot { it in unsignedHeaders }.sorted()
        val signedHeaders = names.joinToString(";")
        val canonicalHeaders = names.joinToString("") { name ->
            val values = prepared.headers.values(name).joinToString(",") {
                it.trim().replace(whitespace, " ")
            }
            "$name:$values\n"
        }
        val canonical = listOf(
            prepared.method,
            canonicalUri(prepared.url),
            canonicalQuery(prepared.url),
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")
        val scope = "$day/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$timestamp\n$scope\n${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
        val dateKey = hmac("AWS4$secretAccessKey".toByteArray(Charsets.UTF_8), day)
        val regionKey = hmac(dateKey, region)
        val serviceKey = hmac(regionKey, "s3")
        val signingKey = hmac(serviceKey, "aws4_request")
        val signature = hex(hmac(signingKey, toSign))
        return prepared.newBuilder().header(
            "Authorization",
            "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature",
        ).build()
    }

    fun canonicalUri(url: HttpUrl): String = "/" + url.pathSegments.joinToString("/") { uriEncode(it) }

    fun canonicalQuery(url: HttpUrl): String = (0 until url.querySize)
        .map { uriEncode(url.queryParameterName(it)) to uriEncode(url.queryParameterValue(it).orEmpty()) }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .joinToString("&") { "${it.first}=${it.second}" }

    /** RFC 3986 unreserved bytes only; spaces are %20 and hex digits are uppercase. */
    fun uriEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            if (code in 65..90 || code in 97..122 || code in 48..57 || code in listOf(45, 46, 95, 126)) {
                append(code.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[code ushr 4])
                append("0123456789ABCDEF"[code and 15])
            }
        }
    }

    internal fun sha256Hex(value: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(value))

    private fun hostHeader(url: HttpUrl): String {
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val defaultPort = if (url.isHttps) 443 else 80
        return if (url.port == defaultPort) host else "$host:${url.port}"
    }

    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append("0123456789abcdef"[value ushr 4])
            append("0123456789abcdef"[value and 15])
        }
    }
}
