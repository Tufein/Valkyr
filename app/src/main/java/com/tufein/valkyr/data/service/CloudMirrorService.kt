package com.tufein.valkyr.data.service

import android.util.Log
import com.tufein.valkyr.data.local.CloudConfig
import com.tufein.valkyr.data.local.CloudMirrorSettings
import com.tufein.valkyr.util.CloudBackupEngine
import com.tufein.valkyr.util.CloudErrors
import com.tufein.valkyr.util.CloudNotConfiguredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 9.5: the second cloud. Every backup that goes to the main cloud is sealed once and also written
 * here, with the same name and the same keep rule, so one server being down, wiped or full never
 * leaves you without a backup. A failing copy is recorded and shown but never fails the main
 * backup, and when the main cloud fails the copy is still made. Restores can read from here too.
 */
@Singleton
class CloudMirrorService @Inject constructor(
    private val settings: CloudMirrorSettings,
    private val connection: CloudConnection
) {
    val config = settings.config
    val records = settings.records

    /** 10.0: the small synced documents also go to this cloud. */
    val syncDocs = settings.syncDocs

    /** Tests the login: finds the working address, prepares the folder and writes a test file. */
    suspend fun test(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            open(settings.config.first())
            settings.recordTest(System.currentTimeMillis(), null)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Second cloud test failed: ${e.javaClass.simpleName}")
            settings.recordTest(System.currentTimeMillis(), CloudErrors.encode(e))
            Result.failure(e)
        }
    }

    /** A ready session for the second cloud; always tested first, so a half-typed address is never written to. */
    suspend fun open(config: CloudConfig? = null): CloudConnection.Session {
        val c = config ?: settings.config.first()
        if (!c.isConfigured) throw CloudNotConfiguredException()
        return connection.connect(c)
    }

    /** Writes [sealed] to the second cloud when one is set up; true when it was written. Never throws except on cancellation. */
    suspend fun copy(sealed: ByteArray, deviceName: String, keep: Int, at: Instant, deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.isConfigured) return@withContext false
        try {
            val session = open(c)
            val uploaded = CloudBackupEngine.upload(session.store, session.serverUrl, session.rootUrl, sealed, deviceName, keep, at, deviceId)
            settings.recordCopy(System.currentTimeMillis(), uploaded.name, uploaded.bytes)
            Log.i(TAG, "Backup copied: ${uploaded.bytes} bytes")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Backup copy failed: ${e.javaClass.simpleName}")
            settings.recordTest(System.currentTimeMillis(), CloudErrors.encode(e))
            false
        }
    }

    private companion object { const val TAG = "CloudMirror" }
}
