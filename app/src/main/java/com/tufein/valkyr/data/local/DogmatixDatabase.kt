package com.tufein.valkyr.data.local

import androidx.room.Database
import com.tufein.valkyr.data.local.entity.PersonalProfileEntity
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.DownloadHistoryDao
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.dao.FavouriteDao
import com.tufein.valkyr.data.local.dao.GameMetadataDao
import com.tufein.valkyr.data.local.dao.ManufacturerDao
import com.tufein.valkyr.data.local.dao.WishlistDao
import com.tufein.valkyr.data.local.entity.WishlistEntity
import com.tufein.valkyr.data.local.entity.CollectionEntity
import com.tufein.valkyr.data.local.entity.CollectionItemEntity
import com.tufein.valkyr.data.local.entity.DatRomEntity
import com.tufein.valkyr.data.local.entity.DatSetEntity
import com.tufein.valkyr.data.local.dao.CollectionDao
import com.tufein.valkyr.data.local.dao.DatDao
import com.tufein.valkyr.data.local.dao.CoverDao
import com.tufein.valkyr.data.local.dao.GameOverrideDao
import com.tufein.valkyr.data.local.entity.CoverEntity
import com.tufein.valkyr.data.local.entity.ConsoleEntity
import com.tufein.valkyr.data.local.entity.DownloadHistoryEntity
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.local.entity.FavouriteEntity
import com.tufein.valkyr.data.local.entity.FileTagEntity
import com.tufein.valkyr.data.local.entity.GameMetadataEntity
import com.tufein.valkyr.data.local.entity.GameOverrideEntity
import com.tufein.valkyr.data.local.entity.ManufacturerEntity
import com.tufein.valkyr.data.local.queries.DownloadableFileFts

@Database(
    entities = [ManufacturerEntity::class, ConsoleEntity::class, DownloadableFileEntity::class, FileTagEntity::class, DownloadableFileFts::class, DownloadHistoryEntity::class, GameMetadataEntity::class, FavouriteEntity::class, WishlistEntity::class, CollectionEntity::class, CollectionItemEntity::class, DatSetEntity::class, DatRomEntity::class, CoverEntity::class, PersonalProfileEntity::class, GameOverrideEntity::class],
    version = 18,
    exportSchema = false
)
abstract class DogmatixDatabase : RoomDatabase() {
    abstract fun downloadableFileDao(): DownloadableFileDao
    abstract fun consoleDao(): ConsoleDao
    abstract fun manufacturerDao(): ManufacturerDao
    abstract fun downloadHistoryDao(): DownloadHistoryDao
    abstract fun gameMetadataDao(): GameMetadataDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun wishlistDao(): WishlistDao
    abstract fun collectionDao(): CollectionDao
    abstract fun datDao(): DatDao
    abstract fun coverDao(): CoverDao
    abstract fun gameOverrideDao(): GameOverrideDao

    companion object {
        /** 4.6: indexes, `searchWords` / `isRomm` columns and the FTS table over the words (see [Migration15To16]). */
        val MIGRATION_15_16: Migration = Migration15To16

        /** 9.0: per-game metadata overrides ("Fix the match"). */
        val MIGRATION_16_17: Migration = Migration16To17
        /** Sources tracker: a download remembers its source and any switch. */
        val MIGRATION_17_18: Migration = Migration17To18
        /** 2.6: safe download failure reasons survive process death. Existing rows remain readable. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_history ADD COLUMN failureCategory TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE download_history ADD COLUMN failureHttpStatusCode INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE download_history ADD COLUMN failureAt INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE favourites_v14 (consoleId TEXT NOT NULL, fileName TEXT NOT NULL, addedAt INTEGER NOT NULL, profileId TEXT NOT NULL DEFAULT '', PRIMARY KEY(consoleId, fileName, profileId))")
                db.execSQL("INSERT INTO favourites_v14 (consoleId, fileName, addedAt) SELECT consoleId, fileName, addedAt FROM favourites")
                db.execSQL("DROP TABLE favourites")
                db.execSQL("ALTER TABLE favourites_v14 RENAME TO favourites")
                db.execSQL("CREATE TABLE personal_profile (id INTEGER NOT NULL PRIMARY KEY, activeId TEXT NOT NULL)")
                db.execSQL("INSERT INTO personal_profile VALUES (0, '')")
            }
        }
        /** 5.0: covers found for library games (see CoverEntity). */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS covers (" +
                        "`key` TEXT NOT NULL PRIMARY KEY, " +
                        "url TEXT NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "fetchedAt INTEGER NOT NULL)"
                )
            }
        }

        /** 4.0: finished downloads are looked up by file name; an index keeps that quick in a big library. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloadable_files_fileName ON downloadable_files (fileName)")
            }
        }

        /**
         * 2.0: every row remembers its source (a rescan replaces one source at a time and can skip
         * an unchanged one) and when a rescan first found it; own collections; imported DAT files.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN firstSeenAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloadable_files_consoleId_sourceUrl ON downloadable_files (consoleId, sourceUrl)")
                db.execSQL("CREATE TABLE IF NOT EXISTS collections (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS collection_items (collectionId INTEGER NOT NULL, consoleId TEXT NOT NULL, " +
                        "fileName TEXT NOT NULL, addedAt INTEGER NOT NULL, PRIMARY KEY(collectionId, consoleId, fileName))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_items_consoleId_fileName ON collection_items (consoleId, fileName)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS dat_sets (consoleId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, version TEXT NOT NULL, " +
                        "games INTEGER NOT NULL, roms INTEGER NOT NULL, importedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS dat_roms (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, consoleId TEXT NOT NULL, " +
                        "gameName TEXT NOT NULL, romName TEXT NOT NULL, size INTEGER NOT NULL, crc TEXT, md5 TEXT, sha1 TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dat_roms_consoleId_crc ON dat_roms (consoleId, crc)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dat_roms_consoleId_sha1 ON dat_roms (consoleId, sha1)")
            }
        }

        /** Wishlist table; the hash a source publishes for a file (checked after the download). */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS wishlist (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "consoleId TEXT DEFAULT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "notifiedAt INTEGER DEFAULT NULL)"
                )
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN expectedHash TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE download_history ADD COLUMN expectedHash TEXT DEFAULT NULL")
            }
        }

        /** Generalises the TorBox resume ids to any debrid service (string ids); SQLite < 3.35 can't drop columns, so rebuild. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS download_history_new (" +
                        "fileName TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "consoleId TEXT NOT NULL, " +
                        "downloadUrl TEXT NOT NULL, " +
                        "fileSize INTEGER NOT NULL, " +
                        "fileExtension TEXT NOT NULL, " +
                        "torrentFileIndex INTEGER, " +
                        "torrentMagnet TEXT, " +
                        "status TEXT NOT NULL, " +
                        "startedAt INTEGER NOT NULL, " +
                        "finishedAt INTEGER, " +
                        "debridProvider TEXT DEFAULT NULL, " +
                        "debridTorrentId TEXT DEFAULT NULL, " +
                        "debridFileId INTEGER DEFAULT NULL)"
                )
                db.execSQL(
                    "INSERT INTO download_history_new (fileName, name, consoleId, downloadUrl, fileSize, fileExtension, " +
                        "torrentFileIndex, torrentMagnet, status, startedAt, finishedAt, debridProvider, debridTorrentId, debridFileId) " +
                        "SELECT fileName, name, consoleId, downloadUrl, fileSize, fileExtension, torrentFileIndex, torrentMagnet, " +
                        "status, startedAt, finishedAt, " +
                        "CASE WHEN torboxTorrentId IS NULL THEN NULL ELSE 'TORBOX' END, " +
                        "CAST(torboxTorrentId AS TEXT), torboxFileId FROM download_history"
                )
                db.execSQL("DROP TABLE download_history")
                db.execSQL("ALTER TABLE download_history_new RENAME TO download_history")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_history ADD COLUMN torboxTorrentId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE download_history ADD COLUMN torboxFileId INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favourites (" +
                        "consoleId TEXT NOT NULL, " +
                        "fileName TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(consoleId, fileName))"
                )
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN torrentFileIndex INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN torrentMagnet TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing rows get their key from DownloadableFileDao.backfillSearchKeys() at app start.
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN searchKey TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS download_history (" +
                        "fileName TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "consoleId TEXT NOT NULL, " +
                        "downloadUrl TEXT NOT NULL, " +
                        "fileSize INTEGER NOT NULL, " +
                        "fileExtension TEXT NOT NULL, " +
                        "torrentFileIndex INTEGER, " +
                        "torrentMagnet TEXT, " +
                        "status TEXT NOT NULL, " +
                        "startedAt INTEGER NOT NULL, " +
                        "finishedAt INTEGER)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE consoles ADD COLUMN shortName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE consoles ADD COLUMN folderAliases TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS game_metadata (" +
                        "lookupKey TEXT NOT NULL PRIMARY KEY, " +
                        "title TEXT NOT NULL, " +
                        "description TEXT NOT NULL, " +
                        "genres TEXT NOT NULL, " +
                        "released TEXT NOT NULL, " +
                        "developer TEXT NOT NULL, " +
                        "imageUrl TEXT NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "fetchedAt INTEGER NOT NULL)"
                )
            }
        }
    }
}
