package com.tufein.valkyr.data.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.CloudSettings
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.CertTrust
import com.tufein.valkyr.util.CrashLog
import com.tufein.valkyr.util.PerfLog
import com.tufein.valkyr.util.DiagnosticsRedactor
import com.tufein.valkyr.util.WebDavPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.data.local.CoverSourceSettings
import com.tufein.valkyr.data.local.RommDeviceSettings

/**
 * A text report for a bug report: app and device versions, what is set up (never the values of
 * tokens, passwords or server addresses), counts, and the app's own recent log. Everything goes
 * through [DiagnosticsRedactor] before it is written; the report is shared through the system
 * share sheet, so the user sees it go and chooses where.
 */
@Singleton
class DiagnosticsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleDao: ConsoleDao,
    private val fileDao: DownloadableFileDao,
    private val downloadService: DownloadService,
    private val rommLibraryService: RommLibraryService,
    private val appSettings: AppSettings,
    private val cloudSettings: CloudSettings,
    private val coverSourceSettings: CoverSourceSettings,
    private val remoteInstallSettings: RommDeviceSettings
) {

    /**
     * The stored values that must never appear in a shared text: tokens, keys, passwords and the
     * server addresses and user names they belong to. Also used by "Report a problem".
     */
    suspend fun secrets(): List<String> {
        val s = settingsRepository
        val rommUrl = s.rommUrl.first()
        val rommToken = s.rommToken.first()
        return buildList {
            add(rommToken)
            add(remoteInstallSettings.pairing.first().token)
            add(CertTrust.hostOf(rommUrl))
            add(rommUrl.removePrefix("https://").removePrefix("http://").substringBefore('/'))
            add(s.torboxApiKey.first())
            add(s.realDebridApiKey.first())
            add(appSettings.raKey.first())
            // The WebDAV cloud: password, backup passphrase, user name and server address.
            val cloud = cloudSettings.config.first()
            add(cloud.password)
            add(cloudSettings.passphrase.first())
            add(cloud.user)
            add(WebDavPaths.normalizeServer(cloud.server)?.let { WebDavPaths.hostOf(it) }.orEmpty())
            add(cloud.server.trim())
            // 4.3.0: the ScreenScraper login and developer credentials.
            val ss = coverSourceSettings.credentials.first()
            add(ss.devPassword)
            add(ss.password)
            add(ss.devId)
            add(ss.user)
            // 4.4.0: the TheGamesDB / SteamGridDB keys and the Twitch client id and secret (the app's own built-in key is not the user's).
            val keys = coverSourceSettings.apiKeys.first()
            add(keys.tgdb)
            add(keys.sgdb)
            add(keys.igdbId)
            add(keys.igdbSecret)
        }.filter { it.isNotBlank() }
    }

    suspend fun buildReport(): String = withContext(Dispatchers.IO) {
        val s = settingsRepository
        val rommUrl = s.rommUrl.first()
        val rommToken = s.rommToken.first()
        val secrets = secrets()

        val downloads = downloadService.getDownloads().groupingBy { it.status.name }.eachCount()
        val consoles = consoleDao.getAllConsoles().first()
        val report = buildString {
            appendLine("Valkyr diagnostics — ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())}")
            appendLine()
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${if (BuildConfig.DEBUG) "debug" else "release"}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine()
            appendLine("Setup")
            appendLine("  download folder set: ${s.downloadDirectory.first().isNotBlank()}; per-console folders: ${s.consoleDownloadDirectories.first().size}")
            appendLine("  separate by console: ${s.separateByConsole.first()}; auto unzip: ${s.autoUnzip.first()}; concurrent: ${s.concurrentDownloads.first()}")
            appendLine("  debrid: ${s.debridProvider.first().name}")
            appendLine("  schedule: wifi=${s.downloadWifiOnly.first()} charging=${s.downloadChargingOnly.first()} night=${s.downloadNightOnly.first()}")
            appendLine("  RomM: url set=${rommUrl.isNotBlank()} (https=${CertTrust.isHttps(rommUrl)}), token set=${rommToken.isNotBlank()}, pinned certificate=${s.rommTrustFingerprint.first().isNotBlank()}, mapped platforms=${s.rommPlatformMap.first().size}, auto upload=${s.rommAutoUpload.first()}, mark games=${s.rommMarkGames.first()}")
            appendLine("  save sync: saves folder=${s.saveSyncSavesDir.first().isNotBlank()} states folder=${s.saveSyncStatesDir.first().isNotBlank()} auto=${s.saveSyncAuto.first()} background=${s.saveSyncBackground.first()} deletions=${s.saveSyncDeletions.first()}")
            appendLine("  3.0: resume=${appSettings.resumeDownloads.first()} requeue=${appSettings.requeueAfterRestart.first()} per server=${appSettings.perServerLimit.first()} m3u=${appSettings.autoM3u.first()} esde covers=${appSettings.esdeArtwork.first()} wishlist auto=${appSettings.wishlistAutoDownload.first()} RA key set=${appSettings.raKey.first().isNotBlank()} profile active=${appSettings.activeProfile.first().isNotBlank()}")
            appendLine("  WebDAV cloud: ${cloudSummary()}")
            // 4.4.0: only whether the keys are set, never their value.
            val coverKeys = coverSourceSettings.apiKeys.first()
            appendLine("  cover sources: TheGamesDB key set=${coverKeys.tgdbKey.isNotEmpty()} (app's own=${coverKeys.tgdbShared}), SteamGridDB key set=${coverKeys.sgdbKey.isNotEmpty()}, IGDB client id set=${coverKeys.igdbClientId.isNotEmpty()} secret set=${coverKeys.igdbClientSecret.isNotEmpty()}")
            appendLine("  update channel: ${if (s.updatePreReleases.first()) "pre-releases" else "releases"}")
            appendLine()
            appendLine("Library")
            appendLine("  consoles: ${consoles.size}; indexed games: ${fileDao.getFilesCount()}")
            appendLine("  downloads: ${downloads.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }}")
            val marks = rommLibraryService.state.value
            appendLine("  games known on RomM: ${marks.games}; last read: ${if (marks.updatedAt > 0) Date(marks.updatedAt) else "never"}${marks.error?.let { "; error: $it" } ?: ""}")
            appendLine()
            append(PerfLog.report(PerfLog.phases.all(), PerfLog.ops.slowest(10), PerfLog.coverCounts(), databaseBytes()))
            appendLine()
            appendLine("Recent exits")
            appendLine(recentExits())
            appendLine()
            appendLine("Last crash")
            appendLine(CrashLog.read(context) ?: "(none recorded)")
            appendLine()
            appendLine("Recent log (this app only)")
            appendLine(recentLog())
        }
        DiagnosticsRedactor.redact(report, secrets)
    }

    /** Size of the library database with its write-ahead log, in bytes; -1 when it cannot be read. */
    private fun databaseBytes(): Long = runCatching {
        val db = context.getDatabasePath("dogmatix_db")
        listOf(db, File(db.path + "-wal")).sumOf { if (it.exists()) it.length() else 0L }
    }.getOrDefault(-1L)

    /** What is set up for the WebDAV cloud, never the values themselves. */
    private suspend fun cloudSummary(): String {
        val config = cloudSettings.config.first()
        val records = cloudSettings.records.first()
        return "server set=${config.server.isNotBlank()} (https=${CertTrust.isHttps(config.server.ifBlank { "" })}), user set=${config.user.isNotBlank()}, password set=${config.password.isNotBlank()}, " +
            "passphrase set=${cloudSettings.hasPassphrase.first()}, pinned certificate=${config.trustFingerprint.isNotBlank()}, auto backup=${cloudSettings.autoBackup.first()}, device sync=${cloudSettings.deviceSync.first()}, " +
            "last backup=${if (records.lastBackupAt > 0) Date(records.lastBackupAt) else "never"}${if (records.lastBackupError.isNotBlank()) " (failed: ${records.lastBackupError})" else ""}, " +
            "last test=${if (records.lastTestAt > 0) (if (records.lastTestError.isBlank()) "ok" else records.lastTestError) else "never"}, " +
            "last sync=${if (records.lastSyncAt > 0) Date(records.lastSyncAt) else "never"}${if (records.lastSyncError.isNotBlank()) " (failed: ${records.lastSyncError})" else ""}"
    }

    /**
     * Why the app's process ended the last few times (Android 11+): a crash, an "app not responding",
     * the system freeing memory, the user swiping it away… An ANR comes with the main thread's stack.
     */
    private fun recentExits(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "(Android 11 or newer only)"
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 6)
            if (exits.isEmpty()) return "(none)"
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            buildString {
                exits.forEach { e ->
                    appendLine("  ${format.format(Date(e.timestamp))} ${exitReason(e.reason)} importance=${e.importance} ${e.description.orEmpty()}")
                    if (e.reason == ApplicationExitInfo.REASON_ANR) {
                        val trace = runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                        trace?.let { t ->
                            val main = t.substringAfter("\"main\"", "").lineSequence().take(25).joinToString("\n")
                            if (main.isNotBlank()) appendLine("    main thread:\n" + main.prependIndent("    "))
                        }
                    }
                }
            }.trimEnd()
        }.getOrElse { "(not available: ${it.message})" }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason $reason"
    }

    /** The app's own recent log lines; an app may read its own process only. */
    private fun recentLog(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-t", "400", "--pid=${Process.myPid()}", "-v", "time").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.ifBlank { "(empty)" }
    }.getOrElse { "(not available: ${it.message})" }

    /**
     * 4.7.0: the same share path for another text the app builds itself (the Performance check's
     * numbers): written to the cache `exports` folder after the same redaction, then the share sheet.
     * [fileName] is a fixed name chosen in code.
     */
    suspend fun shareText(fileName: String, text: String, chooserTitle: String) {
        val secrets = secrets()
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            File(dir, fileName).apply { writeText(DiagnosticsRedactor.redact(text, secrets)) }
        }
        send(file, chooserTitle)
    }

    /** Writes the report and opens the share sheet. */
    suspend fun share(chooserTitle: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            File(dir, "valkyr-diagnostics.txt").apply { writeText(buildReport()) }
        }
        send(file, chooserTitle)
    }

    private fun send(file: File, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
