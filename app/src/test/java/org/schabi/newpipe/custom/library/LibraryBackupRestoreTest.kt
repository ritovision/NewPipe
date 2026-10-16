/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import android.content.SharedPreferences
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.schabi.newpipe.settings.export.BackupFileLocator
import org.schabi.newpipe.settings.export.ImportExportManager
import org.schabi.newpipe.streams.io.StoredFileHelper
import us.shandian.giga.io.FileStream

class LibraryBackupRestoreTest {
    private lateinit var folder: Path
    private lateinit var dbFile: Path
    private lateinit var bindingFile: Path
    private lateinit var locator: BackupFileLocator
    private val validDb by lazy {
        val fixture = Paths.get(
            javaClass.classLoader!!.getResource("settings/db_ser_json.zip")!!.toURI()
        )
        ZipFile(fixture.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry(BackupFileLocator.FILE_NAME_DB))
                .use { it.readAllBytes() }
        }
    }

    @Before
    fun setup() {
        folder = Files.createTempDirectory("np-library-restore-")
        dbFile = folder.resolve(BackupFileLocator.FILE_NAME_DB)
        bindingFile = LibraryBindingStore.pathForDatabase(dbFile)
        locator = Mockito.mock(BackupFileLocator::class.java)
        `when`(locator.db).thenReturn(dbFile)
        `when`(locator.dbWal).thenReturn(folder.resolve("newpipe.db-wal"))
        `when`(locator.dbShm).thenReturn(folder.resolve("newpipe.db-shm"))
        `when`(locator.dbJournal).thenReturn(folder.resolve("newpipe.db-journal"))
    }

    @After
    fun cleanup() {
        Files.walk(folder).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun makeZip(vararg entries: Pair<String, ByteArray>): Path {
        val result = Files.createTempFile(folder, "source-", ".zip")
        ZipOutputStream(Files.newOutputStream(result)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return result
    }

    private fun stored(path: Path): StoredFileHelper = Mockito.mock(StoredFileHelper::class.java).also {
        `when`(it.stream).thenAnswer { FileStream(path.toFile()) }
    }

    private fun assertNoStagingFiles() {
        Files.list(folder).use { paths ->
            assertEquals(
                0L,
                paths.filter {
                    val name = it.fileName.toString()
                    name.startsWith(".newpipe-restore-") ||
                        name.startsWith(".library-restore-") ||
                        name.startsWith(".pre-library-restore-")
                }.count()
            )
        }
    }

    @Test
    fun customBackupRestoresDatabaseAndPairedBinding() {
        Files.write(dbFile, "OLD_DATABASE".toByteArray())
        Files.write(bindingFile, "playlist_id=44\nname=Library\n".toByteArray())
        Files.write(locator.dbWal, "WAL".toByteArray())
        val newBinding = "playlist_id=7\nname=Library\n".toByteArray()
        val zip = makeZip(
            BackupFileLocator.FILE_NAME_DB to validDb,
            LibraryBindingStore.FILE_NAME to newBinding
        )

        assertTrue(LibraryBackupRestore.extractDbAndBinding(stored(zip), locator))
        assertArrayEquals(validDb, Files.readAllBytes(dbFile))
        assertArrayEquals(newBinding, Files.readAllBytes(bindingFile))
        assertFalse(locator.dbWal.exists())
        assertNoStagingFiles()
    }

    @Test
    fun stockBackupDoesNotKeepStaleLibraryBinding() {
        Files.write(dbFile, "OLD_DATABASE".toByteArray())
        Files.write(bindingFile, "playlist_id=4\nname=Library\n".toByteArray())
        val zip = makeZip(BackupFileLocator.FILE_NAME_DB to validDb)

        assertTrue(LibraryBackupRestore.extractDbAndBinding(stored(zip), locator))
        assertArrayEquals(validDb, Files.readAllBytes(dbFile))
        assertFalse(bindingFile.exists())
        assertNoStagingFiles()
    }

    @Test
    fun missingDatabaseLeavesExistingDataUntouched() {
        val old = "OLD_DB".toByteArray()
        val binding = "playlist_id=4\nname=Library\n".toByteArray()
        Files.write(dbFile, old)
        Files.write(bindingFile, binding)
        val zip = makeZip("preferences.json" to "{}".toByteArray())

        assertFalse(LibraryBackupRestore.extractDbAndBinding(stored(zip), locator))
        assertArrayEquals(old, Files.readAllBytes(dbFile))
        assertArrayEquals(binding, Files.readAllBytes(bindingFile))
        assertNoStagingFiles()
    }

    private fun rejectsBeforeWrite(vararg entries: Pair<String, ByteArray>) {
        rejectsBeforeWriteZip(makeZip(*entries))
    }

    private fun rejectsBeforeWriteZip(zip: Path) {
        val old = "OLD_DB".toByteArray()
        Files.write(dbFile, old)
        assertThrows(IllegalStateException::class.java) {
            LibraryBackupRestore.extractDbAndBinding(stored(zip), locator)
        }
        assertArrayEquals(old, Files.readAllBytes(dbFile))
        assertNoStagingFiles()
    }

    @Test
    fun oversizedBindingCannotModifyDatabase() = rejectsBeforeWrite(
        BackupFileLocator.FILE_NAME_DB to validDb,
        LibraryBindingStore.FILE_NAME to ByteArray(4097) { 'x'.code.toByte() }
    )

    @Test
    fun wrongBindingNameCannotModifyDatabase() = rejectsBeforeWrite(
        BackupFileLocator.FILE_NAME_DB to validDb,
        LibraryBindingStore.FILE_NAME to "playlist_id=4\nname=Workout\n".toByteArray()
    )

    @Test
    fun malformedDatabaseCannotReplaceCurrentDatabase() = rejectsBeforeWrite(
        BackupFileLocator.FILE_NAME_DB to "NOT_SQLITE".toByteArray()
    )

    @Test
    fun duplicateDatabaseEntriesAreRejected() {
        // ZipOutputStream disallows duplicate names. Give the second entry a
        // same-length name, then replace its name bytes in the completed ZIP.
        // ZIP payload CRCs do not cover entry names.
        val zip = makeZip(
            BackupFileLocator.FILE_NAME_DB to validDb,
            "newpipe.xx" to validDb
        )
        val data = Files.readAllBytes(zip)
        val from = "newpipe.xx".toByteArray()
        val to = BackupFileLocator.FILE_NAME_DB.toByteArray()
        var substitutions = 0
        for (position in 0..data.size - from.size) {
            if (from.indices.all { data[position + it] == from[it] }) {
                to.copyInto(data, position)
                substitutions++
            }
        }
        assertTrue(substitutions >= 2)
        Files.write(zip, data)
        rejectsBeforeWriteZip(zip)
    }

    @Test
    fun customExportPreservesTheLibraryIdentitySidecar() {
        Files.write(dbFile, validDb)
        val binding = "playlist_id=1\nname=Library\n".toByteArray()
        Files.write(bindingFile, binding)
        val prefs = Mockito.mock(SharedPreferences::class.java)
        `when`(prefs.all).thenReturn(mapOf<String, Any>())
        val output = Files.createTempFile(folder, "export-", ".zip")
        val helper = Mockito.mock(StoredFileHelper::class.java)
        `when`(helper.openAndTruncateStream()).thenAnswer { FileStream(output.toFile()) }

        ImportExportManager(locator).exportDatabase(prefs, helper)

        ZipFile(output.toFile()).use { zip ->
            assertEquals(4, zip.entries().toList().size)
            val entry = zip.getEntry(LibraryBindingStore.FILE_NAME)
            assertTrue(entry != null)
            assertArrayEquals(binding, zip.getInputStream(entry).use { it.readAllBytes() })
        }
        assertNoStagingFiles()
    }
}
