/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.Executors

/** Process-owned temporary native snapshots, never cloud journals or retained backups.
 * The OS releases the exclusive owner lock on death. A new process sweeps only
 * abandoned import/export directories before it creates any new candidate.
 * A second live process cannot sweep the first process's active previews.
 */
internal object NativeCatalogStaging {
    private class Owner(val channel: FileChannel, val lock: FileLock)
    private val owners = HashMap<Path, Owner>()
    private val cleanup = Executors.newSingleThreadExecutor { Thread(it, "NewPipe-staging-cleanup").apply { isDaemon = true } }

    fun startup(context: Context) {
        cleanup.execute { runCatching { initialize(context) } }
        // Preparation retries and reports an error if initialization failed.
    }

    @Synchronized
    fun initialize(context: Context) {
        val root = context.noBackupFilesDir.toPath().toAbsolutePath().normalize()
        if (owners.containsKey(root)) return
        check(Files.isDirectory(root, NOFOLLOW_LINKS)) { "NATIVE_STAGING_UNAVAILABLE" }
        val path = root.resolve(".rv-native-staging-owner")
        check(!Files.isSymbolicLink(path)) { "NATIVE_STAGING_UNAVAILABLE" }
        val channel = FileChannel.open(path, CREATE, WRITE)
        var lock: FileLock? = null
        try {
            lock = channel.tryLock()
            check(lock != null) { "NATIVE_STAGING_IN_USE" }
            Files.list(root).use { entries ->
                entries.forEach { entry ->
                    val name = entry.fileName.toString()
                    if (Regex("tag-(import|export)-[A-Za-z0-9-]+").matches(name) && Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                        // File-swap journals hold independent copies in the database
                        // directory. They never reference these ephemeral inputs.
                        CatalogFileSwap.deleteTree(entry)
                    }
                }
            }
            owners[root] = Owner(channel, lock)
        } catch (error: Throwable) {
            lock?.release()
            channel.close()
            throw error
        }
    }

    @Synchronized
    fun create(context: Context, kind: String): Path {
        require(kind in setOf("import", "export"))
        initialize(context)
        return Files.createTempDirectory(context.noBackupFilesDir.toPath(), "tag-$kind-")
    }
}
