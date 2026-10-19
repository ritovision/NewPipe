/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Callable
import kotlin.concurrent.withLock
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.Kind
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.NativeId
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.custom.library.UnavailableRepository
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime

/**
 * Local, exact-candidate write adapter. A future network/UI coordinator must provide
 * connection-generation admission and required preview/recovery authorization; this
 * primitive neither enables sync nor treats caller data as cloud authentication.
 */
internal class CloudNativeApply(
    private val repository: TagRepository,
    private val fault: (Phase) -> Unit = {}
) {
    enum class Phase { JOURNAL_DURABLE, BEFORE_ROOM_COMMIT, ROOM_COMMITTED, BEFORE_TAG_COMMIT, TAG_COMMITTED }

    fun apply(
        before: CloudNativeSnapshot,
        head: CloudHead,
        target: CloudCatalog,
        hints: Collection<CloudMediaHint> = emptyList(),
        reconcileOrphans: Boolean = false,
        admit: () -> Unit = {}
    ): CloudNativeSnapshot {
        check(Looper.myLooper() != Looper.getMainLooper()) { "CLOUD_IO_ON_MAIN_THREAD" }
        return TagRuntime.lock.withLock {
            available(repository)
            recoverLocked(repository)
            admit()
            check(before.runtimeGeneration == TagRuntime.generation) { "CLOUD_PREVIEW_RUNTIME_CHANGED" }
            val main = repository.library.openHelper.writableDatabase
            val side = repository.store.writableDatabase
            check(!main.inTransaction() && !side.inTransaction()) { "CLOUD_APPLY_NESTED_TRANSACTION" }
            val plan = repository.library.runInTransaction(
                Callable {
                    val current = repository.store.transaction {
                        CloudNativeCatalog(repository).verifyAppliedInTransaction(before.identities.scope, before.identities.incarnation)
                    }
                    check(
                        current.identities == before.identities && current.fingerprint == before.fingerprint &&
                            current.tagRevision == before.tagRevision && current.nativeIds == before.nativeIds &&
                            current.orphaned == before.orphaned
                    ) { "CLOUD_LOCAL_PREVIEW_CHANGED" }
                    val baseline = CloudApplyStore.baseline(side)
                    if (baseline != null) {
                        check(baseline.incarnation == before.identities.incarnation) { "CLOUD_BASELINE_IDENTITY_CHANGED" }
                        val previous = baseline.head
                        check(previous.serviceGeneration == head.serviceGeneration && previous.library == head.library) { "CLOUD_BASELINE_SCOPE_CHANGED" }
                        check(
                            CloudCounter.parse(head.headCounter) >= CloudCounter.parse(previous.headCounter) &&
                                (head.headCounter != previous.headCounter || head == previous) &&
                                (head.epoch != previous.epoch || CloudCounter.parse(head.revision) >= CloudCounter.parse(previous.revision))
                        ) { "CLOUD_REMOTE_ROLLBACK_SUSPECTED" }
                    }
                    val compiled = CloudApplyCompiler.compile(repository, current, head, target, hints, reconcileOrphans)
                    // Round-trip the exact journal representation before anything can mutate Room.
                    val bytes = CloudApplyPlanJson.encode(compiled)
                    val immutable = CloudApplyPlanJson.decode(bytes)
                    admit()
                    val pending = repository.store.transaction { db ->
                        CloudApplyStore.save(
                            db,
                            immutable.operation,
                            immutable.context.incarnation,
                            immutable.beforeFingerprint,
                            immutable.targetHead.fingerprint,
                            bytes
                        )
                    }
                    fault(Phase.JOURNAL_DURABLE)
                    writeMain(main, immutable)
                    repository.library.cloudApplyReceiptDao().record(
                        CloudApplyReceipt(1, immutable.operation, immutable.context.incarnation, immutable.targetHead.fingerprint, pending.digest)
                    )
                    verifyMain(repository, immutable)
                    fault(Phase.BEFORE_ROOM_COMMIT)
                    immutable
                }
            )
            fault(Phase.ROOM_COMMITTED)
            val applied = completeCommitted(repository, plan) { fault(Phase.BEFORE_TAG_COMMIT) }
            fault(Phase.TAG_COMMITTED)
            applied
        }
    }

    private fun writeMain(main: SupportSQLiteDatabase, p: CloudApplyPlan) {
        val streams = p.streams.associate { it.key to it.nativeId }
        for (s in p.streams.filter { it.create }) {
            val hint = s.hint
            main.execSQL(
                "INSERT INTO streams(uid,service_id,url,title,stream_type,duration,uploader,thumbnail_url) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    s.nativeId,
                    s.key.service,
                    s.key.url,
                    hint?.title ?: s.key.url,
                    hint?.streamType ?: "NONE",
                    hint?.duration ?: -1L,
                    hint?.uploader ?: "",
                    hint?.thumbnail
                )
            )
        }
        val ids = p.mappings.entries.associate { (native, portable) -> (native.kind to portable) to native.id }
        for (id in p.deletePlaylists) main.execSQL("DELETE FROM playlists WHERE uid=?", arrayOf(id))
        for (playlist in p.target.playlists) {
            val id = ids.getValue(Kind.PLAYLIST to playlist.id)
            val existing = main.query(SimpleSQLiteQuery("SELECT is_thumbnail_permanent,thumbnail_stream_id FROM playlists WHERE uid=?", arrayOf(id))).use { c ->
                if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else null
            }
            val artwork = playlist.artwork?.let { streams.getValue(it) }
            if (existing == null) {
                main.execSQL(
                    "INSERT INTO playlists(uid,name,is_thumbnail_permanent,thumbnail_stream_id,display_index) VALUES(?,?,?,?,0)",
                    arrayOf<Any?>(id, playlist.name, if (artwork == null) 0 else 1, artwork ?: -1L)
                )
            } else {
                main.execSQL("UPDATE playlists SET name=? WHERE uid=? AND name IS NOT ?", arrayOf<Any?>(playlist.name, id, playlist.name))
                // Retain a still-valid automatic selection, otherwise use native refresh below.
                val thumbnail = artwork ?: if (existing.first == 0) existing.second else -1L
                main.execSQL(
                    "UPDATE playlists SET is_thumbnail_permanent=?,thumbnail_stream_id=? WHERE uid=? AND (is_thumbnail_permanent!=? OR thumbnail_stream_id!=?)",
                    arrayOf<Any>(if (artwork == null) 0 else 1, thumbnail, id, if (artwork == null) 0 else 1, thumbnail)
                )
            }
        }
        val lists = LinkedHashMap<Long, List<Long>>()
        lists[p.context.libraryId] = p.target.library.map(streams::getValue)
        lists[p.context.unavailableId] = p.target.unavailable.map(streams::getValue)
        p.target.playlists.forEach { lists[ids.getValue(Kind.PLAYLIST to it.id)] = it.items.map(streams::getValue) }
        for ((id, items) in lists) {
            val existing = main.query(SimpleSQLiteQuery("SELECT stream_id FROM playlist_stream_join WHERE playlist_id=? ORDER BY join_index", arrayOf(id))).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
            if (existing != items) {
                main.execSQL("DELETE FROM playlist_stream_join WHERE playlist_id=?", arrayOf(id))
                main.compileStatement("INSERT INTO playlist_stream_join(playlist_id,stream_id,join_index) VALUES(?,?,?)").use { insert ->
                    items.forEachIndexed { index, stream ->
                        insert.bindLong(1, id)
                        insert.bindLong(2, stream)
                        insert.bindLong(3, index.toLong())
                        insert.executeInsert()
                    }
                }
            }
            LibraryRepository.refreshAutomaticThumbnail(repository.library, id)
        }
        p.displayOrder.forEachIndexed { index, slot ->
            val table = if (slot.remote) "remote_playlists" else "playlists"
            main.execSQL("UPDATE $table SET display_index=? WHERE uid=? AND display_index!=?", arrayOf<Any>(index.toLong(), slot.nativeId, index.toLong()))
        }
    }

    companion object {
        private fun available(r: TagRepository) {
            check(TagRuntime.lock.isHeldByCurrentThread) { "CLOUD_APPLY_BARRIER_REQUIRED" }
            TagRuntime.assertAvailable()
            check(TagRuntime.lookup(r.library) === r) { "CLOUD_CATALOG_REPLACED" }
        }

        /** Called before native/AI operations and standalone cloud capture; no main deletion is replayed. */
        fun recoverLocked(r: TagRepository): CloudNativeSnapshot? {
            check(TagRuntime.lock.isHeldByCurrentThread) { "CLOUD_APPLY_BARRIER_REQUIRED" }
            // A native file-swap journal can replace BOTH databases. Resolve it via
            // the guarded Room helper before a tag connection opens, even unenrolled.
            val main = r.library.openHelper.writableDatabase
            val side = r.store.writableDatabase
            val pending = try {
                CloudApplyStore.pending(side)
            } catch (error: Exception) {
                throw CloudRecoveryBlockedException(error)
            } catch (error: OutOfMemoryError) {
                throw CloudRecoveryBlockedException(error)
            }
            if (pending == null) {
                try {
                    val witness = r.library.cloudApplyReceiptDao().current()
                    val baseline = CloudApplyStore.baseline(side)
                    check(witness == null || baseline != null && witness.incarnation == baseline.incarnation && witness.operationId == baseline.operation) {
                        "CLOUD_RECOVERY_JOURNAL_LOST"
                    }
                } catch (error: Exception) { throw CloudRecoveryBlockedException(error) }
                catch (error: OutOfMemoryError) { throw CloudRecoveryBlockedException(error) }
                return null
            }
            return try {
                available(r)
                recoverPending(r, main, side, pending)
            } catch (error: CloudRecoveryBlockedException) {
                throw error
            } catch (error: Exception) {
                throw CloudRecoveryBlockedException(error)
            } catch (error: OutOfMemoryError) {
                throw CloudRecoveryBlockedException(error)
            }
        }
        private fun recoverPending(
            r: TagRepository,
            main: SupportSQLiteDatabase,
            side: SQLiteDatabase,
            pending: CloudApplyStore.Pending
        ): CloudNativeSnapshot? {
            check(Looper.myLooper() != Looper.getMainLooper()) { "CLOUD_RECOVERY_REQUIRES_BACKGROUND" }
            check(!main.inTransaction() && !side.inTransaction()) { "CLOUD_RECOVERY_NESTED_TRANSACTION" }
            val bytes = CloudApplyStore.payload(side, pending)
            val p = CloudApplyPlanJson.decode(bytes)
            check(
                p.operation == pending.operation && p.context.incarnation == pending.incarnation &&
                    p.beforeFingerprint == pending.beforeFingerprint && p.targetHead.fingerprint == pending.targetFingerprint
            ) { "CLOUD_JOURNAL_HEADER_MISMATCH" }
            requireIdentity(r, p)
            val witness = r.library.cloudApplyReceiptDao().current()
            if (witness?.operationId == p.operation) {
                requireWitness(witness, pending)
                return completeCommitted(r, p) {}
            }
            // No matching Room witness means no proof of publication. Never retry its
            // destructive Room writes; prove unchanged before-state and clear only intent.
            r.library.runInTransaction {
                r.store.transaction { db ->
                    check(CloudApplyStore.pending(db) == pending) { "CLOUD_JOURNAL_CHANGED" }
                    CloudApplyStore.clearPending(db)
                    val before = CloudNativeCatalog(r).verifyAppliedInTransaction(p.context.scope, p.context.incarnation)
                    check(before.fingerprint == p.beforeFingerprint && before.tagRevision == p.tagRevision) { "CLOUD_RECOVERY_UNPROVEN_MAIN_STATE" }
                }
            }
            return null
        }
        private fun requireIdentity(r: TagRepository, p: CloudApplyPlan) {
            check(CloudIdentityStore.context(r.store.readableDatabase) == p.context) { "CLOUD_RECOVERY_IDENTITY_CHANGED" }
            val binding = r.binding.read() ?: error("CLOUD_RECOVERY_BINDING_MISSING")
            check(
                binding.playlistId == p.context.libraryId && binding.unavailablePlaylistId == p.context.unavailableId &&
                    binding.cloudIncarnation == p.context.incarnation && binding.cloudPlaylistSequence != null
            ) { "CLOUD_RECOVERY_BINDING_CHANGED" }
            val main = r.library.openHelper.readableDatabase
            val sequence = main.query("SELECT seq FROM sqlite_sequence WHERE name='playlists'").use { c ->
                check(c.moveToFirst()) { "CLOUD_RECOVERY_SEQUENCE_MISSING" }
                c.getLong(0)
            }
            check(sequence >= binding.cloudPlaylistSequence) { "CLOUD_RECOVERY_DATABASE_REGRESSED" }
        }
        private fun requireWitness(receipt: CloudApplyReceipt?, p: CloudApplyStore.Pending) {
            check(
                receipt != null && receipt.slot == 1 && receipt.operationId == p.operation && receipt.incarnation == p.incarnation &&
                    receipt.planDigest == p.digest && receipt.targetFingerprint == p.targetFingerprint
            ) { "CLOUD_APPLY_WITNESS_MISMATCH" }
        }
        private fun completeCommitted(r: TagRepository, p: CloudApplyPlan, beforeTagCommit: () -> Unit): CloudNativeSnapshot {
            available(r)
            val result = r.library.runInTransaction(
                Callable {
                    requireIdentity(r, p)
                    val pending = checkNotNull(CloudApplyStore.pending(r.store.readableDatabase)) { "CLOUD_PENDING_MISSING" }
                    check(pending.operation == p.operation) { "CLOUD_JOURNAL_CHANGED" }
                    requireWitness(r.library.cloudApplyReceiptDao().current(), pending)
                    verifyMain(r, p)
                    r.store.transaction { side ->
                        check(r.store.version() == p.tagRevision) { "CLOUD_RECOVERY_TAGS_CHANGED" }
                        writeTags(side, p)
                        CloudApplyStore.clearPending(side)
                        val applied = CloudNativeCatalog(r).verifyAppliedInTransaction(p.context.scope, p.context.incarnation)
                        check(
                            applied.fingerprint == p.targetHead.fingerprint && applied.nativeIds == p.mappings &&
                                applied.orphaned == p.preservedOrphans
                        ) { "CLOUD_FINAL_PROJECTION_MISMATCH" }
                        CloudApplyStore.accept(side, p.context.incarnation, p.operation, p.targetHead)
                        beforeTagCommit()
                        applied
                    }
                }
            )
            TagRuntime.changed()
            return result
        }

        private fun verifyMain(r: TagRepository, p: CloudApplyPlan) {
            val main = r.library.openHelper.readableDatabase
            val ids = p.mappings.entries.associate { (native, portable) -> (native.kind to portable) to native.id }
            val streams = p.streams.associate { it.key to it.nativeId }
            for (stream in p.streams) {
                val matches = main.query(SimpleSQLiteQuery("SELECT service_id,url FROM streams WHERE uid=?", arrayOf(stream.nativeId))).use { c ->
                    c.moveToFirst() && c.getLong(0) == stream.key.service.toLong() && c.getString(1) == stream.key.url && !c.moveToNext()
                }
                check(matches) { "CLOUD_MAIN_STREAM_MISMATCH" }
            }
            val lists = linkedMapOf(
                p.context.libraryId to p.target.library.map(streams::getValue),
                p.context.unavailableId to p.target.unavailable.map(streams::getValue)
            )
            p.target.playlists.forEach { lists[ids.getValue(Kind.PLAYLIST to it.id)] = it.items.map(streams::getValue) }
            val roles = mapOf(p.context.libraryId to LibraryBindingStore.LIBRARY_NAME, p.context.unavailableId to UnavailableRepository.NAME)
            val desired = p.target.playlists.associateBy { ids.getValue(Kind.PLAYLIST to it.id) }
            val ranks = p.displayOrder.withIndex().filter { !it.value.remote }.associate { it.value.nativeId to it.index.toLong() }
            main.query("SELECT uid,name,is_thumbnail_permanent,thumbnail_stream_id,display_index FROM playlists").use { c ->
                var count = 0
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    count++
                    check(id in lists && c.getLong(4) == ranks[id]) { "CLOUD_MAIN_PLAYLIST_SET_CHANGED" }
                    if (id in roles) {
                        check(c.getString(1) == roles[id]) { "CLOUD_MAIN_ROLE_CHANGED" }
                    } else {
                        val wanted = desired.getValue(id)
                        check((if (c.isNull(1)) null else c.getString(1)) == wanted.name) { "CLOUD_MAIN_NAME_MISMATCH" }
                        if (wanted.artwork != null) {
                            check(c.getInt(2) == 1 && c.getLong(3) == streams[wanted.artwork]) { "CLOUD_MAIN_ARTWORK_MISMATCH" }
                        } else {
                            check(c.getInt(2) == 0 || c.getLong(3) == -1L) { "CLOUD_MAIN_ARTWORK_MISMATCH" }
                        }
                    }
                }
                check(count == lists.size) { "CLOUD_MAIN_PLAYLIST_MISSING" }
            }
            val actual = HashMap<Long, MutableList<Long>>()
            main.query("SELECT playlist_id,stream_id,join_index FROM playlist_stream_join ORDER BY playlist_id,join_index").use { c ->
                var count = 0
                var lastPlaylist = -1L
                var lastPosition = -1L
                while (c.moveToNext()) {
                    val playlist = c.getLong(0)
                    val position = c.getLong(2)
                    check(
                        ++count <= CloudCatalogCodec.MAX_MEMBERSHIPS && playlist in lists && position >= 0 &&
                            (playlist != lastPlaylist || position > lastPosition)
                    ) { "CLOUD_MAIN_MEMBERSHIP_MISMATCH" }
                    actual.getOrPut(playlist) { ArrayList() }.add(c.getLong(1))
                    lastPlaylist = playlist
                    lastPosition = position
                }
            }
            check(lists.all { (id, items) -> items == actual[id].orEmpty() }) { "CLOUD_MAIN_MEMBERSHIP_MISMATCH" }
        }

        private fun writeTags(db: SQLiteDatabase, p: CloudApplyPlan) {
            val ids = p.mappings.entries.associate { (native, portable) -> (native.kind to portable) to native.id }
            val categories = p.target.categories.associateBy { ids.getValue(Kind.CATEGORY to it.id) }
            val tags = p.target.tags.associateBy { ids.getValue(Kind.TAG to it.id) }
            val existingCategories = db.rawQuery("SELECT id FROM category", null).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
            val existingTags = db.rawQuery("SELECT id FROM tag", null).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
            // Break category links before deleting/moving categories; native tag IDs survive.
            db.execSQL("UPDATE tag SET category_id=NULL WHERE category_id IS NOT NULL")
            for (id in existingTags - tags.keys) db.delete("tag", "id=?", arrayOf(id.toString()))
            for (id in existingCategories - categories.keys) db.delete("category", "id=?", arrayOf(id.toString()))
            // Private temporary normalized keys permit swaps without transient UNIQUE failures.
            for (id in categories.keys.intersect(existingCategories)) db.execSQL("UPDATE category SET name_key=? WHERE id=?", arrayOf<Any>("\u0000cloud-category-${p.operation}-$id", id))
            for (id in tags.keys.intersect(existingTags)) db.execSQL("UPDATE tag SET name_key=? WHERE id=?", arrayOf<Any>("\u0000cloud-tag-${p.operation}-$id", id))
            for ((id, category) in categories) {
                if (id in existingCategories) {
                    db.execSQL("UPDATE category SET name=?,name_key=? WHERE id=?", arrayOf<Any>(category.name, category.nameKey, id))
                } else {
                    db.execSQL("INSERT INTO category(id,name,name_key) VALUES(?,?,?)", arrayOf<Any>(id, category.name, category.nameKey))
                }
            }
            for ((id, tag) in tags) {
                val category = tag.category?.let { ids.getValue(Kind.CATEGORY to it) }
                if (id in existingTags) {
                    db.execSQL("UPDATE tag SET name=?,name_key=?,category_id=? WHERE id=?", arrayOf<Any?>(tag.name, tag.nameKey, category, id))
                } else {
                    db.execSQL("INSERT INTO tag(id,name,name_key,category_id) VALUES(?,?,?,?)", arrayOf<Any?>(id, tag.name, tag.nameKey, category))
                }
            }
            val targetAssignments = p.target.assignments.associate { it.key to it.tags.map { tag -> ids.getValue(Kind.TAG to tag) }.toSet() } +
                p.preservedOrphans.associate { it.key to it.tagIds.map { tag -> ids.getValue(Kind.TAG to tag) }.toSet() }
            for (key in p.affectedTagKeys) {
                val wanted = targetAssignments[key].orEmpty()
                val old = db.rawQuery("SELECT tag_id FROM assignment WHERE service_id=? AND url=?", arrayOf(key.service.toString(), key.url)).use { c ->
                    buildSet { while (c.moveToNext()) add(c.getLong(0)) }
                }
                if (old != wanted) {
                    db.delete("assignment", "service_id=? AND url=?", arrayOf(key.service.toString(), key.url))
                    for (tag in wanted) db.execSQL("INSERT INTO assignment VALUES(?,?,?)", arrayOf<Any>(key.service, key.url, tag))
                }
                db.execSQL("INSERT OR IGNORE INTO stream_revision VALUES(?,?,0)", arrayOf<Any>(key.service, key.url))
                val revision = db.rawQuery("SELECT revision FROM stream_revision WHERE service_id=? AND url=?", arrayOf(key.service.toString(), key.url)).use { c ->
                    c.moveToFirst()
                    c.getLong(0)
                }
                check(revision in 0 until Long.MAX_VALUE) { "CLOUD_STREAM_REVISION_EXHAUSTED" }
                db.execSQL("UPDATE stream_revision SET revision=revision+1 WHERE service_id=? AND url=?", arrayOf<Any>(key.service, key.url))
            }
            // Associations absent from Library remain local. A global Library removal is
            // in affectedTagKeys and is intentionally deleted; unrelated orphans are untouched.
            db.execSQL("UPDATE catalog_meta SET revision=revision+1 WHERE id=1")
            db.delete("cloud_catalog_identity", null, null)
            db.compileStatement("INSERT INTO cloud_catalog_identity(kind,native_id,portable_id) VALUES(?,?,?)").use { statement ->
                for ((native, portable) in p.mappings) {
                    statement.bindString(1, native.kind.name)
                    statement.bindLong(2, native.id)
                    statement.bindString(3, portable)
                    statement.executeInsert()
                }
            }
        }
    }
}
