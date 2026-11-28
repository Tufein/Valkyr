package com.tufein.valkyr.util

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * 10.0: the CRC32, MD5 and SHA-1 of a file, read once. These are the three sums that DAT files and
 * Redump list, so a dump can be compared by eye. Pure JVM; the caller supplies the stream.
 */
object Fingerprints {
    data class Fingerprint(val name: String, val bytes: Long, val crc32: String, val md5: String, val sha1: String)

    private const val BUFFER = 256 * 1024

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Reads [input] to its end. [onProgress] gets the bytes read so far; stop by cancelling the calling coroutine (it must check) or throwing from [onProgress]. */
    fun compute(name: String, input: InputStream, onProgress: (Long) -> Unit = {}): Fingerprint {
        val crc = CRC32()
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            crc.update(buffer, 0, n); md5.update(buffer, 0, n); sha1.update(buffer, 0, n)
            total += n
            onProgress(total)
        }
        return Fingerprint(name, total, "%08x".format(crc.value), hex(md5.digest()), hex(sha1.digest()))
    }

    /** The text that is copied: one block per file, in the order DAT readers know. */
    fun format(prints: List<Fingerprint>): String = prints.joinToString("\n\n") {
        "${it.name}\nSize: ${it.bytes} bytes\nCRC32: ${it.crc32}\nMD5: ${it.md5}\nSHA-1: ${it.sha1}"
    }
}
