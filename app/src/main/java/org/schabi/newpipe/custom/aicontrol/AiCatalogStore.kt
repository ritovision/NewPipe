/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.extractor.NewPipe

internal fun interface AiServiceNames {
    fun name(serviceId: Int): String
}

/**
 * All methods run under the executor's single Room transaction. No reactive blocking reads,
 * network calls, model-provided StreamEntity metadata, or generic SQL escape hatches.
 */
internal class AiCatalogStore(
    private val db: AppDatabase,
    private val serviceNames: AiServiceNames = AiServiceNames { NewPipe.getService(it).serviceInfo.name }
) {
    private val sql get() = db.openHelper.writableDatabase

    fun run(command: AiCatalogCommand): JsonObject {
        val library = LibraryRepository.boundLibraryId(db) ?: throw AiFailure("LIBRARY_NOT_READY", AiOutcome.NOT_EXECUTED)
        return when (command) {
            AiCatalogCommand.Status -> buildJsonObject {
                put("library_id", library)
                put("saved_items", scalar("SELECT COUNT(*) FROM playlist_stream_join WHERE playlist_id = ?", library))
                put("protocol_version", AiProtocol.VERSION)
            }

            is AiCatalogCommand.ListPlaylists -> listPlaylists(command, library)

            is AiCatalogCommand.ListItems -> listItems(command)

            is AiCatalogCommand.Search -> search(command, library)

            is AiCatalogCommand.GetStream -> {
                val id = resolveSaved(listOf(command.item), library).single()
                query("SELECT s.*, -1 AS join_index FROM streams s WHERE uid = ?", id).use {
                    if (!it.moveToFirst()) throw AiFailure("NOT_FOUND")
                    streamJson(it)
                }
            }

            is AiCatalogCommand.CreatePlaylist -> {
                val ids = resolve(command.items)
                val id = db.playlistDAO().insert(
                    PlaylistEntity(name = command.name.trim(), isThumbnailPermanent = false, thumbnailStreamId = ids.firstOrNull() ?: -1L, displayIndex = -1)
                )
                append(id, ids, allowDuplicates = true)
                writeResult(id, ids.size)
            }

            is AiCatalogCommand.RenamePlaylist -> {
                ordinary(command.playlistId, library, command.expectedRevision)
                sql.execSQL("UPDATE playlists SET name = ? WHERE uid = ?", arrayOf<Any>(command.name.trim(), command.playlistId))
                writeResult(command.playlistId, 1)
            }

            is AiCatalogCommand.DeletePlaylist -> {
                ordinary(command.playlistId, library, command.expectedRevision)
                val memberships = scalar("SELECT COUNT(*) FROM playlist_stream_join WHERE playlist_id = ?", command.playlistId)
                sql.execSQL("DELETE FROM playlists WHERE uid = ?", arrayOf(command.playlistId))
                buildJsonObject {
                    put("playlist_id", command.playlistId)
                    put("removed_playlists", 1)
                    put("removed_memberships", memberships)
                    put("removed_library_items", 0)
                }
            }

            is AiCatalogCommand.Save -> {
                val ids = resolve(command.items)
                val added = LibraryRepository.appendDirectly(db, library, ids).size
                buildJsonObject {
                    put("library_id", library)
                    put("added_items", added)
                }
            }

            is AiCatalogCommand.Add -> {
                ordinary(command.playlistId, library, command.expectedRevision)
                val added = append(command.playlistId, resolve(command.items), command.allowDuplicates)
                writeResult(command.playlistId, added)
            }

            is AiCatalogCommand.Remove -> {
                ordinary(command.playlistId, library, command.expectedRevision)
                val removed = removeMemberships(command.playlistId, resolve(command.items).distinct())
                writeResult(command.playlistId, removed)
            }

            is AiCatalogCommand.Reorder -> {
                ordinary(command.playlistId, library, command.expectedRevision)
                val current = entries(command.playlistId)
                if (command.orderedPositions.size != current.size || command.orderedPositions.toSet() != current.map { it.first }.toSet()) {
                    throw AiFailure("NOT_A_PERMUTATION")
                }
                val byPosition = current.toMap()
                db.playlistStreamDAO().deleteBatch(command.playlistId)
                db.playlistStreamDAO().insertAll(command.orderedPositions.mapIndexed { index, position -> PlaylistStreamEntity(command.playlistId, byPosition.getValue(position), index) })
                writeResult(command.playlistId, current.size)
            }

            is AiCatalogCommand.Move -> {
                ordinary(command.sourcePlaylistId, library, command.sourceRevision)
                ordinary(command.targetPlaylistId, library, command.targetRevision)
                val ids = resolve(command.items).distinct()
                if (ids.any { !contains(command.sourcePlaylistId, it) }) throw AiFailure("NOT_IN_SOURCE")
                val added = append(command.targetPlaylistId, ids, allowDuplicates = false)
                val removed = removeMemberships(command.sourcePlaylistId, ids)
                buildJsonObject {
                    put("added_memberships", added)
                    put("removed_memberships", removed)
                    put("source_revision", revision(command.sourcePlaylistId))
                    put("target_revision", revision(command.targetPlaylistId))
                }
            }

            is AiCatalogCommand.PreviewRemoval -> {
                val ids = resolveSaved(command.items, library).distinct()
                buildJsonObject {
                    put(
                        "items",
                        JsonArray(
                            ids.map { id ->
                                buildJsonObject {
                                    put("stream_id", id)
                                    put("saved", contains(library, id))
                                    put("affected_memberships", scalar("SELECT COUNT(*) FROM playlist_stream_join WHERE stream_id = ?", id))
                                    put("affected_playlists", scalar("SELECT COUNT(DISTINCT playlist_id) FROM playlist_stream_join WHERE stream_id = ?", id))
                                }
                            }
                        )
                    )
                }
            }

            is AiCatalogCommand.RemoveLibrary -> {
                val ids = resolve(command.items).distinct()
                var removedItems = 0
                var removedMemberships = 0L
                for (id in ids) {
                    if (contains(library, id)) {
                        val count = scalar("SELECT COUNT(*) FROM playlist_stream_join WHERE stream_id = ?", id)
                        if (LibraryRepository.removeEverywhere(db, library, id)) {
                            removedItems++
                            removedMemberships += count
                        }
                    }
                }
                buildJsonObject {
                    put("removed_library_items", removedItems)
                    put("removed_memberships", removedMemberships)
                }
            }

            is AiCatalogCommand.Receipt -> throw AiFailure("INVALID_DISPATCH")
        }
    }

    private fun ordinary(id: Long, library: Long, expectedRevision: String?) {
        if (id == library) throw AiFailure("PROTECTED_LIBRARY")
        if (scalar("SELECT COUNT(*) FROM playlists WHERE uid = ?", id) != 1L) throw AiFailure("PLAYLIST_NOT_FOUND")
        if (expectedRevision != null && revision(id) != expectedRevision) throw AiFailure("CONFLICT")
    }

    /** Read-only tools must not distinguish hidden history/feed rows from absent rows. */
    private fun resolveSaved(refs: List<AiStreamRef>, library: Long): List<Long> {
        if (refs.any { !contains(library, it.streamId) }) throw AiFailure("NOT_FOUND")
        return try {
            resolve(refs)
        } catch (error: AiFailure) {
            if (error.code in setOf("STREAM_NOT_FOUND", "STREAM_IDENTITY_MISMATCH", "UNSUPPORTED_SERVICE")) throw AiFailure("NOT_FOUND")
            throw error
        }
    }

    private fun resolve(refs: List<AiStreamRef>): List<Long> {
        if (refs.isEmpty()) return emptyList()
        val unique = refs.map { it.streamId }.distinct()
        val stored = mutableMapOf<Long, Pair<String, String>>()
        query("SELECT uid, service_id, url FROM streams WHERE uid IN (${placeholders(unique.size)})", *unique.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val name = try {
                    serviceNames.name(cursor.getInt(1))
                } catch (error: Exception) {
                    throw AiFailure("UNSUPPORTED_SERVICE")
                }
                stored[cursor.getLong(0)] = name to cursor.getString(2)
            }
        }
        return refs.map { ref ->
            val row = stored[ref.streamId] ?: throw AiFailure("STREAM_NOT_FOUND")
            if (row.first != ref.service || row.second != ref.url) throw AiFailure("STREAM_IDENTITY_MISMATCH")
            ref.streamId
        }
    }

    private fun append(playlistId: Long, proposed: List<Long>, allowDuplicates: Boolean): Int {
        val ids = if (allowDuplicates) proposed else proposed.distinct().filterNot { contains(playlistId, it) }
        if (ids.isNotEmpty()) {
            val next = LibraryRepository.nextJoinIndex(db, playlistId)
            if (next < 0 || next.toLong() + ids.size > Int.MAX_VALUE) throw AiFailure("PLAYLIST_TOO_LARGE")
            db.playlistStreamDAO().insertAll(ids.mapIndexed { index, id -> PlaylistStreamEntity(playlistId, id, next + index) })
        }
        // Explicit saving must restore Library membership even when the ordinary membership already exists.
        LibraryRepository.afterPlaylistInsert(db, playlistId, proposed)
        refreshThumbnail(playlistId)
        return ids.size
    }

    private fun removeMemberships(playlistId: Long, ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val args = arrayOf<Any>(playlistId, *ids.toTypedArray())
        val condition = "playlist_id = ? AND stream_id IN (${placeholders(ids.size)})"
        val count = scalar("SELECT COUNT(*) FROM playlist_stream_join WHERE $condition", *args)
        sql.execSQL("DELETE FROM playlist_stream_join WHERE $condition", args)
        refreshThumbnail(playlistId)
        return count.toInt()
    }

    private fun refreshThumbnail(playlistId: Long) {
        LibraryRepository.refreshAutomaticThumbnail(db, playlistId)
    }

    private fun listPlaylists(command: AiCatalogCommand.ListPlaylists, library: Long): JsonObject {
        val rows = mutableListOf<JsonObject>()
        query(
            "SELECT p.uid, p.name, (SELECT COUNT(*) FROM playlist_stream_join j WHERE j.playlist_id = p.uid) AS item_count " +
                "FROM playlists p WHERE p.uid > ? ORDER BY p.uid LIMIT ?",
            command.after,
            command.limit + 1
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    buildJsonObject {
                        put("playlist_id", cursor.getLong(0))
                        put("name", cursor.getString(1).orEmpty().take(200))
                        put("item_count", cursor.getLong(2))
                        put("canonical_library", cursor.getLong(0) == library)
                    }
                )
            }
        }
        return buildJsonObject {
            put("playlists", JsonArray(rows.take(command.limit)))
            put("next_after", if (rows.size > command.limit) rows[command.limit - 1].getValue("playlist_id") else JsonNull)
        }
    }

    private fun listItems(command: AiCatalogCommand.ListItems): JsonObject {
        val currentRevision = revision(command.playlistId)
        if (command.expectedRevision != null && command.expectedRevision != currentRevision) throw AiFailure("CONFLICT")
        val rows = mutableListOf<JsonObject>()
        query(
            "SELECT s.*, j.join_index FROM playlist_stream_join j INNER JOIN streams s ON s.uid = j.stream_id " +
                "WHERE j.playlist_id = ? AND j.join_index > ? ORDER BY j.join_index LIMIT ?",
            command.playlistId,
            command.after,
            command.limit + 1
        ).use { cursor -> while (cursor.moveToNext()) rows.add(streamJson(cursor)) }
        return buildJsonObject {
            put("playlist_id", command.playlistId)
            put("revision", currentRevision)
            put("items", JsonArray(rows.take(command.limit)))
            put("next_after", if (rows.size > command.limit) rows[command.limit - 1].getValue("position") else JsonNull)
        }
    }

    private fun search(command: AiCatalogCommand.Search, library: Long): JsonObject {
        val pattern = "%${command.query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        val rows = mutableListOf<JsonObject>()
        query(
            """
            SELECT s.*, -1 AS join_index FROM streams s
            WHERE s.uid > ? AND EXISTS (SELECT 1 FROM playlist_stream_join j WHERE j.stream_id = s.uid AND j.playlist_id = ?)
                AND (s.title LIKE ? ESCAPE '\' OR s.uploader LIKE ? ESCAPE '\')
            ORDER BY s.uid LIMIT ?
            """.trimIndent(),
            command.after,
            library,
            pattern,
            pattern,
            command.limit + 1
        ).use { cursor -> while (cursor.moveToNext()) rows.add(streamJson(cursor)) }
        return buildJsonObject {
            put("items", JsonArray(rows.take(command.limit)))
            val last = rows.getOrNull(command.limit - 1)?.get("ref") as? JsonObject
            put("next_after", if (rows.size > command.limit) last!!.getValue("stream_id") else JsonNull)
        }
    }

    private fun streamJson(cursor: Cursor): JsonObject = buildJsonObject {
        val serviceId = cursor.getInt(cursor.getColumnIndexOrThrow("service_id"))
        val service = try {
            serviceNames.name(serviceId)
        } catch (error: Exception) {
            throw AiFailure("UNSUPPORTED_SERVICE")
        }
        put(
            "ref",
            buildJsonObject {
                put("stream_id", cursor.getLong(cursor.getColumnIndexOrThrow("uid")))
                put("service", service)
                put("url", cursor.getString(cursor.getColumnIndexOrThrow("url")))
            }
        )
        put("title", cursor.getString(cursor.getColumnIndexOrThrow("title")).orEmpty().take(512))
        put("uploader", cursor.getString(cursor.getColumnIndexOrThrow("uploader")).orEmpty().take(256))
        put("duration_seconds", cursor.getLong(cursor.getColumnIndexOrThrow("duration")))
        put("position", cursor.getInt(cursor.getColumnIndexOrThrow("join_index")))
    }

    private fun revision(playlistId: Long): String {
        val metadata = query("SELECT name, is_thumbnail_permanent, thumbnail_stream_id, display_index FROM playlists WHERE uid = ?", playlistId).use { cursor ->
            if (!cursor.moveToFirst()) throw AiFailure("PLAYLIST_NOT_FOUND")
            listOf(cursor.getString(0).orEmpty(), cursor.getInt(1).toString(), cursor.getLong(2).toString(), cursor.getLong(3).toString())
        }
        val content = entries(playlistId).map { "${it.first}:${it.second}" }
        return AiProtocol.digest(AiProtocol.json.encodeToString(metadata + content))
    }

    private fun entries(playlistId: Long): List<Pair<Int, Long>> = query(
        "SELECT join_index, stream_id FROM playlist_stream_join WHERE playlist_id = ? ORDER BY join_index",
        playlistId
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getLong(1)) } }

    private fun writeResult(playlistId: Long, affected: Int): JsonObject = buildJsonObject {
        put("playlist_id", playlistId)
        put("affected_memberships", affected)
        put("revision", revision(playlistId))
    }

    private fun contains(playlistId: Long, streamId: Long): Boolean = scalar(
        "SELECT EXISTS(SELECT 1 FROM playlist_stream_join WHERE playlist_id = ? AND stream_id = ?)",
        playlistId,
        streamId
    ) == 1L

    private fun scalar(statement: String, vararg args: Any): Long = query(statement, *args).use {
        check(it.moveToFirst())
        it.getLong(0)
    }

    private fun query(statement: String, vararg args: Any): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")
}
