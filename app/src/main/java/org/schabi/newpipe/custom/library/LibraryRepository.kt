/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.sqlite.db.SimpleSQLiteQuery
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.Callable
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity

/**
 * Canonical Library membership, not a general NewPipe plugin framework.
 *
 * Normal playlist mutations already execute within NewPipe's Room transactions.
 * Hooks only ADD Library entries. Global remove-everywhere is intentionally
 * unavailable until it has its own transaction and confirmation flow.
 */
object LibraryRepository {
    private const val TAG = "RitoVisionLibrary"
    private const val SMALL_INSERT_THRESHOLD = 32

    private data class Session(
        val db: AppDatabase,
        val id: Long,
        // Suppress saves from stale in-memory ordinary playlist editors after
        // an explicit global Library removal. A fresh explicit add clears it.
        val recentlyRemoved: MutableSet<Long> = HashSet()
    )

    @Volatile
    private var session: Session? = null

    @JvmStatic
    fun start(context: Context) {
        val appContext = context.applicationContext
        Completable.fromAction {
            initialize(NewPipeDatabase.getInstance(appContext), LibraryBindingStore(appContext))
        }.subscribeOn(Schedulers.io()).subscribe(
            { Log.i(TAG, "Library initialized") },
            { error ->
                Log.e(TAG, "Library unavailable; use the Library playlist's repair action", error)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        appContext,
                        "Library needs repair. Open Bookmarked Playlists and long-press Library.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    /**
     * Initializes exactly once per database instance. Recreating an Activity
     * must not clear a live Library session or rescan 20k entries.
     *
     * A mismatched/missing binding is never used to adopt a similarly named
     * playlist automatically. If NO candidate exists, creating a NEW Library
     * and backfilling is always additive and never destroys an existing list.
     */
    @Synchronized
    fun initialize(db: AppDatabase, store: LibraryBindingStore): Long {
        current(db)?.let { return it.id }

        val binding = try {
            store.read()
        } catch (error: Exception) {
            Log.w(TAG, "Cannot verify Library identity; refusing automatic adoption", error)
            null
        }
        var reusedVerifiedBinding = false
        val id = db.runInTransaction(
            Callable {
                val named = queryIds(
                    db,
                    "SELECT uid FROM playlists WHERE LOWER(TRIM(name)) = LOWER(?)",
                    LibraryBindingStore.LIBRARY_NAME
                )
                val boundName = binding?.let { readPlaylistName(db, it.playlistId) }
                if (binding != null && boundName == binding.name) {
                    reusedVerifiedBinding = true
                    binding.playlistId
                } else {
                    // Creating a new playlist is safe; adopting one based on
                    // its name or a stale integer ID is not.
                    check(named.isEmpty()) {
                        "An unbound Library playlist already exists. User confirmation is required."
                    }
                    db.playlistDAO().insert(
                        PlaylistEntity(
                            name = LibraryBindingStore.LIBRARY_NAME,
                            isThumbnailPermanent = false,
                            thumbnailStreamId = PlaylistEntity.DEFAULT_THUMBNAIL_ID,
                            displayIndex = -1
                        )
                    )
                }
            }
        )

        // The binding write follows playlist creation. A crash in between
        // leaves an unbound playlist, requiring deliberate rebind instead of
        // silently treating an arbitrary matching name as authoritative.
        if (!reusedVerifiedBinding) {
            store.write(LibraryBindingStore.Binding(id, LibraryBindingStore.LIBRARY_NAME))
        }

        // Publishing inside the write transaction closes the initialization
        // race. A competing write either commits BEFORE this backfill or sees
        // the published session AFTER it obtains the Room writer lock.
        try {
            db.runInTransaction {
                backfill(db, id)
                session = Session(db, id)
            }
        } catch (error: Throwable) {
            session = null
            throw error
        }
        return id
    }

    /**
     * Deliberate recovery only: the user selected this exact playlist in the
     * UI and accepted that it will become the canonical Library. No deletions.
     */
    @Synchronized
    fun rebindExisting(db: AppDatabase, store: LibraryBindingStore, playlistId: Long): Long {
        val previous = current(db)
        check(previous == null || previous.id == playlistId) {
            "A different Library is already active"
        }
        val selectedName = readPlaylistName(db, playlistId)
        check(
            selectedName?.trim()?.equals(
                LibraryBindingStore.LIBRARY_NAME,
                ignoreCase = true
            ) == true
        ) {
            "The selected playlist is not named Library"
        }
        // This user-approved identity recovery also normalizes casing so
        // future exact binding checks remain reliable.
        try {
            db.runInTransaction {
                if (selectedName != LibraryBindingStore.LIBRARY_NAME) {
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE playlists SET name = ? WHERE uid = ?",
                        arrayOf<Any>(LibraryBindingStore.LIBRARY_NAME, playlistId)
                    )
                }
                backfill(db, playlistId)
                store.write(
                    LibraryBindingStore.Binding(playlistId, LibraryBindingStore.LIBRARY_NAME)
                )
                session = Session(db, playlistId)
            }
        } catch (error: Throwable) {
            session = null
            throw error
        }
        return playlistId
    }

    @JvmStatic
    fun confirmRebind(context: Context, playlistId: Long): Completable {
        val appContext = context.applicationContext
        return Completable.fromAction {
            rebindExisting(
                NewPipeDatabase.getInstance(appContext),
                LibraryBindingStore(appContext),
                playlistId
            )
        }.subscribeOn(Schedulers.io())
    }

    /** Cheap UI check; never runs Room queries on the main thread. */
    @JvmStatic
    fun isBoundLibrary(db: AppDatabase, playlistId: Long): Boolean = current(db)?.id == playlistId

    /**
     * Conservative protection while initialization is absent or pending.
     * A name match may BLOCK deletion/rename, but never authorizes cascading
     * deletion or adoption of a playlist as canonical Library.
     * Call only on the manager's IO/Room transaction thread.
     */
    @JvmStatic
    fun isLibrary(db: AppDatabase, playlistId: Long): Boolean {
        val active = current(db)
        return if (active != null) {
            active.id == playlistId
        } else {
            readPlaylistName(db, playlistId)?.trim()
                ?.equals(LibraryBindingStore.LIBRARY_NAME, ignoreCase = true) == true
        }
    }

    @JvmStatic
    fun afterPlaylistInsert(db: AppDatabase, playlistId: Long, streamIds: List<Long>) {
        val active = current(db) ?: return
        if (playlistId != active.id) {
            insertMissing(db, active.id, streamIds)
        }
        // A normal create/append is a deliberate user save, not a stale editor
        // rewrite; it may legitimately restore a globally removed stream.
        active.recentlyRemoved.removeAll(streamIds.toSet())
    }

    /**
     * The caller has verified this is the protected Library (or a conservative
     * name match while the binding is unavailable). Never append duplicates.
     */
    @JvmStatic
    fun appendDirectly(db: AppDatabase, playlistId: Long, streamIds: List<Long>): List<Long> {
        check(isLibrary(db, playlistId)) { "Not a protected Library playlist" }
        val inserted = insertMissing(db, playlistId, streamIds)
        current(db)?.recentlyRemoved?.removeAll(streamIds.toSet())
        return inserted
    }

    /**
     * Library "updateJoin" is allowed to reorder, but never to discard
     * Library-only entries. This also protects cold-start/stale-screen writes.
     */
    @JvmStatic
    fun rewriteLibraryWithoutRemoving(
        db: AppDatabase,
        playlistId: Long,
        orderedFromEditor: List<Long>
    ): Boolean {
        if (!isLibrary(db, playlistId)) return false
        val stored = orderedStreamIds(db, playlistId)
        val membership = stored.toHashSet()
        val safeOrder = LinkedHashSet<Long>()
        for (id in orderedFromEditor) {
            if (id in membership) safeOrder.add(id)
        }
        safeOrder.addAll(stored)
        db.playlistStreamDAO().deleteBatch(playlistId)
        db.playlistStreamDAO().insertAll(
            safeOrder.mapIndexed { index, streamUid ->
                PlaylistStreamEntity(playlistId, streamUid, index)
            }
        )
        return true
    }

    /**
     * Restore the intended Library semantics with a dedicated delete rather
     * than misinterpreting an upstream updateJoin as global deletion.
     *
     * Must be called for a VERIFIED bound Library, never for a name-only
     * fallback. The single transaction deletes ALL occurrences, including
     * duplicate stream rows in ordinary playlists.
     */
    fun removeEverywhere(db: AppDatabase, playlistId: Long, streamId: Long): Boolean {
        require(streamId > 0) { "Invalid stream ID" }
        val active = current(db)
            ?: error("Library is not initialized; repair it before deleting")
        check(active.id == playlistId) {
            "Global removal must target the verified Library"
        }
        return db.runInTransaction(
            Callable {
                if (!libraryContains(db, playlistId, streamId)) {
                    false // Retried removal: already absent, with no side effects.
                } else {
                    active.recentlyRemoved.add(streamId)
                    try {
                        val sql = db.openHelper.writableDatabase
                        sql.execSQL(
                            "DELETE FROM playlist_stream_join WHERE stream_id = ?",
                            arrayOf<Any>(streamId)
                        )
                        sql.execSQL(
                            "UPDATE playlists SET thumbnail_stream_id = ?, " +
                                "is_thumbnail_permanent = 0 WHERE thumbnail_stream_id = ?",
                            arrayOf<Any>(PlaylistEntity.DEFAULT_THUMBNAIL_ID, streamId)
                        )
                        true
                    } catch (error: Throwable) {
                        // The DB transaction rolls back; remove the tombstone too.
                        active.recentlyRemoved.remove(streamId)
                        throw error
                    }
                }
            }
        )
    }

    /**
     * Used by the local Library screen. The user chose the explicit Delete
     * action and confirmed that it applies to all local playlists.
     */
    @JvmStatic
    fun removeEverywhere(context: Context, playlistId: Long, streamId: Long): Completable {
        val appContext = context.applicationContext
        return Completable.fromAction {
            val db = NewPipeDatabase.getInstance(appContext)
            val verifiedId = initialize(db, LibraryBindingStore(appContext))
            check(verifiedId == playlistId) {
                "Library identity changed; removal was not performed"
            }
            removeEverywhere(db, playlistId, streamId)
        }.subscribeOn(Schedulers.io())
    }

    /**
     * Ordinary edit/reorder UIs can retain stale lists after a Library-wide
     * delete. Never let that old list resurrect a globally removed item.
     * Called inside the same Room transaction as updateJoin.
     */
    @JvmStatic
    fun filterStaleOrdinaryUpdate(
        db: AppDatabase,
        playlistId: Long,
        proposedIds: List<Long>
    ): List<Long> {
        val active = current(db) ?: return proposedIds
        if (playlistId == active.id || active.recentlyRemoved.isEmpty()) {
            return proposedIds
        }
        return proposedIds.filterNot { it in active.recentlyRemoved }
    }

    /** Bookmark sorting may update Library metadata, never its name. */
    @JvmStatic
    fun requireUnchangedLibraryName(db: AppDatabase, playlistId: Long, proposedName: String?) {
        if (isLibrary(db, playlistId)) {
            check(proposedName == readPlaylistName(db, playlistId)) {
                "The protected Library name cannot be changed"
            }
        }
    }

    @JvmStatic
    fun requireNotLibrary(db: AppDatabase, playlistId: Long) {
        check(!isLibrary(db, playlistId)) {
            "The protected Library cannot be renamed or deleted"
        }
    }

    /** Called only by the ZIP restore path, after validating/staging the ZIP. */
    @Synchronized
    fun suspendForRestore() {
        session = null
    }

    /** Test helper: emulate a process entry through RouterActivity. */
    fun forgetSessionForTest() {
        session = null
    }

    @JvmStatic
    fun nextJoinIndex(db: AppDatabase, playlistId: Long): Int = db.openHelper.writableDatabase.query(
        SimpleSQLiteQuery(
            "SELECT COALESCE(MAX(join_index), -1) + 1 FROM playlist_stream_join WHERE playlist_id = ?",
            arrayOf(playlistId)
        )
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun current(db: AppDatabase): Session? = session?.takeIf { it.db === db }

    private fun readPlaylistName(db: AppDatabase, playlistId: Long): String? = db.openHelper.writableDatabase.query(
        SimpleSQLiteQuery("SELECT name FROM playlists WHERE uid = ?", arrayOf(playlistId))
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun queryIds(db: AppDatabase, sql: String, vararg args: Any): List<Long> = db.openHelper.writableDatabase.query(
        SimpleSQLiteQuery(sql, args.toList().toTypedArray())
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getLong(0))
        }
    }

    private fun orderedStreamIds(db: AppDatabase, playlistId: Long): List<Long> = queryIds(
        db,
        "SELECT stream_id FROM playlist_stream_join WHERE playlist_id = ? ORDER BY join_index",
        playlistId
    )

    private fun libraryContains(db: AppDatabase, libraryId: Long, streamId: Long): Boolean = db.openHelper.writableDatabase.query(
        SimpleSQLiteQuery(
            "SELECT 1 FROM playlist_stream_join WHERE playlist_id = ? AND stream_id = ? LIMIT 1",
            arrayOf(libraryId, streamId)
        )
    ).use { it.moveToFirst() }

    private fun backfill(db: AppDatabase, libraryId: Long) {
        val all = queryIds(
            db,
            """
            SELECT ps.stream_id FROM playlist_stream_join ps
            JOIN playlists p ON p.uid = ps.playlist_id
            WHERE ps.playlist_id <> ?
            ORDER BY p.display_index, p.uid, ps.join_index
            """.trimIndent(),
            libraryId
        )
        insertMissing(db, libraryId, all)
    }

    private fun insertMissing(
        db: AppDatabase,
        libraryId: Long,
        streamIds: List<Long>
    ): List<Long> {
        if (streamIds.isEmpty()) return emptyList()
        val existing = if (streamIds.size > SMALL_INSERT_THRESHOLD) {
            orderedStreamIds(db, libraryId).toHashSet()
        } else {
            HashSet<Long>()
        }
        var next = nextJoinIndex(db, libraryId)
        val inserts = ArrayList<PlaylistStreamEntity>()
        for (streamId in streamIds) {
            val alreadySeen = streamId in existing ||
                (streamIds.size <= SMALL_INSERT_THRESHOLD && libraryContains(db, libraryId, streamId))
            if (!alreadySeen) {
                existing.add(streamId)
                inserts.add(PlaylistStreamEntity(libraryId, streamId, next++))
            }
        }
        return if (inserts.isEmpty()) emptyList() else db.playlistStreamDAO().insertAll(inserts)
    }
}
