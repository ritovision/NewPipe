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

        try {
            LibraryRepository.initialize(db, store)
            fail("A mismatched binding must fail closed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("does not match"))
        }
        assertFalse(LibraryRepository.isLibrary(db, existing))
        assertEquals(1, manager.playlists.blockingFirst().size)
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
            assertTrue(expected.message!!.contains("Unbound playlist"))
        }
        assertEquals(1, manager.playlists.blockingFirst().size)
    }
}
