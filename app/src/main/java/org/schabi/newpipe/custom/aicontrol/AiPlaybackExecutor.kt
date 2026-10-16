/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object AiProcessIdentity {
    val playbackEpoch: String = UUID.randomUUID().toString()
}

internal fun interface AiPlaybackPort {
    fun execute(command: AiCatalogCommand, checkActive: () -> Unit): JsonObject
}

/**
 * One executor instance per app process. Native player effects cannot join a Room transaction.
 * Epoch fencing forbids replay after process death; bounded in-process receipts suppress repeats.
 * Expiry forbids new work, but absence of an old receipt never proves an earlier action did not run.
 */
internal class AiPlaybackExecutor(
    private val access: AiAccessController,
    private val player: AiPlaybackPort,
    private val playbackEpoch: String = AiProcessIdentity.playbackEpoch,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class RequestKey(val device: String, val epoch: String, val client: String, val request: String)
    private data class RecentResult(val fingerprint: String, val result: AiResult, val retainUntil: Long)
    private val mutex = Any()
    private val recent = LinkedHashMap<RequestKey, RecentResult>()

    fun execute(rawJson: String, session: AiAccessController.Session): AiResult {
        var request: AiRequest? = null
        return try {
            val parsed = AiProtocol.decodeEnvelope(rawJson)
            request = parsed
            AiProtocol.validateShape(parsed)
            if (!AiProtocol.isPlayback(parsed.command)) throw AiFailure("INVALID_DISPATCH", AiOutcome.NOT_EXECUTED)
            access.withAuthorization(parsed, session) { lease ->
                synchronized(mutex) {
                    lease.checkActive()
                    val now = clock()
                    AiProtocol.checkIssueTime(parsed, now)
                    val mutation = AiProtocol.isMutation(parsed.command)
                    if (mutation && parsed.playbackEpoch != playbackEpoch) {
                        throw AiFailure("STALE_PLAYBACK_SESSION", AiOutcome.OUTCOME_UNKNOWN)
                    }
                    val key = RequestKey(parsed.deviceId, parsed.catalogEpoch, parsed.clientId, parsed.requestId)
                    val fingerprint = AiProtocol.fingerprint(parsed)
                    clearOld(now)
                    if (mutation) {
                        val old = recent[key]
                        if (old != null) {
                            if (old.fingerprint != fingerprint) throw AiFailure("REQUEST_ID_REUSED")
                            return@synchronized old.result.copy(replayed = true)
                        }
                        if (parsed.expiresAtMs <= now && now - parsed.issuedAtMs >= RETAIN_MS) {
                            throw AiFailure("RESULT_NOT_RETAINED", AiOutcome.OUTCOME_UNKNOWN)
                        }
                    }
                    AiProtocol.checkDeadline(parsed, now)
                    if (mutation) {
                        if (recent.size >= MAX_RECENT) throw AiFailure("PLAYBACK_BUSY", AiOutcome.NOT_EXECUTED)
                        recent[key] = RecentResult(
                            fingerprint,
                            AiResult(parsed.requestId, parsed.deviceId, AiOutcome.OUTCOME_UNKNOWN, "OUTCOME_UNKNOWN"),
                            now + RETAIN_MS
                        )
                    }
                    val result = try {
                        val data = player.execute(parsed.command) {
                            lease.checkActive()
                            AiProtocol.checkDeadline(parsed, clock())
                        }
                        AiResult(
                            parsed.requestId,
                            parsed.deviceId,
                            AiOutcome.COMPLETED,
                            data = buildJsonObject {
                                data.forEach { (k, v) -> put(k, v) }
                                put("playback_epoch", playbackEpoch)
                            }
                        )
                    } catch (failure: AiFailure) {
                        AiResult(parsed.requestId, parsed.deviceId, failure.outcome, failure.code)
                    } catch (_: Exception) {
                        AiResult(parsed.requestId, parsed.deviceId, AiOutcome.OUTCOME_UNKNOWN, "PLAYBACK_FAILURE")
                    }
                    if (mutation) recent[key] = RecentResult(fingerprint, result, now + RETAIN_MS)
                    result
                }
            }
        } catch (failure: AiFailure) {
            AiResult(request?.requestId.orEmpty(), request?.deviceId.orEmpty(), failure.outcome, failure.code)
        } catch (_: Exception) {
            AiResult(request?.requestId.orEmpty(), request?.deviceId.orEmpty(), AiOutcome.OUTCOME_UNKNOWN, "PLAYBACK_FAILURE")
        }
    }

    private fun clearOld(now: Long) {
        val iter = recent.values.iterator()
        while (iter.hasNext()) if (iter.next().retainUntil <= now) iter.remove()
    }

    companion object {
        private const val MAX_RECENT = 512
        private const val RETAIN_MS = 180_000L
    }
}

/** Used in addition to the opaque queue revision, never as an identity substitute. */
internal object AiSkipGuard {
    fun permits(
        expectedQueueIndex: Int,
        expectedService: String,
        expectedUrl: String,
        currentQueueIndex: Int,
        currentService: String,
        currentUrl: String,
        queueSize: Int
    ): Boolean = queueSize > 1 && expectedQueueIndex == currentQueueIndex &&
        expectedService == currentService && expectedUrl == currentUrl
}
