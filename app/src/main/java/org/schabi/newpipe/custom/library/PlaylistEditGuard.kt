/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import androidx.sqlite.db.SimpleSQLiteQuery
import org.schabi.newpipe.database.AppDatabase

class PlaylistEditConflictException : IllegalStateException("Playlist changed while it was being edited")

/** The UI's old read snapshot must still be current when its replacement acquires the DB lock. */
object PlaylistEditGuard {
    @JvmStatic
    fun verifyUnchanged(db: AppDatabase, playlistId: Long, expectedStreamIds: List<Long>) {
        check(db.inTransaction()) { "Playlist comparison requires the writer transaction" }
        val sql = db.openHelper.writableDatabase
        val exists = sql.query(SimpleSQLiteQuery("SELECT 1 FROM playlists WHERE uid = ?", arrayOf(playlistId))).use { it.moveToFirst() }
        if (!exists) throw PlaylistEditConflictException()
        if (readCurrentIds(db, playlistId) != expectedStreamIds) throw PlaylistEditConflictException()
    }

    @JvmStatic
    fun requireExisting(db: AppDatabase, playlistId: Long) {
        check(db.inTransaction()) { "Playlist validation requires the writer transaction" }
        val exists = db.openHelper.writableDatabase.query(
            SimpleSQLiteQuery("SELECT 1 FROM playlists WHERE uid = ?", arrayOf(playlistId))
        ).use { it.moveToFirst() }
        if (!exists) throw PlaylistEditConflictException()
    }

    @JvmStatic
    fun readCurrentIds(db: AppDatabase, playlistId: Long): List<Long> = db.openHelper.writableDatabase.query(
        SimpleSQLiteQuery("SELECT stream_id FROM playlist_stream_join WHERE playlist_id = ? ORDER BY join_index", arrayOf(playlistId))
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    @JvmStatic
    fun verifyThumbnail(db: AppDatabase, playlistId: Long, streamId: Long) {
        check(db.inTransaction()) { "Thumbnail validation requires the writer transaction" }
        if (streamId == -1L) return
        val savedIn = LibraryRepository.boundLibraryId(db) ?: playlistId
        val saved = db.openHelper.writableDatabase.query(
            SimpleSQLiteQuery("SELECT 1 FROM playlist_stream_join WHERE playlist_id = ? AND stream_id = ? LIMIT 1", arrayOf(savedIn, streamId))
        ).use { it.moveToFirst() }
        if (!saved) throw PlaylistEditConflictException()
    }
}
