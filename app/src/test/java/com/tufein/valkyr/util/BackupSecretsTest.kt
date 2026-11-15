package com.tufein.valkyr.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The WebDAV settings in a backup file: the password never travels, the rest is checked. */
class BackupSecretsTest {

    private fun setting(type: String, value: String) = JsonParser.parseString("""{"t":"$type","v":$value}""")

    @Test fun `a password in a backup file is ignored even when the file has one`() {
        assertNull(BackupJson.decodeSetting(CloudSettingKeys.PASSWORD, setting("s", "\"hunter2\"")))
        assertNull(BackupJson.decodeSetting(CloudSettingKeys.PASSWORD, setting("s", "\"\"")))
    }

    @Test fun `the dav settings round trip through text`() {
        listOf<Pair<String, Any>>(
            CloudSettingKeys.URL to "https://cloud.example.com/remote.php/dav/files/sam/",
            CloudSettingKeys.USER to "sam",
            CloudSettingKeys.FOLDER to "Games/Dogmatix",
            CloudSettingKeys.AUTO_BACKUP to true,
            CloudSettingKeys.DEVICE_SYNC to false,
            CloudSettingKeys.KEEP to 10,
            CloudSettingKeys.TRUST_FINGERPRINT to "cd".repeat(32)
        ).forEach { (name, value) ->
            val encoded = BackupJson.encodeSetting(value)
            assertEquals(name, value, BackupJson.decodeSetting(name, JsonParser.parseString(encoded.toString())))
        }
    }

    @Test fun `a dav setting with the wrong type is dropped`() {
        assertNull(BackupJson.decodeSetting(CloudSettingKeys.AUTO_BACKUP, setting("s", "\"yes\"")))
        assertNull(BackupJson.decodeSetting(CloudSettingKeys.KEEP, setting("s", "\"7\"")))
        assertNull(BackupJson.decodeSetting(CloudSettingKeys.KEEP, setting("l", "7")))
        assertNull(BackupJson.decodeSetting(CloudSettingKeys.URL, setting("b", "true")))
        assertNull(BackupJson.decodeSetting(CloudSettingKeys.DEVICE_SYNC, setting("i", "1")))
    }

    @Test fun `keep and the certificate pin are checked on the way in`() {
        assertEquals(0, BackupJson.decodeSetting(CloudSettingKeys.KEEP, setting("i", "0")))
        assertEquals(100, BackupJson.decodeSetting(CloudSettingKeys.KEEP, setting("i", "100000")))
        assertNull(BackupJson.decodeSetting(CloudSettingKeys.TRUST_FINGERPRINT, setting("s", "\"abcd\"")))
        assertEquals("", BackupJson.decodeSetting(CloudSettingKeys.TRUST_FINGERPRINT, setting("s", "\"\"")))
    }

    @Test fun `the backup passphrase has no setting name at all`() {
        // It lives in its own store (dav_device), so no user_settings key may be called like it.
        assertTrue(CloudSettingKeys.TYPES.keys.none { it.contains("passphrase") })
    }
}
