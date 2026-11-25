package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tufein.valkyr.util.WebDavPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.mirrorStore by preferencesDataStore(name = "dav_mirror")

/** What the second cloud last did; the error is encoded the way the main cloud's errors are. */
data class MirrorRecords(
    val lastCopyAt: Long = 0L,
    val lastCopyName: String = "",
    val lastCopyBytes: Long = 0L,
    val lastTestAt: Long = 0L,
    val lastError: String = ""
)

/**
 * 9.5: the login of the second cloud (a WebDAV server or an S3 bucket) that gets a copy of every
 * backup. Kept on this device only, like the backup passphrase: it is not part of an app backup and
 * a restore never touches it. Its folder holds the same `backups` layout as the main cloud.
 */
@Singleton
class CloudMirrorSettings @Inject constructor(@param:ApplicationContext private val context: Context) {
    private object Keys {
        val URL = stringPreferencesKey("mirror_url")
        val USER = stringPreferencesKey("mirror_user")
        val PASSWORD = stringPreferencesKey("mirror_password")
        val FOLDER = stringPreferencesKey("mirror_folder")
        val COPY_AT = longPreferencesKey("mirror_copy_at")
        val COPY_NAME = stringPreferencesKey("mirror_copy_name")
        val COPY_BYTES = longPreferencesKey("mirror_copy_bytes")
        val TEST_AT = longPreferencesKey("mirror_test_at")
        val ERROR = stringPreferencesKey("mirror_error")
    }

    private val store get() = context.mirrorStore

    val config: Flow<CloudConfig> = store.data.map { p ->
        CloudConfig(
            server = p[Keys.URL] ?: "",
            user = p[Keys.USER] ?: "",
            password = p[Keys.PASSWORD] ?: "",
            folder = p[Keys.FOLDER] ?: WebDavPaths.DEFAULT_FOLDER
        )
    }.distinctUntilChanged()

    val records: Flow<MirrorRecords> = store.data.map { p ->
        MirrorRecords(p[Keys.COPY_AT] ?: 0L, p[Keys.COPY_NAME].orEmpty(), p[Keys.COPY_BYTES] ?: 0L, p[Keys.TEST_AT] ?: 0L, p[Keys.ERROR].orEmpty())
    }.distinctUntilChanged()

    /** Changing the address or login makes the earlier result meaningless. */
    private fun androidx.datastore.preferences.core.MutablePreferences.forgetResult() {
        remove(Keys.TEST_AT); remove(Keys.ERROR)
    }

    suspend fun setServer(value: String) = store.edit { it[Keys.URL] = WebDavPaths.stripCredentials(value); it.forgetResult() }
    suspend fun setUser(value: String) = store.edit { it[Keys.USER] = value.trim(); it.forgetResult() }
    suspend fun setPassword(value: String) = store.edit { it[Keys.PASSWORD] = value; it.forgetResult() }
    suspend fun setFolder(value: String) = store.edit {
        it[Keys.FOLDER] = WebDavPaths.folderSegments(value).joinToString("/").ifEmpty { WebDavPaths.DEFAULT_FOLDER }
        it.forgetResult()
    }

    /** Stops copying: the login and the record go; what is already stored there stays. */
    suspend fun clear() = store.edit { it.clear() }

    suspend fun recordCopy(at: Long, name: String, bytes: Long) = store.edit {
        it[Keys.COPY_AT] = at; it[Keys.COPY_NAME] = name; it[Keys.COPY_BYTES] = bytes; it[Keys.TEST_AT] = at; it.remove(Keys.ERROR)
    }

    suspend fun recordTest(at: Long, error: String?) = store.edit {
        it[Keys.TEST_AT] = at
        if (error == null) it.remove(Keys.ERROR) else it[Keys.ERROR] = error.take(300)
    }
}
