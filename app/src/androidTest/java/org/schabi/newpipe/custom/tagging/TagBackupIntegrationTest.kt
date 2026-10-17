/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.streams.io.StoredFileHelper

/** File-backed Android SQLite tests in per-test private cache directories; never user databases. */
class TagBackupIntegrationTest {
    private lateinit var directory: Path
    private lateinit var context: Context
    private lateinit var repository: TagRepository
    private lateinit var db: AppDatabase

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        TagRuntime.restoring = false
        NewPipeDatabase.close()
        LibraryRepository.forgetSessionForTest()
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "tag-backup-native-")
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = if (name.startsWith("/")) File(name) else directory.resolve(name).toFile()
            override fun getCacheDir(): File = directory.toFile()
        }
        db = NewPipeDatabase.getInstance(context)
        repository = TagRuntime.get(context)
        repository.definitions()
    }

    @After fun cleanup() {
        CatalogFileSwap.fault = null
        NewPipeDatabase.close()
        TagRuntime.restoring = false
        LibraryRepository.forgetSessionForTest()
        CatalogFileSwap.deleteTree(directory)
    }

    private fun item(suffix: String, tag: Long): StreamEntity {
        val stream = StreamEntity(serviceId = 0, url = "https://example.org/$suffix", title = suffix, streamType = StreamType.VIDEO_STREAM, duration = 60, uploader = "Synthetic")
        repository.apply(stream, setOf(tag), 0)
        return repository.snapshot().items.first { it.stream.url == stream.url }.stream
    }
    private fun key(stream: StreamEntity) = StreamKey(stream.serviceId, stream.url)
    private fun file(path: Path) = StoredFileHelper(context, null, Uri.fromFile(path.toFile()), "synthetic-qa")
    private fun exported(): Path {
        val target = directory.resolve("export-${UUID.randomUUID()}.zip")
        Files.createFile(target)
        val prefs = context.getSharedPreferences("synthetic-tag-export-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        TagBackupCoordinator.export(context, prefs, file(target))
        return target
    }
    private fun reopen() {
        // Simulate a fresh process's singleton state without deleting any catalog data.
        TagRuntime.restoring = false
        LibraryRepository.forgetSessionForTest()
        db = NewPipeDatabase.getInstance(context)
        repository = TagRuntime.get(context)
    }
    private fun zipWithoutTags(source: Path): Path {
        val target = directory.resolve("stock-${UUID.randomUUID()}.zip")
        ZipFile(source.toFile()).use { input ->
            ZipOutputStream(Files.newOutputStream(target)).use { output ->
                for (entry in input.entries().toList().filter { it.name != TagStore.FILE_NAME }) {
                    output.putNextEntry(ZipEntry(entry.name))
                    input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        return target
    }

    @Test fun roundTripRestoresCategoryAssignmentsAndExactPlaylistOrder() {
        val category = repository.createCategory("Genre")
        val tag = repository.createTag("Jazz", category)
        val a = item("A", tag)
        val b = item("B", tag)
        val playlist = repository.createPlaylistSnapshot("B-A", listOf(key(b), key(a)))
        val backup = exported()
        ZipFile(backup.toFile()).use { zip ->
            assertNotNull(zip.getEntry(TagStore.FILE_NAME))
            assertNotNull(zip.getEntry(LibraryBindingStore.FILE_NAME))
        }
        repository.delete(repository.deletionPlan(category, true))
        TagBackupCoordinator.prepare(context, file(backup)).use { prepared ->
            var shutdown = false
            TagBackupCoordinator.installWithShutdown(context, prepared) {
                assertFalse(TagRuntime.lock.isHeldByCurrentThread)
                shutdown = true
            }
            assertTrue(shutdown)
        }
        assertThrows(IllegalStateException::class.java) { repository.definitions() }
        assertTrue(TagRuntime.restoring)
        reopen()
        assertEquals(setOf(tag), repository.editor(key(a)).assigned)
        assertEquals(listOf(b.uid, a.uid), db.playlistStreamDAO().getOrderedStreamsOf(playlist).blockingFirst().map { it.streamId })
        assertEquals(
            9001,
            db.openHelper.readableDatabase.query("PRAGMA user_version").use {
                it.moveToFirst()
                it.getInt(0)
            }
        )
    }

    @Test fun stockImportRetainsUnmatchedTagsButDiscardsOldDeletionIntents() {
        val tag = repository.createTag("Saved for later", null)
        val original = item("old", tag)
        val backup = zipWithoutTags(exported())
        val unmatched = item("not-in-backup", tag)
        val library = repository.snapshot().items.size
        assertEquals(2, library)
        repository.store.beginRemoval(key(original), LibraryRepository.boundLibraryId(db)!!)
        TagBackupCoordinator.prepare(context, file(backup)).use { prepared ->
            TagBackupCoordinator.installWithShutdown(context, prepared) {}
        }
        reopen()
        assertEquals(1, repository.snapshot().items.size)
        assertEquals(setOf(tag), repository.editor(key(original)).assigned)
        assertEquals(setOf(tag), repository.editor(key(unmatched)).assigned)
        assertEquals(1, repository.snapshot().orphanedAssignments)
        assertTrue(repository.store.pendingRemovals().isEmpty())
        assertTrue(repository.store.pendingAiRemovals().isEmpty())
    }

    @Test fun malformedDatabaseDoesNotFenceOrAlterExistingCatalog() {
        val tag = repository.createTag("Keep", null)
        val media = item("stay", tag)
        val path = directory.resolve("invalid.zip")
        ZipOutputStream(Files.newOutputStream(path)).use {
            it.putNextEntry(ZipEntry("newpipe.db"))
            it.write("not a database".toByteArray())
            it.closeEntry()
        }
        assertThrows(Exception::class.java) { TagBackupCoordinator.prepare(context, file(path)) }
        assertFalse(TagRuntime.restoring)
        assertEquals(setOf(tag), repository.editor(key(media)).assigned)
        assertEquals(1, repository.snapshot().items.size)
    }

    @Test fun exportedTagMetadataHasNoExecutableHistoryAndPreservesLiveHistory() {
        val tag = repository.createTag("Keep", null)
        val media = item("stay", tag)
        val receipt = org.schabi.newpipe.custom.aicontrol.AiCommandReceipt("d", "e", "c", "r", "f", "tags.write", "{}", Long.MAX_VALUE)
        repository.store.insertReceipt(receipt)
        val path = exported()
        assertNotNull(repository.store.findReceipt("d", "e", "c", "r"))
        TagBackupCoordinator.prepare(context, file(path)).use { p ->
            TagStore(context, p.directory.resolve(TagStore.FILE_NAME).toString()).use { restored ->
                assertEquals(setOf(tag), restored.assigned(key(media)))
                assertTrue(restored.pendingRemovals().isEmpty())
                assertTrue(restored.pendingAiRemovals().isEmpty())
                assertEquals(null, restored.findReceipt("d", "e", "c", "r"))
            }
        }
    }

    @Test fun fileSwapFaultRecoversOldRoomTagsAndBindingBeforeNextOpen() {
        val tag = repository.createTag("Before", null)
        val a = item("before", tag)
        val backup = exported()
        repository.createTag("After backup", null)
        val beforeCount = repository.definitions().tags.size
        val oldRepository = repository
        CatalogFileSwap.fault = { if (it == "new:newpipe.db") throw SyntheticDeath() }
        TagBackupCoordinator.prepare(context, file(backup)).use { prepared ->
            assertThrows(SyntheticDeath::class.java) { TagBackupCoordinator.installWithShutdown(context, prepared) {} }
        }
        assertTrue(Files.exists(directory.resolve(CatalogFileSwap.JOURNAL)))
        assertThrows(IllegalStateException::class.java) { oldRepository.editor(key(a)) }
        CatalogFileSwap.fault = null
        reopen()
        assertEquals(beforeCount, repository.definitions().tags.size)
        assertEquals(setOf(tag), repository.editor(key(a)).assigned)
        assertFalse(Files.exists(directory.resolve(CatalogFileSwap.JOURNAL)))
    }

    @Test fun tagV1MigrationPreservesDefinitionsAndAssignments() {
        val tag = repository.createTag("V1 compatible", null)
        val media = item("v1", tag)
        val target = directory.resolve("old-v1.db")
        TagBackupCoordinator.snapshotTag(repository.store, target)
        android.database.sqlite.SQLiteDatabase.openDatabase(target.toString(), null, 0).use { old ->
            old.execSQL("DROP TABLE tag_command_receipt")
            old.execSQL("DROP TABLE ai_removal_intent")
            old.version = 1
        }
        TagBackupCoordinator.sanitizeTagCopy(context, target)
        TagStore(context, target.toString()).use { upgraded ->
            assertEquals(2, upgraded.readableDatabase.version)
            assertEquals(setOf(tag), upgraded.assigned(key(media)))
        }
    }

    private class SyntheticDeath : Error()
}
