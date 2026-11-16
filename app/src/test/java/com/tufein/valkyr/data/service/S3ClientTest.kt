package com.tufein.valkyr.data.service

import com.tufein.valkyr.util.CloudBackupEngine
import com.tufein.valkyr.util.DavConditionalWriter
import com.tufein.valkyr.util.DavException
import com.tufein.valkyr.util.DavProblem
import com.tufein.valkyr.util.S3Signer
import com.tufein.valkyr.util.S3Target
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder
import java.time.Instant

/** The S3 client against a small in-memory S3: requests are signed, conditions are honoured, listings are paged. */
class S3ClientTest {

    private class FakeS3(private val bucket: String = "b") : Interceptor {
        val objects = sortedMapOf<String, ByteArray>()
        val etags = HashMap<String, String>()
        val log = ArrayList<String>()
        var counter = 0
        var pageSize = 1000
        var denyAll: String? = null
        var ignoreConditions = false

        private fun reply(chain: Interceptor.Chain, code: Int, body: String = "", headers: Map<String, String> = emptyMap()): Response =
            replyBytes(chain, code, body.toByteArray(), headers)

        private fun replyBytes(chain: Interceptor.Chain, code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()): Response =
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("m")
                .body(body.toResponseBody("application/xml".toMediaType())).apply { headers.forEach { (k, v) -> header(k, v) } }.build()

        private fun error(chain: Interceptor.Chain, code: Int, s3: String) = reply(chain, code, "<Error><Code>$s3</Code><Message>m</Message></Error>")

        override fun intercept(chain: Interceptor.Chain): Response {
            val r = chain.request()
            log += "${r.method} ${r.url.encodedPath}${r.url.query?.let { "?$it" } ?: ""}"
            val auth = r.header("Authorization").orEmpty()
            assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKID/"))
            val body = Buffer().also { b -> r.body?.writeTo(b) }.readByteArray()
            assertEquals(S3Signer.sha256Hex(body), r.header("x-amz-content-sha256"))
            denyAll?.let { return error(chain, 403, it) }
            val path = r.url.encodedPath
            if (!path.startsWith("/$bucket")) return error(chain, 404, "NoSuchBucket")
            val key = URLDecoder.decode(path.removePrefix("/$bucket").removePrefix("/"), "UTF-8")
            if (r.method == "GET" && key.isEmpty()) {
                val prefix = r.url.queryParameter("prefix").orEmpty()
                val after = r.url.queryParameter("continuation-token")
                val inPrefix = objects.keys.filter { it.startsWith(prefix) }
                val folders = inPrefix.filter { '/' in it.removePrefix(prefix) }.map { prefix + it.removePrefix(prefix).substringBefore('/') + "/" }.distinct()
                val files = inPrefix.filter { '/' !in it.removePrefix(prefix) }.filter { after == null || it > after }
                val page = files.take(pageSize)
                val more = files.size > page.size
                val xml = buildString {
                    append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><IsTruncated>$more</IsTruncated>")
                    if (more) append("<NextContinuationToken>${page.last()}</NextContinuationToken>")
                    page.forEach { append("<Contents><Key>$it</Key><Size>${objects[it]!!.size}</Size><ETag>&quot;${etags[it]}&quot;</ETag></Contents>") }
                    if (after == null) folders.forEach { append("<CommonPrefixes><Prefix>$it</Prefix></CommonPrefixes>") }
                    append("</ListBucketResult>")
                }
                return reply(chain, 200, xml)
            }
            return when (r.method) {
                "PUT" -> {
                    val copy = r.header("x-amz-copy-source")
                    if (!ignoreConditions) {
                        if (r.header("If-None-Match") == "*" && key in objects) return error(chain, 412, "PreconditionFailed")
                        r.header("If-Match")?.let { if (it.trim('"') != etags[key]) return error(chain, 412, "PreconditionFailed") }
                    }
                    val data = if (copy != null) objects[URLDecoder.decode(copy.removePrefix("/$bucket/"), "UTF-8")] ?: return error(chain, 404, "NoSuchKey") else body
                    objects[key] = data
                    etags[key] = "e${++counter}"
                    reply(chain, 200, if (copy != null) "<CopyObjectResult><ETag>x</ETag></CopyObjectResult>" else "", mapOf("ETag" to "\"${etags[key]}\""))
                }
                "GET" -> objects[key]?.let { replyBytes(chain, 200, it, mapOf("ETag" to "\"${etags[key]}\"")) } ?: error(chain, 404, "NoSuchKey")
                "HEAD" -> objects[key]?.let { reply(chain, 200, "", mapOf("ETag" to "\"${etags[key]}\"", "Content-Length" to it.size.toString())) } ?: reply(chain, 404)
                "DELETE" -> { objects.remove(key); reply(chain, 204) }
                else -> reply(chain, 405)
            }
        }
    }

    private val fixed = Instant.parse("2026-11-16T12:00:00Z")
    private val target = S3Target.parse("s3://s3.example.com/b")!!
    private fun clientOn(fake: FakeS3) = S3Client(target, "AKID", "secret", OkHttpClient.Builder().addInterceptor(fake).build()) { fixed }
    private val root = "https://s3.example.com/b/Dogmatix/"

    @Test fun `put get and delete round trip with a signed request`() {
        val fake = FakeS3(); val s3 = clientOn(fake)
        val etag = s3.put(root + "backups/one.dgxb", "hello".toByteArray(), "application/octet-stream")
        assertNotNull(etag)
        assertArrayEquals("hello".toByteArray(), s3.get(root + "backups/one.dgxb", 100)!!.bytes)
        assertEquals(listOf("Dogmatix/backups/one.dgxb"), fake.objects.keys.toList())
        s3.delete(root + "backups/one.dgxb")
        assertNull(s3.get(root + "backups/one.dgxb", 100))
        s3.delete(root + "backups/one.dgxb") // already gone counts as done
    }

    @Test fun `names with spaces and accents are written under their decoded key`() {
        val fake = FakeS3(); val s3 = clientOn(fake)
        s3.put(root + "backups/Pokémon save 1.bin", byteArrayOf(1), "application/octet-stream")
        assertEquals(listOf("Dogmatix/backups/Pokémon save 1.bin"), fake.objects.keys.toList())
        assertTrue(s3.exists(root + "backups/Pokémon save 1.bin"))
    }

    @Test fun `a listing shows files and sub folders, and follows pages`() {
        val fake = FakeS3(); fake.pageSize = 2; val s3 = clientOn(fake)
        listOf("a", "b", "c", "d", "sync/x").forEach { s3.put(root + it, byteArrayOf(1, 2), "text/plain") }
        val names = s3.list(root).map { it.name to it.isCollection }
        assertEquals(setOf("a" to false, "b" to false, "c" to false, "d" to false, "sync" to true), names.toSet())
        assertEquals(5, names.size)
        assertTrue(s3.list(root + "empty/").isEmpty())
        assertEquals(2L, s3.list(root).first { it.name == "a" }.size)
    }

    @Test fun `a new file refuses a second write and a changed file refuses a stale one`() {
        val fake = FakeS3(); val s3 = clientOn(fake)
        val first = s3.put(root + "f", "1".toByteArray(), "text/plain", ifNoneMatch = true)
        try { s3.put(root + "f", "2".toByteArray(), "text/plain", ifNoneMatch = true); fail() } catch (e: DavException) { assertEquals(DavProblem.PRECONDITION, e.problem) }
        s3.put(root + "f", "3".toByteArray(), "text/plain", ifMatch = first)
        try { s3.put(root + "f", "4".toByteArray(), "text/plain", ifMatch = first); fail() } catch (e: DavException) { assertEquals(DavProblem.PRECONDITION, e.problem) }
        assertEquals("3", String(s3.get(root + "f", 10)!!.bytes))
    }

    @Test fun `the shared writer works on top of it`() {
        val fake = FakeS3(); val s3 = clientOn(fake)
        val writer = DavConditionalWriter(s3, root + "sync/doc.json", "application/json", 1024)
        assertTrue(writer.attempt(null, "{\"a\":1}".toByteArray()) { true })
        val file = s3.get(root + "sync/doc.json", 1024)
        assertTrue(DavConditionalWriter(s3, root + "sync/doc.json", "application/json", 1024).attempt(file, "{\"a\":2}".toByteArray()) { true })
        assertEquals("{\"a\":2}", String(s3.get(root + "sync/doc.json", 1024)!!.bytes))
    }

    @Test fun `move copies then deletes and keeps the bytes`() {
        val fake = FakeS3(); val s3 = clientOn(fake)
        s3.put(root + "backups/x.part", "data".toByteArray(), "text/plain")
        assertTrue(s3.move(root + "backups/x.part", root + "backups/x.dgxb"))
        assertEquals(listOf("Dogmatix/backups/x.dgxb"), fake.objects.keys.toList())
        assertEquals("data", String(fake.objects.getValue("Dogmatix/backups/x.dgxb")))
        assertFalse(s3.moveNoReplace("a", "b"))
    }

    @Test fun `streams are hashed before they are sent`() {
        val fake = FakeS3(); val s3 = clientOn(fake)
        val data = ByteArray(300_000) { (it % 251).toByte() }
        s3.putStream(root + "games/big.bin", data.size.toLong(), { data.inputStream() })
        assertArrayEquals(data, fake.objects["Dogmatix/games/big.bin"])
        val read = s3.getStream(root + "games/big.bin") { it.readBytes().size }
        assertEquals(data.size, read)
        assertNull(s3.getStream(root + "games/none.bin") { it.readBytes() })
    }

    @Test fun `a bad key or a missing bucket is told apart`() {
        val fake = FakeS3(); val s3 = clientOn(fake)
        fake.denyAll = "InvalidAccessKeyId"
        try { s3.get(root + "f", 10); fail() } catch (e: DavException) { assertEquals(DavProblem.AUTH, e.problem) }
        fake.denyAll = null
        val other = S3Client(S3Target.parse("s3://s3.example.com/nope")!!, "AKID", "secret", OkHttpClient.Builder().addInterceptor(fake).build()) { fixed }
        try { other.list("https://s3.example.com/nope/Dogmatix/"); fail() } catch (e: DavException) { assertEquals(DavProblem.NOT_FOUND, e.problem) }
    }

    @Test fun `the connection test writes, reads back and deletes one object`() {
        val fake = FakeS3(); val s3 = clientOn(fake)
        val connection = CloudBackupEngine.connectS3(s3, target, "Dogmatix")
        assertEquals("https://s3.example.com/b/Dogmatix/", connection.rootUrl)
        assertTrue(fake.objects.isEmpty())
        assertEquals(listOf("PUT", "GET", "DELETE"), fake.log.map { it.substringBefore(' ') })
    }
}
