/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.junit.Rule
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
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
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
        assertTrue(repository.editor(key(original)).assigned.isEmpty())
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
            old.execSQL("DROP TABLE native_bulk_intent")
            old.version = 1
        }
        TagBackupCoordinator.sanitizeTagCopy(context, target)
        TagStore(context, target.toString()).use { upgraded ->
            assertEquals(3, upgraded.readableDatabase.version)
            assertEquals(setOf(tag), upgraded.assigned(key(media)))
        }
    }

    @Test fun tagV2MigrationPreservesAssignmentsAndAcceptsNewBatchTable() {
        val tag = repository.createTag("V2 compatible", null)
        val media = item("v2", tag)
        val target = directory.resolve("old-v2.db")
        TagBackupCoordinator.snapshotTag(repository.store, target)
        android.database.sqlite.SQLiteDatabase.openDatabase(target.toString(), null, 0).use { old ->
            old.execSQL("DROP TABLE native_bulk_intent")
            old.version = 2
        }
        TagBackupCoordinator.sanitizeTagCopy(context, target)
        TagStore(context, target.toString()).use { upgraded ->
            assertEquals(3, upgraded.readableDatabase.version)
            assertEquals(setOf(tag), upgraded.assigned(key(media)))
            assertTrue(upgraded.pendingNativeBulks().isEmpty())
        }
    }

    @Test fun executableTriggerCannotHideBehindASqliteLikeNameInAnImport() {
        val tag = repository.createTag("Keep", null)
        val media = item("preserved", tag)
        val source = exported()
        val evilTags = directory.resolve("trigger-tag.db")
        ZipFile(source.toFile()).use { input ->
            input.getInputStream(input.getEntry(TagStore.FILE_NAME)).use { Files.copy(it, evilTags) }
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(evilTags.toString(), null, 0).use { db ->
            db.execSQL("CREATE TRIGGER sqliteXerase AFTER INSERT ON tag BEGIN DELETE FROM assignment; END")
        }
        val target = directory.resolve("trigger-backup.zip")
        ZipFile(source.toFile()).use { input ->
            ZipOutputStream(Files.newOutputStream(target)).use { output ->
                for (entry in input.entries().toList()) {
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == TagStore.FILE_NAME) Files.copy(evilTags, output) else input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        assertThrows(IllegalStateException::class.java) { TagBackupCoordinator.prepare(context, file(target)) }
        assertFalse(TagRuntime.restoring)
        assertEquals(setOf(tag), repository.editor(key(media)).assigned)
        repository.createTag("Still safe", null)
        assertEquals(setOf(tag), repository.editor(key(media)).assigned)
    }

    @Test fun upstreamV4MigrationImportsAndReexportsWithoutRejectingLegacyDefaults() {
        val name = "tag-v4-${UUID.randomUUID()}.db"
        val targetContext = ApplicationProvider.getApplicationContext<Context>()
        val filePath = targetContext.getDatabasePath(name).toPath()
        try {
            migration.createDatabase(name, 4).use { old ->
                old.execSQL("INSERT INTO subscriptions(uid,service_id,url,name) VALUES(42,0,'https://example.org/old-channel','Retained subscription')")
            }
            val source = directory.resolve("upstream4.zip")
            ZipOutputStream(Files.newOutputStream(source)).use { output ->
                output.putNextEntry(ZipEntry("newpipe.db"))
                Files.copy(filePath, output)
                output.closeEntry()
            }
            TagBackupCoordinator.prepare(context, file(source)).use { prepared ->
                TagBackupCoordinator.installWithShutdown(context, prepared) {}
            }
            reopen()
            db.openHelper.readableDatabase.query("SELECT name,notification_mode FROM subscriptions WHERE uid=42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Retained subscription", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            assertEquals(
                9001,
                db.openHelper.readableDatabase.query("PRAGMA user_version").use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            )
            val roundTrip = exported()
            TagBackupCoordinator.prepare(context, file(roundTrip)).close()
        } finally {
            targetContext.deleteDatabase(name)
        }
    }

    @Test fun unknownExpressionDefaultAndTriggerAreRejectedBeforeMigration() {
        val target = directory.resolve("expression.db")
        TagBackupCoordinator.snapshotRoom(db.openHelper.writableDatabase, target)
        android.database.sqlite.SQLiteDatabase.openDatabase(target.toString(), null, 0).use { raw ->
            raw.execSQL("PRAGMA writable_schema=ON")
            raw.execSQL("UPDATE sqlite_master SET sql=replace(sql,'notification_mode` INTEGER NOT NULL','notification_mode` INTEGER NOT NULL DEFAULT (randomblob(8))') WHERE name='subscriptions'")
            raw.execSQL("PRAGMA writable_schema=OFF")
        }
        assertThrows(Exception::class.java) { TagBackupCoordinator.validateRoomCopy(context, target) }
        assertFalse(TagRuntime.restoring)
    }

    @Test fun malformedBindingExportDoesNotOverwriteDestination() {
        val tag = repository.createTag("Keep", null)
        item("saved", tag)
        val binding = LibraryBindingStore.pathForDatabase(context.getDatabasePath("newpipe.db").toPath())
        Files.write(binding, "playlist_id=999999\nname=Library\n".toByteArray())
        val output = directory.resolve("preexisting-backup.zip")
        Files.write(output, "old backup preserved".toByteArray())
        assertThrows(IllegalStateException::class.java) {
            TagBackupCoordinator.export(context, context.getSharedPreferences("bad-binding-test", 0), file(output))
        }
        assertEquals("old backup preserved", String(Files.readAllBytes(output)))
    }

    @Test fun restoreRollbackWaitsForLeasedRoomWriterAndPreservesItsCommittedRow() {
        val tag = repository.createTag("Keep", null)
        item("saved", tag)
        val backup = exported()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val connection = db.openHelper.writableDatabase
        val writer = Thread {
            try {
                db.beginTransaction()
                try {
                    connection.execSQL("INSERT INTO subscriptions(uid,service_id,url,name,notification_mode) VALUES(78,0,'https://example.org/inflight','In-flight commit',0)")
                    held.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        writer.start()
        assertTrue(held.await(3, TimeUnit.SECONDS))
        val releaser = Thread {
            Thread.sleep(300)
            release.countDown()
        }
        var published = false
        CatalogFileSwap.fault = { point ->
            if (point == "prepared") {
                published = true
                assertEquals(0L, finished.count)
            }
            if (point == "new:newpipe.db") throw java.io.IOException("synthetic failed swap")
        }
        try {
            TagBackupCoordinator.prepare(context, file(backup)).use { prepared ->
                assertThrows(java.io.IOException::class.java) {
                    TagBackupCoordinator.installWithShutdown(context, prepared) {
                        assertEquals(1L, finished.count)
                        releaser.start()
                    }
                }
            }
        } finally {
            release.countDown()
            writer.join(5000)
            releaser.join(5000)
            CatalogFileSwap.fault = null
        }
        assertEquals(null, failure.get())
        assertFalse(writer.isAlive)
        reopen()
        db.openHelper.readableDatabase.query("PRAGMA quick_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
        db.openHelper.readableDatabase.query("SELECT name FROM subscriptions WHERE uid=78").use {
            assertTrue(it.moveToFirst())
            assertEquals("In-flight commit", it.getString(0))
        }
        assertTrue("The rollback regression must actually publish the swap before failing", published)
        println("LEASED_ROOM_WRITE_ROLLBACK_PUBLISHED=$published")
    }

    @Test fun busyCheckpointAbortsBeforeSwapAndDoesNotDiscardWal() {
        val target = directory.resolve("busy-checkpoint.db")
        val first = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(target.toFile(), null)
        first.enableWriteAheadLogging()
        first.execSQL("CREATE TABLE test(id INTEGER PRIMARY KEY,value TEXT)")
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = Thread {
            first.beginTransactionNonExclusive()
            try {
                first.execSQL("INSERT INTO test VALUES(1,'keep')")
                held.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                first.setTransactionSuccessful()
            } finally {
                first.endTransaction()
            }
        }
        writer.start()
        assertTrue(held.await(2, TimeUnit.SECONDS))
        try {
            assertThrows(Exception::class.java) { TagBackupCoordinator.awaitClosedCheckpoint(target, 200) }
        } finally {
            release.countDown()
            writer.join(5000)
            first.close()
        }
        TagBackupCoordinator.awaitClosedCheckpoint(target)
        android.database.sqlite.SQLiteDatabase.openDatabase(target.toString(), null, 0).use { raw ->
            raw.rawQuery("SELECT value FROM test", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("keep", it.getString(0))
            }
        }
    }

    @Test fun cachedRoomStatementAndOldHandleCannotWriteAfterReplacement() {
        repository.createTag("Keep", null)
        val backup = exported()
        val old = db.openHelper.writableDatabase
        val cached = old.compileStatement("INSERT INTO subscriptions(uid,service_id,url,name,notification_mode) VALUES(79,0,'https://example.org/stale','Stale',0)")
        try {
            TagBackupCoordinator.prepare(context, file(backup)).use { TagBackupCoordinator.installWithShutdown(context, it) {} }
            assertThrows(java.util.concurrent.CancellationException::class.java) { cached.executeInsert() }
            assertThrows(java.util.concurrent.CancellationException::class.java) { old.beginTransaction() }
            reopen()
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM subscriptions WHERE uid=79").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally {
            cached.close()
        }
    }

    private class SyntheticDeath : Error()
}
