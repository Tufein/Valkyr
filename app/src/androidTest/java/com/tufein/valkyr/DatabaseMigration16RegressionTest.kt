package com.tufein.valkyr

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tufein.valkyr.data.local.DogmatixDatabase
import com.tufein.valkyr.data.local.entity.ConsoleEntity
import com.tufein.valkyr.data.local.entity.ManufacturerEntity
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * 4.6 (schema 15 -> 16). The project does not export Room schemas, so a version 15 file is made by taking the
 * current schema Room creates and putting `downloadable_files`, its FTS table and the indexes back into their
 * 4.5 shape with raw SQL (the text below is what Room 2.8 generated for those entities at version 15).
 * Written from the entity declarations; not run on a device when it was written.
 */
class DatabaseMigration16RegressionTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun makeVersion15(name: String) {
        val first = Room.databaseBuilder(context, DogmatixDatabase::class.java, name).build()
        runBlocking {
            first.manufacturerDao().insertManufacturer(ManufacturerEntity("nintendo", "Nintendo"))
            first.consoleDao().insertConsole(ConsoleEntity("gba", "Game Boy Advance", "nintendo", "[]"))
        }
        first.openHelper.writableDatabase // creates the file
        first.close()
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            raw.execSQL("PRAGMA foreign_keys=OFF")
            raw.beginTransaction()
            listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT").forEach {
                raw.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_downloadable_files_fts_$it")
            }
            raw.execSQL("DROP TABLE IF EXISTS `downloadable_files_fts`")
            raw.execSQL("DROP TABLE `downloadable_files`")
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `downloadable_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`fileName` TEXT NOT NULL, `consoleId` TEXT NOT NULL, `downloadUrl` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, " +
                    "`fileExtension` TEXT NOT NULL, `torrentFileIndex` INTEGER, `torrentMagnet` TEXT, `searchKey` TEXT NOT NULL DEFAULT '', " +
                    "`expectedHash` TEXT DEFAULT NULL, `sourceUrl` TEXT NOT NULL DEFAULT '', `firstSeenAt` INTEGER NOT NULL DEFAULT 0, " +
                    "FOREIGN KEY(`consoleId`) REFERENCES `consoles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_downloadable_files_consoleId` ON `downloadable_files` (`consoleId`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_downloadable_files_consoleId_sourceUrl` ON `downloadable_files` (`consoleId`, `sourceUrl`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_downloadable_files_fileName` ON `downloadable_files` (`fileName`)")
            raw.execSQL("DROP INDEX IF EXISTS `index_favourites_profileId`")
            raw.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `downloadable_files_fts` USING FTS4(`name` TEXT NOT NULL, content=`downloadable_files`)")
            raw.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_downloadable_files_fts_BEFORE_UPDATE BEFORE UPDATE ON `downloadable_files` BEGIN DELETE FROM `downloadable_files_fts` WHERE `docid`=OLD.`rowid`; END")
            raw.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_downloadable_files_fts_BEFORE_DELETE BEFORE DELETE ON `downloadable_files` BEGIN DELETE FROM `downloadable_files_fts` WHERE `docid`=OLD.`rowid`; END")
            raw.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_downloadable_files_fts_AFTER_UPDATE AFTER UPDATE ON `downloadable_files` BEGIN INSERT INTO `downloadable_files_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
            raw.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_downloadable_files_fts_AFTER_INSERT AFTER INSERT ON `downloadable_files` BEGIN INSERT INTO `downloadable_files_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
            // 4.5 data: three games (one served by RomM, one without a search key yet), tags, a favourite
            fun file(id: Int, title: String, fileName: String, url: String, key: String) = raw.execSQL(
                "INSERT INTO downloadable_files (id, name, fileName, consoleId, downloadUrl, fileSize, fileExtension, searchKey, sourceUrl, firstSeenAt) " +
                    "VALUES ($id, '$title', '$fileName', 'gba', '$url', 1024, '.gba', '$key', 'https://src.invalid/', 7)"
            )
            file(1, "Virtua Tennis", "Virtua Tennis.gba", "https://src.invalid/Virtua%20Tennis.gba", "virtuatenis")
            file(2, "Yu-Gi-Oh! Duel Monsters", "Yugioh.gba", "https://romm.invalid/api/roms/42/content/Yugioh.gba", "yugiohduelmonsters")
            file(3, "Zelda", "Zelda.gba", "https://src.invalid/Zelda.gba", "")
            raw.execSQL("INSERT INTO downloadable_file_tags (fileId, tag) VALUES (1, 'USA'), (1, 'English'), (2, 'Europe')")
            raw.execSQL("INSERT INTO favourites (consoleId, fileName, addedAt, profileId) VALUES ('gba', 'Virtua Tennis.gba', 5, '')")
            raw.setTransactionSuccessful()
            raw.endTransaction()
            raw.version = 15
        } finally {
            raw.close()
        }
    }

    @Test fun rowsSurviveAndTheNewIndexesAndFtsAreThere() = runBlocking {
        val name = "migration16-${UUID.randomUUID()}.db"
        makeVersion15(name)
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, GamePackagesEntryPoint::class.java)
        val db = Room.databaseBuilder(context, DogmatixDatabase::class.java, name)
            .addMigrations(DogmatixDatabase.MIGRATION_15_16, DogmatixDatabase.MIGRATION_16_17).build()
        try {
            val dao = db.downloadableFileDao()
            val sql = db.openHelper.writableDatabase
            fun strings(query: String): List<String> = sql.query(query).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

            // nothing lost
            assertEquals(3, dao.getFilesCount())
            assertEquals(setOf("Virtua Tennis", "Yu-Gi-Oh! Duel Monsters", "Zelda"), dao.filesOf("gba").map { it.name }.toSet())
            assertEquals(setOf("USA", "English"), dao.tagsOf(1).toSet())
            assertEquals(listOf("Virtua Tennis.gba"), db.favouriteDao().getAll().map { it.fileName })
            assertEquals(listOf(2L), dao.rommRows().map { it.id })

            // indexes and FTS exist; the redundant one is gone
            val indexes = strings("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('downloadable_files', 'favourites')").toSet()
            listOf("firstSeenAt", "name", "consoleId_searchKey", "consoleId_fileName", "isRomm", "consoleId_sourceUrl", "fileName").forEach {
                assertTrue(it, "index_downloadable_files_$it" in indexes)
            }
            assertTrue("index_favourites_profileId" in indexes)
            assertFalse("index_downloadable_files_consoleId" in indexes)
            assertEquals(listOf("downloadable_files_fts"), strings("SELECT name FROM sqlite_master WHERE name = 'downloadable_files_fts'"))
            assertEquals(4, strings("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'room_fts_content_sync_%'").size)

            // before the backfill the index is empty and search still answers from the LIKE scan
            assertFalse(dao.searchWordsComplete())
            val repository = DownloadableFileRepository(dao, graph.packageProfiles())
            assertEquals(listOf("Virtua Tennis"), repository.searchFilesWithTags("virtual tenis").map { it.file.name })

            // the backfill fills the words and (through the sync triggers) the FTS index
            repository.backfillSearchKeys()
            assertTrue(dao.searchWordsComplete())
            assertEquals(listOf("1"), strings("SELECT docid FROM downloadable_files_fts WHERE downloadable_files_fts MATCH 'virtua* tenis*'"))
            val found = repository.searchFilesWithTags("virtual tenis")
            assertEquals(listOf("Virtua Tennis"), found.map { it.file.name })
            assertEquals(setOf("USA", "English"), found.single().tags.toSet())
            assertEquals(listOf("Yu-Gi-Oh! Duel Monsters"), repository.searchFilesWithTags("yu gi oh").map { it.file.name })
            assertEquals(listOf("Yu-Gi-Oh! Duel Monsters"), repository.searchFilesWithTags("yugioh").map { it.file.name })
            assertEquals(3, repository.searchFilesWithTags("").size)

            // an update or a delete keeps the index in step
            dao.deleteFilesByConsoleId("gba")
            assertEquals(emptyList<String>(), strings("SELECT docid FROM downloadable_files_fts WHERE downloadable_files_fts MATCH 'virtua*'"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun aFreshDatabaseAtVersion16HasTheSameShape() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            val names = sql.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'downloadable_files'").use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
            }
            assertTrue(names.containsAll(listOf(
                "index_downloadable_files_firstSeenAt", "index_downloadable_files_name", "index_downloadable_files_consoleId_searchKey",
                "index_downloadable_files_consoleId_fileName", "index_downloadable_files_isRomm", "index_downloadable_files_consoleId_sourceUrl",
                "index_downloadable_files_fileName"
            )))
            assertFalse("index_downloadable_files_consoleId" in names)
        } finally {
            db.close()
        }
    }
}
