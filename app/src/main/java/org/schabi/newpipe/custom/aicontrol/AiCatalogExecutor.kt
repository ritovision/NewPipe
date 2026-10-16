/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.util.concurrent.Callable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.schabi.newpipe.database.AppDatabase

/**
 * Synchronous IO-thread executor. There is intentionally no exported Android entry point and no
 * networking in this milestone. A transport must supply a separately authenticated live session.
 */
internal class AiCatalogExecutor(
    private val db: AppDatabase,
    private val access: AiAccessController,
    private val store: AiCatalogStore = AiCatalogStore(db),
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(rawJson: String, session: AiAccessController.Session): AiResult {
        var request: AiRequest? = null
        return try {
            val parsed = AiProtocol.decodeEnvelope(rawJson)
            request = parsed
            AiProtocol.validateShape(parsed)
            AiProtocol.checkDeadline(parsed, clock())
            access.withAuthorization(parsed, session) { lease ->
                db.runInTransaction(
                    Callable {
                        // Room can wait for other writers. Never rely on a check made before that wait.
                        lease.checkActive()
                        AiProtocol.checkDeadline(parsed, clock())
                        val dao = db.aiCommandReceiptDao()
                        val command = parsed.command
                        if (command is AiCatalogCommand.Receipt) {
                            val original = dao.find(parsed.deviceId, parsed.catalogEpoch, parsed.clientId, command.originalRequestId)
                                ?: throw AiFailure("RESULT_NOT_RETAINED", AiOutcome.OUTCOME_UNKNOWN)
                            if (original.retainedUntilMs <= clock()) throw AiFailure("RESULT_NOT_RETAINED", AiOutcome.OUTCOME_UNKNOWN)
                            lease.requireScopes(scopes(original.requiredScopes))
                            val result = decodeReceipt(original)
                            lease.checkActive()
                            AiProtocol.checkDeadline(parsed, clock())
                            // Correlate the reply to THIS lookup, not the older mutation request.
                            return@Callable AiResult(
                                parsed.requestId,
                                parsed.deviceId,
                                AiOutcome.COMPLETED,
                                data = buildJsonObject {
                                    put("original_request_id", original.requestId)
                                    put("original_outcome", result.outcome.name)
                                    put("result", result.data)
                                }
                            )
                        }

                        val mutation = AiProtocol.isMutation(command)
                        val fingerprint = AiProtocol.fingerprint(parsed)
                        if (mutation) {
                            val previous = dao.find(parsed.deviceId, parsed.catalogEpoch, parsed.clientId, parsed.requestId)
                            if (previous != null) {
                                if (previous.fingerprint != fingerprint) throw AiFailure("REQUEST_ID_REUSED")
                                lease.requireScopes(scopes(previous.requiredScopes))
                                lease.checkActive()
                                AiProtocol.checkDeadline(parsed, clock())
                                return@Callable decodeReceipt(previous).copy(replayed = true)
                            }
                            dao.deleteExpired(clock())
                            if (dao.count() >= MAX_RECEIPTS) throw AiFailure("RECEIPT_CAPACITY", AiOutcome.NOT_EXECUTED)
                        }

                        val data = store.run(command)
                        val result = AiResult(parsed.requestId, parsed.deviceId, if (mutation) AiOutcome.COMMITTED else AiOutcome.COMPLETED, data = data)
                        val serialized = AiProtocol.json.encodeToString(result)
                        if (serialized.toByteArray(Charsets.UTF_8).size > AiProtocol.MAX_RESULT_BYTES) throw AiFailure("RESULT_TOO_LARGE")
                        if (mutation) {
                            // Same SQLite/Room transaction as the business mutation. On failure BOTH roll back.
                            if (serialized.length > MAX_RECEIPT_BYTES) throw AiFailure("RESULT_TOO_LARGE")
                            dao.insert(
                                AiCommandReceipt(
                                    parsed.deviceId,
                                    parsed.catalogEpoch,
                                    parsed.clientId,
                                    parsed.requestId,
                                    fingerprint,
                                    AiProtocol.requiredScopes(command).map { it.wireName }.sorted().joinToString(","),
                                    serialized,
                                    clock() + AiProtocol.RECEIPT_TTL_MS
                                )
                            )
                        }
                        lease.checkActive()
                        AiProtocol.checkDeadline(parsed, clock())
                        result
                    }
                )
            }
        } catch (error: AiFailure) {
            AiResult(request?.requestId.orEmpty(), request?.deviceId.orEmpty(), error.outcome, error.code)
        } catch (error: Exception) {
            // A failure reported while committing can be ambiguous. Do not assert NOT_EXECUTED.
            // No raw exception message, request payload or database contents cross the bridge.
            AiResult(request?.requestId.orEmpty(), request?.deviceId.orEmpty(), AiOutcome.OUTCOME_UNKNOWN, "STORAGE_OR_EXECUTION_FAILURE")
        }
    }

    private fun decodeReceipt(receipt: AiCommandReceipt): AiResult {
        if (receipt.resultJson.length > MAX_RECEIPT_BYTES) throw AiFailure("INVALID_RECEIPT", AiOutcome.OUTCOME_UNKNOWN)
        val result = AiProtocol.json.decodeFromString<AiResult>(receipt.resultJson)
        if (result.requestId != receipt.requestId || result.deviceId != receipt.deviceId || result.outcome != AiOutcome.COMMITTED) {
            throw AiFailure("INVALID_RECEIPT", AiOutcome.OUTCOME_UNKNOWN)
        }
        return result
    }

    private fun scopes(names: String): Set<AiScope> = names.split(',').map { name ->
        AiScope.entries.singleOrNull { it.wireName == name } ?: throw AiFailure("INVALID_RECEIPT", AiOutcome.OUTCOME_UNKNOWN)
    }.toSet()

    companion object {
        const val MAX_RECEIPTS = 10_000
        const val MAX_RECEIPT_BYTES = 8192
    }
}
