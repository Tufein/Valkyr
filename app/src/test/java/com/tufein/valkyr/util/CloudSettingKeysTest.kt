package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSettingKeysTest {

    private val goodFingerprint = "ab".repeat(32)

    @Test fun `every key has the dav prefix and a type`() {
        val keys = listOf(
            CloudSettingKeys.URL, CloudSettingKeys.USER, CloudSettingKeys.PASSWORD, CloudSettingKeys.FOLDER,
            CloudSettingKeys.AUTO_BACKUP, CloudSettingKeys.KEEP, CloudSettingKeys.DEVICE_SYNC, CloudSettingKeys.TRUST_FINGERPRINT,
            CloudSettingKeys.SHARED_LIST, CloudSettingKeys.SHARED_NAME
        )
        keys.forEach {
            assertTrue(it, it.startsWith("dav_"))
            assertTrue(it, it in CloudSettingKeys.TYPES)
        }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.toSet(), CloudSettingKeys.TYPES.keys)
    }

    @Test fun `only the password is a secret`() {
        assertTrue(CloudSettingKeys.isSecret(CloudSettingKeys.PASSWORD))
        assertFalse(CloudSettingKeys.isSecret(CloudSettingKeys.USER))
        assertFalse(CloudSettingKeys.isSecret(CloudSettingKeys.URL))
        assertFalse(CloudSettingKeys.isSecret("romm_token"))
    }

    @Test fun `keep is held within its range and must be a number`() {
        assertEquals(0, CloudSettingKeys.sanitize(CloudSettingKeys.KEEP, 0))
        assertEquals(0, CloudSettingKeys.sanitize(CloudSettingKeys.KEEP, -7))
        assertEquals(7, CloudSettingKeys.sanitize(CloudSettingKeys.KEEP, 7))
        assertEquals(100, CloudSettingKeys.sanitize(CloudSettingKeys.KEEP, 5000))
        assertNull(CloudSettingKeys.sanitize(CloudSettingKeys.KEEP, "7"))
    }

    @Test fun `an absurdly long address, user or folder is dropped`() {
        val long = "x".repeat(2001)
        assertNull(CloudSettingKeys.sanitize(CloudSettingKeys.URL, long))
        assertNull(CloudSettingKeys.sanitize(CloudSettingKeys.USER, long))
        assertNull(CloudSettingKeys.sanitize(CloudSettingKeys.FOLDER, long))
        assertEquals("cloud.example.com", CloudSettingKeys.sanitize(CloudSettingKeys.URL, "cloud.example.com"))
    }

    @Test fun `a trusted fingerprint is kept only when it is a real one or empty`() {
        assertEquals(goodFingerprint, CloudSettingKeys.sanitize(CloudSettingKeys.TRUST_FINGERPRINT, goodFingerprint))
        assertEquals("", CloudSettingKeys.sanitize(CloudSettingKeys.TRUST_FINGERPRINT, ""))
        assertNull(CloudSettingKeys.sanitize(CloudSettingKeys.TRUST_FINGERPRINT, "abcd"))
        assertNull(CloudSettingKeys.sanitize(CloudSettingKeys.TRUST_FINGERPRINT, "not a fingerprint"))
    }

    @Test fun `the password stays for the same server and user only`() {
        val url = "https://cloud.example.com/remote.php/dav/files/sam/"
        assertTrue(CloudSettingKeys.keepsPassword(url, "sam", url, "sam"))
        // The same address written another way (no trailing slash, upper-case host, no scheme).
        assertTrue(CloudSettingKeys.keepsPassword(url, "sam", "CLOUD.example.com/remote.php/dav/files/sam", "sam"))
        // Spaces around the user name do not make another user.
        assertTrue(CloudSettingKeys.keepsPassword(url, "sam", url, " sam "))
        assertFalse(CloudSettingKeys.keepsPassword(url, "sam", url, "alex"))
        assertFalse(CloudSettingKeys.keepsPassword(url, "sam", "https://other.example.org/dav/", "sam"))
        // http is another server from https.
        assertFalse(CloudSettingKeys.keepsPassword("https://nas.lan/dav/", "sam", "http://nas.lan/dav/", "sam"))
    }

    @Test fun `no password is kept when an address is missing or invalid`() {
        val url = "https://cloud.example.com/dav/"
        assertFalse(CloudSettingKeys.keepsPassword(null, "sam", url, "sam"))
        assertFalse(CloudSettingKeys.keepsPassword(url, "sam", null, "sam"))
        assertFalse(CloudSettingKeys.keepsPassword("", "sam", "", "sam"))
        assertFalse(CloudSettingKeys.keepsPassword("ftp://nas.lan/", "sam", "ftp://nas.lan/", "sam"))
        // A restore that has no user: the old user must not be carried over to "".
        assertFalse(CloudSettingKeys.keepsPassword(url, "sam", url, null))
        assertTrue(CloudSettingKeys.keepsPassword(url, null, url, ""))
    }
}
