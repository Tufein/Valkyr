package com.tufein.valkyr.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The public documentation must name the build that is actually in the repository. */
class ReleaseDocsTest {
    private val gradle = locate("app/build.gradle.kts")
    private val versionName = Regex("""versionName = "([^"]+)"""")
        .find(gradle.readText())!!.groupValues[1]
    private val versionCode = Regex("""versionCode = (\d+)""")
        .find(gradle.readText())!!.groupValues[1]
    private val tag = "releases/tag/v$versionName"

    @Test fun `every readme links the current release and names its build`() {
        listOf("README.md", "README.nl.md", "README.de.md", "README.fr.md", "README.es.md").forEach { name ->
            val line = locate(name).readLines().firstOrNull { it.contains(tag) }
            assertTrue("$name does not link $tag", line != null)
            assertTrue("$name does not name Android build $versionCode", line!!.contains(versionCode))
        }
    }

    @Test fun `the release notes for the current version exist`() {
        assertTrue("docs/release-notes/v$versionName.md is missing",
            locate("docs/release-notes/v$versionName.md").isFile)
    }

    /** Tests run from the module folder in Gradle and from the repository root elsewhere. */
    private fun locate(path: String): File =
        listOf(File(path), File("../$path")).first { it.isFile }
}
