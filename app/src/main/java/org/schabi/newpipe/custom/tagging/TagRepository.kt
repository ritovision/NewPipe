/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.concurrent.Callable
import kotlin.concurrent.withLock
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.Converters
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** One concrete coordinator for the independently versioned tag and canonical Library stores. */
class TagRepository(
    val library: AppDatabase,
    internal val store: TagStore,
    private val binding: LibraryBindingStore
) {
    data class Item(val stream: StreamEntity, val tagIds: Set<Long>)
    data class Snapshot(val definitions: TagDefinitions, val items: List<Item>, val orphanedAssignments: Int)

    /** Invoked BEFORE a Room write transaction, never while holding Room's writer lock. */
    internal fun beforeLibraryMutation() {
        try {
            recoverPending()
        } catch (error: LibraryRepository.IdentityRepairRequiredException) {
            // Preserve the existing ability to edit ordinary playlists while Library is unbound.
            // Pending destructive intents, however, must not run against an unverified Library.
            if (store.pendingRemovals().isNotEmpty()) throw error
        }
    }

    internal fun recoverPending(): Long {
        TagRuntime.assertAvailable()
        val libraryId = LibraryRepository.initialize(library, binding)
        for (intent in store.pendingRemovals()) {
            check(intent.libraryId == libraryId) { "Library identity changed during an interrupted removal. Restore the matching backup before making changes." }
            val uid = currentUid(intent.key)
            if (uid != null) LibraryRepository.removeEverywhere(library, libraryId, uid)
            store.finishRemoval(intent.key)
        }
        return libraryId
    }

    private fun <T> access(action: (Long) -> T): T = TagRuntime.lock.withLock {
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

    fun createCategory(name: String): Long = access { store.createCategory(name).also { TagRuntime.changed() } }
    fun createTag(name: String, categoryId: Long?): Long = access { store.createTag(name, categoryId).also { TagRuntime.changed() } }
    fun renameCategory(id: Long, name: String) = access { store.renameCategory(id, name).also { TagRuntime.changed() } }
    fun editTag(id: Long, name: String, categoryId: Long?) = access { store.editTag(id, name, categoryId).also { TagRuntime.changed() } }
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

    internal fun currentUid(key: StreamKey): Long? = library.openHelper.readableDatabase.query(
        SimpleSQLiteQuery("SELECT uid FROM streams WHERE service_id=? AND url=?", arrayOf<Any>(key.serviceId, key.url))
    ).use { if (it.moveToFirst()) it.getLong(0) else null }
}
