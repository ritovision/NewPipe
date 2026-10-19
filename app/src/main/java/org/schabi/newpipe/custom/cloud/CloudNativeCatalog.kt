/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest
import java.util.concurrent.Callable
import kotlin.concurrent.withLock
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.Kind
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.NativeId
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.UnavailableRepository
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime

/** Current display hints are not semantic content; they never affect the catalog fingerprint. */
internal data class CloudMediaHint(
    val key: CloudKey,
    val title: String,
    val uploader: String,
    val thumbnail: String?,
    val duration: Long,
    val streamType: String
)
internal data class CloudOrphanAssignment(val key: CloudKey, val tagIds: List<String>)

/** Owns the exact canonical bytes independently of any caller-mutated DTO. No native apply API. */
internal class CloudNativeSnapshot(
    val catalog: CloudCatalog,
    val identities: CloudIdentityStore.Context,
    val nativeIds: Map<NativeId, String>,
    val hints: Map<CloudKey, CloudMediaHint>,
    val orphaned: List<CloudOrphanAssignment>,
    val tagRevision: Long,
    val runtimeGeneration: Long,
    canonical: ByteArray
) {
    private val bytes = canonical.copyOf()
    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }
    fun canonicalBytes(): ByteArray = bytes.copyOf()
}

/**
 * Consistent native projection: no Room/tag content edits, role repair, extractor or network work.
 * A shared TagRuntime barrier + a Room transaction fences Room writers during the snapshot.
 * The tag transaction commits only runtime UUID mapping changes after whole-catalog validation.
 */
internal class CloudNativeCatalog(
    private val repository: TagRepository,
    private val afterBindingPersisted: (() -> Unit)? = null
) {
    private data class NativePlaylist(val id: Long, val name: String?, val displayIndex: Long, val artwork: CloudKey?)
    private data class NativeCategory(val id: Long, val name: String, val nameKey: String)
    private data class NativeTag(val id: Long, val name: String, val nameKey: String, val categoryId: Long?)
    private data class NativeAssignment(val key: CloudKey, val tagId: Long)

    /** Explicit local identity enrollment only; never enables sync or obtains cloud authority. */
    fun enroll(scope: CloudIdentityStore.Scope): CloudNativeSnapshot = snapshot(scope, null, true)

    /** Capture cannot recreate absent identity state or silently enroll after restore. */
    fun capture(scope: CloudIdentityStore.Scope, expectedIncarnation: String): CloudNativeSnapshot = snapshot(scope, expectedIncarnation, false)

    private fun snapshot(scope: CloudIdentityStore.Scope, expectedIncarnation: String?, enrollment: Boolean): CloudNativeSnapshot {
        check(Looper.myLooper() != Looper.getMainLooper()) { "CLOUD_IO_ON_MAIN_THREAD" }
        return TagRuntime.lock.withLock {
            TagRuntime.assertAvailable()
            check(TagRuntime.lookup(repository.library) === repository) { "CLOUD_CATALOG_REPLACED" }
            // Open first: the existing guarded helper performs file-swap recovery before any binding read.
            val main = repository.library.openHelper.writableDatabase
            val tags = repository.store.writableDatabase
            check(!main.inTransaction() && !tags.inTransaction()) { "CLOUD_CAPTURE_NESTED_TRANSACTION" }
            CloudNativeApply.recoverLocked(repository)
            repository.library.runInTransaction(
                Callable {
                    repository.store.transaction { side ->
                        captureCore(scope, expectedIncarnation, enrollment, main, side)
                    }
                }
            )
        }
    }

    /** The apply coordinator already owns both writer transactions; validation sees staged tag edits. */
    internal fun verifyAppliedInTransaction(scope: CloudIdentityStore.Scope, incarnation: String): CloudNativeSnapshot {
        check(TagRuntime.lock.isHeldByCurrentThread) { "CLOUD_APPLY_BARRIER_REQUIRED" }
        val main = repository.library.openHelper.writableDatabase
        val side = repository.store.writableDatabase
        check(main.inTransaction() && side.inTransaction()) { "CLOUD_APPLY_TRANSACTION_REQUIRED" }
        return captureCore(scope, incarnation, false, main, side)
    }

    private fun captureCore(
        scope: CloudIdentityStore.Scope,
        expectedIncarnation: String?,
        enrollment: Boolean,
        main: SupportSQLiteDatabase,
        side: SQLiteDatabase
    ): CloudNativeSnapshot {
        requireNoPendingRecovery(side)
        val binding = repository.binding.read() ?: error("IDENTITY_REPAIR_REQUIRED")
        val unavailable = binding.unavailablePlaylistId ?: error("IDENTITY_REPAIR_REQUIRED")
        val oldContext = CloudIdentityStore.context(side)
        if (enrollment) {
            check(oldContext == null) { "CLOUD_ALREADY_ENROLLED" }
        } else {
            check(oldContext != null) { "CLOUD_ENROLLMENT_REQUIRED" }
            check(oldContext.scope == scope) { "CLOUD_SCOPE_MISMATCH" }
            check(oldContext.incarnation == expectedIncarnation) { "CLOUD_INCARNATION_CHANGED" }
            check(binding.cloudIncarnation == oldContext.incarnation) { "CLOUD_MAIN_DATABASE_CHANGED" }
        }
        val sequence = playlistSequence(main)
        check(enrollment || sequence >= checkNotNull(binding.cloudPlaylistSequence)) { "CLOUD_PLAYLIST_SEQUENCE_REGRESSED" }
        val playlists = readPlaylists(main, setOf(binding.playlistId, unavailable))
        check(
            playlists.singleOrNull { it.id == binding.playlistId }?.name == LibraryBindingStore.LIBRARY_NAME &&
                playlists.singleOrNull { it.id == unavailable }?.name == UnavailableRepository.NAME
        ) { "IDENTITY_REPAIR_REQUIRED" }
        val ordinary = playlists.filter { it.id != binding.playlistId && it.id != unavailable }
        check(ordinary.size <= CloudCatalogCodec.MAX_PLAYLISTS) { "NATIVE_PLAYLIST_LIMIT" }
        check(ordinary.none { UnavailableRepository.isReservedName(it.name) }) { "IDENTITY_REPAIR_REQUIRED" }
        val hints = LinkedHashMap<CloudKey, CloudMediaHint>()
        val memberships = readMemberships(main, playlists.mapTo(HashSet()) { it.id }, hints)
        val categories = readCategories(side)
        val definitions = readTags(side)
        val assignments = readAssignments(side)
        val active = buildSet {
            ordinary.forEach { add(NativeId(Kind.PLAYLIST, it.id)) }
            categories.forEach { add(NativeId(Kind.CATEGORY, it.id)) }
            definitions.forEach { add(NativeId(Kind.TAG, it.id)) }
        }
        val (context, map) = CloudIdentityStore.reconcile(
            side,
            scope,
            binding.playlistId,
            unavailable,
            expectedIncarnation,
            active,
            enrollment
        )
        fun portable(kind: Kind, id: Long): String = map[NativeId(kind, id)] ?: error("NATIVE_REFERENCE_MISSING")
        val library = memberships[binding.playlistId].orEmpty()
        val saved = library.toHashSet()
        val byKey = assignments.groupBy { it.key }
        val catalog = CloudCatalog(
            library = library,
            unavailable = memberships[unavailable].orEmpty(),
            playlists = ordinary.map { p ->
                CloudPlaylist(portable(Kind.PLAYLIST, p.id), p.name, p.artwork, memberships[p.id].orEmpty())
            },
            categories = categories.map { CloudCategory(portable(Kind.CATEGORY, it.id), it.name, it.nameKey) },
            tags = definitions.map { CloudTag(portable(Kind.TAG, it.id), it.name, it.nameKey, it.categoryId?.let { id -> portable(Kind.CATEGORY, id) }) },
            assignments = byKey.filterKeys { it in saved }.map { (key, values) ->
                CloudAssignment(key, values.map { portable(Kind.TAG, it.tagId) }.sorted())
            }
        )
        val validated = CloudCatalogCodec.snapshot(catalog)
        val canonical = CloudCatalogCodec.canonicalBytes(validated)
        val orphans = byKey.filterKeys { it !in saved }.map { (key, values) ->
            CloudOrphanAssignment(key, values.map { portable(Kind.TAG, it.tagId) }.sorted())
        }.sortedWith(compareBy<CloudOrphanAssignment> { it.key.service }.thenBy { it.key.url })
        // Re-read persisted role file: supported binding writers also hold TagRuntime.lock.
        check(repository.binding.read() == binding) { "CLOUD_BINDING_CHANGED" }
        // Persist the file anchor before the tag transaction can commit. A failed
        // commit may leave an unclaimed marker, never trusted old mappings.
        val anchored = binding.copy(cloudIncarnation = context.incarnation, cloudPlaylistSequence = sequence)
        if (anchored != binding) {
            repository.binding.write(anchored)
            check(repository.binding.read() == anchored) { "CLOUD_BINDING_WRITE_FAILED" }
        }
        afterBindingPersisted?.invoke()
        return CloudNativeSnapshot(validated, context, map.toMap(), hints.toMap(), orphans, repository.store.version(), TagRuntime.generation, canonical)
    }

    private fun requireNoPendingRecovery(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM removal_intent) OR EXISTS(SELECT 1 FROM ai_removal_intent) " +
                "OR EXISTS(SELECT 1 FROM native_bulk_intent) OR EXISTS(SELECT 1 FROM cloud_apply_pending)",
            null
        ).use { c ->
            check(c.moveToFirst() && c.getInt(0) == 0) { "APPLY_RECOVERY_REQUIRED" }
        }
    }

    private fun playlistSequence(db: SupportSQLiteDatabase): Long = db.query(
        "SELECT seq,typeof(seq),(SELECT MAX(uid) FROM playlists) FROM sqlite_sequence WHERE name='playlists'"
    ).use { c ->
        check(c.moveToFirst() && c.getString(1) == "integer") { "CLOUD_PLAYLIST_SEQUENCE_INVALID" }
        val sequence = c.getLong(0)
        check(sequence > 0 && !c.isNull(2) && sequence >= c.getLong(2) && !c.moveToNext()) { "CLOUD_PLAYLIST_SEQUENCE_INVALID" }
        sequence
    }

    private fun readPlaylists(db: SupportSQLiteDatabase, protectedIds: Set<Long>): List<NativePlaylist> {
        val rows = db.query(
            "SELECT p.uid,p.name,p.display_index,p.is_thumbnail_permanent,p.thumbnail_stream_id,s.service_id,s.url " +
                "FROM playlists p LEFT JOIN streams s ON s.uid=p.thumbnail_stream_id " +
                "ORDER BY p.uid LIMIT ${CloudCatalogCodec.MAX_PLAYLISTS + 3}"
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    check(size < CloudCatalogCodec.MAX_PLAYLISTS + 2) { "NATIVE_PLAYLIST_LIMIT" }
                    val id = c.getLong(0)
                    check(id > 0) { "INVALID_NATIVE_ID" }
                    val permanent = c.getInt(3) != 0
                    val artworkId = c.getLong(4)
                    val artwork = if (id in protectedIds || !permanent || artworkId == -1L) {
                        null
                    } else {
                        check(artworkId > 0 && !c.isNull(5) && !c.isNull(6)) { "NATIVE_ARTWORK_UNRESOLVED:$id" }
                        nativeKey(c.getLong(5), c.getString(6))
                    }
                    add(NativePlaylist(id, if (c.isNull(1)) null else c.getString(1), c.getLong(2), artwork))
                }
            }
        }
        // Same local relative order as MergedPlaylistManager; remote bookmarks and numeric gaps vanish.
        return rows.sortedWith(
            compareBy<NativePlaylist> { it.displayIndex }
                .thenBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.name }.thenBy { it.id }
        )
    }

    private fun readMemberships(
        db: SupportSQLiteDatabase,
        knownPlaylists: Set<Long>,
        hints: MutableMap<CloudKey, CloudMediaHint>
    ): Map<Long, List<CloudKey>> = db.query(
        "SELECT j.playlist_id,j.stream_id,j.join_index,s.service_id,s.url,s.title,s.uploader,s.thumbnail_url,s.duration,s.stream_type " +
            "FROM playlist_stream_join j LEFT JOIN streams s ON s.uid=j.stream_id " +
            "ORDER BY j.playlist_id,j.join_index LIMIT ${CloudCatalogCodec.MAX_MEMBERSHIPS + 1}"
    ).use { c ->
        val members = LinkedHashMap<Long, MutableList<CloudKey>>()
        var count = 0
        var lastPlaylist = -1L
        var lastIndex = -1L
        while (c.moveToNext()) {
            check(++count <= CloudCatalogCodec.MAX_MEMBERSHIPS) { "NATIVE_MEMBERSHIP_LIMIT" }
            val playlist = c.getLong(0)
            val index = c.getLong(2)
            check(playlist in knownPlaylists && c.getLong(1) > 0 && !c.isNull(3) && !c.isNull(4)) { "NATIVE_DANGLING_MEMBERSHIP" }
            check(index >= 0 && index <= Int.MAX_VALUE && (playlist != lastPlaylist || index > lastIndex)) { "NATIVE_INVALID_ORDER" }
            val key = nativeKey(c.getLong(3), c.getString(4))
            members.getOrPut(playlist) { ArrayList() }.add(key)
            hints.putIfAbsent(key, CloudMediaHint(key, c.getString(5).orEmpty(), c.getString(6).orEmpty(), if (c.isNull(7)) null else c.getString(7), c.getLong(8), c.getString(9)))
            lastPlaylist = playlist
            lastIndex = index
        }
        members
    }

    private fun readCategories(db: SQLiteDatabase): List<NativeCategory> = db.rawQuery(
        "SELECT id,name,name_key FROM category ORDER BY id LIMIT ${CloudCatalogCodec.MAX_CATEGORIES + 1}",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                check(size < CloudCatalogCodec.MAX_CATEGORIES) { "NATIVE_CATEGORY_LIMIT" }
                add(NativeCategory(c.getLong(0), c.getString(1), c.getString(2)))
            }
        }
    }
    private fun readTags(db: SQLiteDatabase): List<NativeTag> = db.rawQuery(
        "SELECT id,name,name_key,category_id FROM tag ORDER BY id LIMIT ${CloudCatalogCodec.MAX_TAGS + 1}",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                check(size < CloudCatalogCodec.MAX_TAGS) { "NATIVE_TAG_LIMIT" }
                add(NativeTag(c.getLong(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3)))
            }
        }
    }
    private fun readAssignments(db: SQLiteDatabase): List<NativeAssignment> = db.rawQuery(
        "SELECT service_id,url,tag_id FROM assignment ORDER BY service_id,url,tag_id LIMIT ${CloudCatalogCodec.MAX_MEMBERSHIPS + 1}",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                check(size < CloudCatalogCodec.MAX_MEMBERSHIPS) { "NATIVE_ASSIGNMENT_LIMIT" }
                add(NativeAssignment(nativeKey(c.getLong(0), c.getString(1)), c.getLong(2)))
            }
        }
    }
    private fun nativeKey(service: Long, url: String): CloudKey {
        check(service in 0..Int.MAX_VALUE.toLong()) { "INVALID_NATIVE_SERVICE" }
        return CloudKey(service.toInt(), url)
    }
}
