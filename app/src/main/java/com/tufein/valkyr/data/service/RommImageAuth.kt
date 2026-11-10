package com.tufein.valkyr.data.service

import com.tufein.valkyr.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException

/**
 * Signs image requests to the RomM server (covers, screenshots, avatars) with the configured
 * token, the way the rest of the app talks to RomM. Other hosts never see the token.
 */
@Singleton
class RommImageAuth @Inject constructor(settingsRepository: SettingsRepository) : Interceptor {
    @Volatile private var base: String = ""
    @Volatile private var header: String = ""
    /** The saved address even while "Use RomM" is off, so requests to it can be refused instead of sent unsigned. */
    @Volatile private var savedBase: String = ""
    @Volatile private var enabled: Boolean = true

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(settingsRepository.rommUrl, settingsRepository.rommToken) { url, token -> url.trim().trimEnd('/') to token.trim() }
                .collect { (url, token) ->
                    base = url
                    header = if (url.isEmpty() || token.isEmpty()) "" else RommClient.authHeader(token)
                }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(settingsRepository.rommEnabled, settingsRepository.rommUrlSaved) { on, url -> on to url.trim().trimEnd('/') }
                .collect { (on, url) -> enabled = on; savedBase = url }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // "Use RomM" off: no image request reaches the server (cached covers still draw from Coil's own cache).
        if (!enabled && savedBase.isNotEmpty() && sameServer(savedBase, request.url)) throw IOException("RomM is switched off")
        val b = base
        val h = header
        val signed = if (h.isNotEmpty() && b.isNotEmpty() && request.header("Authorization") == null &&
            sameServer(b, request.url)
        ) request.newBuilder().header("Authorization", h).build() else request
        return chain.proceed(signed)
    }

    /** Same scheme, host and port as the configured address, and below its path (OkHttp has normalised the request's URL). */
    private fun sameServer(base: String, url: okhttp3.HttpUrl): Boolean {
        val configured = base.toHttpUrlOrNull() ?: return false
        if (configured.scheme != url.scheme || configured.host != url.host || configured.port != url.port) return false
        val prefix = configured.encodedPath.trimEnd('/')
        return prefix.isEmpty() || url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/")
    }
}
