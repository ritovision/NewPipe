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
