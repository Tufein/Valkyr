package com.tufein.valkyr.util

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Names, listing and rotation of the encrypted backups in the cloud:
 * `<folder>/backups/valkyr-<yyyyMMdd-HHmm>-<device>.<id>.dgxb` (`dogmatix-…` before 4.0, still read), the time in UTC (so the order is
 * the same whatever time zone each handheld is in), the device as a short slug and (6.0) a short
 * stable id of the installation. Several handhelds share one folder: each one only ever rotates
 * (deletes) its own backups, recognised by the id (two handhelds with the same model name, or a
 * name in another script, have the same slug but never the same id). Names made before 6.0 have no
 * id (`…-<device>.dgxb`): they are still listed, and rotated by the slug as before. Pure JVM for
 * the tests.
 */
object CloudBackupNames {

    const val PREFIX = "valkyr-"
    /** The prefix of backups made before 4.0; still listed, restored and rotated. */
    const val LEGACY_PREFIX = "dogmatix-"
    const val EXTENSION = ".dgxb"
    const val DEFAULT_KEEP = 7
    /** 7.5: "keep everything": rotation never deletes a backup from the server. */
    const val KEEP_ALL = 0
    /** The choices offered for "Backups to keep"; the last one keeps them all. */
    val KEEP_CHOICES = listOf(3, 5, 7, 10, 14, 30, KEEP_ALL)
    /** Hours between automatic backups. */
    const val INTERVAL_HOURS = 24

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
    private val NAME = Regex("""^(?:valkyr|dogmatix)-(\d{8}-\d{4})-([a-z0-9-]+?)(?:\.([0-9a-f]{6,12}))?\.dgxb$""", RegexOption.IGNORE_CASE)

    /** A backup name taken apart: when, the device slug, and the installation id ("" for a name made before 6.0). */
    data class Parsed(val time: Long, val slug: String, val id: String)

    /** Suffix of a backup that is still being uploaded; such files are never listed. */
    const val PART_SUFFIX = ".part"

    /** The short id of an installation (first 8 hex characters of its id), as written into names. */
    fun shortId(deviceId: String): String =
        deviceId.lowercase(Locale.ROOT).filter { it in '0'..'9' || it in 'a'..'f' }.take(8).takeIf { it.length >= 6 }.orEmpty()

    /** A backup in the cloud, as the backup list shows it. */
    data class Listed(
        val name: String,
        /** Full URL of the file. */
        val url: String,
        /** When it was made (from the name, else the server's date); 0 when unknown. */
        val createdAt: Long,
        /** Device slug from the name; empty for a file named another way. */
        val device: String,
        val size: Long?,
        val isThisDevice: Boolean,
        /** Installation id from the name; empty for a file named before 6.0 or another way. */
        val deviceId: String = ""
    )

    /** "Retroid Pocket 5" → "retroid-pocket-5": lower-case ASCII letters, digits and dashes, at most 32. */
    fun deviceSlug(name: String): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val slug = ascii.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).trim('-')
        return slug.ifEmpty { "device" }
    }

    /** [deviceId] is the installation id (see [shortId]); without one the name is the pre-6.0 form. */
    fun fileName(at: Instant, device: String, deviceId: String = ""): String {
        val id = shortId(deviceId)
        return PREFIX + STAMP.format(at.atOffset(ZoneOffset.UTC)) + "-" + deviceSlug(device) + (if (id.isEmpty()) "" else ".$id") + EXTENSION
    }

    /** The parts of a name made by [fileName] (either form); null for any other name. */
    fun parseName(name: String): Parsed? {
        val m = NAME.matchEntire(name.trim()) ?: return null
        val time = runCatching { LocalDateTime.parse(m.groupValues[1], STAMP).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: return null
        return Parsed(time, m.groupValues[2].lowercase(Locale.ROOT), m.groupValues[3].lowercase(Locale.ROOT))
    }

    /** (time in epoch ms, device slug) of a name made by [fileName]; null for any other name. */
    fun parse(name: String): Pair<Long, String>? = parseName(name)?.let { it.time to it.slug }

    /**
     * Whether a backup named like [parsed] belongs to this installation: the ids match; a name
     * without an id (pre-6.0) can only be told by the slug.
     */
    fun isMine(parsed: Parsed, deviceName: String, deviceId: String): Boolean {
        val id = shortId(deviceId)
        return if (parsed.id.isNotEmpty() && id.isNotEmpty()) parsed.id == id
        else if (parsed.id.isNotEmpty()) false
        else parsed.slug == deviceSlug(deviceName)
    }

    /**
     * The `.dgxb` files among the members of the backups folder, newest first. [members] are
     * (name, size, lastModified) of the files there; sub-folders must already be left out.
     */
    fun listing(members: List<Triple<String, Long?, Long?>>, folderUrl: String, thisDevice: String, thisDeviceId: String = ""): List<Listed> {
        return members.filter { (name, _, _) -> name.endsWith(EXTENSION, ignoreCase = true) && !name.startsWith(".") }
            .map { (name, size, modified) ->
                val parsed = parseName(name)
                Listed(
                    name = name,
                    url = WebDavPaths.child(folderUrl, name),
                    createdAt = parsed?.time ?: modified ?: 0L,
                    device = parsed?.slug.orEmpty(),
                    size = size,
                    isThisDevice = parsed != null && isMine(parsed, thisDevice, thisDeviceId),
                    deviceId = parsed?.id.orEmpty()
                )
            }
            .sortedWith(compareByDescending<Listed> { it.createdAt }.thenByDescending { it.name })
    }

    /**
     * Names of [device]'s backups among [names] to delete so its newest [keep] stay. Backups of
     * other devices and files named another way are never touched.
     */
    fun toDelete(names: List<String>, device: String, keep: Int, deviceId: String = ""): List<String> {
        if (keep == KEEP_ALL) return emptyList()
        return names.mapNotNull { name -> parseName(name)?.takeIf { isMine(it, device, deviceId) }?.let { name to it.time } }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenByDescending { it.first })
            .drop(keep.coerceAtLeast(1))
            .map { it.first }
    }

    /** Due when there is none yet or the last one is [intervalHours] old (an hour of slack for the job's timing). */
    fun isDue(last: Long, now: Long, intervalHours: Int = INTERVAL_HOURS): Boolean =
        last <= 0 || now < last || now - last >= intervalHours * 3_600_000L - 3_600_000L

    /** The next value of "Backups to keep" after a ◀ ▶ press. */
    fun shiftKeep(current: Int, delta: Int): Int {
        val i = KEEP_CHOICES.indexOf(current).takeIf { it >= 0 } ?: KEEP_CHOICES.indexOf(DEFAULT_KEEP)
        return KEEP_CHOICES[(i + delta).coerceIn(0, KEEP_CHOICES.lastIndex)]
    }
}
