package com.tufein.valkyr.util

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 8.2: AWS Signature Version 4 for S3-compatible storage (Amazon S3, MinIO, Backblaze B2, Wasabi,
 * Cloudflare R2, Synology C2…). Pure JVM; checked against the examples in Amazon's documentation.
 *
 * Only what S3 needs: the path is encoded once (S3 does not double-encode), the signed headers are
 * `host`, `x-amz-content-sha256`, `x-amz-date` and whatever [sign] is given, and the payload hash is
 * always sent, so a plain-http MinIO on the home network works the same as https.
 */
object S3Signer {
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private const val ALGORITHM = "AWS4-HMAC-SHA256"

    /** Headers to put on the request: `Authorization`, `x-amz-date` and `x-amz-content-sha256`. */
    fun sign(
        method: String,
        host: String,
        encodedPath: String,
        query: List<Pair<String, String>>,
        extraHeaders: Map<String, String>,
        payloadHash: String,
        accessKey: String,
        secretKey: String,
        region: String,
        amzDate: String
    ): Map<String, String> {
        val date = amzDate.substring(0, 8)
        val headers = sortedMapOf<String, String>()
        extraHeaders.forEach { (k, v) -> headers[k.lowercase()] = v.trim().replace(Regex("\\s+"), " ") }
        headers["host"] = host
        headers["x-amz-content-sha256"] = payloadHash
        headers["x-amz-date"] = amzDate
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val canonical = listOf(method, encodedPath, canonicalQuery(query), canonicalHeaders, signedHeaders, payloadHash).joinToString("\n")
        val scope = "$date/$region/s3/aws4_request"
        val toSign = listOf(ALGORITHM, amzDate, scope, sha256Hex(canonical.toByteArray())).joinToString("\n")
        val key = hmac(hmac(hmac(hmac(("AWS4$secretKey").toByteArray(), date), region), "s3"), "aws4_request")
        val signature = hmac(key, toSign).joinToString("") { "%02x".format(it) }
        return mapOf(
            "Authorization" to "$ALGORITHM Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature",
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash
        )
    }

    /** The query as S3 signs it: names and values encoded, sorted by name. */
    fun canonicalQuery(query: List<Pair<String, String>>): String =
        query.map { encode(it.first) to encode(it.second) }.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }

    /** Percent-encodes everything but `A-Za-z0-9-_.~`; a `/` stays when [keepSlash] (the path). */
    fun encode(text: String, keepSlash: Boolean = false): String {
        val out = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' || (keepSlash && ch == '/')) out.append(ch)
            else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return out.toString()
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray(Charsets.UTF_8))
}
