package dev.colormemo.core.sync

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AwsV4SignerTest {
    @Test
    fun matchesPublishedAwsS3GetObjectSignatureVector() {
        // AWS S3 example: GET /test.txt, Range bytes=0-9, 2013-05-24.
        // The fixture was also independently recalculated with Python hashlib/hmac.
        val request = Request.Builder().url("https://examplebucket.s3.amazonaws.com/test.txt")
            .header("Range", "bytes=0-9").build()
        val signed = AwsV4Signer.sign(
            request, "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            "us-east-1", instant = Instant.parse("2013-05-24T00:00:00Z"),
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signed.header("Authorization"),
        )
    }

    @Test
    fun s3PathEncodingDoesNotDoubleEncodeOrNormalizeObjectKeys() {
        assertEquals("/a%3Ab%3Ac", AwsV4Signer.canonicalUri("https://example.com/a%3Ab%3Ac".toHttpUrl()))
        assertEquals("/folder//%E7%AC%94%E8%AE%B0%20%2B%25.md", AwsV4Signer.canonicalUri(
            "https://example.com/folder//%E7%AC%94%E8%AE%B0%20%2B%25.md".toHttpUrl(),
        ))
        assertEquals("%2F%20%2B%25~", AwsV4Signer.uriEncode("/ +%~"))
    }

    @Test
    fun queryParametersSortAfterEncodingAndPreserveDuplicatesAndEmptyValues() {
        val url = "https://example.com/?z=2&a=%E7%AC%94%E8%AE%B0&a=one%20two&acl".toHttpUrl()
        assertEquals("a=%E7%AC%94%E8%AE%B0&a=one%20two&acl=&z=2", AwsV4Signer.canonicalQuery(url))
    }

    @Test
    fun sessionTokenPayloadAndConditionalHeadersParticipateInTheSignature() {
        val request = Request.Builder().url("https://account.r2.cloudflarestorage.com/bucket/key")
            .header("If-Match", "\"version\"").build()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val signed = AwsV4Signer.sign(request, "testkey", "testsecret", "auto", "temporary-token", now, "hello".toByteArray())
        val changed = AwsV4Signer.sign(request, "testkey", "testsecret", "auto", "temporary-token", now, "world".toByteArray())
        assertEquals("temporary-token", signed.header("x-amz-security-token"))
        assertTrue(signed.header("Authorization")!!.contains("/auto/s3/aws4_request"))
        assertTrue(signed.header("Authorization")!!.contains("host;if-match;x-amz-content-sha256;x-amz-date;x-amz-security-token"))
        assertNotEquals(signed.header("Authorization"), changed.header("Authorization"))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", signed.header("x-amz-content-sha256"))
    }

    @Test
    fun invalidSessionTokenIsRejectedWithoutBeingEchoedInTheError() {
        val request = Request.Builder().url("https://example.com/bucket/key").build()
        val error = assertThrows(SyncConfigurationException::class.java) {
            AwsV4Signer.sign(request, "key", "secret", "auto", "do-not-leak\nthis-token")
        }
        assertFalse(error.message.orEmpty().contains("do-not-leak"))
    }
}
