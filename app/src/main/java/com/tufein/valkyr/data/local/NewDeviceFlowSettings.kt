package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tufein.valkyr.util.NewDeviceFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 9.0: the checklist's own marks on this handheld. Its own DataStore so a backup restored from
 * another device never claims this one is set up, and a restore never wipes it mid-flow.
 */
private val Context.newDeviceFlowStore by preferencesDataStore(name = "new_device_flow")

@Singleton
class NewDeviceFlowSettings @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val marksKey = stringSetPreferencesKey(KEY_MARKS)

    val marks: Flow<Set<NewDeviceFlow.Kind>> = context.newDeviceFlowStore.data
        .map { prefs -> (prefs[marksKey] ?: emptySet()).mapNotNull { runCatching { NewDeviceFlow.Kind.valueOf(it) }.getOrNull() }.toSet() }
        .distinctUntilChanged()

    suspend fun setDone(kind: NewDeviceFlow.Kind, done: Boolean) {
        context.newDeviceFlowStore.edit { prefs ->
            val current = (prefs[marksKey] ?: emptySet()).toMutableSet()
            if (done) current += kind.name else current -= kind.name
            prefs[marksKey] = current
        }
    }

    suspend fun clear() { context.newDeviceFlowStore.edit { it.remove(marksKey) } }

    companion object {
        const val KEY_MARKS = "new_device_flow_marks"
    }
}
