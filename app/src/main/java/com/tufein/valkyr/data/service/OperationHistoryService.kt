package com.tufein.valkyr.data.service

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException

data class OperationFile(@field:com.google.gson.annotations.SerializedName(value = "source", alternate = ["a"]) val source: String, @field:com.google.gson.annotations.SerializedName(value = "target", alternate = ["b"]) val target: String, @field:com.google.gson.annotations.SerializedName(value = "name", alternate = ["c"]) val name: String, @field:com.google.gson.annotations.SerializedName(value = "bytes", alternate = ["d"]) val bytes: Long, @field:com.google.gson.annotations.SerializedName(value = "hash", alternate = ["e"]) val hash: String, @field:com.google.gson.annotations.SerializedName(value = "parent", alternate = ["f"]) val parent: String = "", @field:com.google.gson.annotations.SerializedName(value = "removed", alternate = ["g"]) val removed: Boolean = false, @field:com.google.gson.annotations.SerializedName(value = "relativePath") val relativePath: String? = null)
data class OperationDirectory(@field:com.google.gson.annotations.SerializedName(value = "parent") val parent: String, @field:com.google.gson.annotations.SerializedName(value = "name") val name: String)
data class LibraryOperation(
    @field:com.google.gson.annotations.SerializedName(value = "id", alternate = ["a"]) val id: String = UUID.randomUUID().toString(), @field:com.google.gson.annotations.SerializedName(value = "kind", alternate = ["b"]) val kind: String, @field:com.google.gson.annotations.SerializedName(value = "title", alternate = ["c"]) val title: String,
    @field:com.google.gson.annotations.SerializedName(value = "time", alternate = ["d"]) val time: Long = System.currentTimeMillis(), @field:com.google.gson.annotations.SerializedName(value = "phase", alternate = ["e"]) val phase: String = "copying",
    @field:com.google.gson.annotations.SerializedName(value = "source", alternate = ["f"]) val source: String = "", @field:com.google.gson.annotations.SerializedName(value = "target", alternate = ["g"]) val target: String = "", @field:com.google.gson.annotations.SerializedName(value = "files", alternate = ["h"]) val files: List<OperationFile> = emptyList(), @field:com.google.gson.annotations.SerializedName(value = "consoleId", alternate = ["i"]) val consoleId: String = "", @field:com.google.gson.annotations.SerializedName(value = "totalFiles", alternate = ["j"]) val totalFiles: Int = 0, @field:com.google.gson.annotations.SerializedName(value = "destinationPlace", alternate = ["k"]) val destinationPlace: String = "",
    @field:com.google.gson.annotations.SerializedName(value = "directories") val directories: List<OperationDirectory>? = null
)

/** Atomic durable journal. Save failures propagate before any destructive step. */
@Singleton
class OperationHistoryService @Inject constructor(@param:ApplicationContext context: Context) {
    private val disk = AtomicFile(File(context.filesDir, "library_operations.json"))
    private val gson = Gson()
    private var readFailure: Exception? = null
    val unreadable: Boolean get() = readFailure != null
    private val _entries = MutableStateFlow(runCatching { read() }.getOrElse { readFailure = it as? Exception ?: IOException("Journal unavailable"); emptyList() })
    val entries = _entries.asStateFlow()
    private fun read(): List<LibraryOperation> = if (!disk.baseFile.exists()) emptyList() else
        gson.fromJson(disk.openRead().use { it.reader().readText() }, object : TypeToken<List<LibraryOperation>>() {}.type) ?: emptyList()

    @Synchronized fun put(entry: LibraryOperation) {
        if (readFailure != null) throw IOException("Recovery journal cannot be read", readFailure)
        val stored = if (entry.phase == "done" && entry.files.isNotEmpty()) entry.copy(totalFiles = maxOf(entry.totalFiles, entry.files.size), files = entry.files.take(100).map { it.copy(source = "", target = "", hash = "", parent = "") }) else entry
        val next = (_entries.value.filterNot { it.id == stored.id } + stored).sortedByDescending { it.time }
        fun recovery(item: LibraryOperation) = item.kind in setOf("trash", "library_move", "smart_move") && item.phase != "done"
        val retained = next.filter(::recovery).plus(next.filterNot(::recovery).take(300)).sortedByDescending { it.time }
        val stream = disk.startWrite()
        try { stream.write(gson.toJson(retained).toByteArray()); disk.finishWrite(stream) }
        catch (e: Exception) { disk.failWrite(stream); throw e }
        _entries.value = retained
    }
    fun get(id: String) = _entries.value.firstOrNull { it.id == id }
    fun event(kind: String, title: String, phase: String = "done") { runCatching { put(LibraryOperation(kind = kind, title = title, phase = phase)) } }
    /** Public report contains no document URIs, server addresses, credentials or arbitrary errors. */
    fun report(): String = _entries.value.joinToString("\n") { "${it.time} | ${it.kind} | ${it.phase} | ${maxOf(it.totalFiles, it.files.size)} files" }
}
