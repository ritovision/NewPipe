/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.PlayerType
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper

/**
 * Android can start a service asynchronously. Only a one-shot, in-process ticket reaches the
 * existing PlayerService; no network JSON, credentials or caller-controlled queue is accepted there.
 * The actual onStartCommand rechecks the live lease/deadline before applying the prepared queue.
 */
object AiPlayerStartBridge {
    const val EXTRA_TICKET = "org.schabi.newpipe.custom.aicontrol.PLAY_TICKET"
    private class Pending(val call: AiMainThreadCall<JsonObject>, val queue: SinglePlayQueue, val check: () -> Unit)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val main = Handler(Looper.getMainLooper())

    internal fun play(context: Context, items: List<StreamInfoItem>, checkActive: () -> Unit): JsonObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        checkActive()
        val call = AiMainThreadCall<JsonObject>()
        val ticket = UUID.randomUUID().toString()
        val operation = Pending(call, SinglePlayQueue(items, 0), checkActive)
        pending[ticket] = operation
        val app = context.applicationContext
        val start = Runnable {
            if (pending[ticket] !== operation) return@Runnable
            try {
                call.checkActive()
                checkActive()
                val service = AiPlayerRegistry.serviceInstance()
                if (service?.player != null) {
                    applyTicket(service, Intent().putExtra(EXTRA_TICKET, ticket), 0, false)
                } else {
                    ContextCompat.startForegroundService(app, Intent(app, PlayerService::class.java).putExtra(EXTRA_TICKET, ticket))
                }
            } catch (_: Exception) {
                pending.remove(ticket, operation)
                call.run { throw AiFailure("BACKGROUND_START_NOT_ALLOWED", AiOutcome.NOT_EXECUTED) }
            }
        }
        if (!main.post(start)) {
            pending.remove(ticket)
            throw AiFailure("PLAYER_UNAVAILABLE", AiOutcome.NOT_EXECUTED)
        }
        try {
            if (call.await(5000L)) return call.result()
            val phase = call.cancel()
            if (phase == AiMainThreadCall.Phase.FINISHED) return call.result()
            throw AiFailure("PLAYER_TIMEOUT", if (phase == AiMainThreadCall.Phase.PENDING) AiOutcome.NOT_EXECUTED else AiOutcome.OUTCOME_UNKNOWN)
        } catch (_: InterruptedException) {
            val phase = call.cancel()
            Thread.currentThread().interrupt()
            throw AiFailure("INTERRUPTED", if (phase == AiMainThreadCall.Phase.PENDING) AiOutcome.NOT_EXECUTED else AiOutcome.OUTCOME_UNKNOWN)
        } finally {
            call.cancel()
            pending.remove(ticket, operation)
            main.removeCallbacks(start)
        }
    }

    @JvmStatic
    fun onStartCommand(service: PlayerService, incoming: Intent, startId: Int): Int = applyTicket(service, incoming, startId, true)

    private fun applyTicket(service: PlayerService, incoming: Intent, startId: Int, androidStart: Boolean): Int {
        check(Looper.myLooper() == Looper.getMainLooper())
        val ticket = incoming.getStringExtra(EXTRA_TICKET)
        val operation = ticket?.let(pending::remove)
        if (operation != null) {
            operation.call.run {
                operation.call.checkActive()
                operation.check()
                val intent = NavigationHelper.getPlayerIntent(service, PlayerService::class.java, operation.queue, org.schabi.newpipe.player.PlayerIntentType.AllOthers)
                    .putExtra(Player.PLAYER_TYPE, PlayerType.AUDIO)
                    .putExtra(Player.RESUME_PLAYBACK, false)
                operation.call.checkActive()
                operation.check()
                // A merely bound service must first receive a genuine Android service start.
                val previouslyHadPlayer = service.player != null
                try {
                    service.onStartCommand(intent, 0, startId)
                } catch (_: Exception) {
                    if (!previouslyHadPlayer && service.player != null) service.destroyPlayerAndStopService()
                    throw AiFailure("PLAYER_START_FAILED", AiOutcome.OUTCOME_UNKNOWN)
                }
                if (service.player?.playQueue !== operation.queue) throw AiFailure("PLAYER_DID_NOT_ACCEPT_QUEUE", AiOutcome.OUTCOME_UNKNOWN)
                buildJsonObject {
                    put("playback_requested", true)
                    put("queue_size", operation.queue.size())
                    put("queue_revision", operation.queue.stateRevisionToken)
                }
            }
        }
        // Honour startForegroundService's deadline even when a ticket was cancelled before arrival.
        // The existing no-player path posts its dummy foreground notification and closes cleanly.
        if (androidStart && service.player == null) service.onStartCommand(Intent(), 0, startId)
        return Service.START_NOT_STICKY
    }
}
