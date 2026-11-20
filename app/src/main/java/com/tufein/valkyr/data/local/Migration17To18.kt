package com.tufein.valkyr.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Sources tracker: schema 17 -> 18. A download remembers where it came from, which source was
 * used first and which source it was switched away from. Three trailing columns with defaults, so
 * an existing download_history row stays readable and nothing is rebuilt or deleted.
 */
object Migration17To18 : Migration(17, 18) {

    internal val STATEMENTS = listOf(
        "ALTER TABLE `download_history` ADD COLUMN `sourceUrl` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `download_history` ADD COLUMN `originalSourceUrl` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `download_history` ADD COLUMN `switchedFrom` TEXT NOT NULL DEFAULT ''"
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }

    /** Pure list of statements, so the shape is unit-tested without a database. */
    fun plan(): List<String> = STATEMENTS.toList()
}
