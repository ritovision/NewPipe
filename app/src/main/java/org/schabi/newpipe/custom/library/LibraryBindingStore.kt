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

    data class Binding(val playlistId: Long, val name: String)

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
        return Binding(id, name)
    }

    fun write(binding: Binding) {
        require(binding.playlistId > 0 && binding.name == LIBRARY_NAME)
        val values = Properties().apply {
            setProperty("playlist_id", binding.playlistId.toString())
            setProperty("name", binding.name)
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
