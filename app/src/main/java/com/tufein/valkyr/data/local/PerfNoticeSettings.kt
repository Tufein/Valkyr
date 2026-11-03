package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.tufein.valkyr.util.PerfNotices
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 6.3 "Performance notices" switch (Tools → Performance check), in the shared preferences file so backups carry it. */
@Singleton
class PerfNoticeSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val key = booleanPreferencesKey(PerfNotices.KEY)

    /** On unless the user switched it off. */
    val enabled: Flow<Boolean> = context.dataStore.data
        .map { it[key] ?: PerfNotices.DEFAULT_ON }
        .distinctUntilChanged()

    suspend fun setEnabled(on: Boolean) = context.dataStore.edit { it[key] = on }
}
