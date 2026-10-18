/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.util.concurrent.Callable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.database.AppDatabase

/**
 * IO executor. Lock order is access lease -> TagRuntime -> Library -> Room.
 * Room mutations retain atomic Room receipts. Tag-only mutations have atomic tag-store receipts.
 * No independent tag transaction is ever committed from inside a Room transaction.
 */
internal class AiCatalogExecutor(
    private val db: AppDatabase,
    private val access: AiAccessController,
    private val store: AiCatalogStore = AiCatalogStore(db),
    private val urlResolver: AiUrlResolver? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(rawJson: String, session: AiAccessController.Session): AiResult {
        var request: AiRequest? = null
        return try {
            val parsed = AiProtocol.decodeEnvelope(rawJson)
            request = parsed
            AiProtocol.validateShape(parsed)
            if (AiProtocol.isPlayback(parsed.command)) throw AiFailure("INVALID_DISPATCH", AiOutcome.NOT_EXECUTED)
            val mutation = AiProtocol.isMutation(parsed.command)
            checkAdmissionTime(parsed, mutation)
            val urls = when (val command = parsed.command) {
                is AiCatalogCommand.ResolveUrl -> listOf(command.url)
                is AiCatalogCommand.SaveUrls -> command.urls
                else -> emptyList()
            }
            // Existing committed URL saves are acknowledged before extractor/network work.
            val cached = if (urls.isNotEmpty()) {
                access.withAuthorization(parsed, session) { lease ->
                    TagRuntime.mutate(db, Callable { existing(parsed, lease, mutation).also { if (it == null) checkNewExecution(parsed, mutation) } })
                }
            } else {
                null
            }
            if (cached != null) return cached
            val prepared = if (urls.isEmpty()) {
                emptyList()
            } else {
                val resolver = urlResolver ?: throw AiFailure("RESOLVER_UNAVAILABLE", AiOutcome.NOT_EXECUTED)
                urls.map { url ->
                    val check = {
                        access.withAuthorization(parsed, session) { lease ->
                            lease.checkActive()
                            AiProtocol.checkDeadline(parsed, clock())
                        }
                    }
                    check()
                    resolver.resolve(url, check).also { check() }
                }
            }
            access.withAuthorization(parsed, session) { lease ->
                TagRuntime.mutate(
                    db,
                    Callable {
                        lease.checkActive()
                        checkAdmissionTime(parsed, mutation)
                        val command = parsed.command
                        if (command is AiCatalogCommand.Receipt) return@Callable resultLookup(parsed, command, lease)
                        existing(parsed, lease, mutation)?.let { return@Callable it }
                        checkNewExecution(parsed, mutation)
                        val tags = TagRuntime.lookup(db)
                        if (command is AiTagCommand) {
                            if (tags == null) throw AiFailure("TAGGING_UNAVAILABLE", AiOutcome.NOT_EXECUTED)
                            return@Callable AiTagExecutor(tags, clock).execute(parsed, lease)
                        }
                        val needsUnavailable = command is AiCatalogCommand.MarkUnavailable ||
                            command is AiCatalogCommand.MarkAvailable ||
                            command is AiCatalogCommand.ClearUnavailable ||
                            command is AiCatalogCommand.RemoveAllUnavailable
                        if (tags != null && (needsUnavailable || command is AiCatalogCommand.ListPlaylists)) {
                            try {
                                org.schabi.newpipe.custom.library.UnavailableRepository.ensure(db, tags.binding)
                            } catch (error: IllegalStateException) {
                                // A collision must never auto-adopt another playlist or
                                // block reading the rest of the user's catalog.
                                if (needsUnavailable) throw AiFailure("UNAVAILABLE_NOT_READY", AiOutcome.NOT_EXECUTED)
                            }
                        }
                        // Stage outside Room. A rollback must leave no committed receipt; recovery
                        // discards that intent rather than executing a rejected remote deletion later.
                        tags?.let { AiTagExecutor.stageRemoval(it, parsed) }
                        val result = db.runInTransaction(
                            Callable {
                                lease.checkActive()
                                checkAdmissionTime(parsed, mutation)
                                existing(parsed, lease, mutation, roomOnly = true)?.let { return@Callable it }
                                checkNewExecution(parsed, mutation)
                                val dao = db.aiCommandReceiptDao()
                                if (mutation) {
                                    // Recovery completed before this point, so no unresolved tag cleanup
                                    // can lose its authoritative Room receipt to pruning.
                                    dao.deleteExpired(clock())
                                    if (dao.count() >= MAX_RECEIPTS) throw AiFailure("RECEIPT_CAPACITY", AiOutcome.NOT_EXECUTED)
                                }
                                val data = store.run(command, prepared)
                                val committed = AiResult(parsed.requestId, parsed.deviceId, if (mutation) AiOutcome.COMMITTED else AiOutcome.COMPLETED, data = data)
                                val encoded = AiProtocol.json.encodeToString(committed)
                                if (encoded.toByteArray(Charsets.UTF_8).size > AiProtocol.MAX_RESULT_BYTES) throw AiFailure("RESULT_TOO_LARGE")
                                if (mutation) {
                                    if (encoded.toByteArray(Charsets.UTF_8).size > MAX_RECEIPT_BYTES) throw AiFailure("RESULT_TOO_LARGE")
                                    dao.insert(AiCommandReceipt(parsed.deviceId, parsed.catalogEpoch, parsed.clientId, parsed.requestId, AiProtocol.fingerprint(parsed), AiProtocol.requiredScopes(command).map { it.wireName }.sorted().joinToString(","), encoded, clock() + AiProtocol.RECEIPT_TTL_MS))
                                }
                                lease.checkActive()
                                AiProtocol.checkDeadline(parsed, clock())
                                committed
                            }
                        )
                        // Only now is the Room commit known. Failure here is OUTCOME_UNKNOWN;
                        // the durable intent finishes cleanup before a subsequent read or write.
                        tags?.recoverPending()
                        if (mutation) TagRuntime.changed()
                        result
                    }
                )
            }
        } catch (error: AiFailure) {
            AiResult(request?.requestId.orEmpty(), request?.deviceId.orEmpty(), error.outcome, error.code)
        } catch (_: Exception) {
            AiResult(request?.requestId.orEmpty(), request?.deviceId.orEmpty(), AiOutcome.OUTCOME_UNKNOWN, "STORAGE_OR_EXECUTION_FAILURE")
        }
    }

    private fun existing(request: AiRequest, lease: AiAccessController.Lease, mutation: Boolean, roomOnly: Boolean = false): AiResult? {
        if (!mutation) return null
        val previous = if (roomOnly) {
            db.aiCommandReceiptDao().find(request.deviceId, request.catalogEpoch, request.clientId, request.requestId)
        } else {
            AiTagExecutor.receipt(db, request, request.requestId)
        }
        if (previous == null) return null
        if (previous.fingerprint != AiProtocol.fingerprint(request)) throw AiFailure("REQUEST_ID_REUSED")
        lease.requireScopes(AiTagExecutor.parseScopes(previous.requiredScopes))
        lease.checkActive()
        return AiTagExecutor.decode(previous).copy(replayed = true)
    }

    private fun resultLookup(request: AiRequest, command: AiCatalogCommand.Receipt, lease: AiAccessController.Lease): AiResult {
        val previous = AiTagExecutor.receipt(db, request, command.originalRequestId) ?: throw AiFailure("RESULT_NOT_RETAINED", AiOutcome.OUTCOME_UNKNOWN)
        if (previous.retainedUntilMs <= clock()) throw AiFailure("RESULT_NOT_RETAINED", AiOutcome.OUTCOME_UNKNOWN)
        lease.requireScopes(AiTagExecutor.parseScopes(previous.requiredScopes))
        val result = AiTagExecutor.decode(previous)
        lease.checkActive()
        AiProtocol.checkDeadline(request, clock())
        return AiResult(
            request.requestId,
            request.deviceId,
            AiOutcome.COMPLETED,
            data = buildJsonObject {
                put("original_request_id", previous.requestId)
                put("original_outcome", result.outcome.name)
                put("result", result.data)
            }
        )
    }

    private fun checkAdmissionTime(request: AiRequest, mutation: Boolean) {
        if (mutation) AiProtocol.checkIssueTime(request, clock()) else AiProtocol.checkDeadline(request, clock())
    }

    private fun checkNewExecution(request: AiRequest, mutation: Boolean) {
        val now = clock()
        if (mutation && request.expiresAtMs <= now && now - request.issuedAtMs >= AiProtocol.RECEIPT_TTL_MS - AiProtocol.CLOCK_SKEW_MS) {
            throw AiFailure("EXPIRED_RESULT_NOT_RETAINED", AiOutcome.OUTCOME_UNKNOWN)
        }
        AiProtocol.checkDeadline(request, now)
    }

    companion object {
        const val MAX_RECEIPTS = 10_000
        const val MAX_RECEIPT_BYTES = 8192
    }
}
