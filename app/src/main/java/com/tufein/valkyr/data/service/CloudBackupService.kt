package com.tufein.valkyr.data.service

import android.util.Log
import com.tufein.valkyr.data.local.CloudSettings
import com.tufein.valkyr.util.BackupCrypto
import com.tufein.valkyr.util.CloudBackupEngine
import com.tufein.valkyr.util.CloudBackupNames
import com.tufein.valkyr.util.CloudBackupTooLargeException
import com.tufein.valkyr.util.CloudErrors
import com.tufein.valkyr.util.CloudNoPassphraseException
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.util.ActionKind
import com.tufein.valkyr.util.ActionTopic

/** The outcome of a call to the cloud: the value, or what went wrong (`CloudMessages.of(context, error)` is its text). */
sealed class CloudResult<out T> {
    data class Ok<T>(val value: T) : CloudResult<T>()
    data class Failed(val error: Throwable) : CloudResult<Nothing>()
}

/**
 * Encrypted backups on the user's own WebDAV server (Nextcloud, ownCloud, Synology, Koofr…).
 *
 * *Back up*: the same JSON as a local backup ([BackupService.export]; the WebDAV password is never in
 * it), compressed and encrypted with the backup passphrase ([BackupCrypto]), uploaded as
 * `<folder>/backups/dogmatix-<yyyyMMdd-HHmm>-<device>.dgxb`; this device's newest N are kept
 * ([CloudBackupEngine.upload]). *Restore*: download, decrypt, check — nothing on the device is
 * touched until the file is a good backup ([prepareRestore]) and the user confirmed
 * ([restore], which goes through the same [BackupService.restore] as a local restore).
 *
 * Nothing here throws except cancellation: failures come back as [CloudResult.Failed] and are
 * recorded on the device (a code, shown in the screen and the hub). The passphrase, the password
 * and the server address are never logged.
 */
@Singleton
class CloudBackupService @Inject constructor(
    private val settings: CloudSettings,
    private val connection: CloudConnection,
    private val backupService: BackupService,
    private val deviceSync: DeviceSyncService,
    private val history: OperationHistoryService,
    private val actionLog: ActionLogService
) {
    /** A downloaded, decrypted and checked backup waiting for the user's confirmation. */
    class Prepared(val name: String, val backup: JsonObject, val createdAt: Long, val appVersion: String)

    private val lock = Mutex()
    private val active = MutableStateFlow(0)

    /** A backup, a download or a restore is running. */
    val running: Flow<Boolean> = active.map { it > 0 }.distinctUntilChanged()

    private fun begin() = active.update { it + 1 }
    private fun end() = active.update { (it - 1).coerceAtLeast(0) }

    /** The user's "Back up now": waits for a running backup, then makes one. */
    suspend fun backupNow(): CloudResult<CloudBackupEngine.Uploaded> = withContext(Dispatchers.IO) {
        lock.withLock { backup(quietTransient = false) }
    }

    /**
     * For the daily job: a backup when automatic backup is on and one is due. Null when nothing
     * was done (switched off, not due, or another backup was already running).
     */
    suspend fun runIfDue(): CloudResult<CloudBackupEngine.Uploaded>? = withContext(Dispatchers.IO) {
        if (!settings.configured.first() || !settings.autoBackup.first()) return@withContext null
        if (!CloudBackupNames.isDue(settings.records.first().lastBackupAt, System.currentTimeMillis())) return@withContext null
        if (!lock.tryLock()) return@withContext null
        try {
            backup(quietTransient = true)
        } finally {
            lock.unlock()
        }
    }

    /** Must hold [lock]. */
    private suspend fun backup(quietTransient: Boolean): CloudResult<CloudBackupEngine.Uploaded> {
        begin()
        val startedAt = System.currentTimeMillis()
        return try {
            val passphrase = settings.passphrase.first()
            if (!BackupCrypto.isAcceptable(passphrase)) throw CloudNoPassphraseException()
            val (json, _) = backupService.export()
            // A restore refuses anything above this: better to say so now than to keep a backup that cannot come back.
            if (json.length > BackupService.MAX_BACKUP_BYTES) throw CloudBackupTooLargeException(json.length.toLong(), BackupService.MAX_BACKUP_BYTES.toLong())
            val sealed = BackupCrypto.seal(json, passphrase)
            if (sealed.size > BackupService.MAX_BACKUP_BYTES) throw CloudBackupTooLargeException(sealed.size.toLong(), BackupService.MAX_BACKUP_BYTES.toLong())
            val session = connection.open()
            val uploaded = CloudBackupEngine.upload(
                session.store, session.serverUrl, session.rootUrl, sealed,
                settings.deviceName.first(), settings.keep.first(), Instant.ofEpochMilli(startedAt), settings.deviceId()
            )
            settings.recordBackup(System.currentTimeMillis(), uploaded.name, uploaded.bytes)
            Log.i(TAG, "Backup sent: ${uploaded.bytes} bytes, ${uploaded.deleted.size} old removed")
            history.event("cloud_backup", "")
            actionLog.record(ActionKind.BACKED_UP, topic = ActionTopic.CLOUD_BACKUP, bytes = uploaded.bytes)
            CloudResult.Ok(uploaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Backup failed: ${e.javaClass.simpleName}")
            // An automatic backup that finds no network is not worth a red mark; a manual one is.
            if (!(quietTransient && CloudErrors.isTransient(e))) {
                settings.recordBackupError(startedAt, CloudErrors.encode(e))
                connection.noteFailure(e)
                actionLog.cloudFailed(ActionKind.BACKUP_FAILED, ActionTopic.CLOUD_BACKUP, e)
            }
            CloudResult.Failed(e)
        } finally {
            end()
        }
    }

    /** The backups in the cloud, newest first. */
    suspend fun list(): CloudResult<List<CloudBackupNames.Listed>> = withContext(Dispatchers.IO) {
        try {
            val session = connection.open()
            CloudResult.Ok(CloudBackupEngine.list(session.store, session.rootUrl, settings.deviceName.first(), settings.deviceId()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Listing failed: ${e.javaClass.simpleName}")
            connection.noteFailure(e)
            CloudResult.Failed(e)
        }
    }

    /**
     * Downloads [listed], decrypts it with the passphrase of this device and checks it is a backup
     * this version understands. Nothing on the device changes; the restore itself is [restore].
     */
    suspend fun prepareRestore(listed: CloudBackupNames.Listed): CloudResult<Prepared> = withContext(Dispatchers.IO) {
        begin()
        try {
            val passphrase = settings.passphrase.first()
            if (passphrase.isBlank()) throw CloudNoPassphraseException()
            val session = connection.open()
            val text = CloudBackupEngine.download(session.store, listed.url, passphrase)
            val backup = backupService.parse(text)
            val createdAt = runCatching { backup.get("createdAt").asLong }.getOrDefault(0L).takeIf { it > 0 } ?: listed.createdAt
            val appVersion = runCatching { backup.get("appVersion").asString }.getOrDefault("?")
            CloudResult.Ok(Prepared(listed.name, backup, createdAt, appVersion))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading a backup failed: ${e.javaClass.simpleName}")
            connection.noteFailure(e)
            CloudResult.Failed(e)
        } finally {
            end()
        }
    }

    /**
     * Restores a confirmed backup like a local restore does. This device's cloud connection (server,
     * login, switches, certificate) is put back afterwards, so a backup made before the cloud was set
     * up, or on another handheld, never disconnects it; and the device sync forgets its base, so the
     * restored library is merged with the server's copy instead of being sent as "removals" to every
     * other device.
     */
    suspend fun restore(prepared: Prepared): CloudResult<BackupService.Summary> = withContext(Dispatchers.IO + NonCancellable) {
        begin()
        try {
            val cloud = settings.snapshot()
            val summary = try {
                backupService.restore(prepared.backup)
            } finally {
                runCatching { settings.restoreSnapshot(cloud) }
            }
            runCatching { deviceSync.resetBase() }
            actionLog.record(ActionKind.BACKUP_RESTORED, topic = ActionTopic.CLOUD_BACKUP)
            CloudResult.Ok(summary)
        } catch (e: Exception) {
            Log.w(TAG, "Restore failed: ${e.javaClass.simpleName}")
            CloudResult.Failed(e)
        } finally {
            end()
        }
    }

    private companion object {
        const val TAG = "CloudBackup"
    }
}
