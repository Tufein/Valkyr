package com.tufein.valkyr.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every string a user can see exists in all seven languages, and every language only translates
 * strings that still exist. Technical constants (MIME types, HTTP headers, certificates) are
 * marked translatable="false" and are compared against nothing.
 */
class StringResourceParityTest {
    private val locales = listOf("values-nl", "values-de", "values-fr", "values-es", "values-it", "values-pt")

    private val entry = Regex("""<(string|plurals|string-array)\s+[^>]*?name="([^"]+)"[^>]*?>""")

    @Test fun `every translatable resource is translated in all six languages`() {
        val base = names("values")
        assertTrue("no translatable resources found", base.isNotEmpty())
        val problems = locales.mapNotNull { locale ->
            val translated = names(locale)
            val missing = (base - translated).sorted()
            val stale = (translated - base).sorted()
            when {
                missing.isEmpty() && stale.isEmpty() -> null
                else -> buildString {
                    append(locale)
                    if (missing.isNotEmpty()) append(" misses ${missing.size}: ${missing.joinToString(", ")}")
                    if (stale.isNotEmpty()) append(" keeps ${stale.size} that no longer exist: ${stale.joinToString(", ")}")
                }
            }
        }
        assertTrue("Translations out of step —\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test fun `technical constants are not offered for translation`() {
        listOf("mime_octet_stream", "http_user_agent", "http_accept", "http_method_get",
            "com_google_android_gms_fonts_certs_dev", "com_google_android_gms_fonts_certs_prod")
            .forEach { assertTrue("$it must be translatable=\"false\"", it !in names("values")) }
    }

    /** Names of translatable resources in one res directory, e.g. values or values-nl. */
    private fun names(directory: String): Set<String> {
        val root = listOf(File("src/main/res/$directory"), File("app/src/main/res/$directory")).first { it.isDirectory }
        return root.listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            .orEmpty()
            .flatMap { file ->
                entry.findAll(file.readText()).mapNotNull { match ->
                    val tag = match.value
                    if (tag.contains("translatable=\"false\"")) null else match.groupValues[2]
                }
            }
            .toSet()
    }
}
