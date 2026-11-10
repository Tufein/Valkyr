package com.tufein.valkyr.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.tufein.valkyr.data.local.queries.LibraryQueryBuilder
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.local.entity.FileTagEntity
import com.tufein.valkyr.util.SearchNormalizer
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadableFileDao {

    @Query("SELECT COUNT(*) FROM downloadable_files")
    fun observeLibraryChanges(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<DownloadableFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<FileTagEntity>)

    /**
     * The library list: [LibraryQueryBuilder.files]. Tag filters: each category (regions, languages, …) is
     * OR-ed within itself and AND-ed with the others, e.g. (GBC ∨ GBA) ∧ (ES) ∧ (RETROACHIEVEMENTS).
     * RomM rows are recognised by their `/api/roms/{id}/content/` download URL (the `isRomm` column, see RommSource).
     * Built as text because only the conditions in use belong in the statement (see [LibraryQueryBuilder]).
     */
    @RawQuery
    suspend fun queryFilesWithTags(query: SupportSQLiteQuery): List<DownloadableFileWithTagsResult>

    @Query("SELECT COUNT(*) FROM downloadable_files")
    suspend fun getFilesCount(): Int

    /** Indexed games per console, for the library overview. */
    @Query("SELECT consoleId, COUNT(*) AS count FROM downloadable_files GROUP BY consoleId")
    suspend fun countsByConsole(): List<ConsoleFileCount>

    @Query("SELECT fileName FROM downloadable_files WHERE consoleId = :consoleId")
    suspend fun fileNamesFor(consoleId: String): List<String>

    /** The other versions of a game: same console, same cleaned title (see `searchKey`). */
    @Query("SELECT * FROM downloadable_files WHERE consoleId = :consoleId AND searchKey = :searchKey")
    suspend fun versionsOf(consoleId: String, searchKey: String): List<DownloadableFileEntity>

    @Query("SELECT t.tag FROM downloadable_file_tags t WHERE t.fileId = :fileId")
    suspend fun tagsOf(fileId: Long): List<String>

    /** Library rows whose title contains [key] (a [SearchNormalizer] key), optionally of one console. */
    @Query("SELECT COUNT(*) FROM downloadable_files WHERE searchKey LIKE '%' || :key || '%' AND (:consoleId IS NULL OR consoleId = :consoleId)")
    suspend fun countMatching(key: String, consoleId: String?): Int

    @Query("SELECT * FROM downloadable_files WHERE fileName = :fileName LIMIT 1")
    suspend fun getFileByFileName(fileName: String): DownloadableFileEntity?

    /** Library rows whose title contains [key], optionally of one console (for the wishlist's automatic download). */
    @Query("SELECT * FROM downloadable_files WHERE searchKey LIKE '%' || :key || '%' AND (:consoleId IS NULL OR consoleId = :consoleId) LIMIT :limit")
    suspend fun filesMatching(key: String, consoleId: String?, limit: Int = 50): List<DownloadableFileEntity>

    /** Library rows for many downloads at once (keep [names] under SQLite's 999 variables). */
    @Query("SELECT * FROM downloadable_files WHERE fileName IN (:names)")
    suspend fun filesByFileNames(names: List<String>): List<DownloadableFileEntity>

    /** Bounded scan for portable plan identities, including relative/absolute listing hrefs. */
    @Query("SELECT * FROM downloadable_files WHERE consoleId = :consoleId AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun planIdentityRows(consoleId: String, afterId: Long, limit: Int): List<DownloadableFileEntity>

    /** 3.0 "For you": the light columns of the library in id order, one page after [afterId]. */
    @Query("SELECT id, consoleId, fileName, name FROM downloadable_files WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun lightPage(afterId: Long, limit: Int): List<LightFileRow>

    /** Tags of many files at once (keep [fileIds] under SQLite's 999 variables). */
    @Query("SELECT fileId, tag FROM downloadable_file_tags WHERE fileId IN (:fileIds) ORDER BY tag ASC")
    suspend fun tagsOfFiles(fileIds: List<Long>): List<FileTagRow>

    @Query("SELECT tag FROM downloadable_file_tags WHERE fileId = :fileId ORDER BY tag ASC")
    suspend fun getTagsForFile(fileId: Long): List<String>

    /** The tag chips ([LibraryQueryBuilder.tags]). */
    @RawQuery
    suspend fun rawTags(query: SupportSQLiteQuery): List<String>

    /** Consoles with matching files and their counts ([LibraryQueryBuilder.consoles]). */
    @RawQuery
    suspend fun rawConsolesWithFiles(query: SupportSQLiteQuery): List<ConsoleWithFileCount>

    @Transaction
    suspend fun insertFilesWithTags(files: List<DownloadableFileEntity>, tags: List<FileTagEntity>) {
        val ids = insertAll(files)
        val tagsWithIds = tags.mapIndexed { index, tag ->
            tag.copy(fileId = ids.getOrNull(index) ?: 0L)
        }
        insertTags(tagsWithIds)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<DownloadableFileEntity>): List<Long>

    /**
     * The files of one source with their tags (`tags[i]` belongs to `files[i]`), written in one
     * transaction: one commit for the whole source instead of one per statement batch, and the
     * library never shows a source's files without their tags.
     */
    @Transaction
    suspend fun insertSource(files: List<DownloadableFileEntity>, tags: List<List<String>>): Int {
        if (files.isEmpty()) return 0
        val ids = insertAll(files)
        val rows = ArrayList<FileTagEntity>(tags.sumOf { it.size })
        ids.forEachIndexed { i, id -> tags.getOrNull(i)?.forEach { rows += FileTagEntity(fileId = id, tag = it) } }
        insertTags(rows)
        return rows.size
    }

    @Query("DELETE FROM downloadable_files")
    suspend fun clearAll()

    @Query("SELECT fileName, firstSeenAt FROM downloadable_files WHERE consoleId = :consoleId AND (sourceUrl = :sourceUrl OR sourceUrl = '')")
    suspend fun seenIn(consoleId: String, sourceUrl: String): List<FileSeen>

    @Query("SELECT COUNT(*) FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl")
    suspend fun countSource(consoleId: String, sourceUrl: String): Int

    @Query("DELETE FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl")
    suspend fun deleteSourceFiles(consoleId: String, sourceUrl: String)

    @Query("DELETE FROM downloadable_file_tags WHERE fileId IN (SELECT id FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl)")
    suspend fun deleteSourceTags(consoleId: String, sourceUrl: String)

    @Transaction
    suspend fun deleteSource(consoleId: String, sourceUrl: String) {
        deleteSourceTags(consoleId, sourceUrl)
        deleteSourceFiles(consoleId, sourceUrl)
    }

    /** Every (console, source) pair that has rows; '' is a row indexed before 2.0. */
    @Query("SELECT DISTINCT consoleId, sourceUrl FROM downloadable_files")
    suspend fun indexedSources(): List<IndexedSource>

    /** How many files rescans found since [since] (widget, notification). */
    @Query("SELECT COUNT(*) FROM downloadable_files WHERE firstSeenAt >= :since AND firstSeenAt > 0")
    suspend fun countNewSince(since: Long): Int

    @Query("SELECT * FROM downloadable_files WHERE firstSeenAt >= :since AND firstSeenAt > 0 ORDER BY firstSeenAt DESC LIMIT :limit")
    suspend fun newestSince(since: Long, limit: Int): List<DownloadableFileEntity>

    /** Rows served by a RomM server (their URL carries the rom id); served by the `isRomm` index, no LIKE scan. */
    @Query("SELECT * FROM downloadable_files WHERE isRomm = 1")
    suspend fun rommRows(): List<DownloadableFileEntity>

    /** When rescans found files, since [since] (statistics). */
    @Query("SELECT firstSeenAt FROM downloadable_files WHERE firstSeenAt >= :since AND firstSeenAt > 0")
    suspend fun firstSeenSince(since: Long): List<Long>

    /**
     * Consoles with Switch container files (nsp, nsz, xci, xcz). Asks each console in turn and stops at its
     * first hit (the console index finds the rows), instead of scanning the whole table for DISTINCT.
     * LIKE is already ASCII case-insensitive.
     */
    @Query("SELECT c.id FROM consoles c WHERE EXISTS (SELECT 1 FROM downloadable_files df WHERE df.consoleId = c.id AND (df.fileName LIKE '%.nsp' OR df.fileName LIKE '%.nsz' OR df.fileName LIKE '%.xci' OR df.fileName LIKE '%.xcz'))")
    suspend fun switchConsoles(): List<String>

    /** All rows of one console (Switch updates / DLC). */
    @Query("SELECT * FROM downloadable_files WHERE consoleId = :consoleId")
    suspend fun filesOf(consoleId: String): List<DownloadableFileEntity>

    /** The few columns callers that only match or list names need, without the URLs and torrent fields of [filesOf]. */
    @Query("SELECT id, consoleId, name, fileName FROM downloadable_files WHERE consoleId = :consoleId")
    suspend fun fileRefsOf(consoleId: String): List<DownloadableFileRef>

    /** Full rows of the given ids (at most a few hundred per call). */
    @Query("SELECT * FROM downloadable_files WHERE id IN (:ids)")
    suspend fun filesByIds(ids: List<Long>): List<DownloadableFileEntity>

    /**
     * Replaces the rows of one source (and, the first time after the update to 2.0, the console's
     * rows from before sources were remembered) with [files] in one transaction. A file the source
     * listed before keeps when it was first seen; a file it did not list before gets [now] — unless
     * the source had no rows yet, then nothing counts as new (its first scan).
     */
    @Transaction
    suspend fun replaceSource(consoleId: String, sourceUrl: String, files: List<DownloadableFileEntity>, tags: List<List<String>>, now: Long): SourceWrite {
        val seen = HashMap<String, Long>()
        seenIn(consoleId, sourceUrl).forEach { seen[it.fileName] = maxOf(seen[it.fileName] ?: 0L, it.firstSeenAt) }
        val known = seen.isNotEmpty()
        deleteSource(consoleId, sourceUrl)
        deleteSource(consoleId, "")
        val stamped = files.map { it.copy(consoleId = consoleId, sourceUrl = sourceUrl, firstSeenAt = seen[it.fileName] ?: if (known) now else 0L) }
        return SourceWrite(insertSource(stamped, tags), stamped.count { it.firstSeenAt == now })
    }

    @Query("SELECT id, name FROM downloadable_files WHERE searchKey = ''")
    suspend fun getFilesMissingSearchKey(): List<FileIdName>

    @Query("UPDATE downloadable_files SET searchKey = :searchKey WHERE id = :id")
    suspend fun updateSearchKey(id: Long, searchKey: String)

    /** Fills [DownloadableFileEntity.searchKey] for rows indexed before the column existed. */
    @Transaction
    suspend fun backfillSearchKeys() {
        getFilesMissingSearchKey().forEach { updateSearchKey(it.id, SearchNormalizer.key(it.name)) }
    }

    /** Rows from before 4.6 whose [DownloadableFileEntity.searchWords] is still empty, one page after [afterId] (a rowid range scan). */
    @Query("SELECT id, name FROM downloadable_files WHERE id > :afterId AND searchWords = '' AND searchKey <> '' ORDER BY id LIMIT :limit")
    suspend fun filesMissingSearchWords(afterId: Long, limit: Int): List<FileIdName>

    @Query("UPDATE downloadable_files SET searchWords = :words WHERE id = :id")
    suspend fun updateSearchWords(id: Long, words: String)

    /** True when no row is waiting for its [DownloadableFileEntity.searchWords], i.e. the FTS index is complete. */
    @Query("SELECT NOT EXISTS (SELECT 1 FROM downloadable_files WHERE searchWords = '' AND searchKey <> '')")
    suspend fun searchWordsComplete(): Boolean

    /**
     * Fills the words of one page of old rows; each update refreshes the FTS index through Room's sync triggers.
     * One transaction per page keeps every commit short and lets a scan or search run in between.
     * Returns the last id handled, or null when no row was left.
     */
    @Transaction
    suspend fun backfillSearchWordsPage(afterId: Long, limit: Int): Long? {
        val page = filesMissingSearchWords(afterId, limit)
        page.forEach { updateSearchWords(it.id, SearchNormalizer.words(it.name)) }
        return page.lastOrNull()?.id
    }

    /** Rebuilds the FTS index from the table (repair after a failure; the content table is the truth). */
    @Query("INSERT INTO downloadable_files_fts(downloadable_files_fts) VALUES('rebuild')")
    suspend fun rebuildFts()

    @Query("DELETE FROM downloadable_file_tags WHERE fileId IN (SELECT id FROM downloadable_files WHERE consoleId = :consoleId)")
    suspend fun deleteTagsByConsoleId(consoleId: String)

    @Query("DELETE FROM downloadable_files WHERE consoleId = :consoleId")
    suspend fun deleteFilesByConsoleIdInternal(consoleId: String)

    @Transaction
    suspend fun deleteFilesByConsoleId(consoleId: String) {
        deleteTagsByConsoleId(consoleId)
        deleteFilesByConsoleIdInternal(consoleId)
    }
}

/** Light projection of a row: identity and names only (see [DownloadableFileDao.fileRefsOf]). */
data class DownloadableFileRef(
    val id: Long,
    val consoleId: String,
    val name: String,
    val fileName: String
)

data class DownloadableFileWithTagsResult(
    val id: Long,
    val name: String,
    val fileName: String,
    val consoleId: String,
    val downloadUrl: String,
    val fileSize: Long,
    val fileExtension: String,
    val torrentFileIndex: Int?,
    val torrentMagnet: String?,
    val expectedHash: String?,
    val firstSeenAt: Long,
    val tags: String?
)

data class ConsoleWithFileCount(
    val id: String,
    val name: String,
    val manufacturerId: String,
    val urls: String,
    val fileCount: Int
)

data class FileIdName(val id: Long, val name: String)

/** What [DownloadableFileDao.replaceSource] wrote: tag rows, and files the source did not list before. */
data class SourceWrite(val tags: Int, val newFiles: Int)

data class FileSeen(val fileName: String, val firstSeenAt: Long)

data class FileTagRow(val fileId: Long, val tag: String)

data class IndexedSource(val consoleId: String, val sourceUrl: String)

data class ConsoleFileCount(val consoleId: String, val count: Int)

/** The light columns read by [DownloadableFileDao.lightPage]. */
data class LightFileRow(val id: Long, val consoleId: String, val fileName: String, val name: String)

/** The statement as Room's `@RawQuery` wants it (bind arguments are Int/Long/String only). */
fun LibraryQueryBuilder.Built.toSupportQuery(): SupportSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())
