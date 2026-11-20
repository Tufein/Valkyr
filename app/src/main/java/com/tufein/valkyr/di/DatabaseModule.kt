package com.tufein.valkyr.di

import android.content.Context
import androidx.room.Room
import com.tufein.valkyr.data.local.DogmatixDatabase
import com.tufein.valkyr.data.local.dao.CollectionDao
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.DatDao
import com.tufein.valkyr.data.local.dao.DownloadHistoryDao
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.dao.FavouriteDao
import com.tufein.valkyr.data.local.dao.GameMetadataDao
import com.tufein.valkyr.data.local.dao.CoverDao
import com.tufein.valkyr.data.local.dao.GameOverrideDao
import com.tufein.valkyr.data.local.dao.ManufacturerDao
import com.tufein.valkyr.data.local.dao.WishlistDao
import com.tufein.valkyr.data.local.SettingsDataStore
import com.tufein.valkyr.data.repository.DownloadRepository
import com.tufein.valkyr.data.repository.DownloadRepositoryImpl
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.repository.SettingsRepositoryImpl
import com.tufein.valkyr.data.service.ArchiveExtractorService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DogmatixDatabase =
        Room.databaseBuilder(context, DogmatixDatabase::class.java, "dogmatix_db")
            .addMigrations(DogmatixDatabase.MIGRATION_1_2, DogmatixDatabase.MIGRATION_2_3, DogmatixDatabase.MIGRATION_3_4, DogmatixDatabase.MIGRATION_4_5, DogmatixDatabase.MIGRATION_5_6, DogmatixDatabase.MIGRATION_6_7, DogmatixDatabase.MIGRATION_7_8, DogmatixDatabase.MIGRATION_8_9, DogmatixDatabase.MIGRATION_9_10, DogmatixDatabase.MIGRATION_10_11, DogmatixDatabase.MIGRATION_11_12, DogmatixDatabase.MIGRATION_12_13, DogmatixDatabase.MIGRATION_13_14)
            .addMigrations(DogmatixDatabase.MIGRATION_14_15, DogmatixDatabase.MIGRATION_15_16)
            .addMigrations(DogmatixDatabase.MIGRATION_16_17, DogmatixDatabase.MIGRATION_17_18)
            .build()

    @Provides
    fun provideFavouriteDao(db: DogmatixDatabase): FavouriteDao = db.favouriteDao()

    @Provides
    fun provideWishlistDao(db: DogmatixDatabase): WishlistDao = db.wishlistDao()

    @Provides
    fun provideCollectionDao(db: DogmatixDatabase): CollectionDao = db.collectionDao()

    @Provides
    fun provideDatDao(db: DogmatixDatabase): DatDao = db.datDao()

    @Provides
    fun provideConsoleDao(db: DogmatixDatabase): ConsoleDao = db.consoleDao()

    @Provides
    fun provideDownloadableFileDao(db: DogmatixDatabase): DownloadableFileDao = db.downloadableFileDao()

    @Provides
    fun provideManufacturerDao(db: DogmatixDatabase): ManufacturerDao = db.manufacturerDao()

    @Provides
    fun provideDownloadHistoryDao(db: DogmatixDatabase): DownloadHistoryDao = db.downloadHistoryDao()

    @Provides
    fun provideGameMetadataDao(db: DogmatixDatabase): GameMetadataDao = db.gameMetadataDao()

    @Provides
    fun provideCoverDao(db: DogmatixDatabase): CoverDao = db.coverDao()

    @Provides
    fun provideGameOverrideDao(db: DogmatixDatabase): GameOverrideDao = db.gameOverrideDao()
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore = SettingsDataStore(context)

    @Provides
    @Singleton
    fun provideSettingsRepository(settingsRepositoryImpl: SettingsRepositoryImpl): SettingsRepository = settingsRepositoryImpl

    @Provides
    @Singleton
    fun provideDownloadRepository(downloadRepositoryImpl: DownloadRepositoryImpl): DownloadRepository = downloadRepositoryImpl

    @Provides
    @Singleton
    fun provideArchiveExtractorService(): ArchiveExtractorService = ArchiveExtractorService()
}
