/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.custom.tagging.StreamKey
import org.schabi.newpipe.custom.tagging.TagFilter
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.custom.tagging.TagSort
import org.schabi.newpipe.custom.tagging.TagStore
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.testUtil.TestDatabase
import org.schabi.newpipe.testUtil.TrampolineSchedulerRule

/** Actual Room + SQLite integration; exclusively isolated synthetic databases. */
class AiTagIntegrationTest {
    @get:Rule val trampoline = TrampolineSchedulerRule()
    private lateinit var db: AppDatabase
    private lateinit var tags: TagStore
    private lateinit var repo: TagRepository
    private lateinit var binding: Path
    private lateinit var access: AiAccessController
    private lateinit var session: AiAccessController.Session
    private lateinit var executor: AiCatalogExecutor
    private val device = "10000000-0000-0000-0000-000000000001"
    private val epoch = "20000000-0000-0000-0000-000000000001"
    private val client = "tag-test"
    private val now = 1_800_000_000_000L
    private var libraryId = 0L

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LibraryRepository.forgetSessionForTest()
        TagRuntime.restoring = false
        db = TestDatabase.createReplacingNewPipeDatabase()
        binding = context.cacheDir.toPath().resolve("ai-tags-${UUID.randomUUID()}.db")
        libraryId = LibraryRepository.initialize(db, LibraryBindingStore(binding))
        tags = TagStore(context, null)
        repo = TagRepository(db, tags, LibraryBindingStore(binding))
        TagRuntime.registerForTest(db, repo)
        access = AiAccessController()
        access.enableFromLocalConsent(device, epoch, mapOf(client to AiScope.entries.toSet()))
        session = access.openAuthenticatedSession(client)
        executor = AiCatalogExecutor(db, access, clock = { now })
    }

    @After fun cleanup() {
        access.disable()
        TagRuntime.unregister(db)
        tags.close()
        db.close()
        LibraryRepository.forgetSessionForTest()
        LibraryBindingStore.deleteForDatabase(binding)
        TagRuntime.restoring = false
    }

    private fun request(command: AiCatalogCommand, id: String = UUID.randomUUID().toString()) = AiRequest(1, device, client, epoch, id, now, now + 30000, command)
    private fun run(command: AiCatalogCommand) = run(request(command))
    private fun run(request: AiRequest) = executor.execute(AiProtocol.json.encodeToString(request), session)
    private fun number(result: AiResult, key: String) = result.data.getValue(key).jsonPrimitive.long
    private fun item(name: String = "sample"): Pair<StreamEntity, AiStreamRef> {
        val stream = StreamEntity(serviceId = 0, url = "https://example.org/$name", title = name, streamType = StreamType.VIDEO_STREAM, duration = 12, uploader = "Synthetic")
        val tag = repo.definitions().tags.firstOrNull()?.id ?: repo.createTag("Fixture", null)
        repo.apply(stream, setOf(tag), 0)
        val saved = repo.snapshot().items.first { it.stream.url == stream.url }.stream
        return saved to AiStreamRef(saved.uid, "YouTube", saved.url)
    }
    private fun key(stream: StreamEntity) = StreamKey(stream.serviceId, stream.url)

    @Test fun tagCreationReceiptIsAtomicAndReplayDoesNotDuplicate() {
        val request = request(AiTagCommand.CreateCategory("Genre"))
        val first = run(request)
        assertEquals(AiOutcome.COMMITTED, first.outcome)
        assertEquals(AiOutcome.COMMITTED, run(request).outcome)
        assertTrue(run(request).replayed)
        assertEquals(1, repo.definitions().categories.size)
        assertEquals(0, db.aiCommandReceiptDao().count())
        assertNotNull(tags.findReceipt(device, epoch, client, request.requestId))
    }

    @Test fun tagReceiptInsertionFailureRollsBackTagCreation() {
        tags.writableDatabase.execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON tag_command_receipt BEGIN SELECT RAISE(ABORT,'synthetic receipt failure'); END")
        val request = request(AiTagCommand.CreateCategory("Genre"))
        assertEquals(AiOutcome.OUTCOME_UNKNOWN, run(request).outcome)
        assertTrue(repo.definitions().categories.isEmpty())
        assertNull(tags.findReceipt(device, epoch, client, request.requestId))
        tags.writableDatabase.execSQL("DROP TRIGGER fail_receipt")
        assertEquals(AiOutcome.COMMITTED, run(request).outcome)
        assertEquals(1, repo.definitions().categories.size)
    }

    @Test fun receiptIdentityIsSharedAcrossRoomAndTagStores() {
        val request = request(AiTagCommand.CreateCategory("Genre"))
        assertEquals(AiOutcome.COMMITTED, run(request).outcome)
        assertEquals("REQUEST_ID_REUSED", run(request.copy(command = AiCatalogCommand.CreatePlaylist("Duplicate ID"))).code)
        val lookup = run(AiCatalogCommand.Receipt(request.requestId))
        assertEquals(AiOutcome.COMPLETED, lookup.outcome)
        assertEquals(request.requestId, lookup.data.getValue("original_request_id").jsonPrimitive.content)
    }

    @Test fun oldGrantsDoNotAcquireTagPermissions() {
        access.enableFromLocalConsent(device, epoch, mapOf(client to setOf(AiScope.LIBRARY_READ, AiScope.LIBRARY_WRITE, AiScope.LIBRARY_DELETE)))
        session = access.openAuthenticatedSession(client)
        assertEquals("PERMISSION_DENIED", run(AiTagCommand.CreateCategory("Forbidden")).code)
        assertEquals("PERMISSION_DENIED", run(AiTagCommand.Categories()).code)
        assertTrue(repo.definitions().categories.isEmpty())
    }

    @Test fun aiLibraryDeleteClearsAssignmentsAndAllMemberships() {
        val (stream, ref) = item()
        val playlist = repo.createPlaylistSnapshot("Snapshot", listOf(key(stream)))
        assertEquals(AiOutcome.COMMITTED, run(AiCatalogCommand.RemoveLibrary(listOf(ref))).outcome)
        assertTrue(repo.editor(key(stream)).assigned.isEmpty())
        assertTrue(repo.snapshot().items.isEmpty())
        assertTrue(db.playlistStreamDAO().getOrderedStreamsOf(playlist).blockingFirst().isEmpty())
        assertTrue(tags.pendingAiRemovals().isEmpty())
        assertEquals(1, repo.definitions().tags.size)
    }

    @Test fun failedAiDeleteNeverReplaysItsUncommittedDestruction() {
        val (stream, ref) = item()
        val original = repo.editor(key(stream)).assigned
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_room_receipt BEFORE INSERT ON rv_ai_command_receipts BEGIN SELECT RAISE(ABORT,'synthetic rollback'); END")
        assertEquals(AiOutcome.OUTCOME_UNKNOWN, run(AiCatalogCommand.RemoveLibrary(listOf(ref))).outcome)
        assertEquals(original, repo.editor(key(stream)).assigned)
        assertEquals(1, repo.snapshot().items.size)
        assertTrue(tags.pendingAiRemovals().isEmpty())
    }

    @Test fun committedDeleteFinishesCleanupAfterAnInterruptedSidecarWrite() {
        val (stream, ref) = item()
        tags.writableDatabase.execSQL("CREATE TRIGGER fail_cleanup BEFORE DELETE ON assignment BEGIN SELECT RAISE(ABORT,'synthetic cleanup interruption'); END")
        val request = request(AiCatalogCommand.RemoveLibrary(listOf(ref)))
        assertEquals(AiOutcome.OUTCOME_UNKNOWN, run(request).outcome)
        assertNotNull(db.aiCommandReceiptDao().find(device, epoch, client, request.requestId))
        assertFalse(tags.pendingAiRemovals().isEmpty())
        tags.writableDatabase.execSQL("DROP TRIGGER fail_cleanup")
        assertTrue(repo.editor(key(stream)).assigned.isEmpty())
        assertTrue(tags.pendingAiRemovals().isEmpty())
        assertTrue(run(request).replayed)
    }

    @Test fun retryOfOldDeletionDoesNotRemoveReaddedTags() {
        val (stream, ref) = item()
        val request = request(AiCatalogCommand.RemoveLibrary(listOf(ref)))
        assertEquals(AiOutcome.COMMITTED, run(request).outcome)
        val tag = repo.definitions().tags.first().id
        repo.apply(stream, setOf(tag), repo.editor(key(stream)).revision)
        assertTrue(run(request).replayed)
        assertEquals(setOf(tag), repo.editor(key(stream)).assigned)
        assertEquals(1, repo.snapshot().items.size)
    }

    @Test fun committedNoopDeletionDoesNotErasePreservedOrphanTags() {
        val (stream, ref) = item()
        val assigned = repo.editor(key(stream)).assigned
        // Models retained metadata after importing a stock Library missing this stream.
        db.openHelper.writableDatabase.execSQL("DELETE FROM playlist_stream_join WHERE stream_id=?", arrayOf(stream.uid))
        assertEquals(AiOutcome.COMMITTED, run(AiCatalogCommand.RemoveLibrary(listOf(ref))).outcome)
        assertEquals(assigned, repo.editor(key(stream)).assigned)
    }

    @Test fun categoryDeletionThroughAiNeverDeletesMediaAndRejectsStaleState() {
        val category = repo.createCategory("Genre")
        val tag = repo.createTag("Jazz", category)
        val (stream, _) = item()
        repo.apply(stream, setOf(tag), repo.editor(key(stream)).revision)
        val previous = repo.definitions().version
        repo.createTag("Chill", category)
        assertEquals("TAG_CONFLICT", run(AiTagCommand.DeleteCategory(category, previous)).code)
        val result = run(AiTagCommand.DeleteCategory(category, repo.definitions().version))
        assertEquals(AiOutcome.COMMITTED, result.outcome)
        assertEquals(2, number(result, "deleted_tags").toInt())
        assertEquals(1, repo.snapshot().items.size)
        assertTrue(repo.editor(key(stream)).assigned.isEmpty())
    }

    @Test fun assignmentsUseSavedExactIdentityAndRejectConcurrentUiChanges() {
        val (stream, ref) = item()
        val state = repo.editor(key(stream))
        repo.apply(stream, emptySet(), state.revision)
        assertEquals("TAG_CONFLICT", run(AiTagCommand.SetTags(ref, state.assigned.toList(), state.revision)).code)
        assertEquals("NOT_FOUND", run(AiTagCommand.ItemTags(ref.copy(url = "https://example.org/different"))).code)
        assertEquals(AiOutcome.COMMITTED, run(AiTagCommand.SetTags(ref, state.assigned.toList(), repo.editor(key(stream)).revision)).outcome)
    }

    @Test fun nativeAndAiQueriesShareOrderAndRejectStalePagination() {
        item("Zulu")
        item("Alpha")
        item("Beta")
        val result = run(AiTagCommand.Search(sort = "title_asc", limit = 1))
        assertEquals(AiOutcome.COMPLETED, result.outcome)
        assertEquals(3, number(result, "total").toInt())
        val revision = result.data.getValue("revision").jsonPrimitive.content
        val page = run(AiTagCommand.Search(sort = "title_asc", offset = 1, limit = 1, expectedRevision = revision))
        val rows = page.data.getValue("items") as JsonArray
        assertEquals("Beta", (rows[0] as kotlinx.serialization.json.JsonObject).getValue("title").jsonPrimitive.content)
        repo.createTag("Changed", null)
        assertEquals("TAG_RESULTS_CHANGED", run(AiTagCommand.Search(sort = "title_asc", offset = 1, limit = 1, expectedRevision = revision)).code)
        assertEquals(listOf("Alpha", "Beta", "Zulu"), TagFilter(sort = TagSort.TITLE_ASC).select(repo.snapshot().items).map { it.stream.title })
    }

    @Test fun playlistSnapshotKeepsExactOrderAndRefusesRemovedItems() {
        val (a, _) = item("A")
        val (b, bRef) = item("B")
        val id = repo.createPlaylistSnapshot("B then A", listOf(key(b), key(a)))
        assertEquals(listOf(b.uid, a.uid), db.playlistStreamDAO().getOrderedStreamsOf(id).blockingFirst().map { it.streamId })
        run(AiCatalogCommand.RemoveLibrary(listOf(bRef)))
        val before = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playlists").use {
            it.moveToFirst()
            it.getLong(0)
        }
        assertThrows(IllegalStateException::class.java) { repo.createPlaylistSnapshot("Stale", listOf(key(a), key(b))) }
        assertEquals(
            before,
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playlists").use {
                it.moveToFirst()
                it.getLong(0)
            }
        )
    }
}
