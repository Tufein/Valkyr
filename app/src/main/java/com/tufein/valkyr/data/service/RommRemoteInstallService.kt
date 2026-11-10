package com.tufein.valkyr.data.service

import android.os.Build
import android.util.Log
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.data.local.RommDeviceSettings
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.RommRemoteInstall
import com.tufein.valkyr.util.RommSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLEncoder

private const val TAG = "RommRemoteInstall"

/**
 * Remote install (5.6.0): pairs this handheld with the RomM server as a device and turns the "Install on device"
 * requests of RomM's web interface into normal downloads. What RomM offers, and what is checked against its source
 * code rather than a running server, is in [RommRemoteInstall].
 *
 * One poll ([poll]) claims the open requests, queues a download for each one (through the library row the
 * game already has from its `romm://` source) and tells the server it was added to the downloads (or that the game
 * is on the device already, or cannot be found in the library). It runs while the app is open ([runWhileOpen], once a minute)
 * and from [RommRemoteInstallScheduler]'s job (every 15 minutes, not on a low battery). Nothing is sent while
 * "Use RomM" is off or the switch of this feature is off.
 */
@Singleton
class RommRemoteInstallService @Inject constructor(
    private val rommClient: RommClient,
    private val settings: RommDeviceSettings,
    private val appSettings: SettingsRepository,
    private val downloadService: DownloadService,
    private val fileDao: DownloadableFileDao,
    private val libraryIndex: LibraryIndexService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollLock = Mutex()
    private var pairingJob: Job? = null
    @Volatile private var lastPollAt = 0L
    /** Consecutive 401/403 answers that were not RomM's own error; only touched under [pollLock]. */
    private var authFailures = 0

    // ---- pairing ---------------------------------------------------------------------------------

    sealed interface PairState {
        data object Idle : PairState
        /** The user approves [userCode] at [url] (RomM's web interface) before [expiresAt]. */
        data class Waiting(val userCode: String, val url: String?, val expiresAt: Long) : PairState
        data object Done : PairState
        data class Ended(val problem: Problem) : PairState
    }

    enum class Problem { NOT_SET_UP, UNSUPPORTED, DENIED, EXPIRED, SCOPES, CAPABILITY, OFFLINE }

    private val _pairState = MutableStateFlow<PairState>(PairState.Idle)
    val pairState: StateFlow<PairState> = _pairState.asStateFlow()

    /** What the last poll did, for the settings row. */
    data class Summary(val at: Long, val queued: Int, val reported: Int, val problem: PollProblem? = null)

    enum class PollProblem { OFFLINE, REJECTED, DISABLED, SWITCHED_OFF }

    private val _summary = MutableStateFlow<Summary?>(null)
    val summary: StateFlow<Summary?> = _summary.asStateFlow()

    fun cancelPairing() {
        pairingJob?.cancel()
        _pairState.value = PairState.Idle
    }

    fun startPairing() {
        if (pairingJob?.isActive == true) return
        pairingJob = scope.launch {
            try {
                _pairState.value = pair()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pairing could not finish: ${e.javaClass.simpleName}")
                _pairState.value = PairState.Ended(Problem.OFFLINE)
            }
        }
    }

    private suspend fun pair(): PairState {
        val base = try { rommClient.serverBase() } catch (e: RommException) { return PairState.Ended(Problem.NOT_SET_UP) }
        val support = RommRemoteInstall.support(withContext(Dispatchers.IO) { runCatching { rommClient.heartbeat() }.getOrNull() })
        if (support == RommRemoteInstall.Support.NO) return PairState.Ended(Problem.UNSUPPORTED)
        val name = (Build.MODEL ?: "").ifBlank { "Valkyr" }
        val init = withContext(Dispatchers.IO) {
            val body = RommRemoteInstall.initBody(settings.identifier(), name, BuildConfig.VERSION_NAME).toByteArray(Charsets.UTF_8)
            val r = JsonHttp.request("POST", "$base/api/auth/device/init", emptyMap(), body = body, contentType = JsonHttp.JSON, followRedirects = false)
            RommRemoteInstall.parseInit(r.code, r.json)
        } ?: return PairState.Ended(if (support == RommRemoteInstall.Support.UNKNOWN) Problem.UNSUPPORTED else Problem.OFFLINE)
        val deadline = System.currentTimeMillis() + init.expiresInSeconds * 1000L
        _pairState.value = PairState.Waiting(init.userCode, RommRemoteInstall.verificationUrl(base, init.verificationPath), deadline)
        var interval = init.intervalSeconds
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val poll = withContext(Dispatchers.IO) {
                val body = com.google.gson.JsonObject().apply { addProperty("device_code", init.deviceCode) }.toString().toByteArray(Charsets.UTF_8)
                val r = runCatching { JsonHttp.request("POST", "$base/api/auth/device/token", emptyMap(), body = body, contentType = JsonHttp.JSON, followRedirects = false) }.getOrNull()
                    ?: return@withContext RommRemoteInstall.PairPoll.Waiting(false)
                RommRemoteInstall.parsePoll(r.code, r.json)
            }
            when (poll) {
                is RommRemoteInstall.PairPoll.Waiting -> interval = RommRemoteInstall.nextInterval(interval, poll.slowDown)
                RommRemoteInstall.PairPoll.Denied -> return PairState.Ended(Problem.DENIED)
                RommRemoteInstall.PairPoll.Expired -> return PairState.Ended(Problem.EXPIRED)
                is RommRemoteInstall.PairPoll.Failed -> return PairState.Ended(Problem.OFFLINE)
                is RommRemoteInstall.PairPoll.Approved -> {
                    if (!RommRemoteInstall.scopesSufficient(poll.scopes)) return PairState.Ended(Problem.SCOPES)
                    // The device must say it accepts installs, or the web interface does not offer it.
                    val flagged = withContext(Dispatchers.IO) {
                        runCatching {
                            JsonHttp.request(
                                "PUT", "$base/api/devices/${poll.deviceId}", bearer(poll.token),
                                body = RommRemoteInstall.capabilitiesBody().toByteArray(Charsets.UTF_8), contentType = JsonHttp.JSON, followRedirects = false
                            ).ok
                        }.getOrDefault(false)
                    }
                    if (!flagged) return PairState.Ended(Problem.CAPABILITY)
                    settings.savePairing(poll.token, poll.deviceId)
                    settings.setEnabled(true)
                    _summary.value = null
                    return PairState.Done
                }
            }
        }
        return PairState.Ended(Problem.EXPIRED)
    }

    /** Forgets the pairing on this handheld. The device stays listed in RomM until it is removed there. */
    suspend fun unpair() {
        cancelPairing()
        // Waits for a running poll, so it cannot queue downloads after the pairing is gone.
        pollLock.withLock {
            settings.clearPairing()
            authFailures = 0
            _summary.value = null
        }
    }

    // ---- polling ---------------------------------------------------------------------------------

    /** While the app is visible: a poll when due, checked every 15 seconds. Cancel the caller to stop. */
    suspend fun runWhileOpen() {
        while (true) {
            val now = System.currentTimeMillis()
            val pairing = settings.pairing.first()
            if (RommRemoteInstall.foregroundDue(settings.enabled.first(), pairing.paired, lastPollAt, now)) poll()
            delay(15_000)
        }
    }

    /** One poll. Never throws; the outcome is in [summary]. */
    suspend fun poll(): Summary = pollLock.withLock {
        val now = System.currentTimeMillis()
        lastPollAt = now
        val out = try {
            pollNow(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RommSwitchedOffException) {
            Summary(now, 0, 0, PollProblem.SWITCHED_OFF)
        } catch (e: Exception) {
            Log.w(TAG, "Poll failed: ${e.javaClass.simpleName}")
            Summary(now, 0, 0, PollProblem.OFFLINE)
        }
        _summary.value = out
        out
    }

    private suspend fun pollNow(now: Long): Summary = withContext(Dispatchers.IO) {
        val pairing = settings.pairing.first()
        if (!settings.enabled.first() || !pairing.paired) return@withContext Summary(now, 0, 0)
        val base = rommClient.serverBase() // throws while "Use RomM" is off
        val headers = bearer(pairing.token)
        val claim = JsonHttp.request("POST", "$base/api/devices/${pairing.deviceId}/installs/claim", headers, body = ByteArray(0), contentType = JsonHttp.JSON, followRedirects = false)
        if (!claim.ok) {
            val failure = RommRemoteInstall.claimFailure(claim.code, claim.body)
            if (failure == RommRemoteInstall.ClaimFailure.AUTH_UNCERTAIN) authFailures++ else authFailures = 0
            return@withContext when (failure) {
                RommRemoteInstall.ClaimFailure.PAIRING_LOST -> { settings.clearPairing(); Summary(now, 0, 0, PollProblem.REJECTED) }
                RommRemoteInstall.ClaimFailure.AUTH_UNCERTAIN ->
                    if (authFailures >= RommRemoteInstall.AUTH_FAILURES_BEFORE_LOST) { authFailures = 0; settings.clearPairing(); Summary(now, 0, 0, PollProblem.REJECTED) }
                    else Summary(now, 0, 0, PollProblem.OFFLINE)
                RommRemoteInstall.ClaimFailure.DISABLED_ON_SERVER -> Summary(now, 0, 0, PollProblem.DISABLED)
                RommRemoteInstall.ClaimFailure.TEMPORARY -> Summary(now, 0, 0, PollProblem.OFFLINE)
            }
        }
        authFailures = 0
        val claimed = RommRemoteInstall.parseClaim(claim.json)
        var queued = 0
        var reported = 0
        if (claimed.isNotEmpty()) {
            val rowsByRom = romRows(base)
            for (request in claimed) {
                val entities = rowsByRom[request.romId].orEmpty()
                val rows = entities.map { RommRemoteInstall.Row(it.id, it.fileName, owned = runCatching { libraryIndex.isOwned(it) }.getOrDefault(false)) }
                val report = when (val decision = RommRemoteInstall.decide(rows)) {
                    is RommRemoteInstall.Decision.Start -> {
                        // Unpaired meanwhile: queue nothing more.
                        if (settings.pairing.first().token != pairing.token) return@withContext Summary(now, queued, reported)
                        // A file already queued or running is skipped by the queue itself, so a request that is
                        // answered again after a failed report does not download twice.
                        downloadService.startDownload(entities.first { it.id == decision.row.key })
                        queued++
                        RommRemoteInstall.Report(RommRemoteInstall.Report.Kind.DONE)
                    }
                    RommRemoteInstall.Decision.Owned -> RommRemoteInstall.Report(RommRemoteInstall.Report.Kind.ALREADY_INSTALLED)
                    is RommRemoteInstall.Decision.Fail -> RommRemoteInstall.Report(RommRemoteInstall.Report.Kind.FAILED, decision.reason)
                }
                if (sendReport(base, pairing, request.id, report)) reported++
            }
        }
        Summary(now, queued, reported)
    }

    /** The library rows served by the configured RomM server, by RomM game id. */
    private suspend fun romRows(base: String): Map<Int, List<DownloadableFileEntity>> =
        fileDao.rommRows().filter { RommSource.isDownloadFrom(base, it.downloadUrl) }
            .mapNotNull { row -> RommSource.romIdOf(row.downloadUrl)?.let { it to row } }
            .groupBy({ it.first }, { it.second })

    /** True when the server took the report, or no longer has the request (it ended or was cancelled in RomM). */
    private fun sendReport(base: String, pairing: RommDeviceSettings.Pairing, requestId: String, report: RommRemoteInstall.Report): Boolean {
        val url = "$base/api/devices/${pairing.deviceId}/installs/" + URLEncoder.encode(requestId, "UTF-8")
        val r = runCatching {
            JsonHttp.request("PUT", url, bearer(pairing.token), body = RommRemoteInstall.reportBody(report.kind, report.reason).toByteArray(Charsets.UTF_8), contentType = JsonHttp.JSON, followRedirects = false)
        }.getOrNull() ?: return false
        return r.ok || r.code == 404 || r.code == 409
    }

    private fun bearer(token: String): Map<String, String> = mapOf("Authorization" to RommClient.authHeader(token))
}
