package com.tufein.valkyr.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.io.IOException

/**
 * Encrypted backup files for the cloud (`.dgxb`).
 *
 * ```
 * "DGXB1" (5 bytes) | salt (16) | iv (12) | AES-256-GCM ciphertext + 16-byte tag
 * ```
 * The key comes from the user's passphrase through PBKDF2-HMAC-SHA256 with [ITERATIONS] rounds
 * and the per-file random salt; every file has a fresh random IV. The header (magic, salt, IV) is
 * authenticated as GCM associated data, so changing any byte of the file makes it unreadable
 * instead of silently different. The plaintext is the backup JSON, gzip-compressed ([seal] /
 * [open]). A wrong passphrase and a damaged file look the same to GCM: both give
 * [UnreadableException], never garbage. Pure JVM (javax.crypto) for the tests.
 */
object BackupCrypto {

    val MAGIC: ByteArray = "DGXB1".toByteArray(Charsets.US_ASCII)
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BITS = 256
    /** PBKDF2 rounds; part of the DGXB1 format (a reader must use the same number). */
    const val ITERATIONS = 600_000
    /** Shortest passphrase the app accepts. */
    const val MIN_PASSPHRASE = 8
    /** Largest decompressed backup accepted (backups are far smaller; this stops a "zip bomb"). */
    const val MAX_PLAIN_BYTES = 32 * 1024 * 1024

    private val HEADER_BYTES = MAGIC.size + SALT_BYTES + IV_BYTES

    /** The file does not start with the DGXB1 header: not an encrypted Valkyr backup. */
    class NotEncryptedException : IllegalArgumentException("Not an encrypted Valkyr backup")

    /** Wrong passphrase, or the file was changed or cut short. */
    class UnreadableException(cause: Throwable? = null) : GeneralSecurityException("Wrong passphrase or damaged backup", cause)

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /** Long enough to be accepted as a backup passphrase. */
    fun isAcceptable(passphrase: String): Boolean = passphrase.trim().length >= MIN_PASSPHRASE

    /** Encrypts [plain]; [random] and [iterations] are parameters only for the tests. */
    fun encrypt(plain: ByteArray, passphrase: String, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = MAGIC + salt + iv
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(passphrase, salt, iterations), "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    /**
     * Decrypts a file made by [encrypt]. Throws [NotEncryptedException] when [blob] is not one,
     * [UnreadableException] for a wrong passphrase or a damaged / truncated file.
     */
    fun decrypt(blob: ByteArray, passphrase: String, iterations: Int = ITERATIONS): ByteArray {
        if (!isEncrypted(blob)) throw NotEncryptedException()
        if (blob.size < HEADER_BYTES + TAG_BITS / 8) throw UnreadableException()
        val salt = blob.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = blob.copyOfRange(MAGIC.size + SALT_BYTES, HEADER_BYTES)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(passphrase, salt, iterations), "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(blob, 0, HEADER_BYTES)
            cipher.doFinal(blob, HEADER_BYTES, blob.size - HEADER_BYTES)
        } catch (e: GeneralSecurityException) {
            // AEADBadTagException and friends: never return partial or unauthenticated bytes.
            throw UnreadableException(e)
        }
    }

    /** The backup JSON as an encrypted, compressed `.dgxb` file. */
    fun seal(json: String, passphrase: String, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): ByteArray =
        encrypt(gzip(json.toByteArray(Charsets.UTF_8)), passphrase, random, iterations)

    /** The backup JSON inside a `.dgxb` file (see [decrypt] for the failures). */
    fun open(blob: ByteArray, passphrase: String, iterations: Int = ITERATIONS): String {
        val plain = decrypt(blob, passphrase, iterations)
        val bytes = if (plain.size >= 2 && plain[0] == 0x1f.toByte() && plain[1] == 0x8b.toByte()) gunzip(plain) else plain
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * PBKDF2-HMAC-SHA256 key from [passphrase]. The passphrase is trimmed and NFC-normalized, so
     * the same words typed on another keyboard (é as one character or e + accent) give the same key.
     */
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int = ITERATIONS, bits: Int = KEY_BITS): ByteArray {
        val chars = Normalizer.normalize(passphrase.trim(), Normalizer.Form.NFC).toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, bits)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_PLAIN_BYTES) throw UnreadableException()
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: IOException) {
            throw UnreadableException(e)
        }
        return out.toByteArray()
    }
}
