package com.tufein.valkyr.data.service

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.tufein.valkyr.data.local.PerfNoticeSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.PerfNotices
import com.tufein.valkyr.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 6.3: reads the device's state (thermal status, memory pressure, room left in the download folder)
 * and says which performance notices are due, at most once per kind per half hour ([PerfNotices]).
 * The window calls [check] about once a minute while the app is open; nothing runs in the background.
 * The notices shown are kept in memory for the list in Tools → Performance check. Not tested on a device.
 */
@Singleton
class PerfNoticeService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val noticeSettings: PerfNoticeSettings,
    private val settings: SettingsRepository
) {
    private val tracker = PerfNotices.Tracker()
    private val lock = Mutex()

    private val _history = MutableStateFlow<List<PerfNotices.Notice>>(emptyList())
    /** Notices shown since the app started, newest first, as wall-clock times. */
    val history: StateFlow<List<PerfNotices.Notice>> = _history.asStateFlow()

    /** The kinds to tell the user about now; empty while the switch is off. Never throws. */
    suspend fun check(): List<PerfNotices.Kind> {
        if (!runCatching { noticeSettings.enabled.first() }.getOrDefault(PerfNotices.DEFAULT_ON)) return emptyList()
        val reading = runCatching { read() }.getOrNull() ?: return emptyList()
        return lock.withLock {
            val due = tracker.due(reading, SystemClock.elapsedRealtime())
            if (due.isNotEmpty()) {
                val wall = System.currentTimeMillis()
                _history.value = due.map { PerfNotices.Notice(it, wall) } + _history.value.take(PerfNotices.CAPACITY - due.size)
            }
            due
        }
    }

    private suspend fun read(): PerfNotices.Reading = withContext(Dispatchers.IO) {
        val thermal = runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0 }.getOrDefault(0)
        val low = runCatching {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
            info.lowMemory
        }.getOrDefault(false)
        val folder = runCatching { settings.downloadDirectory.first() }.getOrDefault("")
        val free = if (folder.isBlank()) null else runCatching { StorageHelper.getFreeBytes(context, folder) }.getOrNull()
        PerfNotices.Reading(thermal, low, free)
    }
}
