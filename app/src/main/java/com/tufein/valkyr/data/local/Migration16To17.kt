package com.tufein.valkyr.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 9.0: schema 16 -> 17, per-game metadata overrides ("Fix the match").
 *
 * A table `game_overrides` keyed `consoleId|stem` (like covers and RomM marks) so a corrected
 * title, year, genre or pinned cover survives a rescan. Only the fields the user actually changed
 * are stored; the rest stay automatic. Nothing is overwritten on restore of an older backup that
 * lacks the section.
 */
object Migration16To17 : Migration(16, 17) {

    internal const val CREATE_GAME_OVERRIDES =
        "CREATE TABLE IF NOT EXISTS `game_overrides` (" +
            "`key` TEXT NOT NULL PRIMARY KEY, " +
            "`title` TEXT, " +
            "`year` INTEGER, " +
            "`genre` TEXT, " +
            "`pinnedCoverUrl` TEXT, " +
            "`updatedAt` INTEGER NOT NULL DEFAULT 0)"

    internal val STATEMENTS = listOf(CREATE_GAME_OVERRIDES)

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_GAME_OVERRIDES)
    }

    /** Pure list of statements, so the shape is unit-tested without a database. */
    fun plan(): List<String> = STATEMENTS.toList()
}