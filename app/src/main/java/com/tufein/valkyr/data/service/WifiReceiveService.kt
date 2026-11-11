package com.tufein.valkyr.data.service

import android.content.Context
import android.util.Log
import com.tufein.valkyr.data.repository.ConsoleRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.WifiReceive
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the Tools screen shows about the receiver. */
data class WifiReceiveState(
    val running: Boolean = false,
    /** `http://192.168.1.20:8765`, null when stopped. */
    val address: String? = null,
    val pin: String? = null,
    /** Names of the games received in this run, newest last. */
    val received: List<String> = emptyList(),
    val problem: Problem? = null,
    /** Epoch millis the receiver switches itself off at the latest. */
    val stopsAt: Long = 0L
) {
    enum class Problem { NO_WIFI, NO_FOLDER, PIN_LOCKED, TIMEOUT, FAILED }
}

/**
 * 6.6: *Receive games over Wi-Fi*. A small HTTP receiver, switched on by hand, that takes game files
 * from a browser on the same network into the console folders (the folders downloads use), behind a
 * PIN. It listens only on the local network address, answers only local clients, takes one upload at a
 * time, never replaces a file, checks every file by reading it back, and stops by itself after
 * [WifiReceive.IDLE_MINUTES] idle minutes, [WifiReceive.TOTAL_MINUTES] in all, or [WifiReceive.MAX_WRONG_PINS]
 * wrong PINs. Games go only to a console of the library; the app includes no games.
 */
@Singleton
class WifiReceiveService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val consoles: ConsoleRepository,
    private val pathResolver: ConsoleDownloadPathResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WifiReceiveState())
    val state: StateFlow<WifiReceiveState> = _state.asStateFlow()

    private var server: ServerSocket? = null
    private var job: Job? = null
    private val uploading = Mutex()
    @Volatile private var pin = ""
    @Volatile private var wrongPins = 0
    @Volatile private var lastActivity = 0L

    fun start() {
        if (_state.value.running) return
        scope.launch { startReceiver() }
    }

    private suspend fun startReceiver() {
        if (_state.value.running) return
        val address = localAddress() ?: return fail(WifiReceiveState.Problem.NO_WIFI)
        if (settings.downloadDirectory.first().isBlank()) return fail(WifiReceiveState.Problem.NO_FOLDER)
        val socket = try {
            ServerSocket(PORT, 8, address)
        } catch (_: IOException) {
            try { ServerSocket(0, 8, address) } catch (_: IOException) { return fail(WifiReceiveState.Problem.FAILED) }
        }
        server = socket
        pin = WifiReceive.newPin()
        wrongPins = 0
        val now = System.currentTimeMillis()
        lastActivity = now
        _state.value = WifiReceiveState(
            running = true, address = "http://${address.hostAddress}:${socket.localPort}", pin = pin,
            stopsAt = now + WifiReceive.TOTAL_MINUTES * 60_000L
        )
        job = scope.launch { acceptLoop(socket, now + WifiReceive.TOTAL_MINUTES * 60_000L) }
    }

    fun stop() = finish(null)

    private fun fail(problem: WifiReceiveState.Problem) { _state.value = WifiReceiveState(problem = problem) }

    private fun finish(problem: WifiReceiveState.Problem?) {
        runCatching { server?.close() }
        server = null
        job?.cancel()
        job = null
        pin = ""
        _state.update { WifiReceiveState(received = it.received, problem = problem) }
    }

    /** The IPv4 address of the local network (Wi-Fi first); null when the device is on no local network. */
    private fun localAddress(): InetAddress? = runCatching {
        val all = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val candidates = all.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.filter { it.address.size == 4 && it.isSiteLocalAddress }
        candidates.firstOrNull()
    }.getOrNull()

    private suspend fun acceptLoop(socket: ServerSocket, deadline: Long) {
        socket.soTimeout = 1_000
        while (scope.isActive && !socket.isClosed) {
            val now = System.currentTimeMillis()
            if (now >= deadline || now - lastActivity > WifiReceive.IDLE_MINUTES * 60_000L) return finish(WifiReceiveState.Problem.TIMEOUT)
            val client = try { socket.accept() } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { return }
            scope.launch { client.use { handle(it) } }
        }
    }

    private suspend fun handle(socket: Socket) {
        try {
            if (!WifiReceive.isLocalClient(socket.inetAddress)) return
            socket.soTimeout = 60_000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val request = WifiReceive.parseHead(readHead(input) ?: return) ?: return out.write(WifiReceive.json(400, "{\"error\":\"bad request\"}"))
            lastActivity = System.currentTimeMillis()
            if (request.method == "GET" && request.path == "/") {
                out.write(WifiReceive.page(
                    context.getString(com.tufein.valkyr.R.string.wifi66_title), context.getString(com.tufein.valkyr.R.string.wifi66_page_pin),
                    context.getString(com.tufein.valkyr.R.string.wifi66_page_console), context.getString(com.tufein.valkyr.R.string.wifi66_page_choose),
                    context.getString(com.tufein.valkyr.R.string.wifi66_page_send), context.getString(com.tufein.valkyr.R.string.wifi66_page_done),
                    context.getString(com.tufein.valkyr.R.string.wifi66_page_wrong)
                ))
                return
            }
            if (!request.path.startsWith("/api/")) return out.write(WifiReceive.json(404, "{\"error\":\"not found\"}"))
            if (!WifiReceive.pinMatches(pin, WifiReceive.pinOf(request))) {
                out.write(WifiReceive.json(401, "{\"error\":\"wrong pin\"}"))
                if (++wrongPins >= WifiReceive.MAX_WRONG_PINS) finish(WifiReceiveState.Problem.PIN_LOCKED)
                return
            }
            when {
                request.method == "GET" && request.path == "/api/consoles" -> out.write(WifiReceive.json(200, consoleList()))
                request.method == "PUT" && request.path == "/api/upload" -> out.write(upload(request, input))
                else -> out.write(WifiReceive.json(405, "{\"error\":\"not allowed\"}"))
            }
            out.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: ${e.javaClass.simpleName}")
        }
    }

    /** The head of a request (up to the empty line) as text; null when it is too long or the connection ends early. */
    private fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        while (buffer.length <= WifiReceive.MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buffer.append(b.toChar())
            if (buffer.length >= 4 && buffer.endsWith("\r\n\r\n")) return buffer.substring(0, buffer.length - 4)
        }
        return null
    }

    private suspend fun consoleList(): String {
        val list = consoles.getAllConsoles().first().map { it.id }.sortedBy { ConsoleFormatter.getConsoleDisplayName(it).lowercase() }
        return list.joinToString(",", "[", "]") { "{\"id\":${WifiReceive.jsonString(it)},\"label\":${WifiReceive.jsonString(ConsoleFormatter.getConsoleDisplayName(it))}}" }
    }

    private suspend fun upload(request: WifiReceive.Request, input: InputStream): ByteArray = uploading.withLock {
        val length = request.headers["content-length"]?.toLongOrNull() ?: return WifiReceive.json(411, "{\"error\":\"length required\"}")
        if (length < 0 || length > WifiReceive.MAX_FILE_BYTES) return WifiReceive.json(413, "{\"error\":\"too large\"}")
        val name = WifiReceive.safeName(request.query["name"]) ?: return WifiReceive.json(400, "{\"error\":\"bad name\"}")
        val consoleId = request.query["console"].orEmpty()
        if (consoles.getConsoleById(consoleId) == null) return WifiReceive.json(404, "{\"error\":\"unknown console\"}")
        val tree = settings.consoleDownloadDirectories.first()[consoleId]?.takeIf { it.isNotBlank() } ?: settings.downloadDirectory.first()
        if (tree.isBlank()) return WifiReceive.json(409, "{\"error\":\"no download folder\"}")
        val dir = StorageHelper.createDirectory(context, tree, pathResolver.resolve(settings, consoleId).subPath)
            ?: return WifiReceive.json(507, "{\"error\":\"folder not available\"}")
        if (dir.findFile(name) != null) return WifiReceive.json(409, "{\"error\":\"exists\"}")
        val tmpName = ".dogmatix-upload-${UUID.randomUUID()}.part"
        val staged = dir.createFile("application/octet-stream", tmpName)?.takeIf { it.name == tmpName } ?: return WifiReceive.json(507, "{\"error\":\"cannot write\"}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openOutputStream(staged.uri, "w")?.use { target ->
                val buffer = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("Connection ended early")
                    target.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    left -= n
                    lastActivity = System.currentTimeMillis()
                }
            } ?: throw IOException("Cannot open the file")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            StorageHelper.publishStagedFile(context, dir, staged, name, sha)
            _state.update { it.copy(received = it.received + name) }
            WifiReceive.json(201, "{\"ok\":true}")
        } catch (e: CancellationException) {
            runCatching { staged.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { staged.delete() }
            Log.w(TAG, "Upload failed: ${e.javaClass.simpleName}")
            WifiReceive.json(500, "{\"error\":\"failed\"}")
        }
    }

    private companion object {
        const val TAG = "WifiReceive"
        const val PORT = 8765
    }
}
