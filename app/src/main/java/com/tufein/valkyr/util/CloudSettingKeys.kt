package com.tufein.valkyr.util

/**
 * Names, types and backup rules of the WebDAV settings that live in `user_settings` (see
 * [com.tufein.valkyr.data.local.CloudSettings]). A pure object so [BackupJson] and
 * `BackupService` can apply the rules without touching DataStore, and the tests can check them.
 *
 * - Every key has a type tag (like [BackupJson]'s `t`): a backed-up value of another type is
 *   dropped, because the typed DataStore reader would throw a ClassCastException on it.
 * - The app password is a **secret that never travels in a backup file**: the other tokens in
 *   `user_settings` do (the backup says so), but those only open one app; this one opens the
 *   user's whole personal cloud. After a restore it is kept only while the restored settings point
 *   at the same server and user ([keepsPassword]); otherwise the user types it again.
 * - The backup passphrase never lives in `user_settings` at all (it is in `dav_device`).
 */
object CloudSettingKeys {
    const val URL = "dav_url"
    const val USER = "dav_user"
    const val PASSWORD = "dav_password"
    const val FOLDER = "dav_folder"
    const val AUTO_BACKUP = "dav_auto_backup"
    const val KEEP = "dav_keep"
    const val DEVICE_SYNC = "dav_device_sync"
    /** SHA-256 of the WebDAV server certificate the user confirmed (self-signed or private CA). */
    const val TRUST_FINGERPRINT = "dav_trust_fingerprint"
    /** 6.0 shared wishlist: the name of the list (empty = off) and the name this person goes by in it. */
    const val SHARED_LIST = "dav_shared_list"
    const val SHARED_NAME = "dav_shared_name"

    /** 0 = keep every backup ([CloudBackupNames.KEEP_ALL]). */
    const val MIN_KEEP = 0
    const val MAX_KEEP = 100

    /** Type tag per key, as in [BackupJson]: `b` boolean, `i` int, `s` string. */
    val TYPES: Map<String, String> = mapOf(
        URL to "s", USER to "s", PASSWORD to "s", FOLDER to "s", TRUST_FINGERPRINT to "s", SHARED_LIST to "s", SHARED_NAME to "s",
        AUTO_BACKUP to "b", DEVICE_SYNC to "b",
        KEEP to "i"
    )

    /** Values that must never be written into a backup file nor read back from one. */
    val SECRETS: Set<String> = setOf(PASSWORD)

    /**
     * 4.3.0: the ScreenScraper passwords (developer and user) stay out of backups the same way.
     * 4.4.0: so do the TheGamesDB / SteamGridDB keys and the Twitch client secret (the client id may travel).
     */
    fun isSecret(name: String): Boolean = name in SECRETS || name in ScreenScraper.SECRETS || name in CoverApis.SECRETS || name in RommRemoteInstall.SECRETS

    /** [value] of the setting [name] brought into what the app accepts; null drops it. */
    fun sanitize(name: String, value: Any): Any? = when (name) {
        KEEP -> (value as? Int)?.coerceIn(MIN_KEEP, MAX_KEEP)
        URL, USER, FOLDER -> (value as? String)?.takeIf { it.length <= 2000 }
        SHARED_LIST, SHARED_NAME -> (value as? String)?.takeIf { it.length <= 200 }
        TRUST_FINGERPRINT -> (value as? String)?.takeIf { it.isEmpty() || CertTrust.isValid(it) }
        else -> value
    }

    /**
     * Whether the saved password still belongs after a restore changed the connection: only for
     * the same server (compared the way the client reads the address) and the same user name.
     */
    fun keepsPassword(oldUrl: String?, oldUser: String?, newUrl: String?, newUser: String?): Boolean {
        val a = oldUrl?.let { WebDavPaths.normalizeServer(it) } ?: return false
        val b = newUrl?.let { WebDavPaths.normalizeServer(it) } ?: return false
        return a == b && oldUser.orEmpty().trim() == newUser.orEmpty().trim()
    }
}
