/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import org.schabi.newpipe.custom.tagging.TagRuntime

/** Runtime-only mappings in the existing tag DB. No credentials, catalog content or network authority. */
internal object CloudIdentityStore {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private const val MAX_IDENTITIES = CloudCatalogCodec.MAX_PLAYLISTS + CloudCatalogCodec.MAX_CATEGORIES + CloudCatalogCodec.MAX_TAGS

    data class Scope(val service: String, val library: String) {
        init {
            val pattern = Regex("[A-Za-z0-9_-]{1,128}")
            require(pattern.matches(service) && pattern.matches(library)) { "INVALID_CLOUD_SCOPE" }
        }
    }
    data class Context(val scope: Scope, val incarnation: String, val libraryId: Long, val unavailableId: Long)
    enum class Kind { PLAYLIST, CATEGORY, TAG }
    data class NativeId(val kind: Kind, val id: Long) {
        init {
            require(id > 0) { "INVALID_NATIVE_ID" }
        }
    }

    fun createTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE cloud_catalog_context (" +
                "slot INTEGER PRIMARY KEY CHECK(slot=1), service TEXT NOT NULL, library TEXT NOT NULL," +
                "incarnation TEXT NOT NULL, library_id INTEGER NOT NULL CHECK(library_id>0)," +
                "unavailable_id INTEGER NOT NULL CHECK(unavailable_id>0 AND unavailable_id!=library_id))"
        )
        db.execSQL(
            "CREATE TABLE cloud_catalog_identity (" +
                "slot INTEGER NOT NULL DEFAULT 1 CHECK(slot=1) REFERENCES cloud_catalog_context(slot) ON DELETE CASCADE," +
                "kind TEXT NOT NULL CHECK(kind IN ('PLAYLIST','CATEGORY','TAG'))," +
                "native_id INTEGER NOT NULL CHECK(typeof(native_id)='integer' AND native_id>0)," +
                "portable_id TEXT NOT NULL," +
                "PRIMARY KEY(kind,native_id),UNIQUE(kind,portable_id))"
        )
    }

    /** Runs only on an already-private staged backup, or in a transaction on explicit reset. */
    fun discard(db: SQLiteDatabase) {
        check(db.inTransaction()) { "Cloud identity reset requires a transaction" }
        db.delete("cloud_catalog_identity", null, null)
        db.delete("cloud_catalog_context", null, null)
    }

    fun context(db: SQLiteDatabase): Context? = db.rawQuery(
        "SELECT service,library,incarnation,library_id,unavailable_id FROM cloud_catalog_context WHERE slot=1",
        null
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        val value = Context(Scope(c.getString(0), c.getString(1)), c.getString(2), c.getLong(3), c.getLong(4))
        check(uuid.matches(value.incarnation) && value.libraryId > 0 && value.unavailableId > 0 && value.libraryId != value.unavailableId) {
            "CORRUPT_CLOUD_CONTEXT"
        }
        check(!c.moveToNext()) { "CORRUPT_CLOUD_CONTEXT" }
        value
    }

    fun mappings(db: SQLiteDatabase): Map<NativeId, String> = db.rawQuery(
        "SELECT kind,native_id,portable_id FROM cloud_catalog_identity LIMIT ${MAX_IDENTITIES + 1}",
        null
    ).use { c ->
        buildMap {
            while (c.moveToNext()) {
                check(size < MAX_IDENTITIES) { "CLOUD_IDENTITY_LIMIT" }
                val key = NativeId(Kind.valueOf(c.getString(0)), c.getLong(1))
                val value = c.getString(2)
                check(uuid.matches(value)) { "CORRUPT_CLOUD_IDENTITY" }
                check(put(key, value) == null) { "CORRUPT_CLOUD_IDENTITY" }
            }
        }
    }

    /** The caller validates the resulting full catalog before committing this same transaction. */
    fun reconcile(
        db: SQLiteDatabase,
        scope: Scope,
        libraryId: Long,
        unavailableId: Long,
        expectedIncarnation: String?,
        active: Set<NativeId>,
        enrollment: Boolean = false
    ): Pair<Context, Map<NativeId, String>> {
        check(TagRuntime.lock.isHeldByCurrentThread && db.inTransaction()) { "Cloud mapping changes require writer barrier and transaction" }
        check(active.size <= MAX_IDENTITIES) { "CLOUD_IDENTITY_LIMIT" }
        val old = context(db)
        if (enrollment) {
            check(old == null && expectedIncarnation == null) { "CLOUD_ALREADY_ENROLLED" }
        } else {
            check(old != null && expectedIncarnation != null) { "CLOUD_ENROLLMENT_REQUIRED" }
        }
        check(old == null || old.scope == scope) { "CLOUD_SCOPE_MISMATCH" }
        check(old == null || old.libraryId == libraryId && old.unavailableId == unavailableId) { "CLOUD_BINDING_CHANGED" }
        check(expectedIncarnation == null || old?.incarnation == expectedIncarnation) { "CLOUD_INCARNATION_CHANGED" }
        val previous = mappings(db)
        check(old != null || previous.isEmpty()) { "ORPHAN_CLOUD_IDENTITIES" }
        val current = old ?: Context(scope, UUID.randomUUID().toString(), libraryId, unavailableId).also {
            db.execSQL(
                "INSERT INTO cloud_catalog_context(slot,service,library,incarnation,library_id,unavailable_id) VALUES(1,?,?,?,?,?)",
                arrayOf<Any>(it.scope.service, it.scope.library, it.incarnation, it.libraryId, it.unavailableId)
            )
        }
        val mapping = LinkedHashMap<NativeId, String>(active.size)
        db.compileStatement("INSERT INTO cloud_catalog_identity(kind,native_id,portable_id) VALUES(?,?,?)").use { insert ->
            for (native in active) {
                val portable = previous[native] ?: UUID.randomUUID().toString().also {
                    insert.bindString(1, native.kind.name)
                    insert.bindLong(2, native.id)
                    insert.bindString(3, it)
                    insert.executeInsert()
                    insert.clearBindings()
                }
                mapping[native] = portable
            }
        }
        // No current native catalog writes, tag revision increments or name matching.
        db.compileStatement("DELETE FROM cloud_catalog_identity WHERE kind=? AND native_id=?").use { remove ->
            for (native in previous.keys - active) {
                remove.bindString(1, native.kind.name)
                remove.bindLong(2, native.id)
                remove.executeUpdateDelete()
                remove.clearBindings()
            }
        }
        return current to mapping
    }
}
