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
import org.schabi.newpipe.database.stream.model.StreamEntity

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
                val saved = stream.copy(uid = 0)
                val uid = library.streamDAO().upsert(saved)
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
        val rows = library.playlistStreamDAO().getOrderedStreamsOf(libraryId).blockingFirst()
        val keys = HashSet<StreamKey>()
        val items = rows.mapNotNull { row ->
            val key = StreamKey(row.streamEntity.serviceId, row.streamEntity.url)
            if (!keys.add(key)) null else Item(row.streamEntity.copy(), assignments[key] ?: emptySet())
        }
        // An imported stock Library may omit streams. Retain associations, but never expose
        // missing streams as Library content or silently purge their recoverable metadata.
        Snapshot(definitions, items, assignments.filterKeys { it !in keys }.values.sumOf { it.size })
    }

    internal fun currentUid(key: StreamKey): Long? = library.openHelper.readableDatabase.query(
        SimpleSQLiteQuery("SELECT uid FROM streams WHERE service_id=? AND url=?", arrayOf<Any>(key.serviceId, key.url))
    ).use { if (it.moveToFirst()) it.getLong(0) else null }
}
