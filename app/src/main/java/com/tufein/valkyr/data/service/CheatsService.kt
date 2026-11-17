package com.tufein.valkyr.data.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tufein.valkyr.util.Cheats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 9.0: cheat files the user brings. Kept in this app's own `files/cheats/`, never downloaded and
 * never written into another app's private storage. The share action hands one file to whatever
 * emulator or file manager can accept it; enabling a cheat is a choice kept per game and file.
 */
@Singleton
class CheatsService @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val dir = File(context.filesDir, "cheats")
    private val stateFile = AtomicFile(File(dir, "enabled.json"))
    private val gson = Gson()

    private fun readState(): MutableMap<String, Boolean> = runCatching {
        if (!stateFile.baseFile.exists()) return emptyMap<String, Boolean>().toMutableMap()
        val type = object : TypeToken<Map<String, Boolean>>() {}.type
        Gson().fromJson<Map<String, Boolean>>(stateFile.readFully().toString(Charsets.UTF_8), type).toMutableMap()
    }.getOrElse { mutableMapOf() }

    private fun writeState(state: Map<String, Boolean>) {
        try {
            dir.mkdirs()
            val stream = stateFile.startWrite()
            try { stream.write(gson.toJson(state).toByteArray(Charsets.UTF_8)); stateFile.finishWrite(stream) }
            catch (e: Exception) { stateFile.failWrite(stream); throw e }
        } catch (_: Exception) { /* a lost toggle is not worth breaking the screen */ }
    }

    suspend fun files(): List<String> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && Cheats.isCheatFile(f.name) }?.map { it.name }?.sorted() ?: emptyList()
    }

    suspend fun content(name: String): String = withContext(Dispatchers.IO) {
        runCatching { File(dir, Cheats.safeName(name)).readText(Charsets.UTF_8) }.getOrDefault("")
    }

    suspend fun parsed(name: String): List<Cheats.Cheat> = withContext(Dispatchers.IO) { Cheats.parse(content(name)) }

    suspend fun import(uri: Uri, displayName: String): Int = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot read the file")
        if (bytes.isEmpty() || bytes.size > Cheats.MAX_FILE_BYTES) throw IllegalStateException("Cheat file is too large")
        val name = Cheats.safeName(displayName)
        if (!Cheats.isCheatFile(name)) throw IllegalArgumentException("Only .cht files are supported")
        dir.mkdirs()
        val f = AtomicFile(File(dir, name))
        val stream = f.startWrite()
        try { stream.write(bytes); f.finishWrite(stream) } catch (e: Exception) { f.failWrite(stream); throw e }
        Cheats.parse(bytes.toString(Charsets.UTF_8)).size
    }

    suspend fun enabled(name: String, index: Int): Boolean = withContext(Dispatchers.IO) {
        readState()["${Cheats.safeName(name)}#$index"] == true
    }

    suspend fun setEnabled(name: String, index: Int, on: Boolean) = withContext(Dispatchers.IO) {
        val state = readState()
        val key = "${Cheats.safeName(name)}#$index"
        if (on) state[key] = true else state.remove(key)
        writeState(state)
    }

    /** Hands the file to an app that can use it. Nothing is installed or written elsewhere. */
    fun shareIntent(name: String): Intent? {
        val source = File(dir, Cheats.safeName(name))
        if (!source.exists()) return null
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(outDir, source.name)
        runCatching { source.copyTo(out, overwrite = true) }.getOrElse { return null }
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.provider", out) }.getOrNull() ?: return null
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
