/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.Kind
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.NativeId
import org.schabi.newpipe.custom.tagging.CatalogFileSwap
import org.schabi.newpipe.custom.tagging.StreamKey
import org.schabi.newpipe.custom.tagging.TagBackupCoordinator
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.custom.tagging.TagStore
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.streams.io.StoredFileHelper

class CloudNativeApplyTest {
    private lateinit var f: CloudApplyFixture
    private class Injected : RuntimeException()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        f = CloudApplyFixture(context, Files.createTempDirectory(context.cacheDir.toPath(), "cloud-apply-"))
        f.initialize()
    }

    @After fun cleanup() {
        f.close()
        CatalogFileSwap.deleteTree(f.dir)
    }
    private fun rejects(code: String, block: () -> Unit) {
        assertEquals(code, assertThrows(IllegalStateException::class.java, block).message)
    }
    private fun apply(
        before: CloudNativeSnapshot,
        target: CloudCatalog = f.target(before),
        revision: Long = 1,
        fault: (CloudNativeApply.Phase) -> Unit = {}
    ): CloudNativeSnapshot = CloudNativeApply(f.repo, fault).apply(
        before,
        f.head(target, revision),
        target,
        target.library.filter { it !in before.catalog.library }.map(f::hint)
    )
    private fun interrupt(phase: CloudNativeApply.Phase, before: CloudNativeSnapshot, target: CloudCatalog = f.target(before)) {
        assertThrows(Injected::class.java) { apply(before, target) { if (it == phase) throw Injected() } }
    }

    @Test fun exactRemoteCatalogAppliesWithStableNativeIdsAndRoles() {
        val before = f.seed()
        val target = f.target(before)
        val old = before.nativeIds.entries.associate { it.value to it.key }
        val result = apply(before, target)
        assertEquals(f.head(target), CloudApplyStore.baseline(f.repo.store.readableDatabase)!!.head)
        assertEquals(f.head(target).fingerprint, result.fingerprint)
        assertEquals(before.identities, result.identities)
        assertEquals(old[before.catalog.playlists.first().id], result.nativeIds.entries.single { it.value == target.playlists.first().id }.key)
        assertEquals(target, result.catalog)
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        assertNotNull(f.db.cloudApplyReceiptDao().current())
        assertEquals(before.tagRevision + 1, f.repo.store.version())
        assertEquals(9002L, f.scalar("PRAGMA user_version"))
        assertEquals(5, f.repo.store.readableDatabase.version)
    }

    @Test fun newRemoteIdsDoNotAdoptSameNamedLocalPlaylist() {
        val before = f.seed()
        val local = before.catalog.playlists.first()
        val incoming = local.copy(id = UUID.randomUUID().toString())
        val target = before.catalog.copy(playlists = listOf(incoming))
        val after = apply(before, target)
        val oldId = before.nativeIds.entries.single { it.value == local.id }.key.id
        val newId = after.nativeIds.entries.single { it.value == incoming.id }.key.id
        assertNotEquals(oldId, newId)
        assertEquals(local.name, after.catalog.playlists.single().name)
    }

    @Test fun globalRemovalDeletesEveryOccurrenceNotStreamsHistoryOrProgress() {
        val before = f.seed()
        val b = before.catalog.library[1]
        val uid = f.db.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT uid FROM streams WHERE url=?", arrayOf(b.url))).use {
            it.moveToFirst()
            it.getLong(0)
        }
        f.main("INSERT INTO stream_history VALUES(?,1234,2)", uid)
        f.main("INSERT INTO stream_state VALUES(?,777)", uid)
        f.main("INSERT INTO search_history(creation_date,service_id,search) VALUES(1234,0,'keep search')")
        f.main("INSERT INTO subscriptions(service_id,url,name,notification_mode) VALUES(0,'https://example.test/channel','Keep channel',0)")
        val unrelated = listOf("stream_history", "stream_state", "search_history", "subscriptions").associateWith { f.mainRows("SELECT * FROM $it") }
        apply(before)
        assertEquals(0L, f.scalar("SELECT COUNT(*) FROM playlist_stream_join WHERE stream_id=$uid"))
        assertEquals(1L, f.scalar("SELECT COUNT(*) FROM streams WHERE uid=$uid"))
        assertEquals(0L, f.sideScalar("SELECT COUNT(*) FROM assignment WHERE url='${b.url}'"))
        for ((table, rows) in unrelated) assertEquals(table, rows, f.mainRows("SELECT * FROM $table"))
        assertEquals(1, f.capture().catalog.tags.size)
    }

    @Test fun existingStreamMetadataIsNeverOverwrittenByCloudHints() {
        val before = f.seed()
        val a = before.catalog.library.first()
        val metadata = f.mainRows("SELECT * FROM streams WHERE url='${a.url}'")
        val target = before.catalog.copy(library = before.catalog.library.reversed())
        CloudNativeApply(f.repo).apply(before, f.head(target), target, listOf(f.hint(a)))
        assertEquals(metadata, f.mainRows("SELECT * FROM streams WHERE url='${a.url}'"))
        assertEquals(123L, f.scalar("SELECT view_count FROM streams WHERE url='${a.url}'"))
    }

    @Test fun newMissingMetadataUsesExplicitUnknownHintsWithoutExtraction() {
        val before = f.capture()
        val k = CloudKey(0, "https://example.test/unknown")
        val target = CloudCatalog(library = listOf(k))
        CloudNativeApply(f.repo).apply(before, f.head(target), target)
        assertEquals(listOf("${k.url}|NONE|-1|"), f.mainRows("SELECT title,stream_type,duration,uploader FROM streams"))
    }

    @Test fun orphanAssignmentsSurviveUnrelatedApplyAndDoNotEnterLibrary() {
        val before0 = f.seed()
        val tag = f.repo.definitions().tags.single().id
        val orphan = StreamKey(0, "https://example.test/orphan")
        f.repo.store.setTags(orphan, setOf(tag), 0)
        val before = f.capture()
        val after = apply(before)
        assertEquals(before.orphaned, after.orphaned)
        assertEquals(setOf(tag), f.repo.store.assigned(orphan))
        assertFalse(after.catalog.library.contains(CloudKey(0, orphan.url)))
        assertEquals(before0.catalog.tags.single().id, after.catalog.tags.single().id)
    }

    @Test fun conflictingOrphanReactivationBlocksBeforeJournalAndMainWrites() {
        val tag = f.repo.createTag("Orphan", null)
        val orphan = StreamKey(0, "https://example.test/orphan")
        f.repo.store.setTags(orphan, setOf(tag), 0)
        val before = f.capture()
        val target = before.catalog.copy(library = listOf(CloudKey(0, orphan.url)))
        rejects("CLOUD_ORPHAN_RECONCILIATION_REQUIRED") { apply(before, target) }
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        assertEquals(before.fingerprint, f.capture().fingerprint)
        assertEquals(setOf(tag), f.repo.store.assigned(orphan))
    }

    @Test fun remoteTagDeletionCannotSilentlyDestroyUnrelatedOrphanMetadata() {
        val tag = f.repo.createTag("Orphan", null)
        val orphan = StreamKey(0, "https://example.test/orphan")
        f.repo.store.setTags(orphan, setOf(tag), 0)
        val before = f.capture()
        rejects("CLOUD_ORPHAN_RECONCILIATION_REQUIRED") { apply(before, CloudCatalog()) }
        assertEquals(setOf(tag), f.repo.store.assigned(orphan))
    }

    @Test fun matchingOrphanCanRejoinLibraryWithExactRemoteAssignments() {
        val tag = f.repo.createTag("Recover", null)
        val k = StreamKey(0, "https://example.test/recover")
        f.repo.store.setTags(k, setOf(tag), 0)
        val before = f.capture()
        val target = before.catalog.copy(
            library = listOf(CloudKey(k.serviceId, k.url)),
            assignments = listOf(CloudAssignment(CloudKey(k.serviceId, k.url), listOf(before.catalog.tags.single().id)))
        )
        val after = apply(before, target)
        assertTrue(after.orphaned.isEmpty())
        assertEquals(target, after.catalog)
    }

    @Test fun categoryAndTagNameSwapsPreserveTheirPortableAndNativeIds() {
        val first = f.repo.createCategory("First")
        val second = f.repo.createCategory("Second")
        f.repo.createTag("One", first)
        f.repo.createTag("Two", second)
        val before = f.capture()
        val cats = before.catalog.categories
        val tags = before.catalog.tags
        val target = before.catalog.copy(
            categories = listOf(cats[0].copy(name = cats[1].name, nameKey = cats[1].nameKey), cats[1].copy(name = cats[0].name, nameKey = cats[0].nameKey)),
            tags = listOf(tags[0].copy(name = tags[1].name, nameKey = tags[1].nameKey, category = cats[1].id), tags[1].copy(name = tags[0].name, nameKey = tags[0].nameKey, category = cats[0].id))
        )
        val result = apply(before, target)
        assertEquals(before.nativeIds, result.nativeIds)
        assertEquals(f.head(target).fingerprint, result.fingerprint)
    }

    @Test fun categoryDeletionRemovesItsTagsButNotSavedVideos() {
        val before = f.seed()
        val target = before.catalog.copy(categories = emptyList(), tags = emptyList(), assignments = emptyList())
        val after = apply(before, target)
        assertEquals(before.catalog.library, after.catalog.library)
        assertTrue(after.catalog.tags.isEmpty())
        assertEquals(before.catalog.playlists, after.catalog.playlists)
    }

    @Test fun staleNativeAndTagPreviewsAreRejectedBeforeAnyApply() {
        val before = f.seed()
        f.media("Later")
        rejects("CLOUD_LOCAL_PREVIEW_CHANGED") { apply(before) }
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        val fresh = f.capture()
        f.repo.createTag("Later tag", null)
        rejects("CLOUD_LOCAL_PREVIEW_CHANGED") { apply(fresh) }
    }

    @Test fun staleRuntimeGenerationAndAdmissionCannotModifyTheCatalog() {
        val before = f.seed()
        val target = f.target(before)
        rejects("disabled-generation") { CloudNativeApply(f.repo).apply(before, f.head(target), target, admit = { error("disabled-generation") }) }
        assertEquals(before.fingerprint, f.capture().fingerprint)
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
    }

    @Test fun exactHeadDigestAndScopeAreRequired() {
        val before = f.seed()
        val target = f.target(before)
        rejects("CLOUD_TARGET_MISMATCH") { CloudNativeApply(f.repo).apply(before, f.head(target).copy(fingerprint = "0".repeat(64)), target) }
        rejects("CLOUD_TARGET_MISMATCH") { CloudNativeApply(f.repo).apply(before, f.head(target).copy(library = "other"), target) }
        assertEquals(before.fingerprint, f.capture().fingerprint)
    }

    @Test fun remoteRollbackDoesNotOverwriteAcceptedCatalog() {
        val before = f.seed()
        val after = apply(before, revision = 4)
        rejects("CLOUD_REMOTE_ROLLBACK_SUSPECTED") { apply(after, before.catalog, 3) }
        rejects("CLOUD_REMOTE_ROLLBACK_SUSPECTED") { apply(after, before.catalog, 4) }
        assertEquals(after.fingerprint, f.capture().fingerprint)
    }

    @Test fun invalidHintsAndTargetNeverStageAnOperation() {
        val before = f.seed()
        val target = f.target(before)
        val d = target.library.first()
        assertThrows(IllegalArgumentException::class.java) { CloudNativeApply(f.repo).apply(before, f.head(target), target, listOf(f.hint(d).copy(streamType = "MALFORMED"))) }
        assertThrows(IllegalArgumentException::class.java) { CloudNativeApply(f.repo).apply(before, f.head(target), target, listOf(f.hint(CloudKey(0, "https://example.test/other")))) }
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
    }

    @Test fun remoteBookmarksRemainOrderedAndTheirContentIsUnchanged() {
        val before0 = f.seed()
        for (i in 1..3) f.db.playlistRemoteDAO().insert(PlaylistRemoteEntity(serviceId = 0, orderingName = "Remote $i", url = "https://example.test/remote$i", thumbnailUrl = null, uploader = "Keep", displayIndex = i.toLong(), streamCount = 3))
        val before = f.capture()
        val saved = f.mainRows("SELECT uid,service_id,name,url,uploader,stream_count FROM remote_playlists ORDER BY display_index,uid")
        val after = apply(before)
        assertEquals(saved, f.mainRows("SELECT uid,service_id,name,url,uploader,stream_count FROM remote_playlists ORDER BY display_index,uid"))
        assertEquals(before0.identities.libraryId, after.identities.libraryId)
    }

    @Test fun applyingRemoteChangesInvalidatesStaleNativeTagEditors() {
        val before = f.seed()
        val a = before.catalog.library.first()
        val edit = f.repo.editor(StreamKey(a.service, a.url))
        apply(before)
        assertThrows(IllegalStateException::class.java) { f.repo.store.verifyEdit(StreamKey(a.service, a.url), edit.revision, edit.assigned) }
    }

    @Test fun interruptionBeforeRoomTransactionCommitsNeverReplaysMainChanges() {
        for (phase in listOf(CloudNativeApply.Phase.JOURNAL_DURABLE, CloudNativeApply.Phase.BEFORE_ROOM_COMMIT)) {
            val before = if (CloudIdentityStore.context(f.repo.store.readableDatabase) == null) f.seed() else f.capture()
            interrupt(phase, before)
            assertNotNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
            f.reopen()
            f.repo.definitions()
            assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
            assertEquals(before.fingerprint, f.capture().fingerprint)
        }
    }

    @Test fun interruptionAfterRoomCommitCompletesTagsAndMappingsExactlyOnce() {
        val before = f.seed()
        val target = f.target(before)
        interrupt(CloudNativeApply.Phase.ROOM_COMMITTED, before, target)
        val witness = f.db.cloudApplyReceiptDao().current()!!
        assertNotNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        f.reopen()
        f.repo.definitions()
        val after = f.capture()
        assertEquals(f.head(target).fingerprint, after.fingerprint)
        assertEquals(before.tagRevision + 1, after.tagRevision)
        repeat(3) {
            f.repo.definitions()
            f.capture()
        }
        assertEquals(after.tagRevision, f.repo.store.version())
        assertEquals(witness, f.db.cloudApplyReceiptDao().current())
    }

    @Test fun tagPhaseRollbackLeavesReceiptAndJournalForSafeCompletion() {
        val before = f.seed()
        interrupt(CloudNativeApply.Phase.BEFORE_TAG_COMMIT, before)
        assertEquals(before.tagRevision, f.repo.store.version())
        assertEquals(before.nativeIds, CloudIdentityStore.mappings(f.repo.store.readableDatabase))
        f.reopen()
        f.repo.definitions()
        assertEquals(f.head(f.target(before)).fingerprint, f.capture().fingerprint)
        assertEquals(before.tagRevision + 1, f.repo.store.version())
    }

    @Test fun lostFinalAcknowledgmentHasAcceptedBaselineWithoutPendingReplay() {
        val before = f.seed()
        interrupt(CloudNativeApply.Phase.TAG_COMMITTED, before)
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        assertEquals(f.head(f.target(before)), CloudApplyStore.baseline(f.repo.store.readableDatabase)!!.head)
        rejects("CLOUD_LOCAL_PREVIEW_CHANGED") { apply(before) }
    }

    @Test fun corruptPendingBytesBlockWithoutReapplyingRoom() {
        val before = f.seed()
        interrupt(CloudNativeApply.Phase.ROOM_COMMITTED, before)
        val witness = f.db.cloudApplyReceiptDao().current()
        f.repo.store.writableDatabase.execSQL("UPDATE cloud_apply_payload SET bytes=zeroblob(length(bytes)) WHERE chunk_index=0")
        rejects("CORRUPT_CLOUD_PAYLOAD_DIGEST") { f.repo.definitions() }
        assertEquals(witness, f.db.cloudApplyReceiptDao().current())
        assertNotNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
    }

    @Test fun missingWitnessAfterChangedRoomStateDoesNotGuessOrReplay() {
        val before = f.seed()
        interrupt(CloudNativeApply.Phase.ROOM_COMMITTED, before)
        f.rawMain("DELETE FROM rv_cloud_apply_receipt")
        assertThrows(IllegalStateException::class.java) { f.repo.definitions() }
        assertNotNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        assertEquals(2L, f.scalar("SELECT COUNT(*) FROM playlist_stream_join WHERE playlist_id=${before.identities.libraryId}"))
    }

    @Test fun changedWitnessOrMainProjectionBlocksRecovery() {
        val before = f.seed()
        interrupt(CloudNativeApply.Phase.ROOM_COMMITTED, before)
        f.rawMain("UPDATE rv_cloud_apply_receipt SET plan_digest=?", "0".repeat(64))
        rejects("CLOUD_APPLY_WITNESS_MISMATCH") { f.repo.definitions() }
        val pending = CloudApplyStore.pending(f.repo.store.readableDatabase)!!
        f.rawMain("UPDATE rv_cloud_apply_receipt SET plan_digest=?", pending.digest)
        f.rawMain("UPDATE playlists SET name='Unrelated change' WHERE uid=3")
        rejects("CLOUD_MAIN_NAME_MISMATCH") { f.repo.definitions() }
        assertNotNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
    }

    @Test fun changedTagRevisionStopsRecoveryInsteadOfOverwritingNewTags() {
        val before = f.seed()
        interrupt(CloudNativeApply.Phase.ROOM_COMMITTED, before)
        f.repo.store.createTag("External", null)
        rejects("CLOUD_RECOVERY_TAGS_CHANGED") { f.repo.definitions() }
        assertTrue(f.repo.store.definitions().tags.any { it.name == "External" })
    }

    @Test fun ordinaryNativeMutationFirstCompletesPendingCloudMetadata() {
        val before = f.seed()
        interrupt(CloudNativeApply.Phase.ROOM_COMMITTED, before)
        val remote = f.target(before)
        f.media("Native after recovery")
        val after = f.capture()
        assertEquals(remote.library + CloudKey(0, "https://example.test/Native after recovery"), after.catalog.library)
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        assertEquals(f.head(remote), CloudApplyStore.baseline(f.repo.store.readableDatabase)!!.head)
    }

    @Test fun pendingRecoveryRequiresMatchingDatabaseLifetime() {
        val before = f.seed()
        interrupt(CloudNativeApply.Phase.ROOM_COMMITTED, before)
        f.repo.binding.write(f.repo.binding.read()!!.copy(cloudIncarnation = UUID.randomUUID().toString()))
        rejects("CLOUD_RECOVERY_BINDING_CHANGED") { f.repo.definitions() }
        assertNotNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
    }

    @Test fun unchangedSnapshotAcceptsBaselineWithoutLosingData() {
        val before = f.seed()
        val after = apply(before, before.catalog)
        assertEquals(before.fingerprint, after.fingerprint)
        assertEquals(before.nativeIds, after.nativeIds)
        assertEquals(f.head(before.catalog), CloudApplyStore.baseline(f.repo.store.readableDatabase)!!.head)
    }

    @Test fun applyRefusesMainThreadBeforeMutation() {
        val before = f.seed()
        val error = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                apply(before)
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        assertEquals("CLOUD_IO_ON_MAIN_THREAD", error.get()?.message)
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
    }

    @Test fun fullBackupStripsApplyReceiptsBaselineAndPendingTables() {
        val before = f.seed()
        apply(before)
        val output = Files.createFile(f.dir.resolve("export.zip"))
        val helper = StoredFileHelper(f.context, null, Uri.fromFile(output.toFile()), "cloud-apply-test")
        TagBackupCoordinator.export(f.context, f.context.getSharedPreferences("cloud-apply-fixture", Context.MODE_PRIVATE), helper)
        TagBackupCoordinator.prepare(f.context, helper).use { prepared ->
            TagStore(f.context, prepared.directory.resolve(TagStore.FILE_NAME).toString()).use { tags ->
                assertNull(CloudApplyStore.pending(tags.readableDatabase))
                assertNull(CloudApplyStore.baseline(tags.readableDatabase))
                assertNull(CloudIdentityStore.context(tags.readableDatabase))
            }
            val main = org.schabi.newpipe.NewPipeDatabase.openStagedCopy(f.context, prepared.directory.resolve(AppDatabase.DATABASE_NAME))
            try {
                assertNull(main.cloudApplyReceiptDao().current())
            } finally {
                main.close()
            }
        }
        assertNotNull(f.db.cloudApplyReceiptDao().current())
        assertNotNull(CloudApplyStore.baseline(f.repo.store.readableDatabase))
    }

    @Test fun plansUseExact64BitIdsAndRejectTamperedShapes() {
        val before = f.seed()
        interrupt(CloudNativeApply.Phase.JOURNAL_DURABLE, before)
        val pending = CloudApplyStore.pending(f.repo.store.readableDatabase)!!
        val bytes = CloudApplyStore.payload(f.repo.store.readableDatabase, pending)
        val plan = CloudApplyPlanJson.decode(bytes)
        assertEquals(plan, CloudApplyPlanJson.decode(CloudApplyPlanJson.encode(plan)))
        val json = JsonParser.`object`().from(bytes.toString(Charsets.UTF_8))
        json["unexpected"] = true
        assertThrows(IllegalArgumentException::class.java) { CloudApplyPlanJson.decode(JsonWriter.string().`object`(json).done().toByteArray()) }
        assertThrows(Exception::class.java) { CloudApplyPlanJson.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test fun chunkedPendingPlanIsDurableAndDetectsMissingChunks() {
        val before = f.seed()
        interrupt(CloudNativeApply.Phase.JOURNAL_DURABLE, before)
        val pending = CloudApplyStore.pending(f.repo.store.readableDatabase)!!
        f.repo.store.writableDatabase.execSQL("DELETE FROM cloud_apply_payload WHERE chunk_index=0")
        rejects("CORRUPT_CLOUD_PAYLOAD") { f.repo.definitions() }
        assertEquals(pending, CloudApplyStore.pending(f.repo.store.readableDatabase))
    }

    @Test fun oneThousandStreamsCanBeAppliedAndGloballyRemoved() {
        val before = f.capture()
        val keys = (0 until 1000).map { CloudKey(0, "https://example.test/bulk/$it") }
        val target = CloudCatalog(
            library = keys,
            playlists = (1..5).map {
                CloudPlaylist("30000000-0000-0000-0000-${it.toString().padStart(12, '0')}", "Cloud $it", null, keys)
            }
        )
        val start = System.nanoTime()
        val after = apply(before, target)
        assertEquals(1000, after.catalog.library.size)
        assertEquals(5000, after.catalog.playlists.sumOf { it.items.size })
        assertEquals(1000L, f.scalar("SELECT COUNT(*) FROM streams"))
        val removed = after.catalog.copy(library = emptyList(), playlists = after.catalog.playlists.map { it.copy(items = emptyList()) })
        val final = apply(after, removed, 2)
        assertTrue(final.catalog.library.isEmpty())
        assertEquals(1000L, f.scalar("SELECT COUNT(*) FROM streams"))
        assertEquals(0L, f.scalar("SELECT COUNT(*) FROM playlist_stream_join"))
        println("CLOUD_NATIVE_APPLY_SCALE streams=1000 ordinary_memberships=5000 full_apply_and_remove_ms=${(System.nanoTime() - start) / 1_000_000}")
    }

    @Test fun higherHeadCounterCannotHideARevisionRollbackWithinTheSameEpoch() {
        val before = f.seed()
        val after = apply(before, revision = 4)
        val regressed = f.head(before.catalog, 3).copy(headCounter = "5")
        rejects("CLOUD_REMOTE_ROLLBACK_SUSPECTED") { CloudNativeApply(f.repo).apply(after, regressed, before.catalog) }
        assertEquals(after.fingerprint, f.capture().fingerprint)
    }

    @Test fun twoIndependentNativeCatalogsExchangeContentWithoutSharingNativeIds() {
        val a = f
        val source = a.seed()
        val app = ApplicationProvider.getApplicationContext<Context>()
        val secondDir = Files.createTempDirectory(app.noBackupFilesDir.toPath(), "cloud-other-install-")
        val b = CloudApplyFixture(app, secondDir)
        try {
            b.initialize()
            b.main("UPDATE sqlite_sequence SET seq=500 WHERE name='playlists'")
            b.playlist("Unrelated local", listOf(b.media("B-local")))
            val beforeB = b.capture()
            val appliedB = CloudNativeApply(b.repo).apply(beforeB, b.head(source.catalog), source.catalog, source.hints.values)
            assertEquals(source.fingerprint, appliedB.fingerprint)
            val aIds = source.nativeIds.filterKeys { it.kind == Kind.PLAYLIST }.entries.associate { it.value to it.key.id }
            val bIds = appliedB.nativeIds.filterKeys { it.kind == Kind.PLAYLIST }.entries.associate { it.value to it.key.id }
            assertEquals(aIds.keys, bIds.keys)
            assertTrue(aIds.keys.all { aIds[it] != bIds[it] })
            b.media("B-new")
            val changedB = b.capture()
            a.reopen()
            a.initialize()
            val beforeA = a.capture()
            val appliedA = CloudNativeApply(a.repo).apply(beforeA, a.head(changedB.catalog, 2), changedB.catalog, changedB.hints.values)
            assertEquals(changedB.fingerprint, appliedA.fingerprint)
            assertEquals(aIds, appliedA.nativeIds.filterKeys { it.kind == Kind.PLAYLIST }.entries.associate { it.value to it.key.id })
            assertTrue(appliedA.catalog.library.contains(CloudKey(0, "https://example.test/B-new")))
        } finally {
            b.close()
            CatalogFileSwap.deleteTree(secondDir)
        }
    }

    @Test fun permanentArtworkOutsideLibrarySurvivesNativeOrphanCleanup() {
        val before = f.capture()
        val artwork = CloudKey(0, "https://example.test/art-only")
        val target = CloudCatalog(playlists = listOf(CloudPlaylist("30000000-0000-0000-0000-000000000123", "Artwork only", artwork, emptyList())))
        val applied = apply(before, target)
        f.rawMain("INSERT INTO streams(uid,service_id,url,title,stream_type,duration,uploader) VALUES(900,0,'https://example.test/disposable','Disposable','NONE',-1,'')")
        assertEquals(1, f.db.streamDAO().deleteOrphans())
        assertEquals(1L, f.scalar("SELECT COUNT(*) FROM streams"))
        assertEquals(applied.fingerprint, f.capture().fingerprint)
        assertTrue(f.capture().catalog.library.isEmpty())
    }

    @Test fun permanentArtworkCleanupBetweenRoomCommitAndRecoveryIsSafe() {
        val before = f.capture()
        val artwork = CloudKey(0, "https://example.test/pending-art")
        val target = CloudCatalog(playlists = listOf(CloudPlaylist("30000000-0000-0000-0000-000000000123", "Artwork only", artwork, emptyList())))
        interrupt(CloudNativeApply.Phase.ROOM_COMMITTED, before, target)
        assertEquals(0, f.db.streamDAO().deleteOrphans())
        f.reopen()
        f.initialize()
        assertEquals(f.head(target).fingerprint, f.capture().fingerprint)
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
    }

    private fun recoveryBackup(name: String): Pair<java.nio.file.Path, StoredFileHelper> {
        val output = Files.createFile(f.dir.resolve(name))
        val helper = StoredFileHelper(f.context, null, Uri.fromFile(output.toFile()), "cloud-recovery-test")
        TagBackupCoordinator.export(f.context, f.context.getSharedPreferences("cloud-recovery-fixture", Context.MODE_PRIVATE), helper)
        return output to helper
    }

    @Test fun completeValidatedNativeBackupCanReplaceACorruptCloudJournal() {
        val before = f.seed()
        val (_, backup) = recoveryBackup("before-corruption.zip")
        interrupt(CloudNativeApply.Phase.ROOM_COMMITTED, before)
        f.repo.store.writableDatabase.execSQL("UPDATE cloud_apply_payload SET bytes=zeroblob(length(bytes)) WHERE chunk_index=0")
        assertEquals("CORRUPT_CLOUD_PAYLOAD_DIGEST", assertThrows(CloudRecoveryBlockedException::class.java) { f.repo.definitions() }.message)
        TagBackupCoordinator.prepare(f.context, backup).use { TagBackupCoordinator.installWithShutdown(f.context, it) {} }
        f.reopen()
        f.initialize()
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        assertNull(CloudApplyStore.baseline(f.repo.store.readableDatabase))
        assertNull(f.db.cloudApplyReceiptDao().current())
        assertNull(CloudIdentityStore.context(f.repo.store.readableDatabase))
        val restored = f.capture()
        assertEquals(before.catalog.library, restored.catalog.library)
        assertEquals(before.catalog.playlists.map { it.name to it.items }, restored.catalog.playlists.map { it.name to it.items })
        assertEquals(before.catalog.tags.map { it.name }, restored.catalog.tags.map { it.name })
        assertNotEquals(before.identities.incarnation, restored.identities.incarnation)
    }

    @Test fun stockBackupMayNotRetainTagsFromABlockedCloudRecovery() {
        val before = f.seed()
        val (full, _) = recoveryBackup("complete.zip")
        val path = f.dir.resolve("stock.zip")
        ZipFile(full.toFile()).use { input ->
            ZipOutputStream(Files.newOutputStream(path)).use { output ->
                for (entry in input.entries().toList().filter { it.name != TagStore.FILE_NAME }) {
                    output.putNextEntry(ZipEntry(entry.name))
                    input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        interrupt(CloudNativeApply.Phase.ROOM_COMMITTED, before)
        f.repo.store.writableDatabase.execSQL("UPDATE cloud_apply_payload SET bytes=zeroblob(length(bytes)) WHERE chunk_index=0")
        val helper = StoredFileHelper(f.context, null, Uri.fromFile(path.toFile()), "cloud-recovery-test")
        TagBackupCoordinator.prepare(f.context, helper).use { prepared ->
            assertThrows(CloudRecoveryBlockedException::class.java) { TagBackupCoordinator.installWithShutdown(f.context, prepared) {} }
        }
        assertFalse(TagRuntime.restoring)
        assertNotNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        assertEquals(2L, f.scalar("SELECT COUNT(*) FROM playlist_stream_join WHERE playlist_id=${before.identities.libraryId}"))
    }
}
