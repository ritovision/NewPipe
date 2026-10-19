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
import java.util.Properties
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
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.bookmark.MergedPlaylistManager
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
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

    /** Fixture convenience only: production callers must choose enroll vs required-incarnation capture. */
    private fun capture(incarnation: String? = null): CloudNativeSnapshot {
        val expected = incarnation ?: CloudIdentityStore.context(repository.store.readableDatabase)?.incarnation
        val reader = CloudNativeCatalog(repository)
        return if (expected == null) reader.enroll(scope) else reader.capture(scope, expected)
    }
    private fun rejects(code: String, operation: () -> Unit) {
        assertEquals(code, assertThrows(IllegalStateException::class.java, operation).message)
    }
    private fun roomContentDigest(): String = CloudRoomContentDigest.of(db.openHelper.readableDatabase)

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
        assertEquals(42L, result.hints[key(a)]?.duration)
        assertEquals("VIDEO_STREAM", result.hints[key(a)]?.streamType)
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
        rejects("IDENTITY_REPAIR_REQUIRED") { capture() }
        assertEquals(before, db.playlistDAO().count.blockingFirst())
        assertEquals(0, mapCount())
        assertNull(CloudIdentityStore.context(repository.store.readableDatabase))
    }

    @Test fun missingUnavailableDesignationIsNotAutomaticallyCreated() {
        val binding = repository.binding.read()!!
        repository.binding.write(binding.copy(unavailablePlaylistId = null))
        rejects("IDENTITY_REPAIR_REQUIRED") { capture() }
        assertNull(repository.binding.read()!!.unavailablePlaylistId)
    }

    @Test fun staleBindingIsRejectedEvenWhenNativeSessionWasCached() {
        val binding = repository.binding.read()!!
        db.openHelper.writableDatabase.execSQL("UPDATE playlists SET name='Other' WHERE uid=?", arrayOf(binding.playlistId))
        rejects("IDENTITY_REPAIR_REQUIRED") { capture() }
        assertEquals(0, mapCount())
    }

    @Test fun reservedOrdinaryNameBlocksWithoutRenamingIt() {
        playlist("\u00a0UNAVAILABLE\u2003", emptyList())
        rejects("IDENTITY_REPAIR_REQUIRED") { capture() }
        assertEquals(0, mapCount())
        assertTrue(db.playlistDAO().getAll().blockingFirst().any { it.name == "\u00a0UNAVAILABLE\u2003" })
    }

    @Test fun pendingRecoveryIsNotExecutedByCloudSnapshot() {
        val a = media("A")
        val library = LibraryRepository.boundLibraryId(db)!!
        repository.store.beginRemoval(nativeKey(a), library)
        rejects("APPLY_RECOVERY_REQUIRED") { capture() }
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
        rejects("NATIVE_ARTWORK_UNRESOLVED:$p") { capture() }
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
        assertThrows(IllegalStateException::class.java) { CloudNativeCatalog(repository).capture(CloudIdentityStore.Scope("other", "library-test"), before.identities.incarnation) }
        rejects("CLOUD_INCARNATION_CHANGED") { capture(UUID.randomUUID().toString()) }
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
        rejects("CLOUD_BINDING_CHANGED") { capture() }
    }

    @Test fun mainThreadRejectedBeforeOpeningOrMapping() {
        val error = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                CloudNativeCatalog(repository).enroll(scope)
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
                    CloudIdentityStore.reconcile(side, scope, old.identities.libraryId, old.identities.unavailableId, null, setOf(NativeId(Kind.TAG, 500)), enrollment = true)
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
        assertThrows(IllegalStateException::class.java) { reader.capture(scope, old.identities.incarnation) }
        reopen()
        rejects("CLOUD_ENROLLMENT_REQUIRED") { capture(old.identities.incarnation) }
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
        val roomBefore = roomContentDigest()
        val one = capture()
        val captured = System.nanoTime()
        val two = capture(one.identities.incarnation)
        assertEquals(20_000, one.catalog.library.size)
        assertEquals(100_000, one.catalog.playlists.sumOf { it.items.size })
        assertEquals(20_000, one.catalog.assignments.size)
        assertEquals(one.fingerprint, two.fingerprint)
        assertEquals(one.nativeIds, two.nativeIds)
        assertEquals(roomBefore, roomContentDigest())
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

    @Test fun captureCannotEnrollOrReplaceExistingEnrollment() {
        val reader = CloudNativeCatalog(repository)
        rejects("CLOUD_ENROLLMENT_REQUIRED") { reader.capture(scope, UUID.randomUUID().toString()) }
        assertEquals(0, mapCount())
        assertNull(repository.binding.read()!!.cloudIncarnation)
        val enrolled = reader.enroll(scope)
        rejects("CLOUD_ALREADY_ENROLLED") { reader.enroll(scope) }
        assertEquals(enrolled.identities, reader.capture(scope, enrolled.identities.incarnation).identities)
    }

    @Test fun recreatedMainDatabaseCannotReuseSurvivingTagIdentities() {
        val p = playlist("Old", listOf(media("old")))
        val old = capture()
        assertEquals(1L, old.identities.libraryId)
        assertEquals(2L, old.identities.unavailableId)
        assertEquals(3L, p)
        NewPipeDatabase.close()
        TagRuntime.restoring = false
        LibraryRepository.forgetSessionForTest()
        UnavailableRepository.reset()
        val path = context.getDatabasePath(AppDatabase.DATABASE_NAME).toPath()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + suffix))
        open()
        repository.definitions()
        val q = playlist("Unrelated", listOf(media("new")))
        assertEquals(p, q)
        assertNull(repository.binding.read()!!.cloudIncarnation)
        rejects("CLOUD_MAIN_DATABASE_CHANGED") { CloudNativeCatalog(repository).capture(scope, old.identities.incarnation) }
        assertEquals(old.nativeIds, CloudIdentityStore.mappings(repository.store.readableDatabase))
        rejects("CLOUD_ALREADY_ENROLLED") { CloudNativeCatalog(repository).enroll(scope) }
    }

    @Test fun sameRoleIdsWithoutBindingMarkerDoNotProveDatabaseLifetime() {
        playlist("P", listOf(media("A")))
        val old = capture()
        repository.binding.write(repository.binding.read()!!.copy(cloudIncarnation = null, cloudPlaylistSequence = null))
        rejects("CLOUD_MAIN_DATABASE_CHANGED") { capture(old.identities.incarnation) }
        assertEquals(old.nativeIds, CloudIdentityStore.mappings(repository.store.readableDatabase))
    }

    @Test fun playlistSequenceRegressionBlocksOldMapsEvenWithMatchingMarker() {
        val p = playlist("P", listOf(media("A")))
        main("INSERT INTO playlists(uid,name,is_thumbnail_permanent,thumbnail_stream_id,display_index) VALUES(100,'High',0,-1,1)")
        val old = capture()
        assertEquals(100L, repository.binding.read()!!.cloudPlaylistSequence)
        main("DELETE FROM playlists WHERE uid=100")
        main("UPDATE sqlite_sequence SET seq=? WHERE name='playlists'", p)
        rejects("CLOUD_PLAYLIST_SEQUENCE_REGRESSED") { capture(old.identities.incarnation) }
        assertEquals(old.nativeIds, CloudIdentityStore.mappings(repository.store.readableDatabase))
    }

    @Test fun sameLibraryRebindAndUnavailableBindingCopyPreserveLifetimeAnchor() {
        media("A")
        val old = capture()
        val binding = repository.binding.read()!!
        LibraryRepository.rebindExisting(db, repository.binding, binding.playlistId)
        assertEquals(binding, repository.binding.read())
        repository.binding.write(repository.binding.read()!!.copy(unavailablePlaylistId = binding.unavailablePlaylistId))
        assertEquals(old.identities, capture(old.identities.incarnation).identities)
    }

    @Test fun ignoredProtectedArtworkDoesNotBlockCapture() {
        media("A")
        val old = capture()
        val binding = repository.binding.read()!!
        main("UPDATE playlists SET is_thumbnail_permanent=1,thumbnail_stream_id=999999 WHERE uid IN (?,?)", binding.playlistId, binding.unavailablePlaylistId!!)
        assertEquals(old.fingerprint, capture().fingerprint)
        assertEquals(999999L, db.playlistDAO().getPlaylist(binding.playlistId).blockingFirst().single().thumbnailStreamId)
    }

    @Test fun pruningOldMappingsRollsBackAfterLateContentValidationFailure() {
        val tag = repository.createTag("Keep", null)
        val p = playlist("Remove", listOf(media("A")))
        val old = capture()
        val binding = repository.binding.read()
        main("DELETE FROM playlists WHERE uid=?", p)
        repository.store.writableDatabase.execSQL("UPDATE tag SET name_key='WRONG' WHERE id=?", arrayOf(tag))
        assertEquals("INVALID_CATALOG:stored tag key", assertThrows(IllegalArgumentException::class.java) { capture() }.message)
        assertEquals(old.nativeIds, CloudIdentityStore.mappings(repository.store.readableDatabase))
        assertEquals(binding, repository.binding.read())
    }

    @Test fun failedEnrollmentAfterBindingPersistDoesNotCreateTrustedMaps() {
        playlist("P", listOf(media("A")))
        rejects("synthetic-after-binding") { CloudNativeCatalog(repository) { error("synthetic-after-binding") }.enroll(scope) }
        val orphanMarker = repository.binding.read()!!.cloudIncarnation
        assertTrue(orphanMarker != null)
        assertNull(CloudIdentityStore.context(repository.store.readableDatabase))
        assertEquals(0, mapCount())
        rejects("CLOUD_ENROLLMENT_REQUIRED") { CloudNativeCatalog(repository).capture(scope, orphanMarker!!) }
        val next = CloudNativeCatalog(repository).enroll(scope)
        assertNotEquals(orphanMarker, next.identities.incarnation)
        assertEquals(next.identities.incarnation, repository.binding.read()!!.cloudIncarnation)
    }

    @Test fun failedCaptureAfterHighWaterAdvanceKeepsExistingIdentity() {
        playlist("P", listOf(media("A")))
        val old = capture()
        val q = playlist("Later", emptyList())
        val oldMap = old.nativeIds
        rejects("synthetic-after-binding") { CloudNativeCatalog(repository) { error("synthetic-after-binding") }.capture(scope, old.identities.incarnation) }
        assertEquals(q, repository.binding.read()!!.cloudPlaylistSequence)
        assertEquals(oldMap, CloudIdentityStore.mappings(repository.store.readableDatabase))
        val next = capture(old.identities.incarnation)
        assertEquals(old.identities.incarnation, next.identities.incarnation)
        assertTrue(next.nativeIds.containsKey(NativeId(Kind.PLAYLIST, q)))
    }

    @Test fun realMergedPlaylistOrderingMatchesPortableRank() {
        val a = media("A")
        for ((name, rank) in listOf("Zulu" to 3L, "alpha" to 3L, "ALPHA" to 3L, null to 3L, "Zero" to 0L)) playlist(name, listOf(a), rank)
        db.playlistRemoteDAO().insert(PlaylistRemoteEntity(serviceId = 0, orderingName = "Remote", url = "https://example.test/remote-order", thumbnailUrl = null, uploader = null, displayIndex = 3, streamCount = 1))
        val expected = MergedPlaylistManager.merge(LocalPlaylistManager(db).playlists.blockingFirst(), db.playlistRemoteDAO().getAll().blockingFirst())
            .filterIsInstance<PlaylistMetadataEntry>().filter { it.uid != LibraryRepository.boundLibraryId(db) && it.uid != UnavailableRepository.boundId(db) }.map { it.uid }
        val s = capture()
        assertEquals(expected.map { s.nativeIds[NativeId(Kind.PLAYLIST, it)] }, s.catalog.playlists.map { it.id })
    }

    @Test fun durationAndTypeHintsUpdateWithoutChangingSemanticContent() {
        val a = media("A")
        val before = capture()
        main("UPDATE streams SET duration=999,stream_type='AUDIO_STREAM' WHERE uid=?", a.uid)
        val next = capture()
        assertEquals(before.fingerprint, next.fingerprint)
        assertEquals(999L, next.hints[key(a)]!!.duration)
        assertEquals("AUDIO_STREAM", next.hints[key(a)]!!.streamType)
    }

    @Test fun legacyV3TagArchiveCompletesPrepareSanitizeAndInstall() {
        val tag = repository.createTag("Legacy", null)
        val a = media("A", setOf(tag))
        playlist("P", listOf(a))
        val old = capture()
        val original = exported()
        val legacyTags = dir.resolve("legacy-v3-tags.db")
        TagRuntime.lock.withLock { TagBackupCoordinator.snapshotTag(repository.store, legacyTags) }
        SQLiteDatabase.openDatabase(legacyTags.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { legacy ->
            legacy.execSQL("DROP TABLE cloud_catalog_identity")
            legacy.execSQL("DROP TABLE cloud_catalog_context")
            legacy.version = 3
        }
        val zip = dir.resolve("legacy-v3.zip")
        ZipFile(original.toFile()).use { input ->
            ZipOutputStream(Files.newOutputStream(zip)).use { output ->
                for (entry in input.entries().toList()) {
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == TagStore.FILE_NAME) Files.copy(legacyTags, output) else input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        TagBackupCoordinator.prepare(context, file(zip)).use { prepared ->
            TagStore(context, prepared.directory.resolve(TagStore.FILE_NAME).toString()).use { staged ->
                assertEquals(TagStore.SCHEMA_VERSION, staged.readableDatabase.version)
                assertNull(CloudIdentityStore.context(staged.readableDatabase))
                assertTrue(CloudIdentityStore.mappings(staged.readableDatabase).isEmpty())
            }
            val props = Properties().also { p -> Files.newInputStream(prepared.directory.resolve(LibraryBindingStore.FILE_NAME)).use { p.load(it) } }
            assertEquals(null to null, LibraryBindingStore.cloudMarker(props))
            TagBackupCoordinator.installWithShutdown(context, prepared) {}
        }
        reopen()
        assertEquals(setOf(tag), repository.store.assigned(nativeKey(a)))
        rejects("CLOUD_ENROLLMENT_REQUIRED") { CloudNativeCatalog(repository).capture(scope, old.identities.incarnation) }
        val next = CloudNativeCatalog(repository).enroll(scope)
        assertEquals(old.catalog.library, next.catalog.library)
        assertNotEquals(old.identities.incarnation, next.identities.incarnation)
    }

    @Test fun roomContentDigestDetectsMetadataAndNullVersusEmpty() {
        val a = media("Digest")
        val first = roomContentDigest()
        assertEquals(first, roomContentDigest())
        main("UPDATE streams SET title='Changed' WHERE uid=?", a.uid)
        val titleChanged = roomContentDigest()
        assertNotEquals(first, titleChanged)
        main("UPDATE streams SET thumbnail_url='' WHERE uid=?", a.uid)
        val empty = roomContentDigest()
        assertNotEquals(titleChanged, empty)
        main("UPDATE streams SET thumbnail_url=NULL WHERE uid=?", a.uid)
        assertEquals(titleChanged, roomContentDigest())
    }
}
