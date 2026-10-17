/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.exoplayer2.Player.STATE_ENDED
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.player.Player

/**
 * Existing-player commands use a main-thread, weak lifecycle reference. They never bind/start a
 * service or replace PlayerHolder's listener. A single pending operation can be cancelled before
 * execution, including interruption; a started effect can only be reported as uncertain on timeout.
 */
internal class AiAndroidPlaybackPort(context: Context, private val media: AiSavedMediaLookup = AiSavedMediaReader(context)) : AiPlaybackPort {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun execute(command: AiCatalogCommand, checkActive: () -> Unit): JsonObject {
        if (Looper.myLooper() == Looper.getMainLooper()) throw AiFailure("WRONG_THREAD", AiOutcome.NOT_EXECUTED)
        checkActive()
        val items = when (command) {
            is AiCatalogCommand.PlayItems -> media.read(command.items, checkActive)
            is AiCatalogCommand.AppendQueue -> media.read(command.items, checkActive)
            else -> emptyList()
        }
        checkActive()
        if (command is AiCatalogCommand.PlayItems) return AiPlayerStartBridge.play(app, items, checkActive)
        val call = AiMainThreadCall<JsonObject>()
        val task = Runnable {
            call.run {
                val checked = {
                    call.checkActive()
                    checkActive()
                }
                checked()
                runOnMain(command, AiPlayerRegistry.player(), items, checked)
            }
        }
        if (!main.post(task)) throw AiFailure("PLAYER_UNAVAILABLE", AiOutcome.NOT_EXECUTED)
        try {
            if (call.await(MAIN_TIMEOUT_MS)) return call.result()
            val phase = call.cancel()
            if (phase == AiMainThreadCall.Phase.FINISHED) return call.result()
            throw AiFailure("PLAYER_TIMEOUT", if (phase == AiMainThreadCall.Phase.PENDING) AiOutcome.NOT_EXECUTED else AiOutcome.OUTCOME_UNKNOWN)
        } catch (_: InterruptedException) {
            val phase = call.cancel()
            Thread.currentThread().interrupt()
            throw AiFailure("INTERRUPTED", if (phase == AiMainThreadCall.Phase.PENDING) AiOutcome.NOT_EXECUTED else AiOutcome.OUTCOME_UNKNOWN)
        } finally {
            call.cancel()
            main.removeCallbacks(task)
        }
    }

    private fun runOnMain(command: AiCatalogCommand, player: Player?, items: List<org.schabi.newpipe.extractor.stream.StreamInfoItem>, checkActive: () -> Unit): JsonObject {
        checkActive()
        val queue = player?.playQueue
        if (command == AiCatalogCommand.NowPlaying && (player == null || queue == null)) {
            return buildJsonObject { put("active", false) }
        }
        if (player == null || player.exoPlayerIsNull() || queue == null) {
            throw AiFailure(if (command is AiCatalogCommand.AppendQueue) "NO_ACTIVE_QUEUE" else "NO_ACTIVE_PLAYER", AiOutcome.NOT_EXECUTED)
        }
        return synchronized(queue) {
            checkActive()
            val selected = queue.item
            val current = player.currentItem
            val consistent = selected != null && current === selected
            when (command) {
                AiCatalogCommand.NowPlaying -> buildJsonObject {
                    put("active", current != null)
                    put("transitioning", !consistent)
                    put("playing", player.isPlaying)
                    put("play_when_ready", player.playWhenReady)
                    put("stopped", player.isStopped)
                    put("queue_index", queue.index)
                    put("queue_size", queue.size())
                    put("queue_revision", queue.stateRevisionToken)
                    if (current != null) {
                        put("service", serviceName(current.serviceId))
                        put("url", current.url)
                        put("title", current.title.take(512))
                    }
                }

                is AiCatalogCommand.AppendQueue -> {
                    if (queue.isEmpty || queue.broadcastReceiver == null) throw AiFailure("NO_ACTIVE_QUEUE", AiOutcome.NOT_EXECUTED)
                    if (queue.size() + items.size > 10_000) throw AiFailure("QUEUE_TOO_LARGE", AiOutcome.NOT_EXECUTED)
                    checkActive()
                    val added = org.schabi.newpipe.player.playqueue.SinglePlayQueue(items, 0).streams
                    queue.appendInOrder(added)
                    buildJsonObject {
                        put("appended_items", added.size)
                        put("queue_size", queue.size())
                        put("queue_revision", queue.stateRevisionToken)
                    }
                }

                AiCatalogCommand.Pause -> {
                    checkActive()
                    if (player.playWhenReady) player.pause()
                    if (player.playWhenReady) throw AiFailure("PLAYER_DID_NOT_PAUSE", AiOutcome.OUTCOME_UNKNOWN)
                    buildJsonObject {
                        put("paused", true)
                        put("play_when_ready", player.playWhenReady)
                    }
                }

                AiCatalogCommand.Resume -> {
                    if (!consistent) throw AiFailure("PLAYER_TRANSITIONING", AiOutcome.NOT_EXECUTED)
                    if (player.isStopped || player.exoPlayer.playbackState == STATE_ENDED) throw AiFailure("NO_ACTIVE_PLAYBACK", AiOutcome.NOT_EXECUTED)
                    checkActive()
                    if (!player.playWhenReady) player.play()
                    if (!player.playWhenReady) throw AiFailure("PLAYER_DID_NOT_RESUME", AiOutcome.OUTCOME_UNKNOWN)
                    buildJsonObject { put("play_when_ready", player.playWhenReady) }
                }

                is AiCatalogCommand.SkipIfCurrent -> {
                    if (!consistent) throw AiFailure("PLAYER_TRANSITIONING", AiOutcome.NOT_EXECUTED)
                    if (command.expectedQueueRevision != queue.stateRevisionToken ||
                        !AiSkipGuard.permits(
                            command.expectedQueueIndex,
                            command.service,
                            command.url,
                            queue.index,
                            serviceName(selected!!.serviceId),
                            selected.url,
                            queue.size()
                        )
                    ) {
                        throw AiFailure("PLAYER_STATE_CHANGED", AiOutcome.NOT_EXECUTED)
                    }
                    // At the loaded tail of an incomplete queue there is no known next selection.
                    if (queue.index + 1 >= queue.size() && !queue.isComplete) throw AiFailure("NO_NEXT_TRACK", AiOutcome.NOT_EXECUTED)
                    val oldIndex = queue.index
                    checkActive()
                    player.playNext()
                    if (queue.index == oldIndex) throw AiFailure("NO_NEXT_TRACK", AiOutcome.NOT_EXECUTED)
                    buildJsonObject {
                        put("skipped", true)
                        put("queue_index", queue.index)
                        put("queue_revision", queue.stateRevisionToken)
                    }
                }

                else -> throw AiFailure("INVALID_DISPATCH", AiOutcome.NOT_EXECUTED)
            }
        }
    }

    private fun serviceName(id: Int): String = try {
        NewPipe.getService(id).serviceInfo.name
    } catch (_: Exception) {
        throw AiFailure("UNSUPPORTED_SERVICE")
    }

    companion object {
        private const val MAIN_TIMEOUT_MS = 2_000L
    }
}
