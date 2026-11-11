package com.tufein.valkyr.data.service

import android.app.job.JobInfo
import kotlinx.coroutines.CancellationException
import com.tufein.valkyr.R
import com.tufein.valkyr.MainActivity
import com.tufein.valkyr.DogmatixApplication
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.CloudSettings
import com.tufein.valkyr.util.BackupRotation
import com.tufein.valkyr.util.DiskScanner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

private const val JOB_ID = 4231
/** The WebDAV cloud job: encrypted backup and device sync, on an unmetered network while charging. */
private const val CLOUD_JOB_ID = 4232
private const val INTERVAL_DAYS = 7
private const val FAILED_NOTIFICATION_ID = 4233

/**
 * Weekly backup into a folder the user picked (Settings → Automatic backup): the same file as
 * *Back up*, named by date; the newest five are kept. A daily job (charging, any network not
 * needed) checks whether a week has passed.
 *
 * 5.0: it also owns the daily WebDAV cloud job (job 4232, Wi-Fi + charging), which sends an
 * encrypted backup when automatic cloud backup is on and syncs the library with the other devices
 * when device sync is on, and (6.0) syncs the shared family wishlist when it is on. All run in [AutoBackupJobService]; the job id tells them apart. It also
 * keeps [DeviceSyncService] alive from app start (its triggers live there).
 */
@Singleton
class AutoBackupScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val backupService: BackupService,
    private val cloudSettings: CloudSettings,
    private val cloudBackup: CloudBackupService,
    private val deviceSync: DeviceSyncService,
    private val sharedWishlist: SharedWishlistService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            combine(appSettings.autoBackup, appSettings.autoBackupDir) { on, dir -> on && dir.isNotBlank() }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
        scope.launch {
            combine(cloudSettings.configured, cloudSettings.autoBackup, cloudSettings.deviceSync, cloudSettings.sharedActive) { set, backup, sync, shared -> set && (backup || sync || shared) }
                .distinctUntilChanged()
                .collect { applyCloud(it) }
        }
    }

    private fun applyCloud(on: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!on) { scheduler.cancel(CLOUD_JOB_ID); return }
        // Already scheduled: leave it, so starting the app does not push the next run back every time.
        if (scheduler.getPendingJob(CLOUD_JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(CLOUD_JOB_ID, ComponentName(context, AutoBackupJobService::class.java))
                .setPeriodic(24L * 3_600_000)
                .setRequiresCharging(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build()
        )
    }

    /** The daily cloud run: an encrypted backup when one is due, then the device sync (both only when switched on). */
    suspend fun runCloud() {
        cloudBackup.runIfDue()
        deviceSync.syncScheduled()
        sharedWishlist.syncScheduled()
    }

    private fun apply(on: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!on) { scheduler.cancel(JOB_ID); return }
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, AutoBackupJobService::class.java))
                .setPeriodic(24L * 3_600_000)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build()
        )
    }

    /** Writes a backup now when one is due ([force]: regardless); returns the file name, or null. */
    suspend fun runIfDue(force: Boolean = false): String? {
        val dirUri = appSettings.autoBackupDir.first().takeIf { it.isNotBlank() } ?: return null
        if (!force && !BackupRotation.isDue(appSettings.autoBackupLast.first(), System.currentTimeMillis(), INTERVAL_DAYS)) return null
        val name = try {
            writeBackup(dirUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AutoBackup", "Backup failed: ${e.message}")
            null
        }
        // 6.6: a backup that was due and could not be written is said out loud, once per daily run.
        if (name == null && !force) notifyFailed()
        return name
    }

    private fun notifyFailed() {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, DogmatixApplication.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(context.getString(R.string.backup66_failed_title))
            .setContentText(context.getString(R.string.backup66_failed_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.backup66_failed_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(FAILED_NOTIFICATION_ID, notification) }
    }

    private suspend fun writeBackup(dirUri: String): String? = withContext(Dispatchers.IO) {
        val dir = DiskScanner.rootOf(dirUri) ?: return@withContext null
        val (json, _) = backupService.export()
        val name = BackupRotation.fileName(LocalDate.now())
        val resolver = context.contentResolver
        // One backup per day: today's is replaced.
        DiskScanner.list(context, dir).filter { it.name == name }.forEach { DiskScanner.delete(context, it.uri) }
        val doc = DocumentsContract.createDocument(resolver, DiskScanner.uriOf(dir), "application/octet-stream", name) ?: return@withContext null
        resolver.openOutputStream(doc)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: return@withContext null
        val entries = DiskScanner.list(context, dir)
        BackupRotation.toDelete(entries.map { it.name }).forEach { old -> entries.firstOrNull { it.name == old }?.let { DiskScanner.delete(context, it.uri) } }
        appSettings.setAutoBackupLast(System.currentTimeMillis())
        Log.i("AutoBackup", "Backup written: $name")
        name
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoBackupEntryPoint {
    fun autoBackup(): AutoBackupScheduler
}

class AutoBackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One job per scheduled job id: the local and the cloud backup can run at the same time. */
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val backup = EntryPointAccessors.fromApplication(applicationContext, AutoBackupEntryPoint::class.java).autoBackup()
        val cloud = params.jobId == CLOUD_JOB_ID
        running[params.jobId] = scope.launch {
            if (cloud) runCatching { backup.runCloud() }.onFailure { Log.w("AutoBackup", "Cloud run failed: ${it.javaClass.simpleName}") }
            else runCatching { backup.runIfDue() }.onFailure { Log.w("AutoBackup", "Backup failed: ${it.message}") }
            running.remove(params.jobId)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { running.remove(params.jobId)?.cancel(); return true }
}
