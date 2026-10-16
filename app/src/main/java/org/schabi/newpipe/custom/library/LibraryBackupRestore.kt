/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipInputStream
import kotlin.io.path.deleteIfExists
import org.schabi.newpipe.settings.export.BackupFileLocator
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.StoredFileHelper

/**
 * Reads a NewPipe ZIP backup ONCE, staging its database and optional Library
 * identity together before replacing the installed database. A single pass is
 * important for one-shot streams and for preserving upstream import tests.
 *
 * The caller must restart NewPipe after importing, as upstream already does.
 */
object LibraryBackupRestore {
    @JvmStatic
    fun extractDbAndBinding(file: StoredFileHelper, locator: BackupFileLocator): Boolean {
        val tempDb = Files.createTempFile(locator.db.parent, ".newpipe-restore-", ".db")
        val tempBinding = Files.createTempFile(locator.db.parent, ".library-restore-", ".binding")

        try {
            var containsDb = false
            var containsBinding = false

            ZipInputStream(SharpInputStream(file.stream).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        BackupFileLocator.FILE_NAME_DB -> {
                            check(!containsDb && !entry.isDirectory) {
                                "Invalid backup: duplicate or directory database entry"
                            }
                            Files.copy(zip, tempDb, REPLACE_EXISTING)
                            containsDb = true
                        }

                        LibraryBindingStore.FILE_NAME -> {
                            check(!containsBinding && !entry.isDirectory) {
                                "Invalid backup: duplicate or directory Library binding"
                            }
                            Files.copy(zip, tempBinding, REPLACE_EXISTING)
                            check(Files.size(tempBinding) <= 4096) {
                                "Invalid backup: Library binding exceeds 4 KiB"
                            }
                            containsBinding = true
                        }
                    }
                    zip.closeEntry()
                }
            }

            if (!containsDb) return false

            // A database replacement invalidates the previous Library identity.
            // Always fail closed if the supplied ZIP has no matching binding.
            LibraryRepository.suspendForRestore()
            LibraryBindingStore.deleteForDatabase(locator.db)
            Files.move(tempDb, locator.db, REPLACE_EXISTING)

            if (containsBinding) {
                Files.move(
                    tempBinding,
                    LibraryBindingStore.pathForDatabase(locator.db),
                    REPLACE_EXISTING
                )
            }

            locator.dbJournal.deleteIfExists()
            locator.dbWal.deleteIfExists()
            locator.dbShm.deleteIfExists()
            return true
        } finally {
            tempDb.deleteIfExists()
            tempBinding.deleteIfExists()
        }
    }
}
