/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.Kind
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.NativeId
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.custom.library.UnavailableRepository
import org.schabi.newpipe.custom.tagging.CatalogFileSwap
import org.schabi.newpipe.custom.tagging.StreamKey
import org.schabi.newpipe.custom.tagging.TagBackupCoordinator
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.custom.tagging.TagStore
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.streams.io.StoredFileHelper

/** All content is synthetic and file-backed under the separate QA package cache. */
class CloudNativeCatalogTest {
    private lateinit var dir: Path
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: TagRepository
    private val scope = CloudIdentityStore.Scope("cloud-test", "library-test")
    private fun key(s: StreamEntity) = CloudKey(s.serviceId, s.url)
    private fun nativeKey(s: StreamEntity) = StreamKey(s.serviceId, s.url)
    private fun capture(incarnation: String? = null) = CloudNativeCatalog(repository).capture(scope, incarnation)

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        NewPipeDatabase.close()
        TagRuntime.restoring = false
        LibraryRepository.forgetSessionForTest()
        UnavailableRepository.reset()
        dir = Files.createTempDirectory(app.cacheDir.toPath(), "cloud-native-")
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = if (name.startsWith("/")) File(name) else dir.resolve(name).toFile()
            override fun getCacheDir(): File = dir.toFile()
        }
        open()
        repository.definitions() // Explicit existing native initialization, never hidden inside cloud capture.
    }
    private fun open() {
        db = NewPipeDatabase.getInstance(context)
        repository = TagRuntime.get(context)
    }

    @After fun cleanup() {
        NewPipeDatabase.close()
        TagRuntime.restoring = false
        LibraryRepository.forgetSessionForTest()
        UnavailableRepository.reset()
        CatalogFileSwap.deleteTree(dir)
    }
    private fun reopen() {
        NewPipeDatabase.close()
        TagRuntime.restoring = false
        LibraryRepository.forgetSessionForTest()
        UnavailableRepository.reset()
        open()
    }
    private fun media(name: String, assigned: Set<Long> = emptySet()): StreamEntity {
        val s = StreamEntity(serviceId = 0, url = "https://example.test/$name", title = name, streamType = StreamType.VIDEO_STREAM, duration = 42, uploader = "Synthetic")
        val id = TagRuntime.mutate(
            db,
            java.util.concurrent.Callable {
                db.runInTransaction(
                    java.util.concurrent.Callable {
                        val uid = db.streamDAO().upsert(s)
                        LibraryRepository.appendDirectly(db, checkNotNull(LibraryRepository.boundLibraryId(db)), listOf(uid))
                        uid
                    }
                )
            }
        )
        val saved = s.copy(uid = id)
        if (assigned.isNotEmpty()) repository.apply(saved, assigned, repository.store.revision(nativeKey(saved)))
        return saved
    }
    private fun playlist(name: String?, items: List<StreamEntity>, rank: Long = 0, permanent: StreamEntity? = null): Long = TagRuntime.mutate(
        db,
        java.util.concurrent.Callable {
            db.runInTransaction(
                java.util.concurrent.Callable {
                    val id = db.playlistDAO().insert(PlaylistEntity(name = name, isThumbnailPermanent = permanent != null, thumbnailStreamId = permanent?.uid ?: -1, displayIndex = rank))
                    db.playlistStreamDAO().insertAll(items.mapIndexed { index, stream -> PlaylistStreamEntity(id, stream.uid, index) })
                    id
                }
            )
        }
    )
    private fun main(sql: String, vararg args: Any) = TagRuntime.runMutation(db, Runnable { db.openHelper.writableDatabase.execSQL(sql, args) })
    private fun mapCount(): Int = repository.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM cloud_catalog_identity", null).use {
        it.moveToFirst()
        it.getInt(0)
    }
    private fun exported(): Path {
        val target = dir.resolve("backup-${UUID.randomUUID()}.zip")
        Files.createFile(target)
        val prefs = context.getSharedPreferences("cloud-fixture-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        TagBackupCoordinator.export(context, prefs, file(target))
        return target
    }
    private fun file(p: Path) = StoredFileHelper(context, null, Uri.fromFile(p.toFile()), "cloud-native-test")

    @Test fun realSnapshotPreservesOrderDuplicatesTagsAndPermanentArtwork() {
        val category = repository.createCategory("Genre")
        val tag = repository.createTag("Jazz", category)
        val a = media("A", setOf(tag))
        val b = media("B")
        val c = media("C")
        val p = playlist("Mix", listOf(b, a, a), permanent = c)
        main("INSERT INTO playlist_stream_join(playlist_id,stream_id,join_index) VALUES(?,?,0)", checkNotNull(UnavailableRepository.boundId(db)), a.uid)
        val result = capture()
        assertEquals(listOf(key(a), key(b), key(c)), result.catalog.library)
        assertEquals(listOf(key(a)), result.catalog.unavailable)
        val list = result.catalog.playlists.single()
        assertEquals(result.nativeIds[NativeId(Kind.PLAYLIST, p)], list.id)
        assertEquals(listOf(key(b), key(a), key(a)), list.items)
        assertEquals(key(c), list.artwork)
        assertEquals("Jazz", result.catalog.tags.single().name)
        assertEquals(listOf(result.catalog.tags.single().id), result.catalog.assignments.single().tags)
        assertEquals("A", result.hints[key(a)]?.title)
        assertEquals(3, mapCount())
        assertEquals(CloudCatalogCodec.fingerprint(result.catalog), result.fingerprint)
    }

    @Test fun identitiesPersistAcrossRenameReorderAndReopen() {
        val a = media("A")
        val b = media("B")
        val p = playlist("Same", listOf(a, b))
        val cat = repository.createCategory("Before")
        val tag = repository.createTag("Old", cat)
        val before = capture()
        main("UPDATE playlists SET name=? WHERE uid=?", "Renamed", p)
        main("UPDATE playlist_stream_join SET join_index=join_index+10 WHERE playlist_id=?", p)
        main("UPDATE playlist_stream_join SET join_index=CASE stream_id WHEN ? THEN 1 ELSE 0 END WHERE playlist_id=?", a.uid, p)
        repository.renameCategory(cat, "After")
        repository.editTag(tag, "New", cat)
        reopen()
        val next = capture(before.identities.incarnation)
        assertEquals(before.nativeIds, next.nativeIds)
        assertEquals(before.identities, next.identities)
        assertEquals(listOf(key(b), key(a)), next.catalog.playlists.single().items)
        assertNotEquals(before.fingerprint, next.fingerprint)
    }

    @Test fun sameNameNativePlaylistsNeverShareIdentity() {
        val a = media("A")
        val one = playlist("Same", listOf(a))
        val two = playlist("Same", listOf(a))
        val s = capture()
        assertNotEquals(s.nativeIds[NativeId(Kind.PLAYLIST, one)], s.nativeIds[NativeId(Kind.PLAYLIST, two)])
    }

    @Test fun nativeCacheAndDisplayIndexGapsDoNotChangeFingerprint() {
        val a = media("A")
        val b = media("B")
        val p = playlist("P", listOf(a), 2)
        val q = playlist("Q", listOf(b), 8)
        val before = capture()
        main("UPDATE streams SET title=?,uploader=?,thumbnail_url=? WHERE uid=?", "Fresh title", "Fresh uploader", "https://example.test/new.jpg", a.uid)
        main("UPDATE playlists SET thumbnail_stream_id=? WHERE uid=?", b.uid, p)
        main("UPDATE playlists SET display_index=? WHERE uid=?", 20L, p)
        main("UPDATE playlists SET display_index=? WHERE uid=?", 80L, q)
        val next = capture()
        assertEquals(before.fingerprint, next.fingerprint)
        assertEquals("Fresh title", next.hints[key(a)]?.title)
        main("UPDATE playlists SET is_thumbnail_permanent=1 WHERE uid=?", p)
        assertNotEquals(before.fingerprint, capture().fingerprint)
    }

    @Test fun remoteBookmarksNeverEnterPortableCatalogOrChangeRelativeRank() {
        val a = media("A")
        playlist("zulu", listOf(a), 3)
        playlist("Alpha", listOf(a), 3)
        playlist(null, emptyList(), 3)
        val before = capture()
        assertEquals(listOf("Alpha", "zulu", null), before.catalog.playlists.map { it.name })
        db.playlistRemoteDAO().insert(PlaylistRemoteEntity(serviceId = 0, orderingName = "Remote", url = "https://example.test/remote", thumbnailUrl = null, uploader = null, displayIndex = 3, streamCount = 100))
        assertEquals(before.fingerprint, capture().fingerprint)
    }

    @Test fun orphanAssignmentsRemainLocalAndAreReportedSeparately() {
        val tag = repository.createTag("Old", null)
        val a = media("A", setOf(tag))
        val orphan = StreamKey(0, "https://example.test/orphan")
        repository.store.setTags(orphan, setOf(tag), 0)
        val revision = repository.store.version()
        val s = capture()
        assertEquals(listOf(key(a)), s.catalog.assignments.map { it.key })
        assertEquals(CloudKey(0, orphan.url), s.orphaned.single().key)
        assertEquals(listOf(s.catalog.tags.single().id), s.orphaned.single().tagIds)
        assertEquals(setOf(tag), repository.store.assigned(orphan))
        assertEquals(revision, repository.store.version())
    }

    @Test fun missingBindingDoesNotCreateOrAdoptProtectedPlaylists() {
        media("A")
        val before = db.playlistDAO().count.blockingFirst()
        LibraryBindingStore.deleteForDatabase(context.getDatabasePath(AppDatabase.DATABASE_NAME).toPath())
        assertThrows(IllegalStateException::class.java) { capture() }
        assertEquals(before, db.playlistDAO().count.blockingFirst())
        assertEquals(0, mapCount())
        assertNull(CloudIdentityStore.context(repository.store.readableDatabase))
    }

    @Test fun missingUnavailableDesignationIsNotAutomaticallyCreated() {
        val binding = repository.binding.read()!!
        repository.binding.write(binding.copy(unavailablePlaylistId = null))
        assertThrows(IllegalStateException::class.java) { capture() }
        assertNull(repository.binding.read()!!.unavailablePlaylistId)
    }

    @Test fun staleBindingIsRejectedEvenWhenNativeSessionWasCached() {
        val binding = repository.binding.read()!!
        db.openHelper.writableDatabase.execSQL("UPDATE playlists SET name='Other' WHERE uid=?", arrayOf(binding.playlistId))
        assertThrows(IllegalStateException::class.java) { capture() }
        assertEquals(0, mapCount())
    }

    @Test fun reservedOrdinaryNameBlocksWithoutRenamingIt() {
        playlist("\u00a0UNAVAILABLE\u2003", emptyList())
        assertThrows(IllegalStateException::class.java) { capture() }
        assertEquals(0, mapCount())
        assertTrue(db.playlistDAO().getAll().blockingFirst().any { it.name == "\u00a0UNAVAILABLE\u2003" })
    }

    @Test fun pendingRecoveryIsNotExecutedByCloudSnapshot() {
        val a = media("A")
        val library = LibraryRepository.boundLibraryId(db)!!
        repository.store.beginRemoval(nativeKey(a), library)
        assertThrows(IllegalStateException::class.java) { capture() }
        assertEquals(1, repository.store.pendingRemovals().size)
        assertEquals(1, db.playlistStreamDAO().getOrderedStreamsOf(library).blockingFirst().size)
    }

    @Test fun invalidCatalogRollsBackNewMappingsAndContext() {
        val a = media("A")
        val p = playlist("Valid", listOf(a))
        val tag = repository.createTag("Valid", null)
        repository.store.writableDatabase.execSQL("UPDATE tag SET name_key='wrong' WHERE id=?", arrayOf(tag))
        assertThrows(IllegalArgumentException::class.java) { capture() }
        assertEquals(0, mapCount())
        assertNull(CloudIdentityStore.context(repository.store.readableDatabase))
        assertEquals("Valid", db.playlistDAO().getPlaylist(p).blockingFirst().single().name)
    }

    @Test fun duplicateProtectedMembershipIsNotSilentlyDeduplicated() {
        val a = media("A")
        main("INSERT INTO playlist_stream_join(playlist_id,stream_id,join_index) VALUES(?,?,1)", LibraryRepository.boundLibraryId(db)!!, a.uid)
        assertThrows(IllegalArgumentException::class.java) { capture() }
        assertEquals(2, db.playlistStreamDAO().getOrderedStreamsOf(LibraryRepository.boundLibraryId(db)!!).blockingFirst().size)
    }

    @Test fun unresolvedPermanentArtworkIsNotSilentlyErased() {
        val a = media("A")
        val p = playlist("P", listOf(a), permanent = a)
        main("UPDATE playlists SET thumbnail_stream_id=999999 WHERE uid=?", p)
        assertThrows(IllegalStateException::class.java) { capture() }
        assertEquals(999999L, db.playlistDAO().getPlaylist(p).blockingFirst().single().thumbnailStreamId)
    }

    @Test fun deletedMappingsArePrunedButNewSameNameUsesNewUuid() {
        val a = media("A")
        val p = playlist("Same", listOf(a))
        val old = capture()
        main("DELETE FROM playlists WHERE uid=?", p)
        assertTrue(capture().catalog.playlists.isEmpty())
        assertEquals(0, mapCount())
        val newId = playlist("Same", listOf(a))
        val next = capture()
        assertNotEquals(p, newId)
        assertNotEquals(old.catalog.playlists.single().id, next.catalog.playlists.single().id)
    }

    @Test fun scopeAndIncarnationNeverSwitchImplicitly() {
        media("A")
        val before = capture()
        assertThrows(IllegalStateException::class.java) { CloudNativeCatalog(repository).capture(CloudIdentityStore.Scope("other", "library-test")) }
        assertThrows(IllegalStateException::class.java) { capture(UUID.randomUUID().toString()) }
        assertEquals(before.identities, CloudIdentityStore.context(repository.store.readableDatabase))
    }

    @Test fun changedVerifiedBindingRequiresExplicitReenrollment() {
        media("A")
        capture()
        val binding = repository.binding.read()!!
        // Simulate an explicit persisted rebind completed outside this capture API.
        main("UPDATE playlists SET name='Former' WHERE uid=?", binding.unavailablePlaylistId!!)
        val newId = playlist("Unavailable", emptyList())
        repository.binding.write(binding.copy(unavailablePlaylistId = newId))
        assertThrows(IllegalStateException::class.java) { capture() }
    }

    @Test fun mainThreadRejectedBeforeOpeningOrMapping() {
        val error = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                capture()
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        assertTrue(error.get() is IllegalStateException)
        assertEquals("CLOUD_IO_ON_MAIN_THREAD", error.get()?.message)
        assertEquals(0, mapCount())
    }

    @Test fun captureWaitsForExistingWriterAndSeesCompleteCommit() {
        val a = media("A")
        val p = playlist("Before", listOf(a))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<CloudNativeSnapshot?>()
        val error = AtomicReference<Throwable?>()
        val writer = thread {
            TagRuntime.lock.withLock {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                db.openHelper.writableDatabase.execSQL("UPDATE playlists SET name='After' WHERE uid=?", arrayOf(p))
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val reader = thread {
            try {
                result.set(capture())
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        Thread.sleep(100)
        assertNull(result.get())
        release.countDown()
        writer.join(10_000)
        reader.join(10_000)
        assertFalse(writer.isAlive)
        assertFalse(reader.isAlive)
        assertNull(error.get())
        assertEquals("After", result.get()!!.catalog.playlists.single().name)
    }

    @Test fun mappingTransactionFailureCannotPersistPartialScope() {
        val a = media("A")
        playlist("P", listOf(a))
        val old = capture()
        TagRuntime.lock.withLock {
            assertThrows(IllegalStateException::class.java) {
                repository.store.transaction { side ->
                    CloudIdentityStore.discard(side)
                    CloudIdentityStore.reconcile(side, scope, old.identities.libraryId, old.identities.unavailableId, null, setOf(NativeId(Kind.TAG, 500)))
                    error("synthetic interruption before commit")
                }
            }
        }
        assertEquals(old.identities, capture().identities)
        assertEquals(old.nativeIds, capture().nativeIds)
    }

    @Test fun exporterRemovesMappingsOnlyFromStagedCopy() {
        val a = media("A")
        playlist("P", listOf(a))
        val tag = repository.createTag("Keep", null)
        val live = capture()
        val backup = exported()
        assertEquals(live.identities, capture().identities)
        TagBackupCoordinator.prepare(context, file(backup)).use { prepared ->
            TagStore(context, prepared.directory.resolve(TagStore.FILE_NAME).toString()).use { restored ->
                assertNull(CloudIdentityStore.context(restored.readableDatabase))
                assertTrue(CloudIdentityStore.mappings(restored.readableDatabase).isEmpty())
                assertEquals(tag, restored.definitions().tags.single().id)
            }
        }
        assertEquals(live.nativeIds, capture().nativeIds)
    }

    @Test fun fullRestoreInvalidatesOldMapContextAndOldCaptureHandle() {
        val a = media("A")
        playlist("P", listOf(a))
        val old = capture()
        val reader = CloudNativeCatalog(repository)
        val backup = exported()
        TagBackupCoordinator.prepare(context, file(backup)).use { TagBackupCoordinator.installWithShutdown(context, it) {} }
        assertThrows(IllegalStateException::class.java) { reader.capture(scope) }
        reopen()
        assertThrows(IllegalStateException::class.java) { capture(old.identities.incarnation) }
        val next = capture()
        assertNotEquals(old.identities.incarnation, next.identities.incarnation)
        assertEquals(old.catalog.library, next.catalog.library)
        assertNotEquals(old.nativeIds, next.nativeIds)
    }

    @Test fun stockRestoreAlsoClearsRetainedTagMappingsButPreservesOrphans() {
        val tag = repository.createTag("Keep", null)
        media("A", setOf(tag))
        val old = capture()
        val complete = exported()
        val stock = dir.resolve("stock.zip")
        ZipFile(complete.toFile()).use { input ->
            ZipOutputStream(Files.newOutputStream(stock)).use { output ->
                for (entry in input.entries().toList().filter { it.name != TagStore.FILE_NAME }) {
                    output.putNextEntry(ZipEntry(entry.name))
                    input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        val orphan = StreamKey(0, "https://example.test/later")
        repository.store.setTags(orphan, setOf(tag), 0)
        TagBackupCoordinator.prepare(context, file(stock)).use { TagBackupCoordinator.installWithShutdown(context, it) {} }
        reopen()
        assertNull(CloudIdentityStore.context(repository.store.readableDatabase))
        assertEquals(setOf(tag), repository.store.assigned(orphan))
        assertNotEquals(old.identities.incarnation, capture().identities.incarnation)
    }

    @Test fun schemaOneTwoThreeUpgradePreservesDefinitionsAndAssignments() {
        for (version in 1..3) {
            val path = dir.resolve("legacy-$version.db").toString()
            TagStore(context, path).use { store ->
                val category = store.createCategory("Genre")
                val tag = store.createTag("Keep", category)
                store.setTags(StreamKey(0, "https://example.test/legacy"), setOf(tag), 0)
                val raw = store.writableDatabase
                raw.execSQL("DROP TABLE cloud_catalog_identity")
                raw.execSQL("DROP TABLE cloud_catalog_context")
                if (version < 3) raw.execSQL("DROP TABLE native_bulk_intent")
                if (version < 2) {
                    raw.execSQL("DROP TABLE ai_removal_intent")
                    raw.execSQL("DROP TABLE tag_command_receipt")
                }
                raw.version = version
            }
            TagStore(context, path).use { restored ->
                assertEquals(4, restored.writableDatabase.version)
                assertEquals("Genre", restored.definitions().categories.single().name)
                assertEquals("Keep", restored.definitions().tags.single().name)
                assertEquals(1, restored.assigned(StreamKey(0, "https://example.test/legacy")).size)
                assertNull(CloudIdentityStore.context(restored.readableDatabase))
                assertTrue(CloudIdentityStore.mappings(restored.readableDatabase).isEmpty())
                restored.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            }
        }
    }

    @Test fun realDatabaseScaleCaptureDoesNotRewriteNativeCatalog() {
        val started = System.nanoTime()
        val libraryId = LibraryRepository.boundLibraryId(db)!!
        val category = repository.createCategory("Scale")
        val tag = repository.createTag("Synthetic", category)
        TagRuntime.lock.withLock {
            db.runInTransaction {
                val main = db.openHelper.writableDatabase
                val playlists = (1..5).map { db.playlistDAO().insert(PlaylistEntity(name = "P$it", isThumbnailPermanent = false, thumbnailStreamId = -1, displayIndex = it.toLong())) }
                val insertStream = main.compileStatement("INSERT INTO streams(uid,service_id,url,title,stream_type,duration,uploader) VALUES(?,0,?,?,'VIDEO_STREAM',42,'Synthetic')")
                val insertJoin = main.compileStatement("INSERT INTO playlist_stream_join(playlist_id,stream_id,join_index) VALUES(?,?,?)")
                insertStream.use { s ->
                    insertJoin.use { j ->
                        for (i in 1..20_000) {
                            s.bindLong(1, i.toLong())
                            s.bindString(2, "https://example.test/scale/$i")
                            s.bindString(3, "Item $i")
                            s.executeInsert()
                            for (p in listOf(libraryId) + playlists) {
                                j.bindLong(1, p)
                                j.bindLong(2, i.toLong())
                                j.bindLong(3, (i - 1).toLong())
                                j.executeInsert()
                            }
                        }
                    }
                }
            }
            repository.store.transaction { side ->
                side.compileStatement("INSERT INTO assignment VALUES(0,?,?)").use { s ->
                    for (i in 1..20_000) {
                        s.bindString(1, "https://example.test/scale/$i")
                        s.bindLong(2, tag)
                        s.executeInsert()
                    }
                }
            }
        }
        val seeded = System.nanoTime()
        val tagRevision = repository.store.version()
        val one = capture()
        val captured = System.nanoTime()
        val two = capture(one.identities.incarnation)
        assertEquals(20_000, one.catalog.library.size)
        assertEquals(100_000, one.catalog.playlists.sumOf { it.items.size })
        assertEquals(20_000, one.catalog.assignments.size)
        assertEquals(one.fingerprint, two.fingerprint)
        assertEquals(one.nativeIds, two.nativeIds)
        assertEquals(tagRevision, repository.store.version())
        assertEquals(7, mapCount())
        assertEquals(
            9001,
            db.openHelper.readableDatabase.query("PRAGMA user_version").use {
                it.moveToFirst()
                it.getInt(0)
            }
        )
        println("CLOUD_NATIVE_SCALE streams=20000 ordinary_memberships=100000 assignments=20000 canonical_bytes=${one.canonicalBytes().size} seed_ms=${(seeded - started) / 1_000_000} capture_ms=${(captured - seeded) / 1_000_000} repeat_ms=${(System.nanoTime() - captured) / 1_000_000}")
    }
}
