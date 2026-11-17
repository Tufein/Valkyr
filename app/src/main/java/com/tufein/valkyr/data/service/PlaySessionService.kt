package com.tufein.valkyr.data.service

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.tufein.valkyr.util.PlaySessions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlaySessions"

/**
 * 9.0: measures a play session from the moment a game starts until the app comes back from the
 * emulator. One small AtomicFile per profile in `files/play_sessions/`. A session still running
 * when the process is lost is ignored (not counted), so a crash loses at most the current session.
 */
@Singleton
class PlaySessionService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiles: ProfileService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val dir = File(context.filesDir, "play_sessions")
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** The session waiting for the app to return. In memory only across a process restart. */
    private var active: PlaySessions.Session? = null

    private fun file(profileId: String): AtomicFile =
        AtomicFile(File(dir, "sessions_${profileId.ifBlank { "default" }.take(40)}.json"))

    /** Must hold [lock]. */
    private fun read(profileId: String): List<PlaySessions.Session> {
        val f = file(profileId)
        if (!f.baseFile.exists()) return emptyList()
        return runCatching { PlaySessions.fromJson(f.readFully().toString(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "Play sessions unreadable: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
    }

    /** Must hold [lock]. */
    private fun write(profileId: String, sessions: List<PlaySessions.Session>) {
        val f = file(profileId)
        try {
            dir.mkdirs()
            val stream = f.startWrite()
            try { stream.write(PlaySessions.toJson(sessions).toByteArray(Charsets.UTF_8)); f.finishWrite(stream) }
            catch (e: Exception) { f.failWrite(stream); throw e }
            _revision.value++
        } catch (e: Exception) { Log.w(TAG, "Play sessions not saved: ${e.javaClass.simpleName}") }
    }

    /** Called when a game actually started. Fire-and-forget: a launch must never wait on disk. */
    fun start(consoleId: String, fileName: String?) {
        if (fileName.isNullOrBlank()) return
        scope.launch {
            lock.withLock {
                val id = profiles.currentIdNow()
                val session = PlaySessions.Session("$consoleId|$fileName", id, System.currentTimeMillis(), 0L)
                active = session
                write(id, PlaySessions.merge(read(id), listOf(session)).let(PlaySessions::trim))
            }
        }
    }

    /** Called when the app returns from the emulator: closes the open session at the time of return. */
    fun returning() {
        scope.launch {
            lock.withLock {
                val open = active ?: return@withLock
                val closed = PlaySessions.close(open, System.currentTimeMillis())
                active = null
                write(open.profileId, read(open.profileId).map { if (it == open) closed else it }.let(PlaySessions::trim))
            }
        }
    }

    /** A failed or test launch must not leave a session behind. */
    fun abandon() {
        scope.launch {
            lock.withLock {
                val open = active ?: return@withLock
                active = null
                write(open.profileId, read(open.profileId).filterNot { it == open })
            }
        }
    }

    /** Totals for one game and the active profile. */
    suspend fun totals(consoleId: String, fileName: String): PlaySessions.Totals = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = profiles.currentIdNow()
            PlaySessions.totals(read(id), "$consoleId|$fileName", id)
        }
    }

    /** Total time for the active profile, used by the recap. */
    suspend fun totalTime(): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = profiles.currentIdNow()
            PlaySessions.totalTime(read(id), id)
        }
    }

    /** All finished sessions of the active profile, for tests and tools. */
    suspend fun sessions(): List<PlaySessions.Session> = withContext(Dispatchers.IO) {
        lock.withLock { PlaySessions.finished(read(profiles.currentIdNow())) }
    }
}
