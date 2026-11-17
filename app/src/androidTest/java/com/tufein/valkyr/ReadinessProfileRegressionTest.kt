package com.tufein.valkyr

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.DocumentsContract
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.DogmatixDatabase
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.*
import com.tufein.valkyr.ui.screens.tools.GameReadinessViewModel
import com.tufein.valkyr.util.GameEmulatorOverrides
import com.tufein.valkyr.util.StorageHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test


/** An old readiness screen cannot write the incoming profile's emulator settings or launch a game. */
class ReadinessProfileRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, Readiness28ProfileEntryPoint::class.java)
    @Test fun oldEmulatorSelectionResetAndTestCannotActAfterSwitchingProfiles() = runBlocking {
        val id = UUID.randomUUID().toString()
        val name = "Readiness-$id.gba"
        val settings = graph.readinessSettings()
        val repository = graph.readinessRepository()
        val beforeProfile = settings.activeProfile.first()
        val beforeRoot = repository.downloadDirectory.first()
        val beforeSeparate = repository.separateByConsole.first()
        val beforeCustom = repository.consoleDownloadDirectories.first()["gba"]
        val prefs = context.getSharedPreferences("readiness-$id-game_launchers", Context.MODE_PRIVATE)
        val launched = AtomicInteger()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("readiness-$id-$name", mode)
            override fun startActivity(intent: Intent) { launched.incrementAndGet() }
        }
        val db = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.valkyr.test.storage", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val test = InstrumentationRegistry.getInstrumentation().context
        test.grantUriPermission(context.packageName, tree, flags)
        val folder = requireNotNull(StorageHelper.createDirectory(context, tree.toString(), "readiness-$id"))
        val folderTree = DocumentsContract.buildTreeDocumentUri(tree.authority, DocumentsContract.getDocumentId(folder.uri))
        test.grantUriPermission(context.packageName, folderTree, flags)
        var vm: GameReadinessViewModel? = null
        try {
            settings.setActiveProfile("readiness-parent")
            repository.updateDownloadDirectory(folderTree.toString())
            repository.setSeparateByConsole(false)
            repository.updateConsoleDownloadDirectory("gba", "")
            StorageHelper.writeBytesSafely(context, folder, "", name, "fixture game".toByteArray())
            db.manufacturerDao().insertManufacturer(com.tufein.valkyr.data.local.entity.ManufacturerEntity("fixture", "Fixture"))
            db.consoleDao().insertConsole(com.tufein.valkyr.data.local.entity.ConsoleEntity("gba", "Game Boy Advance", "fixture", "[]"))
            db.downloadableFileDao().insertFiles(listOf(DownloadableFileEntity(consoleId = "gba", name = name, fileName = name,
                downloadUrl = "https://example.invalid/$name", sourceUrl = "https://example.invalid/readiness", fileSize = 12, fileExtension = "gba")))
            val files = db.downloadableFileDao()
            val profiles = graph.readinessProfiles()
            val library = graph.readinessLibrary()
            library.refresh()
            val launcher = GameLaunchService(isolated, library, graph.readinessLog(), profiles, PlaySessionService(isolated, profiles))
            val readiness = GameReadinessService(context, library, launcher, graph.readinessBios())
            val access = JournalGameAccess(settings, DownloadableFileRepository(files, profiles))
            val model = GameReadinessViewModel(files, library, launcher, readiness, profiles, settings, access)
            vm = model
            model.check("gba", name)
            val shown = withTimeout(15_000) { model.ui.first { !it.loading && it.games.isNotEmpty() && it.key?.profileId == "readiness-parent" } }
            val game = shown.games.first { it.handlers.isNotEmpty() }
            val handler = game.handlers.first()
            val childKey = GameEmulatorOverrides.storageKey("readiness-child", "gba", name)
            prefs.edit().putString(childKey, "child.package/child.package.Main").commit()
            val before = prefs.all.toMap()
            settings.setActiveProfile("readiness-child")
            model.select("gba", name, handler.key, true)
            model.useConsole("gba", name)
            model.test(isolated, "gba", game, handler)
            delay(300)
            assertEquals(before, prefs.all)
            assertEquals(0, launched.get())
            assertTrue(model.ui.value.games.isEmpty())
        } finally {
            vm?.viewModelScope?.cancel()
            settings.setActiveProfile(beforeProfile)
            repository.updateDownloadDirectory(beforeRoot)
            repository.setSeparateByConsole(beforeSeparate)
            repository.updateConsoleDownloadDirectory("gba", beforeCustom.orEmpty())
            folder.delete(); db.close(); prefs.edit().clear().commit()
            test.revokeUriPermission(folderTree, flags); test.revokeUriPermission(tree, flags)
            graph.readinessLibrary().refresh()
        }
    }
}
