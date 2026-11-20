package com.tufein.valkyr.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration17To18Test {
    @Test fun `the migration only appends columns with defaults`() {
        val plan = Migration17To18.plan()
        assertEquals(3, plan.size)
        assertTrue(plan.all { it.startsWith("ALTER TABLE `download_history` ADD COLUMN") })
        assertTrue(plan.all { it.contains("NOT NULL DEFAULT ''") })
        assertTrue(plan.none { it.contains("DROP") || it.contains("DELETE") || it.contains("RENAME") })
    }

    @Test fun `the entity defaults match the migration`() {
        // Room compares these literals with the column definition when it opens an old database.
        assertEquals("ALTER TABLE `download_history` ADD COLUMN `sourceUrl` TEXT NOT NULL DEFAULT ''", Migration17To18.plan()[0])
        assertEquals("ALTER TABLE `download_history` ADD COLUMN `originalSourceUrl` TEXT NOT NULL DEFAULT ''", Migration17To18.plan()[1])
        assertEquals("ALTER TABLE `download_history` ADD COLUMN `switchedFrom` TEXT NOT NULL DEFAULT ''", Migration17To18.plan()[2])
    }
}
