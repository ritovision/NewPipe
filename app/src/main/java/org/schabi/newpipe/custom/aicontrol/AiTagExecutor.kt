/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.custom.tagging.StreamKey
import org.schabi.newpipe.custom.tagging.TagFilter
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.custom.tagging.TagSort
import org.schabi.newpipe.custom.tagging.TagStore
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.extractor.NewPipe

/** Runs only after authorization, under TagRuntime, NEVER inside a Room transaction. */
internal class AiTagExecutor(private val repository: TagRepository, private val clock: () -> Long) {
    fun execute(request: AiRequest, lease: AiAccessController.Lease): AiResult {
        check(!repository.library.inTransaction())
        val command = request.command as AiTagCommand
        val mutation = AiTagProtocol.mutation(command)
        val previous = receipt(repository.library, request, request.requestId)
        if (previous != null && mutation) {
            if (previous.fingerprint != AiProtocol.fingerprint(request)) throw AiFailure("REQUEST_ID_REUSED")
            lease.requireScopes(parseScopes(previous.requiredScopes))
            return decode(previous).copy(replayed = true)
        }
        val now = clock()
        if (mutation && request.expiresAtMs <= now && now - request.issuedAtMs >= AiProtocol.RECEIPT_TTL_MS - AiProtocol.CLOCK_SKEW_MS) {
            throw AiFailure("EXPIRED_RESULT_NOT_RETAINED", AiOutcome.OUTCOME_UNKNOWN)
        }
        lease.checkActive()
        AiProtocol.checkDeadline(request, now)
        if (!mutation) {
            return AiResult(request.requestId, request.deviceId, AiOutcome.COMPLETED, data = run(command)).also {
                lease.checkActive()
                AiProtocol.checkDeadline(request, clock())
                if (AiProtocol.json.encodeToString(it).toByteArray().size > AiProtocol.MAX_RESULT_BYTES) throw AiFailure("RESULT_TOO_LARGE")
            }
        }
        // Tag-only effects + deduplication receipt commit together in the independent tag DB.
        return repository.store.transaction {
            repository.store.pruneReceipts(clock())
            val data = run(command)
            val result = AiResult(request.requestId, request.deviceId, AiOutcome.COMMITTED, data = data)
            val encoded = AiProtocol.json.encodeToString(result)
            if (encoded.toByteArray().size > AiCatalogExecutor.MAX_RECEIPT_BYTES) throw AiFailure("RESULT_TOO_LARGE")
            repository.store.insertReceipt(AiCommandReceipt(request.deviceId, request.catalogEpoch, request.clientId, request.requestId, AiProtocol.fingerprint(request), AiTagProtocol.scopes(command).map { it.wireName }.sorted().joinToString(","), encoded, clock() + AiProtocol.RECEIPT_TTL_MS))
            lease.checkActive()
            AiProtocol.checkDeadline(request, clock())
            result
        }
    }

    private fun run(command: AiTagCommand): JsonObject {
        val definitions = repository.definitions()
        fun verifyRevision(expected: Long) {
            if (expected != definitions.version) throw AiFailure("TAG_CONFLICT")
        }
        fun result(id: Long, category: Boolean) = buildJsonObject {
            put(if (category) "category_id" else "tag_id", id)
            put("catalog_revision", repository.definitions().version)
        }
        return when (command) {
            is AiTagCommand.Categories -> {
                val entries = definitions.categories.filter { it.id > command.after }.sortedBy { it.id }
                buildJsonObject {
                    put(
                        "categories",
                        JsonArray(
                            entries.take(command.limit).map {
                                buildJsonObject {
                                    put("category_id", it.id)
                                    put("name", it.name)
                                }
                            }
                        )
                    )
                    put("catalog_revision", definitions.version)
                    put("next_after", if (entries.size > command.limit) JsonPrimitive(entries[command.limit - 1].id) else JsonNull)
                }
            }

            is AiTagCommand.Tags -> {
                if (command.categoryId != null && definitions.categories.none { it.id == command.categoryId }) throw AiFailure("CATEGORY_NOT_FOUND")
                val entries = definitions.tags.filter { it.id > command.after && (command.categoryId == null || it.categoryId == command.categoryId) }.sortedBy { it.id }
                buildJsonObject {
                    put(
                        "tags",
                        JsonArray(
                            entries.take(command.limit).map {
                                buildJsonObject {
                                    put("tag_id", it.id)
                                    put("name", it.name)
                                    put("category_id", it.categoryId?.let(::JsonPrimitive) ?: JsonNull)
                                }
                            }
                        )
                    )
                    put("catalog_revision", definitions.version)
                    put("next_after", if (entries.size > command.limit) JsonPrimitive(entries[command.limit - 1].id) else JsonNull)
                }
            }

            is AiTagCommand.ItemTags -> {
                val state = repository.editor(savedKey(command.item))
                buildJsonObject {
                    put("tag_ids", JsonArray(state.assigned.sorted().map(::JsonPrimitive)))
                    put("revision", state.revision)
                    put("catalog_revision", state.definitions.version)
                }
            }

            is AiTagCommand.Search -> search(command)

            is AiTagCommand.CreateCategory -> result(repository.createCategory(command.name), true)

            is AiTagCommand.CreateTag -> result(repository.createTag(command.name, command.categoryId), false)

            is AiTagCommand.RenameCategory -> {
                verifyRevision(command.expectedRevision)
                repository.renameCategory(command.categoryId, command.name)
                result(command.categoryId, true)
            }

            is AiTagCommand.UpdateTag -> {
                verifyRevision(command.expectedRevision)
                repository.editTag(command.tagId, command.name, command.categoryId)
                result(command.tagId, false)
            }

            is AiTagCommand.DeleteTag, is AiTagCommand.DeleteCategory -> {
                val category = command is AiTagCommand.DeleteCategory
                val id = if (category) (command as AiTagCommand.DeleteCategory).categoryId else (command as AiTagCommand.DeleteTag).tagId
                verifyRevision(if (category) (command as AiTagCommand.DeleteCategory).expectedRevision else (command as AiTagCommand.DeleteTag).expectedRevision)
                val plan = repository.deletionPlan(id, category)
                repository.delete(plan)
                buildJsonObject {
                    put("deleted_tags", plan.tags)
                    put("deleted_assignments", plan.assignments)
                    put("deleted_categories", if (category) 1 else 0)
                    put("removed_library_items", 0)
                    put("catalog_revision", repository.definitions().version)
                }
            }

            is AiTagCommand.SetTags -> {
                val key = savedKey(command.item)
                val state = repository.editor(key)
                if (state.revision != command.expectedRevision) throw AiFailure("TAG_CONFLICT")
                if (!definitions.tags.map { it.id }.containsAll(command.tagIds)) throw AiFailure("TAG_NOT_FOUND")
                repository.setSavedTags(key, command.tagIds.toSet(), command.expectedRevision)
                buildJsonObject {
                    put("revision", repository.editor(key).revision)
                    put("tag_ids", JsonArray(command.tagIds.sorted().map(::JsonPrimitive)))
                }
            }
        }
    }

    private fun savedKey(ref: AiStreamRef): StreamKey {
        val db = repository.library
        val libraryId = LibraryRepository.boundLibraryId(db) ?: throw AiFailure("LIBRARY_NOT_READY")
        return db.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT service_id,url FROM streams WHERE uid=? AND EXISTS(SELECT 1 FROM playlist_stream_join WHERE playlist_id=? AND stream_id=streams.uid)", arrayOf(ref.streamId, libraryId))).use { c ->
            if (!c.moveToFirst()) throw AiFailure("NOT_FOUND")
            val serviceId = c.getInt(0)
            if (c.getString(1) != ref.url || NewPipe.getService(serviceId).serviceInfo.name != ref.service) throw AiFailure("NOT_FOUND")
            StreamKey(serviceId, ref.url)
        }
    }

    private fun search(command: AiTagCommand.Search): JsonObject {
        val snapshot = repository.snapshot()
        val known = snapshot.definitions.tags.map { it.id }.toSet()
        if (!known.containsAll(command.included + command.excluded)) throw AiFailure("TAG_NOT_FOUND")
        val filter = TagFilter(
            command.query,
            command.included.toSet(),
            command.excluded.toSet(),
            command.matchAll,
            command.untagged,
            when (command.sort) {
                "title_asc" -> TagSort.TITLE_ASC
                "title_desc" -> TagSort.TITLE_DESC
                else -> TagSort.LIBRARY
            }
        )
        val rows = filter.select(snapshot.items)
        val revision = AiProtocol.digest(AiProtocol.json.encodeToString(command.copy(offset = 0, expectedRevision = null)) + ":" + snapshot.definitions.version + ":" + AiProtocol.json.encodeToString(rows.map { listOf(it.stream.uid.toString(), it.stream.serviceId.toString(), it.stream.url, it.stream.title, it.stream.uploader) }))
        if (command.expectedRevision != null && command.expectedRevision != revision) throw AiFailure("TAG_RESULTS_CHANGED")
        return buildJsonObject {
            put("revision", revision)
            put("total", rows.size)
            put(
                "items",
                JsonArray(
                    rows.drop(command.offset).take(command.limit).map { item ->
                        buildJsonObject {
                            val stream = item.stream
                            put(
                                "ref",
                                buildJsonObject {
                                    put("stream_id", stream.uid)
                                    put("service", NewPipe.getService(stream.serviceId).serviceInfo.name)
                                    put("url", stream.url)
                                }
                            )
                            put("title", stream.title.take(512))
                            put("uploader", stream.uploader.take(256))
                            put("tag_ids", JsonArray(item.tagIds.sorted().map(::JsonPrimitive)))
                        }
                    }
                )
            )
            put("next_offset", if (command.offset + command.limit < rows.size) JsonPrimitive(command.offset + command.limit) else JsonNull)
        }
    }

    companion object {
        fun parseScopes(value: String): Set<AiScope> = value.split(',').map { wire -> AiScope.entries.singleOrNull { it.wireName == wire } ?: throw AiFailure("INVALID_RECEIPT", AiOutcome.OUTCOME_UNKNOWN) }.toSet()
        fun decode(receipt: AiCommandReceipt): AiResult {
            if (receipt.resultJson.length > AiCatalogExecutor.MAX_RECEIPT_BYTES) throw AiFailure("INVALID_RECEIPT", AiOutcome.OUTCOME_UNKNOWN)
            return AiProtocol.json.decodeFromString<AiResult>(receipt.resultJson).also { if (it.requestId != receipt.requestId || it.deviceId != receipt.deviceId || it.outcome != AiOutcome.COMMITTED) throw AiFailure("INVALID_RECEIPT", AiOutcome.OUTCOME_UNKNOWN) }
        }
        fun receipt(db: AppDatabase, request: AiRequest, id: String): AiCommandReceipt? {
            val room = db.aiCommandReceiptDao().find(request.deviceId, request.catalogEpoch, request.clientId, id)
            val tags = TagRuntime.lookup(db)?.store?.findReceipt(request.deviceId, request.catalogEpoch, request.clientId, id)
            if (room != null && tags != null) throw AiFailure("AMBIGUOUS_RECEIPT", AiOutcome.OUTCOME_UNKNOWN)
            return room ?: tags
        }

        /** Staged before Room; only receipt-backed commits may trigger tag cleanup. */
        fun stageRemoval(repository: TagRepository, request: AiRequest) {
            val command = request.command as? AiCatalogCommand.RemoveLibrary ?: return
            if (receipt(repository.library, request, request.requestId) != null) return
            val libraryId = LibraryRepository.boundLibraryId(repository.library) ?: throw AiFailure("LIBRARY_NOT_READY")
            val keys = repository.readLibraryStreams(libraryId).associateBy { it.uid }
            val intents = command.items.distinctBy { it.streamId }.mapNotNull { ref ->
                val row = keys[ref.streamId] ?: return@mapNotNull null
                if (row.url != ref.url || NewPipe.getService(row.serviceId).serviceInfo.name != ref.service) throw AiFailure("STREAM_IDENTITY_MISMATCH")
                TagStore.AiRemoval(request.deviceId, request.catalogEpoch, request.clientId, request.requestId, AiProtocol.fingerprint(request), libraryId, StreamKey(row.serviceId, row.url))
            }
            if (intents.isNotEmpty()) repository.store.stageAiRemovals(intents)
        }
    }
}
