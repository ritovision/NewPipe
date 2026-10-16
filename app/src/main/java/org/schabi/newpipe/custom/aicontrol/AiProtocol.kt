/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Versioned device protocol. It is deliberately not MCP and contains no credential fields. */
@Serializable
internal data class AiRequest(
    val version: Int,
    @SerialName("device_id") val deviceId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("catalog_epoch") val catalogEpoch: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("issued_at_ms") val issuedAtMs: Long,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
    val command: AiCatalogCommand
)

/** The row ID alone is never sufficient authorization to mutate a stream. */
@Serializable
internal data class AiStreamRef(
    @SerialName("stream_id") val streamId: Long,
    val service: String,
    val url: String
)

@Serializable
internal sealed class AiCatalogCommand {
    @Serializable
    @SerialName("get_catalog_status")
    data object Status : AiCatalogCommand()

    @Serializable
    @SerialName("list_playlists")
    data class ListPlaylists(val after: Long = 0, val limit: Int = 50) : AiCatalogCommand()

    @Serializable
    @SerialName("list_playlist_items")
    data class ListItems(
        @SerialName("playlist_id") val playlistId: Long,
        val after: Int = -1,
        val limit: Int = 50,
        @SerialName("expected_revision") val expectedRevision: String? = null
    ) : AiCatalogCommand()

    @Serializable
    @SerialName("search_library")
    data class Search(val query: String = "", val after: Long = 0, val limit: Int = 50) : AiCatalogCommand()

    @Serializable
    @SerialName("get_stream")
    data class GetStream(val item: AiStreamRef) : AiCatalogCommand()

    @Serializable
    @SerialName("create_playlist")
    data class CreatePlaylist(val name: String, val items: List<AiStreamRef> = emptyList()) : AiCatalogCommand()

    @Serializable
    @SerialName("rename_playlist")
    data class RenamePlaylist(
        @SerialName("playlist_id") val playlistId: Long,
        val name: String,
        @SerialName("expected_revision") val expectedRevision: String? = null
    ) : AiCatalogCommand()

    @Serializable
    @SerialName("delete_playlist")
    data class DeletePlaylist(
        @SerialName("playlist_id") val playlistId: Long,
        @SerialName("expected_revision") val expectedRevision: String? = null
    ) : AiCatalogCommand()

    @Serializable
    @SerialName("save_to_library")
    data class Save(val items: List<AiStreamRef>) : AiCatalogCommand()

    @Serializable
    @SerialName("add_to_playlist")
    data class Add(
        @SerialName("playlist_id") val playlistId: Long,
        val items: List<AiStreamRef>,
        @SerialName("allow_duplicates") val allowDuplicates: Boolean = false,
        @SerialName("expected_revision") val expectedRevision: String? = null
    ) : AiCatalogCommand()

    /** Removes every occurrence of the specified stream identities, not the saved media. */
    @Serializable
    @SerialName("remove_from_playlist")
    data class Remove(
        @SerialName("playlist_id") val playlistId: Long,
        val items: List<AiStreamRef>,
        @SerialName("expected_revision") val expectedRevision: String? = null
    ) : AiCatalogCommand()

    /** A permutation of the original join positions handles duplicate streams unambiguously. */
    @Serializable
    @SerialName("reorder_playlist_items")
    data class Reorder(
        @SerialName("playlist_id") val playlistId: Long,
        @SerialName("ordered_positions") val orderedPositions: List<Int>,
        @SerialName("expected_revision") val expectedRevision: String
    ) : AiCatalogCommand()

    @Serializable
    @SerialName("move_items_between_playlists")
    data class Move(
        @SerialName("source_playlist_id") val sourcePlaylistId: Long,
        @SerialName("target_playlist_id") val targetPlaylistId: Long,
        val items: List<AiStreamRef>,
        @SerialName("source_revision") val sourceRevision: String? = null,
        @SerialName("target_revision") val targetRevision: String? = null
    ) : AiCatalogCommand()

    /** Optional informational operation: this is not a human-approval gate. */
    @Serializable
    @SerialName("preview_library_removal")
    data class PreviewRemoval(val items: List<AiStreamRef>) : AiCatalogCommand()

    @Serializable
    @SerialName("remove_library_items")
    data class RemoveLibrary(val items: List<AiStreamRef>) : AiCatalogCommand()

    @Serializable
    @SerialName("get_command_result")
    data class Receipt(@SerialName("original_request_id") val originalRequestId: String) : AiCatalogCommand()
}

internal enum class AiScope(val wireName: String) {
    LIBRARY_READ("library.read"),
    LIBRARY_WRITE("library.write"),
    LIBRARY_DELETE("library.delete"),
    PLAYLIST_WRITE("playlist.write"),
    PLAYLIST_DELETE("playlist.delete")
}

@Serializable
internal enum class AiOutcome { COMPLETED, COMMITTED, REJECTED, NOT_EXECUTED, OUTCOME_UNKNOWN }

@Serializable
internal data class AiResult(
    @SerialName("request_id") val requestId: String,
    @SerialName("device_id") val deviceId: String,
    val outcome: AiOutcome,
    val code: String = "OK",
    val data: JsonObject = JsonObject(emptyMap()),
    val replayed: Boolean = false
)

/** Only fixed, non-sensitive error codes cross the bridge; never propagate exception messages. */
internal class AiFailure(val code: String, val outcome: AiOutcome = AiOutcome.REJECTED) : RuntimeException(code)

internal object AiProtocol {
    const val VERSION = 1
    const val MAX_REQUEST_BYTES = 65_536
    const val MAX_RESULT_BYTES = 262_144
    const val MAX_ITEMS = 500
    const val MAX_PAGE = 50
    const val MAX_TTL_MS = 120_000L
    const val CLOCK_SKEW_MS = 5_000L
    const val RECEIPT_TTL_MS = 86_400_000L

    val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    fun decode(raw: String): AiRequest = decodeEnvelope(raw).also(::validateShape)

    /** Correlation IDs are validated before argument checks, so a rejection can still be routed. */
    fun decodeEnvelope(raw: String): AiRequest {
        checkInput(raw.length <= MAX_REQUEST_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES, "PAYLOAD_TOO_LARGE")
        // Bound nesting before invoking a recursive JSON parser. Strings cannot add structural depth.
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in raw) {
            if (quoted) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    quoted = false
                }
            } else {
                when (c) {
                    '"' -> quoted = true

                    '{', '[' -> {
                        depth++
                        checkInput(depth <= 12, "INVALID_REQUEST")
                    }

                    '}', ']' -> depth--
                }
            }
        }
        return try {
            json.decodeFromString<AiRequest>(raw).also {
                requireUuid(it.requestId)
                requireUuid(it.deviceId)
            }
        } catch (error: SerializationException) {
            throw AiFailure("INVALID_REQUEST")
        } catch (error: IllegalArgumentException) {
            throw AiFailure("INVALID_REQUEST")
        }
    }

    fun validateShape(request: AiRequest) {
        checkInput(request.version == VERSION, "UNSUPPORTED_VERSION")
        requireUuid(request.deviceId)
        requireUuid(request.catalogEpoch)
        requireUuid(request.requestId)
        checkInput(request.clientId.matches(Regex("[A-Za-z0-9._:-]{1,128}")), "INVALID_CLIENT")
        checkInput(request.issuedAtMs > 0 && request.expiresAtMs > request.issuedAtMs, "INVALID_DEADLINE")
        checkInput(request.expiresAtMs - request.issuedAtMs <= MAX_TTL_MS, "INVALID_DEADLINE")
        when (val command = request.command) {
            AiCatalogCommand.Status -> Unit

            is AiCatalogCommand.ListPlaylists -> {
                checkInput(command.after >= 0)
                page(command.limit)
            }

            is AiCatalogCommand.ListItems -> {
                playlist(command.playlistId)
                checkInput(command.after >= -1)
                page(command.limit)
                revision(command.expectedRevision)
            }

            is AiCatalogCommand.Search -> {
                checkInput(command.query.length <= 200 && command.after >= 0)
                page(command.limit)
            }

            is AiCatalogCommand.GetStream -> item(command.item)

            is AiCatalogCommand.CreatePlaylist -> {
                name(command.name)
                items(command.items, allowEmpty = true)
            }

            is AiCatalogCommand.RenamePlaylist -> {
                playlist(command.playlistId)
                name(command.name)
                revision(command.expectedRevision)
            }

            is AiCatalogCommand.DeletePlaylist -> {
                playlist(command.playlistId)
                revision(command.expectedRevision)
            }

            is AiCatalogCommand.Save -> items(command.items)

            is AiCatalogCommand.Add -> {
                playlist(command.playlistId)
                items(command.items)
                revision(command.expectedRevision)
            }

            is AiCatalogCommand.Remove -> {
                playlist(command.playlistId)
                items(command.items)
                revision(command.expectedRevision)
            }

            is AiCatalogCommand.Reorder -> {
                playlist(command.playlistId)
                checkInput(command.orderedPositions.size <= MAX_ITEMS)
                checkInput(command.orderedPositions.all { it >= 0 })
                checkInput(command.orderedPositions.distinct().size == command.orderedPositions.size)
                revision(command.expectedRevision)
            }

            is AiCatalogCommand.Move -> {
                playlist(command.sourcePlaylistId)
                playlist(command.targetPlaylistId)
                checkInput(command.sourcePlaylistId != command.targetPlaylistId)
                items(command.items)
                revision(command.sourceRevision)
                revision(command.targetRevision)
            }

            is AiCatalogCommand.PreviewRemoval -> items(command.items)

            is AiCatalogCommand.RemoveLibrary -> items(command.items)

            is AiCatalogCommand.Receipt -> requireUuid(command.originalRequestId)
        }
    }

    fun checkIssueTime(request: AiRequest, nowMs: Long) {
        if (request.issuedAtMs > nowMs && request.issuedAtMs - nowMs > CLOCK_SKEW_MS) throw AiFailure("CLOCK_SKEW", AiOutcome.NOT_EXECUTED)
    }

    fun checkDeadline(request: AiRequest, nowMs: Long) {
        checkIssueTime(request, nowMs)
        if (request.expiresAtMs <= nowMs) throw AiFailure("EXPIRED", AiOutcome.NOT_EXECUTED)
    }

    fun requiredScopes(command: AiCatalogCommand): Set<AiScope> = when (command) {
        is AiCatalogCommand.Save -> setOf(AiScope.LIBRARY_WRITE)

        is AiCatalogCommand.RemoveLibrary -> setOf(AiScope.LIBRARY_DELETE)

        is AiCatalogCommand.DeletePlaylist -> setOf(AiScope.PLAYLIST_DELETE)

        is AiCatalogCommand.CreatePlaylist -> if (command.items.isEmpty()) setOf(AiScope.PLAYLIST_WRITE) else setOf(AiScope.PLAYLIST_WRITE, AiScope.LIBRARY_WRITE)

        is AiCatalogCommand.Add, is AiCatalogCommand.Move -> setOf(AiScope.PLAYLIST_WRITE, AiScope.LIBRARY_WRITE)

        is AiCatalogCommand.RenamePlaylist, is AiCatalogCommand.Remove, is AiCatalogCommand.Reorder -> setOf(AiScope.PLAYLIST_WRITE)

        is AiCatalogCommand.Receipt -> emptySet()

        // Enumerate reads: a future new command must explicitly choose its scope.
        AiCatalogCommand.Status, is AiCatalogCommand.ListPlaylists, is AiCatalogCommand.ListItems,
        is AiCatalogCommand.Search, is AiCatalogCommand.GetStream, is AiCatalogCommand.PreviewRemoval -> setOf(AiScope.LIBRARY_READ)
    }

    fun isMutation(command: AiCatalogCommand): Boolean = when (command) {
        is AiCatalogCommand.CreatePlaylist, is AiCatalogCommand.RenamePlaylist, is AiCatalogCommand.DeletePlaylist,
        is AiCatalogCommand.Save, is AiCatalogCommand.Add, is AiCatalogCommand.Remove, is AiCatalogCommand.Reorder,
        is AiCatalogCommand.Move, is AiCatalogCommand.RemoveLibrary -> true

        AiCatalogCommand.Status, is AiCatalogCommand.ListPlaylists, is AiCatalogCommand.ListItems,
        is AiCatalogCommand.Search, is AiCatalogCommand.GetStream, is AiCatalogCommand.PreviewRemoval,
        is AiCatalogCommand.Receipt -> false
    }

    fun fingerprint(request: AiRequest): String = digest(json.encodeToString(request))

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun checkInput(condition: Boolean, code: String = "INVALID_ARGUMENT") {
        if (!condition) throw AiFailure(code)
    }

    fun requireUuid(value: String) {
        val valid = try {
            value.length == 36 && UUID.fromString(value).toString() == value
        } catch (error: IllegalArgumentException) {
            false
        }
        checkInput(valid, "INVALID_IDENTIFIER")
    }

    private fun page(value: Int) = checkInput(value in 1..MAX_PAGE)
    private fun playlist(value: Long) = checkInput(value > 0)
    private fun revision(value: String?) = checkInput(value == null || value.matches(Regex("[0-9a-f]{64}")))
    private fun name(value: String) = checkInput(value.trim().isNotEmpty() && value.length <= 200 && value.none { it.isISOControl() })

    private fun items(values: List<AiStreamRef>, allowEmpty: Boolean = false) {
        checkInput(values.size <= MAX_ITEMS && (allowEmpty || values.isNotEmpty()))
        values.forEach(::item)
    }

    private fun item(value: AiStreamRef) {
        checkInput(value.streamId > 0 && value.service.isNotBlank() && value.service.length <= 64)
        checkInput(value.url.length in 1..2048)
        val uri = try {
            URI(value.url)
        } catch (error: Exception) {
            throw AiFailure("INVALID_URL")
        }
        checkInput(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null, "INVALID_URL")
    }
}
