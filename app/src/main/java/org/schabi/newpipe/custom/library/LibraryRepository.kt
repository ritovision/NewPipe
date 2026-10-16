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
 * The ONLY owner of the special Library playlist.
 *
 * All methods called from LocalPlaylistManager's mutation paths run inside its
 * existing Room transaction. This class owns no extractor, search, or player code.
 *
 * First milestone: safe adds, backfill, and protected reordering only.
 * Intentional remove-from-Library-everywhere is deliberately NOT enabled yet.
 */
object LibraryRepository {
    private const val TAG = "RitoVisionLibrary"

    private data class Session(val db: AppDatabase, val id: Long)

    @Volatile
    private var session: Session? = null

    /** Startup only. Do not block the main thread while Room opens the DB. */
    @JvmStatic
    fun start(context: Context) {
        val appContext = context.applicationContext
        Completable.fromAction {
            initialize(NewPipeDatabase.getInstance(appContext), LibraryBindingStore(appContext))
        }.subscribeOn(Schedulers.io()).subscribe(
            { Log.i(TAG, "Library initialized") },
            { error ->
                Log.e(TAG, "Library unavailable; refusing unbound writes", error)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        appContext,
                        "Library setup needs attention; automatic saving is unavailable.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    /**
     * Initializes and safely backfills existing playlists.
     * Exposed to instrumented tests with a separate in-memory DB and sidecar.
     */
    @Synchronized
    fun initialize(db: AppDatabase, store: LibraryBindingStore): Long {
        // Never authorize a prior database's cached playlist identity.
        session = null
        val binding = store.read()
        val id = db.runInTransaction(
            Callable {
                if (binding == null) {
                    // Name alone is not proof of identity: don't silently adopt one.
                    val named = queryIds(
                        db,
                        "SELECT uid FROM playlists WHERE LOWER(TRIM(name)) = LOWER(?)",
                        LibraryBindingStore.LIBRARY_NAME
                    )
                    check(named.isEmpty()) {
                        "Unbound playlist named Library exists. Review its identity before enabling."
                    }
                    db.playlistDAO().insert(
                        PlaylistEntity(
                            name = LibraryBindingStore.LIBRARY_NAME,
                            isThumbnailPermanent = false,
                            thumbnailStreamId = PlaylistEntity.DEFAULT_THUMBNAIL_ID,
                            displayIndex = -1
                        )
                    )
                } else {
                    check(readPlaylistName(db, binding.playlistId) == binding.name) {
                        "Library binding does not match database; refusing to adopt a playlist"
                    }
                    binding.playlistId
                }
            }
        )

        if (binding == null) {
            store.write(LibraryBindingStore.Binding(id, LibraryBindingStore.LIBRARY_NAME))
        }

        // This transaction completes all backfill before the session becomes active.
        db.runInTransaction { backfill(db, id) }
        session = Session(db, id)

        // Close the race between committing backfill and publishing the session:
        // an ordinary write during that interval may have skipped its hook.
        db.runInTransaction { backfill(db, id) }
        return id
    }

    @JvmStatic
    fun isLibrary(db: AppDatabase, playlistId: Long): Boolean = current(db)?.id == playlistId

    /** Called *inside* the manager's transaction after an ordinary join insertion. */
    @JvmStatic
    fun afterPlaylistInsert(db: AppDatabase, playlistId: Long, streamIds: List<Long>) {
        val id = current(db)?.id ?: return
        if (playlistId != id) {
            insertMissing(db, id, streamIds)
        }
    }

    /** Called inside the manager's transaction after an ordinary join replacement. */
    @JvmStatic
    fun afterPlaylistUpdate(db: AppDatabase, playlistId: Long, streamIds: List<Long>) {
        afterPlaylistInsert(db, playlistId, streamIds)
    }

    /**
     * Called in the append transaction when the selected target is Library.
     * Unlike NewPipe's normal append, a repeated Library save is idempotent.
     */
    @JvmStatic
    fun appendDirectly(db: AppDatabase, playlistId: Long, streamIds: List<Long>): List<Long> {
        check(isLibrary(db, playlistId)) { "Library binding is not active" }
        return insertMissing(db, playlistId, streamIds)
    }

    /**
     * Called in updateJoin's transaction only for the canonical Library.
     * Reordering cannot delete saved entries. Entries added since an editor
     * loaded are retained at the end, not treated as global deletions.
     */
    @JvmStatic
    fun rewriteLibraryWithoutRemoving(
        db: AppDatabase,
        playlistId: Long,
        orderedFromEditor: List<Long>
    ): Boolean {
        if (!isLibrary(db, playlistId)) {
            return false
        }
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
     * Reserve this playlist from rename and delete even in manager code, not
     * only in UI. Global removal will have its own explicit, tested API later.
     */
    @JvmStatic
    fun requireNotLibrary(db: AppDatabase, playlistId: Long) {
        check(!isLibrary(db, playlistId)) {
            "The canonical Library cannot be renamed or deleted"
        }
    }

    /**
     * Used by NewPipe's native ZIP restore path before replacing the database.
     * Never use a cached id with a newly imported database.
     */
    @JvmStatic
    fun suspendForRestore() {
        session = null
    }

    /** Tests only; production code must never reset identity without restore. */
    fun forgetSessionForTest() {
        session = null
    }

    /** Compute inside the enclosing transaction, not before it (append race). */
    @JvmStatic
    fun nextJoinIndex(db: AppDatabase, playlistId: Long): Int {
        val sql = """
            SELECT COALESCE(MAX(join_index), -1) + 1
            FROM playlist_stream_join WHERE playlist_id = ?
        """.trimIndent()
        return db.openHelper.writableDatabase.query(
            SimpleSQLiteQuery(sql, arrayOf(playlistId))
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
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

    private fun backfill(db: AppDatabase, libraryId: Long) {
        // Stable iteration order for the first encounter of a stream; preserve
        // existing order, including all duplicates, in the ordinary playlists.
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
        val existing = orderedStreamIds(db, libraryId).toHashSet()
        var next = nextJoinIndex(db, libraryId)
        val inserts = ArrayList<PlaylistStreamEntity>()
        for (streamId in streamIds) {
            if (existing.add(streamId)) {
                inserts.add(PlaylistStreamEntity(libraryId, streamId, next++))
            }
        }
        return if (inserts.isEmpty()) emptyList() else db.playlistStreamDAO().insertAll(inserts)
    }
}
