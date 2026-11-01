package com.tufein.valkyr.data.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.tufein.valkyr.util.OfflineMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the device has a working internet connection (6.0), for the Offline chip and for actions
 * that wait for a connection. [DownloadGate] watches the same default network, but only for "unmetered".
 * Based on the platform's connectivity callbacks (public Android API); not verified on a handheld here.
 */
@Singleton
class NetworkMonitor @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val _online = MutableStateFlow(true)
    /** True while the default network reaches the internet (assumed true until Android says otherwise). */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager != null) {
            runCatching {
                manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        _online.value = OfflineMode.isOnline(
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        )
                    }
                    override fun onLost(network: Network) { _online.value = false }
                })
            }
            runCatching {
                val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                _online.value = caps != null && OfflineMode.isOnline(
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
            }
        }
    }
}
