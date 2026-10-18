/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.testUtil.TestDatabase
import org.schabi.newpipe.testUtil.TrampolineSchedulerRule

class TagRepositoryTest {
    @get:Rule val trampoline = TrampolineSchedulerRule()
    private lateinit var db: AppDatabase
    private lateinit var store: TagStore
    private lateinit var repository: TagRepository
    private lateinit var bindingPath: Path
    private var libraryId = -1L

    @Before fun setup() {
        LibraryRepository.forgetSessionForTest()
        TagRuntime.restoring = false
        db = TestDatabase.createReplacingNewPipeDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        bindingPath = context.cacheDir.toPath().resolve("tag-binding-${System.nanoTime()}.db")
        val binding = LibraryBindingStore(bindingPath)
        libraryId = LibraryRepository.initialize(db, binding)
        store = TagStore(context, null)
        repository = TagRepository(db, store, binding)
        TagRuntime.registerForTest(db, repository)
    }

    @After fun cleanup() {
        TagRuntime.unregister(db)
        store.close()
        db.close()
        LibraryRepository.forgetSessionForTest()
        LibraryBindingStore.deleteForDatabase(bindingPath)
        TagRuntime.restoring = false
    }

    private fun media(url: String = "https://example.org/a", service: Int = 0) = StreamEntity(
        serviceId = service,
        url = url,
        title = "Synthetic $url",
        streamType = StreamType.VIDEO_STREAM,
        duration = 60,
        uploader = "Tagging test"
    )
    private fun key(item: StreamEntity) = StreamKey(item.serviceId, item.url)
    private fun apply(item: StreamEntity, ids: Set<Long>) = repository.apply(item, ids, repository.editor(key(item)).revision)
    private fun savedCount() = repository.snapshot().items.size
    private fun append(id: Long, item: StreamEntity) = LocalPlaylistManager(db).appendToPlaylist(id, listOf(item)).blockingGet()
    private fun playlistCount(id: Long) = LocalPlaylistManager(db).getPlaylistStreams(id).blockingFirst().size

    @Test fun assigningFirstTagSavesOnlyOnceAndFinalTagRemovalKeepsLibrary() {
        val item = media()
        val tag = repository.createTag("Jazz", null)
        apply(item, setOf(tag))
        apply(item, setOf(tag))
        assertEquals(1, savedCount())
        assertEquals(setOf(tag), repository.editor(key(item)).assigned)
        apply(item, emptySet())
        assertEquals(1, savedCount())
        assertTrue(repository.editor(key(item)).assigned.isEmpty())
    }

    @Test fun tagResultsCannotCreateAnotherProtectedUnavailableName() {
        val video = media("https://example.org/reserved")
        append(libraryId, video)
        try {
            repository.createPlaylistSnapshot("  unavailable  ", listOf(key(video)))
            fail("The Tags save action must reserve the Unavailable name")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("reserved"))
        }
        assertEquals(2, LocalPlaylistManager(db).playlists.blockingFirst().size)
    }

    @Test fun categoryDeletionCascadesOnlyMetadata() {
        val item = media()
        val category = repository.createCategory("Genre")
        val jazz = repository.createTag("Jazz", category)
        val chill = repository.createTag("Chill", null)
        apply(item, setOf(jazz, chill))
        LocalPlaylistManager(db).createPlaylist("Synthetic list", listOf(item)).blockingGet()
        val before = db.playlistStreamDAO().getAll().blockingFirst().size
        val plan = repository.deletionPlan(category, true)
        assertEquals(1, plan.tags)
        assertEquals(1, plan.assignments)
        repository.delete(plan)
        assertEquals(before, db.playlistStreamDAO().getAll().blockingFirst().size)
        assertEquals(setOf(chill), repository.editor(key(item)).assigned)
        assertEquals(listOf(chill), repository.definitions().tags.map { it.id })
    }

    @Test fun serviceIdentityIsNotUrlOnlyAndNamesAreCaseInsensitive() {
        val a = media(service = 0)
        val b = media(service = 1)
        val tag = repository.createTag("  Jazz  ", null)
        apply(a, setOf(tag))
        append(libraryId, b)
        assertEquals(2, savedCount())
        assertTrue(repository.editor(key(b)).assigned.isEmpty())
        try {
            repository.createTag("jAZZ", null)
            fail("duplicate")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
    }

    @Test fun globalRemovalDeletesAllMembershipsAndAssignmentsButNotDefinitions() {
        val item = media()
        val tag = repository.createTag("Jazz", null)
        apply(item, setOf(tag))
        LocalPlaylistManager(db).createPlaylist("Synthetic list", listOf(item, item)).blockingGet()
        repository.removeEverywhere(libraryId, repository.currentUid(key(item))!!)
        assertEquals(0, savedCount())
        assertTrue(db.playlistStreamDAO().getAll().blockingFirst().isEmpty())
        assertTrue(repository.editor(key(item)).assigned.isEmpty())
        assertEquals(1, repository.definitions().tags.size)
        assertTrue(store.pendingRemovals().isEmpty())
    }

    @Test fun staleEditorCannotResurrectGloballyRemovedVideo() {
        val item = media()
        val tag = repository.createTag("Jazz", null)
        apply(item, setOf(tag))
        val old = repository.editor(key(item))
        repository.removeEverywhere(libraryId, repository.currentUid(key(item))!!)
        try {
            repository.apply(item, old.assigned, old.revision)
            fail("stale editor")
        } catch (_: IllegalStateException) { }
        assertEquals(0, savedCount())
    }

    @Test fun failedTagCommitLeavesSavedUntaggedItemAndAllowsRetry() {
        val item = media()
        val tag = repository.createTag("Jazz", null)
        store.writableDatabase.execSQL("CREATE TRIGGER fail_tags BEFORE INSERT ON assignment BEGIN SELECT RAISE(ABORT,'injected tag failure'); END")
        try {
            apply(item, setOf(tag))
            fail("tag commit")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(1, savedCount())
        assertTrue(repository.editor(key(item)).assigned.isEmpty())
        store.writableDatabase.execSQL("DROP TRIGGER fail_tags")
        apply(item, setOf(tag))
        assertEquals(setOf(tag), repository.editor(key(item)).assigned)
    }

    @Test fun pendingDeleteIsRecoveredBeforeAnOrdinaryExplicitReadd() {
        val item = media()
        val tag = repository.createTag("Jazz", null)
        apply(item, setOf(tag))
        store.beginRemoval(key(item), libraryId) // Simulate death before Room deletion.
        append(libraryId, item) // The registered pre-write barrier first finishes deletion.
        assertEquals(1, savedCount())
        assertTrue(repository.editor(key(item)).assigned.isEmpty())
        assertTrue(store.pendingRemovals().isEmpty())
    }

    @Test fun pendingCleanupAfterRoomCommitSurvivesReaddWithoutOldTags() {
        val item = media()
        val tag = repository.createTag("Jazz", null)
        apply(item, setOf(tag))
        val old = repository.editor(key(item))
        store.beginRemoval(key(item), libraryId)
        LibraryRepository.removeEverywhere(db, libraryId, repository.currentUid(key(item))!!)
        append(libraryId, item)
        assertEquals(1, savedCount())
        assertTrue(repository.editor(key(item)).assigned.isEmpty())
        assertTrue(repository.editor(key(item)).revision > old.revision)
    }

    @Test fun changedLibraryIdentityNeverReplaysDestructiveIntent() {
        val item = media()
        append(libraryId, item)
        store.beginRemoval(key(item), libraryId + 100)
        try {
            append(libraryId, media("https://example.org/b"))
            fail("identity mismatch")
        } catch (_: IllegalStateException) { }
        assertEquals(1, playlistCount(libraryId))
        assertEquals(1, store.pendingRemovals().size)
    }

    @Test fun deletionConfirmationRejectsChangedMetadata() {
        val category = repository.createCategory("Genre")
        repository.createTag("Jazz", category)
        val plan = repository.deletionPlan(category, true)
        repository.createTag("Rock", category)
        try {
            repository.delete(plan)
            fail("stale plan")
        } catch (_: IllegalStateException) { }
        assertEquals(2, repository.definitions().tags.size)
    }

    @Test fun missingImportedStreamsDoNotPurgeAssociations() {
        val item = media()
        val tag = repository.createTag("Jazz", null)
        apply(item, setOf(tag))
        // Mismatched stock import simulation: there is no explicit removal intent.
        db.playlistStreamDAO().deleteBatch(libraryId)
        assertEquals(1, repository.snapshot().orphanedAssignments)
        assertEquals(setOf(tag), store.assigned(key(item)))
        append(libraryId, item)
        assertEquals(setOf(tag), repository.editor(key(item)).assigned)
    }

    @Test
    fun applyingTagsPreservesExistingMetadataFromNewerLibraryRow() {
        val old = media().copy(title = "Old title", viewCount = 12)
        append(libraryId, old)
        val latest = media().copy(title = "Refreshed title", viewCount = 999, thumbnailUrl = "https://example.org/latest.jpg")
        db.streamDAO().upsert(latest)
        val tag = repository.createTag("Jazz", null)
        apply(old.copy(viewCount = -1), setOf(tag))
        val stored = repository.snapshot().items.single().stream
        assertEquals("Refreshed title", stored.title)
        assertEquals(999L, stored.viewCount)
        assertEquals("https://example.org/latest.jpg", stored.thumbnailUrl)
    }

    @Test
    fun renameAfterPendingRemovalDoesNotRestoreOldThumbnail() {
        val a = media()
        val b = media("https://example.org/b")
        val manager = LocalPlaylistManager(db)
        manager.createPlaylist("List", listOf(a, b)).blockingGet()
        val playlistId = manager.playlists.blockingFirst().single { it.orderingName == "List" }.uid
        store.beginRemoval(key(a), libraryId)
        manager.renamePlaylist(playlistId, "Renamed").blockingGet()
        val result = db.playlistDAO().getPlaylist(playlistId).blockingFirst().single()
        assertEquals("Renamed", result.name)
        assertEquals(repository.currentUid(key(b)), result.thumbnailStreamId)
        assertEquals(1, playlistCount(playlistId))
    }

    @Test
    fun bookmarkReorderCannotOverwriteARepairedThumbnailOrNewerName() {
        val a = media()
        val b = media("https://example.org/b")
        val manager = LocalPlaylistManager(db)
        manager.createPlaylist("List", listOf(a, b)).blockingGet()
        val old = manager.playlists.blockingFirst().single { it.orderingName == "List" }
        manager.renamePlaylist(old.uid, "Updated name").blockingGet()
        store.beginRemoval(key(a), libraryId)
        old.displayIndex = 10
        manager.updatePlaylists(listOf(old), emptyList()).blockingAwait()
        val result = db.playlistDAO().getPlaylist(old.uid).blockingFirst().single()
        assertEquals("Updated name", result.name)
        assertEquals(repository.currentUid(key(b)), result.thumbnailStreamId)
        assertEquals(10L, result.displayIndex)
    }

    @Test fun tagDatabasePersistsAfterCloseAndReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "tag-test-${System.nanoTime()}.db"
        try {
            val first = TagStore(context, name)
            val id = first.createTag("Persistent", null)
            first.setTags(StreamKey(0, "https://example.org/p"), setOf(id), 0)
            first.beginRemoval(StreamKey(0, "https://example.org/p"), 12)
            first.close()
            TagStore(context, name).use { second ->
                assertEquals(setOf(id), second.assigned(StreamKey(0, "https://example.org/p")))
                assertEquals(1, second.pendingRemovals().size)
                assertEquals("Persistent", second.definitions().tags.single().name)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
