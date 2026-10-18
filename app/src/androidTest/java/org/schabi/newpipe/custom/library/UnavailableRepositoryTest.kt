/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.schabi.newpipe.custom.tagging.StreamKey
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.custom.tagging.TagStore
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.testUtil.TestDatabase
import org.schabi.newpipe.testUtil.TrampolineSchedulerRule

/** Isolated native SQLite/Room checks. No user catalog or YouTube access. */
class UnavailableRepositoryTest {
    @get:Rule val trampoline = TrampolineSchedulerRule()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var binding: LibraryBindingStore
    private lateinit var bindingPath: Path
    private lateinit var tags: TagStore
    private lateinit var repo: TagRepository
    private lateinit var manager: LocalPlaylistManager
    private var libraryId = 0L

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        LibraryRepository.forgetSessionForTest()
        TagRuntime.restoring = false
        db = TestDatabase.createReplacingNewPipeDatabase()
        bindingPath = context.cacheDir.toPath().resolve("unavailable-${System.nanoTime()}.db")
        binding = LibraryBindingStore(bindingPath)
        libraryId = LibraryRepository.initialize(db, binding)
        tags = TagStore(context, null)
        repo = TagRepository(db, tags, binding)
        TagRuntime.registerForTest(db, repo)
        manager = LocalPlaylistManager(db)
    }

    @After fun cleanup() {
        TagRuntime.unregister(db)
        tags.close()
        db.close()
        LibraryRepository.forgetSessionForTest()
        LibraryBindingStore.deleteForDatabase(bindingPath)
        TagRuntime.restoring = false
    }

    private fun stream(suffix: String) = StreamEntity(
        serviceId = 0,
        url = "https://example.org/$suffix",
        title = "Synthetic $suffix",
        streamType = StreamType.VIDEO_STREAM,
        duration = 24,
        uploader = "Test fixture"
    )
    private fun uid(stream: StreamEntity): Long = db.streamDAO().getStream(
        stream.serviceId.toLong(),
        stream.url
    ).blockingFirst().single().uid
    private fun contents(id: Long) = manager.getPlaylistStreams(id).blockingFirst().map { it.streamId }
    private fun mark(stream: StreamEntity, value: Boolean) {
        UnavailableRepository.setMarked(context, uid(stream), value).blockingAwait()
    }

    @Test fun createsStableEmptyProtectedSubset() {
        val unavailable = UnavailableRepository.boundId(db)!!
        assertTrue(contents(unavailable).isEmpty())
        assertTrue(unavailable != libraryId)
        assertEquals(unavailable, binding.read()!!.unavailablePlaylistId)
        LibraryRepository.forgetSessionForTest()
        LibraryRepository.initialize(db, binding)
        assertEquals(unavailable, UnavailableRepository.boundId(db))
        assertEquals(2, manager.playlists.blockingFirst().size)
        manager.renamePlaylist(unavailable, "Other").test().await()
            .assertError(IllegalStateException::class.java)
        manager.updatePlaylists(emptyList(), listOf(unavailable)).test().await()
            .assertError(IllegalStateException::class.java)
    }

    @Test fun markUnmarkIsIdempotentAndPreservesAllOtherMemberships() {
        val song = stream("britney")
        val playlist = manager.createPlaylist("Jams", listOf(song)).blockingGet().let {
            manager.playlists.blockingFirst().single { it.orderingName == "Jams" }.uid
        }
        val id = uid(song)
        val unavailable = UnavailableRepository.boundId(db)!!
        assertEquals(listOf(id), contents(playlist))
        mark(song, true)
        mark(song, true)
        assertEquals(listOf(id), contents(unavailable))
        assertEquals(listOf(id), contents(playlist))
        assertEquals(listOf(id), contents(libraryId))
        mark(song, false)
        mark(song, false)
        assertTrue(contents(unavailable).isEmpty())
        assertEquals(listOf(id), contents(playlist))
    }

    @Test fun staleClearFailsWithoutDestroyingNewMarkings() {
        val a = stream("a")
        val b = stream("b")
        manager.appendToPlaylist(libraryId, listOf(a, b)).blockingGet()
        mark(a, true)
        val preview = UnavailableRepository.getPreview(context).blockingGet()
        mark(b, true)
        try {
            UnavailableRepository.clear(context, preview).blockingAwait()
            fail("must reject stale confirmation")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("changed"))
        }
        assertEquals(2, contents(UnavailableRepository.boundId(db)!!).size)
        val now = UnavailableRepository.getPreview(context).blockingGet()
        UnavailableRepository.clear(context, now).blockingAwait()
        assertTrue(contents(now.playlistId).isEmpty())
        assertEquals(2, contents(libraryId).size)
    }

    @Test fun bulkGlobalRemovalPreservesDefinitionsButRemovesAllMembershipsAndAssignments() {
        val a = stream("a")
        val b = stream("b")
        val tag = repo.createTag("Bad link", null)
        repo.apply(a, setOf(tag), 0L)
        repo.apply(b, setOf(tag), 0L)
        val other = manager.createPlaylist("Mix", listOf(a, b, a)).blockingGet().let {
            manager.playlists.blockingFirst().single { it.orderingName == "Mix" }.uid
        }
        mark(a, true)
        mark(b, true)
        val preview = UnavailableRepository.getPreview(context).blockingGet()
        assertEquals(2, preview.count)
        UnavailableRepository.removeAll(context, preview).blockingAwait()
        assertTrue(contents(libraryId).isEmpty())
        assertTrue(contents(other).isEmpty())
        assertTrue(contents(preview.playlistId).isEmpty())
        assertTrue(repo.editor(StreamKey(a.serviceId, a.url)).assigned.isEmpty())
        assertTrue(repo.editor(StreamKey(b.serviceId, b.url)).assigned.isEmpty())
        assertEquals(1, repo.definitions().tags.size)
        assertTrue(tags.pendingRemovals().isEmpty())
    }

    @Test fun libraryRebindInitializesUnavailableWithoutAnotherRestart() {
        val previous = UnavailableRepository.boundId(db)!!
        // Restore-like state on the isolated in-memory test database.
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM playlists WHERE uid=?",
            arrayOf<Any>(previous)
        )
        binding.write(binding.read()!!.copy(unavailablePlaylistId = null))
        LibraryRepository.forgetSessionForTest()
        assertEquals(null, UnavailableRepository.boundId(db))
        LibraryRepository.rebindExisting(db, binding, libraryId)
        val current = UnavailableRepository.boundId(db)!!
        assertTrue(current > 0L && current != previous)
        assertEquals(current, binding.read()!!.unavailablePlaylistId)
    }

    @Test fun directUnavailableAppendUpdatesThumbnailAndSkipsDuplicates() {
        val song = stream("thumbnail-on-insert")
        val id = UnavailableRepository.boundId(db)!!
        manager.appendToPlaylist(id, listOf(song, song)).blockingGet()
        assertEquals(listOf(uid(song)), contents(id))
        assertEquals(uid(song), manager.getPlaylistThumbnailStreamId(id))
        assertEquals(listOf(uid(song)), contents(libraryId))
    }

    @Test fun confirmationsUseCorrectSingularAndPlural() {
        val one = context.resources.getQuantityString(
            org.schabi.newpipe.R.plurals.rv_unavailable_clear_message,
            1,
            1
        )
        val more = context.resources.getQuantityString(
            org.schabi.newpipe.R.plurals.rv_unavailable_remove_all_message,
            2,
            2
        )
        assertTrue(one.contains("1 marked item from"))
        assertFalse(one.contains("1 marked items"))
        assertTrue(more.contains("2 marked items from"))
    }

    @Test fun rolledBackNativeBulkNeverReplaysDeletionOrDropsTags() {
        val a = stream("rollback-a")
        val b = stream("rollback-b")
        val tag = repo.createTag("Needs review", null)
        repo.apply(a, setOf(tag), 0L)
        repo.apply(b, setOf(tag), 0L)
        mark(a, true)
        mark(b, true)
        val original = contents(libraryId)
        val preview = UnavailableRepository.getPreview(context).blockingGet()
        val second = uid(b)
        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            "CREATE TEMP TRIGGER fail_second_delete BEFORE DELETE ON playlist_stream_join " +
                "WHEN OLD.stream_id=$second BEGIN SELECT RAISE(ABORT,'synthetic batch rollback'); END"
        )
        try {
            UnavailableRepository.removeAll(context, preview).blockingAwait()
            fail("The Room transaction must roll back")
        } catch (_: Exception) {
            // expected: the second deletion fails after the first was attempted
        } finally {
            sql.execSQL("DROP TRIGGER fail_second_delete")
        }
        assertEquals(original, contents(libraryId))
        assertEquals(2, contents(preview.playlistId).size)
        assertEquals(1, tags.pendingNativeBulks().size)
        // Simulates reopening the app after a failure or process death.
        assertEquals(2, repo.snapshot().items.size)
        assertTrue(tags.pendingNativeBulks().isEmpty())
        assertEquals(setOf(tag), repo.editor(StreamKey(a.serviceId, a.url)).assigned)
        assertEquals(setOf(tag), repo.editor(StreamKey(b.serviceId, b.url)).assigned)
        assertEquals(original, contents(libraryId))
    }

    @Test fun recoverNativeBulkBeforeRoomCommitDiscardsWithoutDeleting() {
        val a = stream("staged-before")
        val tag = repo.createTag("Retain", null)
        repo.apply(a, setOf(tag), 0L)
        mark(a, true)
        tags.stageNativeBulk(
            TagStore.NativeBulk("synthetic-before", libraryId, listOf(StreamKey(a.serviceId, a.url)))
        )
        assertEquals(1, tags.pendingNativeBulks().size)
        assertEquals(1, repo.snapshot().items.size)
        assertTrue(tags.pendingNativeBulks().isEmpty())
        assertEquals(setOf(tag), repo.editor(StreamKey(a.serviceId, a.url)).assigned)
        assertEquals(listOf(uid(a)), contents(UnavailableRepository.boundId(db)!!))
    }

    @Test fun recoverNativeBulkAfterRoomCommitFinishesOnlyTagCleanup() {
        val a = stream("staged-after")
        val tag = repo.createTag("Purge", null)
        repo.apply(a, setOf(tag), 0L)
        mark(a, true)
        val key = StreamKey(a.serviceId, a.url)
        tags.stageNativeBulk(TagStore.NativeBulk("synthetic-after", libraryId, listOf(key)))
        // The Room transaction already committed. Simulate process death
        // before the independent tag database cleanup.
        assertTrue(LibraryRepository.removeEverywhere(db, libraryId, uid(a)))
        assertEquals(setOf(tag), tags.assigned(key))
        assertTrue(repo.snapshot().items.isEmpty())
        assertTrue(tags.pendingNativeBulks().isEmpty())
        assertTrue(repo.editor(key).assigned.isEmpty())
        assertEquals(1, repo.definitions().tags.size)
    }

    @Test fun mixedNativeBulkStateFailsClosedWithoutFurtherDeletion() {
        val a = stream("partial-a")
        val b = stream("partial-b")
        manager.appendToPlaylist(libraryId, listOf(a, b)).blockingGet()
        val keys = listOf(StreamKey(a.serviceId, a.url), StreamKey(b.serviceId, b.url))
        tags.stageNativeBulk(TagStore.NativeBulk("synthetic-partial", libraryId, keys))
        // This state cannot result from a single atomic Room batch; treat it
        // as corruption or an unsupported external writer instead of guessing.
        assertTrue(LibraryRepository.removeEverywhere(db, libraryId, uid(a)))
        try {
            repo.snapshot()
            fail("Recovery must refuse a partly committed batch")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Partial native batch"))
        }
        assertEquals(1, contents(libraryId).size)
        assertEquals(1, tags.pendingNativeBulks().size)
    }

    @Test fun existingNamedPlaylistCanBeNormalizedAndDeduplicated() {
        val a = stream("adopt-a")
        val b = stream("adopt-b")
        manager.appendToPlaylist(libraryId, listOf(a, b)).blockingGet()
        val original = UnavailableRepository.boundId(db)!!
        val chosen = manager.createPlaylist("Candidate", listOf(a, a, b)).blockingGet().let {
            manager.playlists.blockingFirst().single { it.orderingName == "Candidate" }.uid
        }
        db.openHelper.writableDatabase.execSQL(
            "UPDATE playlists SET name='  unavailable  ' WHERE uid=?",
            arrayOf<Any>(chosen)
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE playlists SET name='Former Unavailable' WHERE uid=?",
            arrayOf<Any>(original)
        )
        binding.write(binding.read()!!.copy(unavailablePlaylistId = null))
        LibraryRepository.forgetSessionForTest()
        LibraryRepository.initialize(db, binding)
        assertEquals(null, UnavailableRepository.boundId(db))
        UnavailableRepository.rebind(context, chosen).blockingAwait()
        assertEquals(chosen, UnavailableRepository.boundId(db))
        assertEquals(
            "Unavailable",
            manager.playlists.blockingFirst().single { it.uid == chosen }.orderingName
        )
        assertEquals(listOf(uid(a), uid(b)), contents(chosen))
        assertEquals(2, contents(libraryId).size)
        assertEquals(uid(a), manager.getPlaylistThumbnailStreamId(chosen))
    }

    @Test fun nativePlaylistCreationReservesUnavailableName() {
        val a = stream("name-reserved")
        manager.createPlaylist(" unavailable ", listOf(a)).test().await()
            .assertError(IllegalArgumentException::class.java)
        assertEquals(2, manager.playlists.blockingFirst().size)
    }

    @Test fun sameNameCollisionNeverBecomesProtectedByAccident() {
        val ordinary = db.playlistDAO().insert(
            PlaylistEntity(
                name = "Unavailable",
                isThumbnailPermanent = false,
                thumbnailStreamId = PlaylistEntity.DEFAULT_THUMBNAIL_ID,
                displayIndex = -1
            )
        )
        // Deliberately erase only the optional designation from the *test* binding
        // and restart the session. A collision must block adoption, not delete data.
        val existing = binding.read()!!
        binding.write(existing.copy(unavailablePlaylistId = null))
        LibraryRepository.forgetSessionForTest()
        LibraryRepository.initialize(db, binding)
        assertFalse(UnavailableRepository.isBound(db, ordinary))
        val original = manager.playlists.blockingFirst()
            .single { it.orderingName == "Unavailable" && it.uid != ordinary }.uid
        // The old protected Unavailable also exists: fail without adopting either.
        try {
            UnavailableRepository.rebind(context, original).blockingAwait()
            fail("ambiguous identity")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(null, UnavailableRepository.boundId(db))
        manager.renamePlaylist(ordinary, "Former candidate").blockingGet()
        UnavailableRepository.rebind(context, original).blockingAwait()
        assertEquals(original, UnavailableRepository.boundId(db))
        assertEquals(original, binding.read()!!.unavailablePlaylistId)
    }
}
