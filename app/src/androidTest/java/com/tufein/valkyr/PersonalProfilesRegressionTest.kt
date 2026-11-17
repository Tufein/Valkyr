package com.tufein.valkyr

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tufein.valkyr.data.local.*
import com.tufein.valkyr.data.local.entity.*
import com.tufein.valkyr.data.service.ActionLogService
import com.tufein.valkyr.util.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PersonalProfilesRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migrationPreservesLegacyFavouritesAndSeparatesSameGameAcrossProfiles() = runBlocking {
        val name = "profiles-migration-${UUID.randomUUID()}"
        fun open() = Room.databaseBuilder(context, DogmatixDatabase::class.java, name)
            .addMigrations(DogmatixDatabase.MIGRATION_13_14, DogmatixDatabase.MIGRATION_14_15, DogmatixDatabase.MIGRATION_15_16, DogmatixDatabase.MIGRATION_16_17).build()
        try {
            open().also { db ->
                db.favouriteDao().upsert(FavouriteEntity("gba", "Game.gba", 123L))
                db.close()
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("CREATE TABLE favourites_v13 (consoleId TEXT NOT NULL, fileName TEXT NOT NULL, addedAt INTEGER NOT NULL, PRIMARY KEY(consoleId, fileName))")
                old.execSQL("INSERT INTO favourites_v13 SELECT consoleId,fileName,addedAt FROM favourites WHERE profileId = ''")
                old.execSQL("DROP TABLE favourites")
                old.execSQL("ALTER TABLE favourites_v13 RENAME TO favourites")
                old.execSQL("DROP TABLE personal_profile")
                restoreVersion14DownloadHistorySchema(old)
                old.execSQL("DELETE FROM room_master_table")
                old.version = 13
            }
            open().also { db ->
                val dao = db.favouriteDao()
                assertEquals(FavouriteEntity("gba", "Game.gba", 123L), dao.getAll().single())
                dao.setActive(PersonalProfileEntity(activeId = "child"))
                assertTrue(dao.getAll().isEmpty())
                dao.upsert(FavouriteEntity("gba", "Game.gba", 456L))
                assertEquals("child", dao.getAll().single().profileId)
                assertEquals(2, dao.allProfiles().size)
                dao.delete("gba", "Game.gba")
                dao.setActive(PersonalProfileEntity(activeId = ""))
                assertEquals(123L, dao.getAll().single().addedAt)
                db.close()
            }
            open().also { db -> assertEquals(123L, db.favouriteDao().getAll().single().addedAt); db.close() }
            Unit
        } finally { context.deleteDatabase(name) }
    }

    @Test fun queuedActionsKeepTheInitiatingProfileAcrossAnImmediateSwitch() = runBlocking {
        val settings = AppSettings(context)
        val before = settings.activeProfile.first()
        val dir = File(context.cacheDir, "history-switch-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = dir }
        try {
            settings.setActiveProfile("profile-a")
            val log = ActionLogService(isolated, settings)
            repeat(120) { log.record(ActionKind.PLAYED, "A$it") }
            settings.setActiveProfile("profile-b")
            log.record(ActionKind.PLAYED, "B")
            val all = withTimeout(10000) { log.entries.first { it?.size == 121 }!! }
            assertTrue(all.take(120).all { it.profileId == "profile-a" })
            assertEquals("profile-b", all.last().profileId)
        } finally { settings.setActiveProfile(before); dir.deleteRecursively() }
    }

    @Test fun clearingOneProfilesHistoryRetainsOtherProfilesAfterRestart() = runBlocking {
        val settings = AppSettings(context)
        val before = settings.activeProfile.first()
        val dir = File(context.cacheDir, "history-profiles-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = dir }
        try {
            settings.setActiveProfile("profile-a")
            val log = ActionLogService(isolated, settings)
            log.record(ActionKind.PLAYED, "A", consoleId = "gba", fileName = "A.gba")
            withTimeout(5000) { log.entries.first { it?.size == 1 } }
            settings.setActiveProfile("profile-b")
            log.record(ActionKind.PLAYED, "B", consoleId = "gba", fileName = "B.gba")
            val both = withTimeout(5000) { log.entries.first { it?.size == 2 }!! }
            assertEquals(listOf("profile-a", "profile-b"), both.map { it.profileId })
            log.clear()
            assertEquals("profile-a", ActionLogFile(File(dir, "action_log.jsonl")).load().single().profileId)
            val restarted = ActionLogService(isolated, settings)
            assertEquals("A", withTimeout(5000) { restarted.entries.first { it != null }!! }.single().title)
        } finally { settings.setActiveProfile(before); dir.deleteRecursively() }
    }
}
