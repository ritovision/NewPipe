/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.Kind
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.NativeId
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.extractor.stream.StreamType

/** Compiles trusted native IDs under the actual Room writer transaction; never accepts remote SQL/IDs. */
internal object CloudApplyCompiler {
    private data class Display(val remote: Boolean, val id: Long, val name: String?, val rank: Long)

    fun compile(
        repository: TagRepository,
        before: CloudNativeSnapshot,
        head: CloudHead,
        targetInput: CloudCatalog,
        mediaHints: Collection<CloudMediaHint>
    ): CloudApplyPlan {
        val main = repository.library.openHelper.writableDatabase
        val side = repository.store.writableDatabase
        check(TagRuntime.lock.isHeldByCurrentThread && main.inTransaction() && !side.inTransaction()) { "CLOUD_COMPILER_TRANSACTION_REQUIRED" }
        val target = CloudCatalogCodec.snapshot(targetInput)
        check(
            head.serviceGeneration == before.identities.scope.service && head.library == before.identities.scope.library &&
                CloudCatalogCodec.fingerprint(target) == head.fingerprint
        ) { "CLOUD_TARGET_MISMATCH" }
        val targetKeys = target.library.toHashSet()
        val tags = target.tags.mapTo(HashSet()) { it.id }
        val assigned = target.assignments.associate { it.key to it.tags.toSet() }
        // Unrelated orphan associations remain local. A target that would silently drop or
        // reactivate divergent orphan metadata must go through explicit future recovery UI.
        for (orphan in before.orphaned) {
            check(
                tags.containsAll(orphan.tagIds) &&
                    (orphan.key !in targetKeys || orphan.tagIds.toSet() == assigned[orphan.key].orEmpty())
            ) { "CLOUD_ORPHAN_RECONCILIATION_REQUIRED" }
        }
        val preserved = before.orphaned.filter { it.key !in targetKeys }
        CloudApplyBounds.requireAssignmentBudget(
            preserved.sumOf { it.tagIds.size.toLong() },
            target.assignments.sumOf { it.tags.size.toLong() }
        )
        val prior = before.nativeIds.entries.associate { (native, portable) -> (native.kind to portable) to native.id }
        val mapping = LinkedHashMap<NativeId, String>()
        fun identities(kind: Kind, ids: List<String>, initial: Long) {
            var high = initial
            for (id in ids) {
                val native = prior[kind to id] ?: Math.addExact(high, 1).also { high = it }
                mapping[NativeId(kind, native)] = id
            }
        }
        identities(Kind.PLAYLIST, target.playlists.map { it.id }, mainHighWater(main, "playlists", "uid"))
        identities(Kind.CATEGORY, target.categories.map { it.id }, sideHighWater(side, "category"))
        identities(Kind.TAG, target.tags.map { it.id }, sideHighWater(side, "tag"))
        val nativePlaylists = mapping.filterKeys { it.kind == Kind.PLAYLIST }
        val deleted = before.nativeIds.keys.filter { it.kind == Kind.PLAYLIST && it !in nativePlaylists }.map { it.id }
        val hintByKey = mediaHints.associateBy { it.key }
        require(hintByKey.size == mediaHints.size && mediaHints.size <= CloudCatalogCodec.MAX_STREAMS + CloudCatalogCodec.MAX_PLAYLISTS) { "INVALID_CLOUD_HINTS" }
        val desiredKeys = LinkedHashSet(target.library)
        target.playlists.forEach { it.artwork?.let(desiredKeys::add) }
        require(hintByKey.keys.all { it in desiredKeys }) { "ORPHAN_CLOUD_HINT" }
        mediaHints.forEach(::validateHint)
        var streamHigh = mainHighWater(main, "streams", "uid")
        val streams = desiredKeys.map { key ->
            val existing = main.query(SimpleSQLiteQuery("SELECT uid FROM streams WHERE service_id=? AND url=?", arrayOf<Any>(key.service, key.url))).use { c ->
                if (!c.moveToFirst()) null else c.getLong(0).also { check(it > 0 && !c.moveToNext()) { "AMBIGUOUS_NATIVE_STREAM" } }
            }
            CloudStreamPlan(key, existing ?: Math.addExact(streamHigh, 1).also { streamHigh = it }, existing == null, hintByKey[key])
        }
        val slots = displaySlots(main, before, target, nativePlaylists)
        check(before.tagRevision < Long.MAX_VALUE) { "CLOUD_TAG_REVISION_EXHAUSTED" }
        val affected = (before.catalog.library + target.library).distinct()
        return CloudApplyPlan(
            UUID.randomUUID().toString(), before.identities, before.fingerprint, head, target,
            mapping, streams, deleted, slots, preserved, affected, before.tagRevision
        )
    }

    fun validateHint(h: CloudMediaHint) {
        CloudCatalogCodec.snapshot(CloudCatalog(library = listOf(h.key)))
        require(h.duration >= -1) { "INVALID_CLOUD_HINT_DURATION" }
        require(runCatching { StreamType.valueOf(h.streamType) }.isSuccess) { "INVALID_CLOUD_HINT_TYPE" }
        // Reuse codec Unicode/length validation, including unpaired-surrogate rejection.
        for (text in listOfNotNull(h.title, h.uploader, h.thumbnail)) {
            require(text.length <= CloudCatalogCodec.MAX_TEXT_UNITS) { "INVALID_CLOUD_HINT_TEXT" }
            if (text.isNotBlank()) CloudCatalogCodec.snapshot(CloudCatalog(library = listOf(CloudKey(0, text))))
        }
    }

    private fun displaySlots(
        main: SupportSQLiteDatabase,
        before: CloudNativeSnapshot,
        target: CloudCatalog,
        identities: Map<NativeId, String>
    ): List<CloudDisplaySlot> {
        val rows = main.query(
            "SELECT 0 AS remote,uid,name,display_index FROM playlists UNION ALL " +
                "SELECT 1 AS remote,uid,name,display_index FROM remote_playlists LIMIT 10001"
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    check(size < 10_000) { "CLOUD_DISPLAY_LIMIT" }
                    add(Display(c.getInt(0) == 1, c.getLong(1), if (c.isNull(2)) null else c.getString(2), c.getLong(3)))
                }
            }
        }.sortedWith(
            compareBy<Display> { it.rank }.thenBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.name }
                .thenBy { !it.remote }.thenBy { it.id }
        )
        val byPortable = identities.entries.associate { it.value to it.key.id }
        val incoming = target.playlists.map { byPortable.getValue(it.id) }.iterator()
        val special = setOf(before.identities.libraryId, before.identities.unavailableId)
        return buildList {
            for (row in rows) {
                if (row.remote || row.id in special) {
                    add(CloudDisplaySlot(row.remote, row.id))
                } else if (incoming.hasNext()) {
                    add(CloudDisplaySlot(false, incoming.next()))
                }
            }
            while (incoming.hasNext()) add(CloudDisplaySlot(false, incoming.next()))
            check(size <= 10_000) { "CLOUD_DISPLAY_LIMIT" }
        }
    }

    private fun mainHighWater(db: SupportSQLiteDatabase, table: String, column: String): Long = db.query(
        "SELECT COALESCE((SELECT seq FROM sqlite_sequence WHERE name='$table'),0),COALESCE((SELECT MAX($column) FROM $table),0)"
    ).use { c ->
        check(c.moveToFirst() && c.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER && c.getType(1) == android.database.Cursor.FIELD_TYPE_INTEGER) { "INVALID_NATIVE_SEQUENCE" }
        maxOf(c.getLong(0), c.getLong(1)).also { check(it >= 0) { "INVALID_NATIVE_SEQUENCE" } }
    }
    private fun sideHighWater(db: SQLiteDatabase, table: String): Long = db.rawQuery(
        "SELECT COALESCE((SELECT seq FROM sqlite_sequence WHERE name='$table'),0),COALESCE((SELECT MAX(id) FROM $table),0)",
        null
    ).use { c ->
        check(c.moveToFirst() && c.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER && c.getType(1) == android.database.Cursor.FIELD_TYPE_INTEGER) { "INVALID_NATIVE_SEQUENCE" }
        maxOf(c.getLong(0), c.getLong(1)).also { check(it >= 0) { "INVALID_NATIVE_SEQUENCE" } }
    }
}
