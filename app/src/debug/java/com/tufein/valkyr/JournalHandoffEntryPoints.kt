package com.tufein.valkyr

import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.dao.*
import com.tufein.valkyr.data.repository.*
import com.tufein.valkyr.data.service.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint @InstallIn(SingletonComponent::class)
interface Journal28TestEntryPoint {
    fun journalSettings(): AppSettings
    fun journalProfiles(): ProfileService
    fun journalRepository(): DownloadableFileRepository
    fun journalSources(): SourcesRepository
    fun journalCollections(): CollectionsRepository
    fun journalFavourites(): FavouriteDao
    fun journalDownloads(): DownloadHistoryDao
    fun journalWishlist(): WishlistDao
    fun journalOverrides(): GameOverrideDao
}

@EntryPoint @InstallIn(SingletonComponent::class)
interface Handoff28TestEntryPoint {
    fun handoffSettings(): AppSettings
    fun handoffRepository(): SettingsRepository
    fun handoffAccess(): JournalGameAccess
    fun handoffLog(): ActionLogService
}

@EntryPoint @InstallIn(SingletonComponent::class)
interface Readiness28ProfileEntryPoint {
    fun readinessSettings(): AppSettings
    fun readinessRepository(): SettingsRepository
    fun readinessLibrary(): LibraryIndexService
    fun readinessProfiles(): ProfileService
    fun readinessBios(): BiosService
    fun readinessLog(): ActionLogService
}
