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
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.testUtil.TestDatabase
import org.schabi.newpipe.testUtil.TrampolineSchedulerRule

class LibraryRepositoryTest {
    @get:Rule val trampolineScheduler = TrampolineSchedulerRule()

    private lateinit var db: AppDatabase
    private lateinit var manager: LocalPlaylistManager
    private lateinit var store: LibraryBindingStore
    private lateinit var bindingDbPath: Path

    @Before
    fun setUp() {
        LibraryRepository.forgetSessionForTest()
        db = TestDatabase.createReplacingNewPipeDatabase()
        manager = LocalPlaylistManager(db)
        bindingDbPath = ApplicationProvider.getApplicationContext<Context>()
            .cacheDir.toPath().resolve("library-test-${System.nanoTime()}.db")
        store = LibraryBindingStore(bindingDbPath)
    }

    @After
    fun tearDown() {
        LibraryRepository.forgetSessionForTest()
        db.close()
        LibraryBindingStore.deleteForDatabase(bindingDbPath)
    }

    private fun stream(url: String, serviceId: Int = 1) = StreamEntity(
        serviceId = serviceId,
        url = url,
        title = "Test ${url.substringAfterLast('/')}",
        streamType = StreamType.VIDEO_STREAM,
        duration = 120,
        uploader = "Test uploader",
        uploaderUrl = "https://example.org/u"
    )

    private fun createPlaylist(name: String, vararg streams: StreamEntity): Long {
        manager.createPlaylist(name, streams.toList()).test().await().assertComplete()
        return manager.playlists.blockingFirst().single { it.orderingName == name }.uid
    }

    private fun entries(playlistId: Long): List<Long> = manager.getPlaylistStreams(playlistId).blockingFirst().map { it.streamId }

    private fun uid(serviceId: Long, url: String) = db.streamDAO().getStream(serviceId, url).blockingFirst().single().uid

    @Test
    fun initializeCreatesAnEmptyProtectedLibrary() {
        val id = LibraryRepository.initialize(db, store)

        assertEquals(emptyList<Long>(), entries(id))
        assertEquals(listOf("Library"), manager.playlists.blockingFirst().map { it.orderingName })
        assertTrue(LibraryRepository.isLibrary(db, id))
        assertEquals(id, LibraryRepository.initialize(db, store))
        assertEquals(1, manager.playlists.blockingFirst().size)
    }

    @Test
    fun addingToSeveralPlaylistsSavesOnlyOnceInLibrary() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/a")

        val favorites = createPlaylist("Favorites", a)
        val workout = createPlaylist("Workout", a)

        assertEquals(1, entries(library).size)
        assertEquals(entries(favorites), entries(workout))
        assertEquals(entries(favorites), entries(library))
    }

    @Test
    fun savingDirectlyToLibraryIsIdempotentAndDoesNotCreateOtherMemberships() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/a")

        manager.appendToPlaylist(library, listOf(a)).test().await().assertComplete()
        manager.appendToPlaylist(library, listOf(a, a)).test().await().assertComplete()

        assertEquals(listOf(uid(1, a.url)), entries(library))
        assertEquals(1, manager.playlists.blockingFirst().size)
    }

    @Test
    fun ordinaryRemovalsDoNotUnsavedFromLibrary() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/a")
        val favorites = createPlaylist("Favorites", a)

        manager.updateJoin(favorites, emptyList()).test().await().assertComplete()

        assertTrue(entries(favorites).isEmpty())
        assertEquals(listOf(uid(1, a.url)), entries(library))
    }

    @Test
    fun libraryRewriteCannotDeleteSavedOnlyOrRecentlyAddedItems() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/a")
        val b = stream("https://example.org/b")
        val c = stream("https://example.org/c")

        createPlaylist("Favorites", a)
        manager.appendToPlaylist(library, listOf(b)).test().await().assertComplete()
        val staleEditorList = listOf(uid(1, a.url))
        manager.appendToPlaylist(library, listOf(c)).test().await().assertComplete()

        manager.updateJoin(library, staleEditorList).test().await().assertComplete()

        assertEquals(listOf(uid(1, a.url), uid(1, b.url), uid(1, c.url)), entries(library))
        assertEquals(3, entries(library).toSet().size)
    }

    @Test
    fun backfillExistingPlaylistsIsDeterministicAndIdempotent() {
        val a = stream("https://example.org/a")
        val b = stream("https://example.org/b")
        val favorites = createPlaylist("Favorites", a, b)
        val workout = createPlaylist("Workout", b, a, b)
        val beforeFavorites = entries(favorites)
        val beforeWorkout = entries(workout)

        val library = LibraryRepository.initialize(db, store)
        assertEquals(listOf(uid(1, a.url), uid(1, b.url)), entries(library))

        LibraryRepository.initialize(db, store)
        assertEquals(2, entries(library).size)
        assertEquals(beforeFavorites, entries(favorites))
        assertEquals(beforeWorkout, entries(workout))
    }

    @Test
    fun identicalUrlsFromDifferentServicesRemainDistinctStreams() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/shared", 1)
        val b = stream("https://example.org/shared", 2)

        createPlaylist("A", a)
        createPlaylist("B", b)

        assertEquals(2, entries(library).size)
        assertFalse(uid(1, a.url) == uid(2, b.url))
    }

    @Test
    fun cannotRenameOrDeleteCanonicalLibraryThroughPlaylistManager() {
        val library = LibraryRepository.initialize(db, store)

        manager.renamePlaylist(library, "Not Library")
            .test().await().assertError(IllegalStateException::class.java)
        manager.updatePlaylists(emptyList(), listOf(library))
            .test().await().assertError(IllegalStateException::class.java)

        assertTrue(LibraryRepository.isLibrary(db, library))
        assertEquals(1, manager.playlists.blockingFirst().size)
    }

    @Test
    fun bindingDoesNotSilentlyAdoptAnotherPlaylistAfterRestore() {
        val existing = createPlaylist("Workout", stream("https://example.org/a"))
        store.write(LibraryBindingStore.Binding(existing, LibraryBindingStore.LIBRARY_NAME))

        val fresh = LibraryRepository.initialize(db, store)
        assertTrue(fresh != existing)
        assertFalse(LibraryRepository.isLibrary(db, existing))
        assertEquals(2, manager.playlists.blockingFirst().size)
        assertEquals(entries(existing), entries(fresh))
    }

    @Test
    fun existingPlaylistNamedLibraryWithoutBindingIsNotAdopted() {
        db.playlistDAO().insert(
            PlaylistEntity(
                name = "Library",
                isThumbnailPermanent = false,
                thumbnailStreamId = PlaylistEntity.DEFAULT_THUMBNAIL_ID,
                displayIndex = 0
            )
        )

        try {
            LibraryRepository.initialize(db, store)
            fail("A playlist's display name does not establish identity")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("unbound Library playlist"))
        }
        assertEquals(1, manager.playlists.blockingFirst().size)
    }

    @Test
    fun coldStartDirectSaveIsIdempotent() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/cold-save")
        manager.appendToPlaylist(library, listOf(a)).test().await().assertComplete()

        LibraryRepository.forgetSessionForTest()
        manager.appendToPlaylist(library, listOf(a)).test().await().assertComplete()
        manager.appendToPlaylist(library, listOf(a)).test().await().assertComplete()

        assertEquals(listOf(uid(1, a.url)), entries(library))
        assertEquals(library, LibraryRepository.initialize(db, store))
    }

    @Test
    fun coldStartStaleLibraryUpdateNeverRemovesSavedOnlyStream() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/cold-a")
        val b = stream("https://example.org/cold-b")
        manager.appendToPlaylist(library, listOf(a, b)).test().await().assertComplete()

        LibraryRepository.forgetSessionForTest()
        manager.updateJoin(library, listOf(uid(1, a.url))).test().await().assertComplete()

        assertEquals(listOf(uid(1, a.url), uid(1, b.url)), entries(library))
    }

    @Test
    fun repeatedInitializationPreservesBoundProtection() {
        val library = LibraryRepository.initialize(db, store)
        manager.appendToPlaylist(library, listOf(stream("https://example.org/a")))
            .test().await().assertComplete()

        assertEquals(library, LibraryRepository.initialize(db, store))
        manager.updateJoin(library, emptyList()).test().await().assertComplete()
        assertEquals(1, entries(library).size)
    }

    @Test
    fun duplicateRowsInOrdinaryPlaylistStillYieldOneLibraryRow() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/repeat")
        val playlist = createPlaylist("Repeats", a, a)

        assertEquals(2, entries(playlist).size)
        assertEquals(listOf(uid(1, a.url)), entries(library))
    }

    @Test
    fun explicitlyRebindingUnboundLibraryPreservesItsOnlyEntries() {
        val a = stream("https://example.org/only-in-imported-library")
        val library = createPlaylist("Library", a)
        try {
            LibraryRepository.initialize(db, store)
            fail("Never auto-adopt an unbound Library")
        } catch (expected: IllegalStateException) {
            // Deliberate repair requires explicit user selection.
        }

        val rebound = LibraryRepository.rebindExisting(db, store, library)
        assertEquals(library, rebound)
        assertEquals(listOf(uid(1, a.url)), entries(library))
        assertTrue(LibraryRepository.isBoundLibrary(db, library))
    }

    @Test
    fun mismatchedBindingWithNoNamedCandidateCreatesNewLibraryWithoutDeletingOld() {
        val a = stream("https://example.org/mismatch")
        val old = createPlaylist("Workout", a)
        store.write(LibraryBindingStore.Binding(old, LibraryBindingStore.LIBRARY_NAME))

        val newLibrary = LibraryRepository.initialize(db, store)
        assertTrue(newLibrary != old)
        assertEquals(listOf(uid(1, a.url)), entries(newLibrary))
        assertEquals(listOf(uid(1, a.url)), entries(old))
    }

    @Test
    fun boundLibraryMetadataCannotBeRenamedByBookmarkUpsert() {
        val library = LibraryRepository.initialize(db, store)
        val metadata = manager.playlists.blockingFirst().single { it.uid == library }
        val wrong = org.schabi.newpipe.database.playlist.PlaylistMetadataEntry(
            uid = metadata.uid,
            orderingName = "New name",
            thumbnailUrl = metadata.thumbnailUrl,
            displayIndex = metadata.displayIndex,
            isThumbnailPermanent = metadata.isThumbnailPermanent,
            thumbnailStreamId = metadata.thumbnailStreamId,
            streamCount = metadata.streamCount
        )

        manager.updatePlaylists(listOf(wrong), emptyList()).test().await()
            .assertError(IllegalStateException::class.java)
        assertEquals("Library", manager.playlists.blockingFirst().single().orderingName)
    }

    @Test
    fun deletingFromLibraryRemovesEveryOccurrenceFromAllPlaylists() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/global-a")
        val b = stream("https://example.org/keep-b")
        val favorites = createPlaylist("Favorites", a, a, b)
        val workout = createPlaylist("Workout", b, a)
        val deletedId = uid(1, a.url)
        val keptId = uid(1, b.url)

        assertEquals(2, entries(library).size)
        assertEquals(3, entries(favorites).size)
        assertEquals(deletedId, manager.getPlaylistThumbnailStreamId(favorites))

        assertTrue(LibraryRepository.removeEverywhere(db, library, deletedId))

        assertEquals(listOf(keptId), entries(library))
        assertEquals(listOf(keptId), entries(favorites))
        assertEquals(listOf(keptId), entries(workout))
        assertEquals(
            keptId,
            manager.getPlaylistThumbnailStreamId(favorites)
        )
        // Removal from playlists must not erase history, subscription or the
        // stream row itself. NewPipe may independently garbage-collect orphans.
        assertEquals(deletedId, uid(1, a.url))
    }

    @Test
    fun deletingLibraryOnlyEntryDoesNotDeleteAnyOtherStream() {
        val library = LibraryRepository.initialize(db, store)
        val savedOnly = stream("https://example.org/library-only")
        val other = stream("https://example.org/elsewhere")
        manager.appendToPlaylist(library, listOf(savedOnly)).test().await().assertComplete()
        val favorites = createPlaylist("Favorites", other)

        assertTrue(LibraryRepository.removeEverywhere(db, library, uid(1, savedOnly.url)))
        assertEquals(listOf(uid(1, other.url)), entries(library))
        assertEquals(listOf(uid(1, other.url)), entries(favorites))
    }

    @Test
    fun staleOrdinaryPlaylistEditorCannotResurrectGloballyDeletedEntry() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/stale-a")
        val b = stream("https://example.org/stale-b")
        val ordinary = createPlaylist("Favorites", a, b)
        val oldEditorList = entries(ordinary)

        assertTrue(LibraryRepository.removeEverywhere(db, library, uid(1, a.url)))
        // Simulate an ordinary playlist editor loaded before global removal.
        manager.updateJoin(ordinary, oldEditorList).test().await().assertComplete()

        assertEquals(listOf(uid(1, b.url)), entries(ordinary))
        assertEquals(listOf(uid(1, b.url)), entries(library))

        LibraryRepository.forgetSessionForTest()
        LibraryRepository.initialize(db, store)
        assertEquals(listOf(uid(1, b.url)), entries(library))
    }

    @Test
    fun explicitNewSaveAfterGlobalDeleteAddsItemBackAgain() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/save-again")
        val favorites = createPlaylist("Favorites", a)
        val id = uid(1, a.url)
        assertTrue(LibraryRepository.removeEverywhere(db, library, id))
        assertTrue(entries(library).isEmpty())
        assertTrue(entries(favorites).isEmpty())

        manager.appendToPlaylist(favorites, listOf(a)).test().await().assertComplete()

        assertEquals(listOf(id), entries(library))
        assertEquals(listOf(id), entries(favorites))
        manager.updateJoin(favorites, listOf(id)).test().await().assertComplete()
        assertEquals(listOf(id), entries(favorites))
    }

    @Test
    fun globalDeletionIsIdempotentAndRequiresCorrectLibraryIdentity() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/id-check")
        val favorites = createPlaylist("Favorites", a)
        val id = uid(1, a.url)

        try {
            LibraryRepository.removeEverywhere(db, favorites, id)
            fail("Cannot globally delete using an ordinary playlist ID")
        } catch (expected: IllegalStateException) {
            assertEquals(listOf(id), entries(library))
            assertEquals(listOf(id), entries(favorites))
        }

        assertTrue(LibraryRepository.removeEverywhere(db, library, id))
        assertFalse(LibraryRepository.removeEverywhere(db, library, id))
        assertTrue(entries(library).isEmpty())
        assertTrue(entries(favorites).isEmpty())
    }

    @Test
    fun sameNamedOrdinaryPlaylistRetainsNormalActionsAndNoGlobalAuthority() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/duplicate-library-name")
        manager.createPlaylist("Library", listOf(a)).test().await().assertComplete()
        val ordinary = manager.playlists.blockingFirst().single { it.uid != library }.uid
        assertFalse(LibraryRepository.isUiProtected(db, ordinary, "Library"))
        assertTrue(LibraryRepository.isUiProtected(db, library, "Library"))
        assertFalse(LibraryRepository.isBoundLibrary(db, ordinary))
        try {
            LibraryRepository.removeEverywhere(db, ordinary, uid(1, a.url))
            fail("An ordinary playlist must never authorize global deletion")
        } catch (expected: IllegalStateException) {
            assertEquals(1, entries(library).size)
        }
        manager.updateJoin(ordinary, emptyList()).test().await().assertComplete()
        assertEquals(1, entries(library).size)
        manager.renamePlaylist(ordinary, "Renamed duplicate").test().await().assertComplete()
        manager.updatePlaylists(emptyList(), listOf(ordinary)).test().await().assertComplete()
        assertEquals(1, manager.playlists.blockingFirst().size)
    }

    @Test
    fun identityObserverRebindsArtworkAfterInitializationAndSessionReset() {
        val observer = LibraryRepository.observeLibraryId(db).test()
        observer.assertValues(-1L)
        val library = LibraryRepository.initialize(db, store)
        observer.assertValues(-1L, library)
        LibraryRepository.forgetSessionForTest()
        observer.assertValues(-1L, library, -1L)
        LibraryRepository.initialize(db, store)
        observer.assertValues(-1L, library, -1L, library)
        observer.dispose()
    }

    @Test
    fun thumbnailFallbackKeepsUnaffectedPinnedImageAndResetsEmptyPlaylist() {
        val library = LibraryRepository.initialize(db, store)
        val a = stream("https://example.org/thumbnail-a")
        val b = stream("https://example.org/thumbnail-b")
        val emptyAfter = createPlaylist("Only A", a)
        val pinned = createPlaylist("Pinned B", a, b)
        manager.changePlaylistThumbnail(pinned, uid(1, b.url), true)
            .test().await().assertComplete()
        LibraryRepository.removeEverywhere(db, library, uid(1, a.url))
        assertEquals(PlaylistEntity.DEFAULT_THUMBNAIL_ID, manager.getPlaylistThumbnailStreamId(emptyAfter))
        assertEquals(uid(1, b.url), manager.getPlaylistThumbnailStreamId(pinned))
        assertTrue(manager.getIsPlaylistThumbnailPermanent(pinned))
    }
}
