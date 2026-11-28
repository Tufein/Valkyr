package com.tufein.valkyr.data.service

import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.util.LibraryRestrictions
import com.tufein.valkyr.util.Profile
import com.tufein.valkyr.util.ProfileSync
import com.tufein.valkyr.util.Profiles
import com.tufein.valkyr.util.SyncProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.data.local.dao.FavouriteDao
import com.tufein.valkyr.data.local.entity.PersonalProfileEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The profiles and which one is active. [restrictions] is what the library query leaves out;
 * switching away from a restricted profile asks for the PIN when one is set.
 */
@Singleton
class ProfileService @Inject constructor(private val settings: AppSettings, private val favourites: FavouriteDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val switchLock = Mutex()
    init { scope.launch { settings.activeProfile.collect { switchLock.withLock { favourites.setActive(PersonalProfileEntity(activeId = settings.activeProfile.first())) } } } }

    val profiles: StateFlow<List<Profile>> = settings.profiles.map { Profiles.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeId: StateFlow<String> = settings.activeProfile.stateIn(scope, SharingStarted.Eagerly, "")

    private val loaded: StateFlow<LibraryRestrictions?> = combine(settings.profiles, settings.activeProfile) { json, id -> Profiles.restrictionsOf(Profiles.fromJson(json), id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** What the active profile hides; [LibraryRestrictions.NONE] until the settings are read (see [current]). */
    val restrictions: StateFlow<LibraryRestrictions> = loaded.map { it ?: LibraryRestrictions.NONE }
        .stateIn(scope, SharingStarted.Eagerly, LibraryRestrictions.NONE)

    /**
     * The restrictions once the stored settings are read — the library waits for this, so the
     * first screen after starting the app never shows what a profile hides (no blocking read at startup).
     */
    suspend fun current(): LibraryRestrictions = combine(settings.profiles, settings.activeProfile) { json, id ->
        Profiles.restrictionsOf(Profiles.fromJson(json), id)
    }.first()

    fun currentIdNow(): String = settings.activeProfileSnapshot ?: activeId.value

    /** 9.4: the active profile's name, which names its shared documents. */
    fun currentNameNow(): String = currentIdNow().let { id -> profiles.value.firstOrNull { it.id == id }?.name.orEmpty() }

    val pinHash: StateFlow<String> = settings.profilePinHash.stateIn(scope, SharingStarted.Eagerly, "")

    suspend fun save(profile: Profile) {
        val others = Profiles.fromJson(settings.profiles.first()).filterNot { it.id == profile.id }
        settings.setProfiles(Profiles.toJson(others + profile))
    }

    /** 10.0: the profiles as stored, for the sync. */
    suspend fun listNow(): List<Profile> = Profiles.fromJson(settings.profiles.first())

    /** 10.0: takes synced profiles in (changed hiding, new profiles); ids, PINs and names stay. True when the list changed. */
    suspend fun applySynced(merged: Map<String, SyncProfile>): Boolean = switchLock.withLock {
        val local = Profiles.fromJson(settings.profiles.first())
        val next = ProfileSync.apply(local, merged) { java.util.UUID.randomUUID().toString() }
        if (next == local) false else { settings.setProfiles(Profiles.toJson(next)); true }
    }

    suspend fun delete(id: String) {
        switchLock.withLock {
            settings.setProfiles(Profiles.toJson(Profiles.fromJson(settings.profiles.first()).filterNot { it.id == id }))
            if (settings.activeProfile.first() == id) {
                favourites.setActive(PersonalProfileEntity(activeId = ""))
                settings.setActiveProfile("")
            }
            favourites.deleteProfile(id)
        }
    }

    /** Switches to [id] ("" = everything). Leaving a restricted profile needs [pin] when a PIN is set; false when it is wrong. */
    suspend fun switchTo(id: String, pin: String = ""): Boolean {
        return switchLock.withLock {
            val stored = settings.activeProfile.first()
            val all = Profiles.fromJson(settings.profiles.first())
            if (id.isNotEmpty() && all.none { it.id == id }) return@withLock false
            val leavingRestricted = Profiles.restrictionsOf(all, stored).active && id != stored
            if (leavingRestricted && !Profiles.pinMatches(pin, settings.profilePinHash.first())) return@withLock false
            favourites.setActive(PersonalProfileEntity(activeId = id))
            try { settings.setActiveProfile(id) }
            catch (e: Exception) { favourites.setActive(PersonalProfileEntity(activeId = stored)); throw e }
            true
        }
    }

    /** Sets the PIN (empty removes it). Only allowed while no restricted profile is active. */
    suspend fun setPin(pin: String): Boolean {
        if (current().active) return false
        settings.setProfilePinHash(if (pin.isBlank()) "" else Profiles.pinHash(pin.trim()))
        return true
    }
}
