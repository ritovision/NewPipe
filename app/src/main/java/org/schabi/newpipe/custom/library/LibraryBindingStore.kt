/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import android.content.Context
import android.util.AtomicFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import org.schabi.newpipe.database.AppDatabase

/**
 * Sidecar identity for our protected Library playlist.
 *
 * This is NOT a playlist-name lookup. The file is included with the NewPipe
 * database in custom backups and discarded when importing stock backups.
 * A missing/mismatched binding must not silently adopt an unrelated playlist.
 */
class LibraryBindingStore(private val databasePath: Path) {
    constructor(context: Context) : this(
        context.getDatabasePath(AppDatabase.DATABASE_NAME).toPath()
    )

    data class Binding(
        val playlistId: Long,
        val name: String,
        val unavailablePlaylistId: Long? = null,
        val cloudIncarnation: String? = null,
        val cloudPlaylistSequence: Long? = null
    )

    private val atomicFile = AtomicFile(pathForDatabase(databasePath).toFile())

    fun read(): Binding? {
        if (!atomicFile.baseFile.exists() &&
            !atomicFile.baseFile.resolveSibling(
                atomicFile.baseFile.name + ".bak"
            ).exists()
        ) {
            return null
        }
        val properties = Properties()
        atomicFile.openRead().use { properties.load(it) }

        val id = properties.getProperty("playlist_id")?.toLongOrNull()
            ?: error("Invalid Library identity: missing playlist ID")
        val name = properties.getProperty("name")
            ?: error("Invalid Library identity: missing playlist name")
        check(id > 0 && name == LIBRARY_NAME) {
            "Invalid Library identity: unexpected ID or name"
        }
        val unavailableValue = properties.getProperty("unavailable_playlist_id")
        val unavailable = unavailableValue?.toLongOrNull()
        check(unavailableValue == null || unavailable != null)
        check(unavailable == null || unavailable > 0 && unavailable != id) {
            "Invalid Unavailable playlist identity"
        }
        val (incarnation, sequence) = cloudMarker(properties)
        return Binding(id, name, unavailable, incarnation, sequence)
    }

    fun write(binding: Binding) {
        require(binding.playlistId > 0 && binding.name == LIBRARY_NAME)
        require(binding.unavailablePlaylistId == null || binding.unavailablePlaylistId > 0 && binding.unavailablePlaylistId != binding.playlistId)
        validateCloudMarker(binding.cloudIncarnation, binding.cloudPlaylistSequence)
        val values = Properties().apply {
            setProperty("playlist_id", binding.playlistId.toString())
            setProperty("name", binding.name)
            binding.unavailablePlaylistId?.let { setProperty("unavailable_playlist_id", it.toString()) }
            binding.cloudIncarnation?.let { setProperty(CLOUD_INCARNATION, it) }
            binding.cloudPlaylistSequence?.let { setProperty(CLOUD_SEQUENCE, it.toString()) }
        }
        val stream = atomicFile.startWrite()
        try {
            values.store(stream, "RitoVision Library binding (restore with newpipe.db)")
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            atomicFile.failWrite(stream)
            throw e
        }
    }

    companion object {
        const val LIBRARY_NAME = "Library"
        const val FILE_NAME = "ritovision-library.binding"
        private const val CLOUD_INCARNATION = "cloud_incarnation"
        private const val CLOUD_SEQUENCE = "cloud_playlist_sequence"
        private val UUID_TEXT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        private fun validateCloudMarker(incarnation: String?, sequence: Long?) {
            check(
                incarnation == null && sequence == null ||
                    incarnation != null && UUID_TEXT.matches(incarnation) && sequence != null && sequence >= 0
            ) { "Invalid cloud catalog binding marker" }
        }

        /** Optional paired metadata; legacy bindings have neither field. */
        fun cloudMarker(properties: Properties): Pair<String?, Long?> {
            val incarnation = properties.getProperty(CLOUD_INCARNATION)
            val raw = properties.getProperty(CLOUD_SEQUENCE)
            val sequence = raw?.toLongOrNull()
            check(raw == null || sequence != null && raw == sequence.toString()) { "Invalid cloud catalog sequence marker" }
            validateCloudMarker(incarnation, sequence)
            return incarnation to sequence
        }

        /** Staged backup copy ONLY; never call on the live binding. No auth/state travels in ZIPs. */
        fun stripCloudMarkerFromCopy(path: Path) {
            if (!Files.exists(path)) return
            val p = Properties().also { properties -> Files.newInputStream(path).use { properties.load(it) } }
            cloudMarker(p)
            if (!p.containsKey(CLOUD_INCARNATION) && !p.containsKey(CLOUD_SEQUENCE)) return
            p.remove(CLOUD_INCARNATION)
            p.remove(CLOUD_SEQUENCE)
            Files.newOutputStream(path).use { p.store(it, "RitoVision Library binding (runtime cloud marker excluded)") }
        }

        @JvmStatic
        fun pathForDatabase(dbFile: Path): Path = dbFile.resolveSibling(FILE_NAME)

        /** Called when replacing newpipe.db from a backup (never keep the old binding). */
        @JvmStatic
        fun deleteForDatabase(dbFile: Path) {
            // This path is also used by host-JVM import tests. NIO is sufficient
            // for the AtomicFile base and its possible recovery sidecars.
            val path = pathForDatabase(dbFile)
            Files.deleteIfExists(path)
            Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + ".new"))
            Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + ".bak"))
        }
    }
}
