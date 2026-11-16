package com.tufein.valkyr.util

import java.time.Instant

/**
 * The cloud backup steps on any [DavStore]: finding the working address and preparing the
 * folder ([connect]), sending an encrypted backup and rotating the old ones ([upload]), listing
 * ([list]) and reading one back ([download]). Encryption is [BackupCrypto]; names and rotation
 * are [CloudBackupNames]. Blocking, like the store. Pure JVM for the tests.
 *
 * Folder layout: the `.dgxb` files in `<root>/backups/`, and `<root>/sync/library.json`, where `<root>` is the
 * user's folder (default `Dogmatix`) under the server address.
 */
object CloudBackupEngine {

    const val BACKUPS = "backups"
    const val SYNC = "sync"
    /** A backup file larger than this is not downloaded (real ones are far smaller). */
    const val MAX_BACKUP_BYTES = 64L * 1024 * 1024
    /** Written and deleted again by [connect] to prove the folder is writable. */
    const val PROBE_NAME = ".dogmatix-write-test"

    /** A working connection: [serverUrl] answered as WebDAV, [rootUrl] is the app's folder. */
    data class Connection(val serverUrl: String, val rootUrl: String, val createdFolder: Boolean)

    data class Uploaded(val name: String, val url: String, val bytes: Long, val deleted: List<String>)

    fun backupsUrl(rootUrl: String): String = WebDavPaths.childCollection(rootUrl, BACKUPS)

    fun syncUrl(rootUrl: String): String = WebDavPaths.childCollection(rootUrl, SYNC)

    /**
     * Tries the addresses [WebDavPaths.candidates] gives for [server] until one answers as WebDAV
     * (a same-host redirect there is adopted), creates [folder] with its `backups` and `sync`
     * folders when missing, and checks a file can be written. Throws the most telling
     * [DavException] when no address works.
     */
    fun connect(store: DavStore, server: String, user: String, folder: String): Connection {
        val candidates = WebDavPaths.candidates(server, user)
        if (candidates.isEmpty()) throw DavException(DavProblem.BAD_URL)
        val failures = mutableListOf<DavException>()
        var found: String? = null
        for (candidate in candidates) {
            try {
                store.propfind(candidate, 0)
                found = WebDavPaths.normalizeServer(store.lastUrl.ifEmpty { candidate }) ?: candidate
                break
            } catch (e: DavException) {
                failures += e
                if (e.problem in WebDavStatus.FINAL) break
            }
        }
        val base = found ?: throw (WebDavStatus.mostRelevant(failures) ?: DavException(DavProblem.NOT_WEBDAV))
        val root = WebDavPaths.folderUrl(base, folder) ?: throw DavException(DavProblem.BAD_URL)
        val created = store.ensureCollection(root, base)
        store.ensureCollection(backupsUrl(root), root)
        store.ensureCollection(syncUrl(root), root)
        // Backups need to write: a read-only share must fail now, not at the first automatic backup.
        val probe = WebDavPaths.child(root, PROBE_NAME)
        store.put(probe, "ok".toByteArray(), "text/plain")
        runCatching { store.delete(probe) }
        return Connection(base, root, created)
    }

    /**
     * 8.2: the same check for an S3 bucket: there is nothing to discover and no folder to create (a
     * "folder" is a key prefix), so the connection is proven by writing, reading back and deleting
     * one small object under [folder].
     */
    fun connectS3(store: DavStore, target: S3Target, folder: String): Connection {
        val base = target.baseUrl
        val root = WebDavPaths.folderUrl(base, folder) ?: throw DavException(DavProblem.BAD_URL)
        val probe = WebDavPaths.child(root, PROBE_NAME)
        store.put(probe, "ok".toByteArray(), "text/plain")
        val back = store.get(probe, 64)
        runCatching { store.delete(probe) }
        if (back == null || !back.bytes.contentEquals("ok".toByteArray())) throw DavException(DavProblem.BAD_RESPONSE, 0, "the bucket did not return what was written")
        return Connection(base, root, false)
    }

    /**
     * Sends [sealed] (a [BackupCrypto.seal]ed backup) as this device's backup of [now], checks the
     * server lists it with the right size, then deletes this device's backups beyond the newest
     * [keep] (others' are never touched; a failed delete is left for next time). The file is
     * written as `<name>.part` and moved into place when the server can, so an upload that is cut
     * off never shows up as the newest backup; otherwise it is written directly. [deviceId] is this
     * installation's id: backups are told apart (and rotated) by it, not by the device name.
     */
    fun upload(
        store: DavStore, serverUrl: String, rootUrl: String, sealed: ByteArray, deviceName: String, keep: Int, now: Instant,
        deviceId: String = ""
    ): Uploaded {
        val folder = backupsUrl(rootUrl)
        store.ensureCollection(folder, WebDavPaths.normalizeServer(serverUrl) ?: rootUrl)
        val name = CloudBackupNames.fileName(now, deviceName, deviceId)
        val url = WebDavPaths.child(folder, name)
        val partUrl = url + CloudBackupNames.PART_SUFFIX
        val moved = try {
            store.put(partUrl, sealed, "application/octet-stream")
            store.move(partUrl, url)
        } catch (e: DavException) {
            // A full disk, a wrong login or a lost connection fail the direct write the same way.
            if (e.problem in WebDavStatus.FINAL || e.problem == DavProblem.NO_SPACE || e.problem == DavProblem.TOO_LARGE) throw e
            false
        }
        if (!moved) {
            store.put(url, sealed, "application/octet-stream")
            runCatching { store.delete(partUrl) }
        }
        val members = store.list(folder).filter { !it.isCollection }
        val arrived = members.firstOrNull { it.name == name }
        if (arrived?.size != null && arrived.size != sealed.size.toLong()) {
            throw DavException(DavProblem.BAD_RESPONSE, 0, "stored ${arrived.size} of ${sealed.size} bytes")
        }
        // Leftovers of this device's cut-off uploads go too (a part file is never listed as a backup).
        members.filter { it.name.endsWith(CloudBackupNames.EXTENSION + CloudBackupNames.PART_SUFFIX, ignoreCase = true) }
            .filter { part ->
                CloudBackupNames.parseName(part.name.removeSuffix(CloudBackupNames.PART_SUFFIX))?.let { CloudBackupNames.isMine(it, deviceName, deviceId) } == true
            }
            .forEach { part -> runCatching { store.delete(WebDavPaths.child(folder, part.name)) } }
        val deleted = CloudBackupNames.toDelete(members.map { it.name }, deviceName, keep, deviceId)
            .filter { old -> old != name && runCatching { store.delete(WebDavPaths.child(folder, old)) }.isSuccess }
        return Uploaded(name, url, sealed.size.toLong(), deleted)
    }

    /** The backups in the cloud, newest first (empty when the folder does not exist yet). */
    fun list(store: DavStore, rootUrl: String, deviceName: String, deviceId: String = ""): List<CloudBackupNames.Listed> {
        val folder = backupsUrl(rootUrl)
        val files = store.list(folder).filter { !it.isCollection }
        return CloudBackupNames.listing(files.map { Triple(it.name, it.size, it.lastModified) }, folder, deviceName, deviceId)
    }

    /**
     * The backup JSON of the file at [url]. Throws [DavException] ([DavProblem.NOT_FOUND] when it
     * is gone), [BackupCrypto.NotEncryptedException] or [BackupCrypto.UnreadableException].
     */
    fun download(store: DavStore, url: String, passphrase: String, iterations: Int = BackupCrypto.ITERATIONS): String {
        val file = store.get(url, MAX_BACKUP_BYTES) ?: throw DavException(DavProblem.NOT_FOUND, 404)
        return BackupCrypto.open(file.bytes, passphrase, iterations)
    }
}
