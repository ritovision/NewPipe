/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.io.path.deleteIfExists
import org.schabi.newpipe.custom.aicontrol.AiDatabaseMigration
import org.schabi.newpipe.settings.export.BackupFileLocator
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.StoredFileHelper

/**
 * One-pass ZIP extraction with complete staging BEFORE touching the installed
 * NewPipe database. Copy old DB/binding/WAL companions for error rollback.
 *
 * This does not make two independent files crash-atomic. A crash during swap
 * can leave an unbound Library; the user must explicitly rebind it.
 * Global removal requires a verified bound Library and refuses unbound IDs.
 */
object LibraryBackupRestore {
    private const val MAX_BINDING_SIZE = 4096
    private val SQLITE_MAGIC = byteArrayOf(
        0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00
    )

    @JvmStatic
    fun extractDbAndBinding(file: StoredFileHelper, locator: BackupFileLocator): Boolean {
        val directory = locator.db.parent
        val tempDb = Files.createTempFile(directory, ".newpipe-restore-", ".db")
        val tempBinding = Files.createTempFile(directory, ".library-restore-", ".binding")
        val created = mutableListOf(tempDb, tempBinding)

        try {
            var foundDb = false
            var foundBinding = false

            ZipInputStream(SharpInputStream(file.stream).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        BackupFileLocator.FILE_NAME_DB -> {
                            check(!foundDb && !entry.isDirectory) {
                                "Invalid backup: duplicate or directory database entry"
                            }
                            Files.copy(zip, tempDb, REPLACE_EXISTING)
                            foundDb = true
                        }

                        LibraryBindingStore.FILE_NAME -> {
                            check(!foundBinding && !entry.isDirectory) {
                                "Invalid backup: duplicate or directory Library binding"
                            }
                            // Stream with a hard bound to avoid allocating an
                            // arbitrarily large entry before checking its size.
                            Files.newOutputStream(tempBinding).use { output ->
                                val buffer = ByteArray(1024)
                                var total = 0
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count == -1) break
                                    total += count
                                    check(total <= MAX_BINDING_SIZE) {
                                        "Invalid backup: Library binding exceeds 4 KiB"
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                            foundBinding = true
                        }
                    }
                    zip.closeEntry()
                }
            }

            if (!foundDb) return false
            requireSupportedDatabase(tempDb)
            if (foundBinding) validateBinding(tempBinding)

            val binding = LibraryBindingStore.pathForDatabase(locator.db)
            val bindingNew = binding.resolveSibling(binding.fileName.toString() + ".new")
            val bindingBak = binding.resolveSibling(binding.fileName.toString() + ".bak")

            fun snapshot(path: Path): Path? {
                if (!Files.exists(path)) return null
                val temp = Files.createTempFile(directory, ".pre-library-restore-", ".bak")
                created.add(temp)
                Files.copy(path, temp, REPLACE_EXISTING)
                return temp
            }

            // Stage the rollback set before touching either installed file.
            val previousDb = snapshot(locator.db)
            val previousBinding = snapshot(binding)
            val previousBindingNew = snapshot(bindingNew)
            val previousBindingBak = snapshot(bindingBak)
            val previousWal = snapshot(locator.dbWal)
            val previousShm = snapshot(locator.dbShm)
            val previousJournal = snapshot(locator.dbJournal)

            LibraryRepository.suspendForRestore()
            try {
                Files.move(tempDb, locator.db, REPLACE_EXISTING)
                LibraryBindingStore.deleteForDatabase(locator.db)
                if (foundBinding) {
                    Files.move(tempBinding, binding, REPLACE_EXISTING)
                }

                locator.dbJournal.deleteIfExists()
                locator.dbWal.deleteIfExists()
                locator.dbShm.deleteIfExists()
                return true
            } catch (error: Exception) {
                // Attempt to return to the exact pre-import files; don't
                // discard the original error if rollback itself also fails.
                try {
                    restoreFile(previousDb, locator.db)
                    restoreFile(previousBinding, binding)
                    restoreFile(previousBindingNew, bindingNew)
                    restoreFile(previousBindingBak, bindingBak)
                    restoreFile(previousJournal, locator.dbJournal)
                    restoreFile(previousWal, locator.dbWal)
                    restoreFile(previousShm, locator.dbShm)
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
                throw error
            }
        } finally {
            for (path in created) path.deleteIfExists()
        }
    }

    private fun requireSupportedDatabase(path: Path) {
        val header = Files.newInputStream(path).use { it.readNBytes(64) }
        check(header.size == 64 && header.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) {
            "Invalid backup: bad or truncated SQLite database header"
        }
        // SQLite file header: the application user_version is big-endian at bytes 60..63.
        // Reject a future schema BEFORE replacing the live files, not at the next app restart.
        val schemaVersion = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(60)
        check(schemaVersion in 0..AiDatabaseMigration.VERSION) {
            "This backup requires a newer version of the app"
        }
    }

    private fun validateBinding(path: Path) {
        val properties = Properties()
        Files.newInputStream(path).use { properties.load(it) }
        val id = properties.getProperty("playlist_id")?.toLongOrNull()
        check(
            id != null && id > 0 &&
                properties.getProperty("name") == LibraryBindingStore.LIBRARY_NAME
        ) {
            "Invalid backup: unrecognized Library identity"
        }
    }

    private fun restoreFile(snapshot: Path?, target: Path) {
        if (snapshot != null) {
            Files.copy(snapshot, target, REPLACE_EXISTING)
        } else {
            target.deleteIfExists()
        }
    }
}
