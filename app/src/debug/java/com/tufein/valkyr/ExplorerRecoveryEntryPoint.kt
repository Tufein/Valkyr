package com.tufein.valkyr

import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.ArchiveExtractorService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.StorageMoveGate
import com.tufein.valkyr.data.service.TrashService
import com.tufein.valkyr.data.service.VerifiedDocumentCopy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.SmartStorageSettings
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.repository.DownloadRepository
import com.tufein.valkyr.data.service.DownloadFileManager
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.GamePackageService
import com.tufein.valkyr.data.service.LibraryMoveService
import com.tufein.valkyr.data.service.OperationHistoryService
import com.tufein.valkyr.data.service.RecoveryHubService
import com.tufein.valkyr.data.service.SaveSyncService
import com.tufein.valkyr.data.service.StorageAvailabilityService
import com.tufein.valkyr.data.service.StorageDownloadHolds

/** Actual explorer dependencies for UI recovery regression tests, absent from release builds. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExplorerRecoveryEntryPoint {
    fun recovery28ServiceFactory(): StorageRecoveryServiceFactory
    fun recovery28AppSettings(): AppSettings
    fun recovery28Hub(): RecoveryHubService
    fun recovery28Availability(): StorageAvailabilityService
    fun recovery28Downloads(): DownloadService
    fun recovery28DownloadHolds(): StorageDownloadHolds
    fun recovery28DownloadRepository(): DownloadRepository
    fun recovery28Files(): DownloadableFileDao
    fun recovery28FileManager(): DownloadFileManager
    fun recovery28Packages(): GamePackageService
    fun recovery28History(): OperationHistoryService
    fun recovery28Mover(): LibraryMoveService
    fun recovery28SmartSettings(): SmartStorageSettings
    fun recovery28SaveSync(): SaveSyncService
    fun explorer27SettingsRepository(): SettingsRepository
    fun explorer27Extractor(): ArchiveExtractorService
    fun explorer27VerifiedCopy(): VerifiedDocumentCopy
    fun explorer27Trash(): TrashService
    fun explorer27Library(): LibraryIndexService
    fun explorer27MoveGate(): StorageMoveGate
}
