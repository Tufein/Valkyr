package com.tufein.valkyr.util

import com.tufein.valkyr.data.service.S3Errors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S3Test {

    private val access = "AKIAIOSFODNN7EXAMPLE"
    private val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

    private fun signatureOf(headers: Map<String, String>) = headers.getValue("Authorization").substringAfter("Signature=")

    @Test fun `GET object with a range matches the example in Amazon's documentation`() {
        val h = S3Signer.sign("GET", "examplebucket.s3.amazonaws.com", "/test.txt", emptyList(), mapOf("Range" to "bytes=0-9"),
            S3Signer.EMPTY_SHA256, access, secret, "us-east-1", "20130524T000000Z")
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", signatureOf(h))
        assertTrue(h.getValue("Authorization").contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date"))
    }

    @Test fun `PUT object with a dollar sign matches the example in Amazon's documentation`() {
        val payload = S3Signer.sha256Hex("Welcome to Amazon S3.".toByteArray())
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", payload)
        val h = S3Signer.sign("PUT", "examplebucket.s3.amazonaws.com", "/" + S3Signer.encode("test\$file.text", true), emptyList(),
            mapOf("Date" to "Fri, 24 May 2013 00:00:00 GMT", "x-amz-storage-class" to "REDUCED_REDUNDANCY"),
            payload, access, secret, "us-east-1", "20130524T000000Z")
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd", signatureOf(h))
    }

    @Test fun `GET with a query sorts and encodes it`() {
        assertEquals("list-type=2&prefix=a%20b%2Fc%2F", S3Signer.canonicalQuery(listOf("prefix" to "a b/c/", "list-type" to "2")))
    }

    @Test fun `encode keeps unreserved characters and the path slash only when asked`() {
        assertEquals("a-b_c.d~e", S3Signer.encode("a-b_c.d~e"))
        assertEquals("a%2Fb", S3Signer.encode("a/b"))
        assertEquals("a/b%20c%C3%A9", S3Signer.encode("a/b cé", keepSlash = true))
    }

    @Test fun `an s3 address names host, bucket and region`() {
        val aws = S3Target.parse("s3://s3.eu-central-1.amazonaws.com/my-bucket")!!
        assertEquals("https://s3.eu-central-1.amazonaws.com/my-bucket/", aws.baseUrl)
        assertEquals("eu-central-1", aws.region)
        val b2 = S3Target.parse("S3://s3.us-west-004.backblazeb2.com/games")!!
        assertEquals("us-west-004", b2.region)
        val minio = S3Target.parse("s3+http://192.168.1.20:9000/valkyr")!!
        assertEquals("http://192.168.1.20:9000/valkyr/", minio.baseUrl)
        assertEquals("us-east-1", minio.region)
        assertEquals("auto", S3Target.parse("s3://acct.r2.cloudflarestorage.com/b?region=auto")!!.region)
    }

    @Test fun `an s3 address without host or bucket, or with a login in it, is refused`() {
        assertNull(S3Target.parse("s3://"))
        assertNull(S3Target.parse("s3://host"))
        assertNull(S3Target.parse("s3://key:secret@host/bucket"))
        assertNull(S3Target.parse("https://host/bucket"))
        assertTrue(S3Target.isS3(" S3+http://h/b"))
    }

    @Test fun `the server field is normalised like any other address`() {
        assertEquals("https://s3.example.com/b/", WebDavPaths.normalizeServer("s3://s3.example.com/b"))
        assertNull(WebDavPaths.normalizeServer("s3://s3.example.com"))
        assertEquals("https://s3.example.com/b/Dogmatix/", WebDavPaths.folderUrl("s3://s3.example.com/b", ""))
        assertTrue(WebDavPaths.isCleartextRisk("s3+http://storage.example.com/b"))
        assertEquals(false, WebDavPaths.isCleartextRisk("s3+http://192.168.1.20:9000/b"))
    }

    @Test fun `a listing gives keys, folders and the continuation token`() {
        val xml = """<?xml version="1.0"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
            <Name>b</Name><IsTruncated>true</IsTruncated><NextContinuationToken>tok/1</NextContinuationToken>
            <Contents><Key>Dogmatix/a.json</Key><LastModified>2026-11-15T10:00:00.000Z</LastModified><ETag>&quot;abc&quot;</ETag><Size>12</Size></Contents>
            <CommonPrefixes><Prefix>Dogmatix/sync/</Prefix></CommonPrefixes></ListBucketResult>"""
        val l = S3Xml.parseListing(xml)
        assertEquals(listOf("Dogmatix/a.json"), l.items.map { it.key })
        assertEquals(12L, l.items[0].size)
        assertEquals("abc", l.items[0].etag)
        assertEquals(listOf("Dogmatix/sync/"), l.prefixes)
        assertEquals("tok/1", l.nextToken)
        assertEquals(1_794_736_800_000L, l.items[0].lastModified)
    }

    @Test fun `an error body gives its code`() {
        val e = S3Xml.parseError("<Error><Code>SignatureDoesNotMatch</Code><Message>x</Message></Error>")
        assertEquals("SignatureDoesNotMatch", e?.code)
        assertNull(S3Xml.parseError("<html>no</html>"))
    }

    @Test fun `statuses and codes map to the problems the cloud screens know`() {
        fun p(status: Int, code: String = "") = S3Errors.classify(status, S3Xml.Error(code, "")).problem
        assertEquals(DavProblem.AUTH, p(403, "InvalidAccessKeyId"))
        assertEquals(DavProblem.AUTH, p(403, "SignatureDoesNotMatch"))
        assertEquals(DavProblem.FORBIDDEN, p(403, "AccessDenied"))
        assertEquals(DavProblem.NOT_FOUND, p(404, "NoSuchBucket"))
        assertEquals(DavProblem.PRECONDITION, p(412, "PreconditionFailed"))
        assertEquals(DavProblem.PRECONDITION, p(409, "ConditionalRequestConflict"))
        assertEquals(DavProblem.REDIRECT, p(301, "PermanentRedirect"))
        assertEquals(DavProblem.SERVER, p(503, "SlowDown"))
        assertEquals(DavProblem.NO_SPACE, p(507))
        assertEquals("clock", S3Errors.classify(403, S3Xml.Error("RequestTimeTooSkewed", "")).detail)
    }
}
