/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.UUID
import java.util.concurrent.Callable
import kotlin.concurrent.withLock
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.custom.library.UnavailableRepository
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.Converters
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** One concrete coordinator for the independently versioned tag and canonical Library stores. */
class TagRepository(
    val library: AppDatabase,
    internal val store: TagStore,
    internal val binding: LibraryBindingStore
) {
    @Volatile private var invalidated = false
    internal fun invalidate() {
        invalidated = true
    }

    class NativeBulkRecoveryBlockedException(message: String) : IllegalStateException(message)

    data class Item(val stream: StreamEntity, val tagIds: Set<Long>)
    data class Snapshot(val definitions: TagDefinitions, val items: List<Item>, val orphanedAssignments: Int)

    /** Invoked BEFORE a Room write transaction, never while holding Room's writer lock. */
    internal fun beforeLibraryMutation() {
        try {
            recoverPending()
        } catch (error: LibraryRepository.IdentityRepairRequiredException) {
            // Preserve the existing ability to edit ordinary playlists while Library is unbound.
            // Pending destructive intents, however, must not run against an unverified Library.
            if (store.pendingRemovals().isNotEmpty() || store.pendingNativeBulks().isNotEmpty()) throw error
        }
    }

    internal fun recoverPending(): Long {
        TagRuntime.assertAvailable()
        check(!invalidated) { "This Library was closed or replaced. Restart the app." }
        // Resolve an already-committed cloud write before any native/AI cleanup can act.
        org.schabi.newpipe.custom.cloud.CloudNativeApply.recoverLocked(this)
        val libraryId = LibraryRepository.initialize(library, binding)
        for (intent in store.pendingAiRemovals()) {
            // A same-database receipt proves the old deletion commit even after a user
            // repairs Library identity. This cleanup never replays the Library deletion.
            val receipt = library.aiCommandReceiptDao().find(intent.device, intent.epoch, intent.client, intent.request)
            check(receipt == null || receipt.fingerprint == intent.fingerprint) { "Command identity mismatch during tag cleanup" }
            // No receipt means Room rolled back. Never replay an AI deletion from tag storage.
            store.settleAiRemoval(intent, receipt != null)
        }
        // Room membership is the authority for native bulk removal.
        // Crash before COMMIT: all items remain saved -> discard intents.
        // Crash after COMMIT: none remains saved -> finish tag cleanup only.
        // Never replay the native batch deletion from independent tag storage.
        for (batch in store.pendingNativeBulks()) {
            if (batch.libraryId != libraryId) {
                throw NativeBulkRecoveryBlockedException("Library identity changed during an interrupted bulk removal")
            }
            val saved = readLibraryStreams(libraryId).mapTo(HashSet()) {
                StreamKey(it.serviceId, it.url)
            }
            val survivors = batch.keys.count { it in saved }
            if (survivors != 0 && survivors != batch.keys.size) {
                throw NativeBulkRecoveryBlockedException(
                    "Partial native batch state detected; refusing automatic deletion or cleanup"
                )
            }
            store.settleNativeBulk(batch, committed = survivors == 0)
        }
        for (intent in store.pendingRemovals()) {
            check(intent.libraryId == libraryId) { "Library identity changed during an interrupted removal. Restore the matching backup before making changes." }
            val uid = currentUid(intent.key)
            if (uid != null) LibraryRepository.removeEverywhere(library, libraryId, uid)
            store.finishRemoval(intent.key)
        }
        return libraryId
    }

    internal fun <T> access(action: (Long) -> T): T = TagRuntime.lock.withLock {
        action(recoverPending())
    }

    fun definitions(): TagDefinitions = access { store.definitions() }

    fun editor(key: StreamKey): TagEditSnapshot = access { store.editSnapshot(key) }

    fun apply(stream: StreamEntity, tagIds: Set<Long>, expectedRevision: Long) = access { libraryId ->
        val key = StreamKey(stream.serviceId, stream.url)
        // Validate BEFORE saving. A stale editor cannot re-add a globally removed stream.
        store.verifyEdit(key, expectedRevision, tagIds)
        if (tagIds.isNotEmpty()) {
            library.runInTransaction {
                // Tag edits must not overwrite an existing stream with a sparse/stale
                // details-screen snapshot (for example losing its known view count).
                val uid = currentUid(key) ?: library.streamDAO().upsert(stream.copy(uid = 0))
                LibraryRepository.appendDirectly(library, libraryId, listOf(uid))
            }
        }
        // This may fail after Library save: saved-but-untagged is intentionally valid.
        store.setTags(key, tagIds, expectedRevision)
        TagRuntime.changed()
    }

    /** Saved-only metadata mutation shared with AI; it never upserts stream metadata. */
    internal fun setSavedTags(key: StreamKey, tagIds: Set<Long>, expectedRevision: Long) = access { libraryId ->
        val uid = currentUid(key) ?: error("Video is not in Library")
        val saved = library.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT 1 FROM playlist_stream_join WHERE playlist_id=? AND stream_id=?", arrayOf(libraryId, uid))).use { it.moveToFirst() }
        check(saved) { "Video is not in Library" }
        store.setTags(key, tagIds, expectedRevision)
        TagRuntime.changed()
    }

    fun createCategory(name: String): Long = access { store.createCategory(name).also { TagRuntime.changed() } }
    fun createTag(name: String, categoryId: Long?): Long = access { store.createTag(name, categoryId).also { TagRuntime.changed() } }
    fun renameCategory(id: Long, name: String, expectedRevision: Long? = null) = access { store.renameCategory(id, name, expectedRevision).also { TagRuntime.changed() } }
    fun editTag(id: Long, name: String, categoryId: Long?, expectedRevision: Long? = null) = access { store.editTag(id, name, categoryId, expectedRevision).also { TagRuntime.changed() } }
    fun deletionPlan(id: Long, category: Boolean): TagDeletePlan = access { store.deletionPlan(id, category) }
    fun delete(plan: TagDeletePlan) = access { store.delete(plan).also { TagRuntime.changed() } }

    fun removeEverywhere(libraryId: Long, uid: Long) = access { currentLibrary ->
        check(libraryId == currentLibrary) { "Library changed; removal was not performed" }
        val key = library.openHelper.readableDatabase.query(
            SimpleSQLiteQuery(
                "SELECT s.service_id,s.url FROM streams s JOIN playlist_stream_join j ON j.stream_id=s.uid WHERE j.playlist_id=? AND s.uid=? LIMIT 1",
                arrayOf(libraryId, uid)
            )
        ).use { c -> if (c.moveToFirst()) StreamKey(c.getInt(0), c.getString(1)) else null }
        if (key != null) {
            store.beginRemoval(key, libraryId)
            // On interruption this durable user-initiated operation finishes before any re-add.
            LibraryRepository.removeEverywhere(library, libraryId, uid)
            store.finishRemoval(key)
            TagRuntime.changed()
        }
    }

    /**
     * Confirmed native bulk removal: all Room playlist deletions commit as one
     * transaction; durable tag intents complete cleanup after a process death.
     */
    fun removeUnavailableEverywhere(expected: org.schabi.newpipe.custom.library.UnavailableRepository.Preview) = access { libraryId ->
        val unavailable = org.schabi.newpipe.custom.library.UnavailableRepository
        unavailable.ensure(library, binding)
        unavailable.verify(library, expected)
        val byId = readLibraryStreams(libraryId).associateBy { it.uid }
        val keys = expected.items.map { id ->
            val stream = byId[id] ?: error("The marked video is no longer in Library")
            StreamKey(stream.serviceId, stream.url)
        }
        if (keys.isEmpty()) return@access
        // Separate from legacy single-item intents, which intentionally replay.
        val batch = TagStore.NativeBulk(UUID.randomUUID().toString(), libraryId, keys)
        store.stageNativeBulk(batch)
        // On rollback the playlist memberships still exist. Recovery discards
        // the staged intent without touching the songs or their tags.
        library.runInTransaction {
            unavailable.verify(library, expected)
            for (id in expected.items) {
                check(LibraryRepository.removeEverywhere(library, libraryId, id)) {
                    "The marked video is no longer saved"
                }
            }
        }
        // After COMMIT, if tag cleanup fails or the process dies, recovery
        // finishes tag cleanup only. Never run these deletions a second time.
        recoverPending()
        TagRuntime.changed()
    }

    fun snapshot(): Snapshot = access { libraryId ->
        val definitions = store.definitions()
        val assignments = store.allAssignments()
        val rows = readLibraryStreams(libraryId)
        val keys = HashSet<StreamKey>()
        val items = rows.mapNotNull { stream ->
            val key = StreamKey(stream.serviceId, stream.url)
            if (!keys.add(key)) null else Item(stream, assignments[key] ?: emptySet())
        }
        // An imported stock Library may omit streams. Retain associations, but never expose
        // missing streams as Library content or silently purge their recoverable metadata.
        Snapshot(definitions, items, assignments.filterKeys { it !in keys }.values.sumOf { it.size })
    }

    /** Direct cursor avoids waiting for a Room query-executor task while holding our lock. */
    internal fun readLibraryStreams(libraryId: Long): List<StreamEntity> = library.openHelper.readableDatabase.query(
        SimpleSQLiteQuery("SELECT s.* FROM streams s JOIN playlist_stream_join j ON j.stream_id=s.uid WHERE j.playlist_id=? ORDER BY j.join_index", arrayOf(libraryId))
    ).use { cursor ->
        val names = cursor.columnNames.withIndex().associate { it.value to it.index }
        fun text(name: String): String? = names.getValue(name).let { if (cursor.isNull(it)) null else cursor.getString(it) }
        fun number(name: String): Long? = names.getValue(name).let { if (cursor.isNull(it)) null else cursor.getLong(it) }
        buildList {
            while (cursor.moveToNext()) {
                add(
                    StreamEntity(
                        uid = number(StreamEntity.STREAM_ID)!!,
                        serviceId = number(StreamEntity.STREAM_SERVICE_ID)!!.toInt(),
                        url = text(StreamEntity.STREAM_URL)!!,
                        title = text(StreamEntity.STREAM_TITLE)!!,
                        streamType = StreamType.valueOf(text(StreamEntity.STREAM_TYPE)!!),
                        duration = number(StreamEntity.STREAM_DURATION)!!,
                        uploader = text(StreamEntity.STREAM_UPLOADER)!!,
                        uploaderUrl = text(StreamEntity.STREAM_UPLOADER_URL),
                        thumbnailUrl = text(StreamEntity.STREAM_THUMBNAIL_URL),
                        viewCount = number(StreamEntity.STREAM_VIEWS),
                        textualUploadDate = text(StreamEntity.STREAM_TEXTUAL_UPLOAD_DATE),
                        uploadDate = Converters().offsetDateTimeFromTimestamp(number(StreamEntity.STREAM_UPLOAD_DATE)),
                        isUploadDateApproximation = number(StreamEntity.STREAM_IS_UPLOAD_DATE_APPROXIMATION)?.let { it != 0L }
                    )
                )
            }
        }
    }

    fun resolveSnapshot(keys: List<StreamKey>): List<StreamEntity> = access { libraryId ->
        library.runInTransaction(Callable { resolveSnapshotLocked(libraryId, keys) })
    }

    private fun resolveSnapshotLocked(libraryId: Long, keys: List<StreamKey>): List<StreamEntity> {
        require(keys.isNotEmpty() && keys.distinct().size == keys.size) { "Choose a nonempty distinct result snapshot" }
        val saved = readLibraryStreams(libraryId).associateBy { StreamKey(it.serviceId, it.url) }
        return keys.map { saved[it] ?: error("A selected video was removed from Library. Refresh the results before retrying.") }
    }

    fun createPlaylistSnapshot(name: String, keys: List<StreamKey>): Long = access { libraryId ->
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 150) { "Use a playlist name between 1 and 150 characters" }
        require(!title.equals(UnavailableRepository.NAME, ignoreCase = true)) {
            "Unavailable is reserved for the protected playlist"
        }
        library.runInTransaction(
            Callable {
                val streams = resolveSnapshotLocked(libraryId, keys)
                val id = library.playlistDAO().insert(PlaylistEntity(name = title, isThumbnailPermanent = false, thumbnailStreamId = streams.first().uid, displayIndex = -1))
                library.playlistStreamDAO().insertAll(streams.mapIndexed { index, stream -> PlaylistStreamEntity(id, stream.uid, index) })
                id
            }
        )
    }

    internal fun currentUid(key: StreamKey): Long? = library.openHelper.readableDatabase.query(
        SimpleSQLiteQuery("SELECT uid FROM streams WHERE service_id=? AND url=?", arrayOf<Any>(key.serviceId, key.url))
    ).use { if (it.moveToFirst()) it.getLong(0) else null }
}
