/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository

internal data class AiUiSnapshot(
    val state: AiLocalState? = null,
    val connection: String = "LOADING",
    val error: String? = null,
    val lastOperation: String? = null,
    val lastOutcome: String? = null,
    val lastOperationAtMs: Long = 0
)

/** One process owner. IO/configuration never blocks the UI; every old connection is generation-fenced. */
internal class AiControlRuntime private constructor(context: Context) : AiRelayTransport.Listener {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "NewPipe-AI-control").apply { isDaemon = true } }
    private val network = Executors.newFixedThreadPool(2) { task -> Thread(task, "NewPipe-AI-network").apply { isDaemon = true } }
    private val commands = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(8),
        { task -> Thread(task, "NewPipe-AI-command").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val listeners = CopyOnWriteArraySet<(AiUiSnapshot) -> Unit>()
    private val access = AiAccessController()
    private val keys = AiDeviceKeys()
    private val store by lazy { AiLocalStateStore(app) }
    private val db get() = NewPipeDatabase.getInstance(app)
    private val catalog by lazy { AiCatalogExecutor(db, access, urlResolver = AiNativeUrlResolver()) }
    private val playback by lazy { AiPlaybackExecutor(access, AiAndroidPlaybackPort(app)) }
    private val serviceActive = AtomicBoolean(false)
    private val policy = AiPolicyGate()
    private val restoring = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val pairingCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>()

    @Volatile private var relay: AiRelayTransport? = null

    @Volatile var snapshot = AiUiSnapshot()
        private set
    private var reconnect: ScheduledFuture<*>? = null
    private var reconnectAttempt = 0
    private var rateWindow = 0L
    private var rateCount = 0

    init {
        val version = policy.version
        control.execute {
            try {
                var value = store.read()
                if (value.paired && !validKey(value)) value = store.update { it.copy(enabled = false, paired = false, clients = emptyMap()) }
                policy.commit(version, value.enabled) {
                    publish { it.copy(state = value, connection = if (value.enabled) "STOPPED" else "DISABLED") }
                }
            } catch (_: Exception) {
                policy.invalidate()
                publish { it.copy(connection = "DISABLED", error = "INVALID_LOCAL_STATE") }
            }
        }
    }

    fun observe(listener: (AiUiSnapshot) -> Unit): AutoCloseable {
        listeners.add(listener)
        main.post {
            if (listener in listeners) {
                try {
                    listener(snapshot)
                } catch (_: Exception) { /* A detached view is not an executor failure. */ }
            }
        }
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun publish(transform: (AiUiSnapshot) -> AiUiSnapshot) {
        val value = synchronized(this) { transform(snapshot).also { snapshot = it } }
        main.post {
            listeners.forEach { listener ->
                try {
                    listener(value)
                } catch (_: Exception) { /* Presentation never controls execution. */ }
            }
        }
    }

    private fun validKey(value: AiLocalState): Boolean = keys.exists(value.deviceId) && AiProtocol.digest(keys.publicKey(value.deviceId)) == value.keyFingerprint
    private fun complete(done: (String?) -> Unit, error: String? = null) {
        main.post { done(error) }
    }

    /** Cancellation is immediate; persistence/configuration then runs on one IO executor. */
    private fun policyChange(): Long {
        return policy.invalidate { stopConnection() }
    }

    fun pair(origin: String, name: String, code: String, permissions: Set<String>, done: (String?) -> Unit) {
        if (restoring.get()) {
            complete(done, "APP_RESTART_REQUIRED")
            return
        }
        val version = policyChange()
        control.execute {
            if (version != policy.version) {
                complete(done, "CANCELLED")
                return@execute
            }
            try {
                val old = store.update { it.copy(enabled = false) }
                val proposed = AiLocalStatePolicy.validate(old.copy(gatewayOrigin = AiLocalStatePolicy.origin(origin), deviceName = name.trim(), permissions = permissions))
                publish { it.copy(state = old, connection = "PAIRING", error = null) }
                network.execute {
                    try {
                        if (version != policy.version) {
                            complete(done, "CANCELLED")
                            return@execute
                        }
                        val paired = AiRelayTransport.pair(
                            proposed,
                            code.trim(),
                            keys,
                            check = { if (version != policy.version || restoring.get()) throw AiFailure("CANCELLED", AiOutcome.NOT_EXECUTED) },
                            registered = { pairingCall.set(it) },
                            released = { pairingCall.compareAndSet(it, null) }
                        )
                        control.execute {
                            if (version != policy.version) {
                                complete(done, "CANCELLED")
                                return@execute
                            }
                            try {
                                val value = store.replace(paired)
                                publish { it.copy(state = value, connection = "DISABLED", error = null) }
                                complete(done)
                            } catch (_: Exception) {
                                stateFailure("LOCAL_STATE_WRITE_FAILED", done)
                            }
                        }
                    } catch (error: Exception) {
                        control.execute {
                            if (version == policy.version) {
                                stateFailure((error as? AiFailure)?.code ?: "NETWORK_UNAVAILABLE", done)
                            } else {
                                complete(done, "CANCELLED")
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                stateFailure((error as? AiFailure)?.code ?: "LOCAL_STATE_WRITE_FAILED", done)
            }
        }
    }

    fun configure(name: String, permissions: Set<String>, done: (String?) -> Unit) = change(done) {
        it.copy(deviceName = name.trim(), permissions = permissions)
    }

    fun setEnabled(enable: Boolean, done: (String?) -> Unit = {}) = change(done) {
        if (enable && (!it.paired || !validKey(it))) throw AiFailure("NOT_PAIRED")
        it.copy(enabled = enable)
    }

    fun revokeClient(client: String, done: (String?) -> Unit) = change(done) {
        it.copy(blockedClients = it.blockedClients + client, clients = it.clients - client)
    }

    private fun change(done: (String?) -> Unit, transform: (AiLocalState) -> AiLocalState) {
        if (restoring.get()) {
            complete(done, "APP_RESTART_REQUIRED")
            return
        }
        policy.enqueueChange(::stopConnection) { version ->
            control.execute {
                // An intent admitted before maintenance must persist (especially revocations).
                // The maintenance version prevents enabling/publication; its queued barrier then
                // forces disabled state and rotates epoch before the database is replaced.
                try {
                    access.disable()
                    val value = policy.applyOrdered(version, { store.update { previous -> transform(previous).let { if (restoring.get()) it.copy(enabled = false) else it } } }, { it.enabled && !restoring.get() }) { latest ->
                        publish { it.copy(state = latest, connection = if (latest.enabled) "STARTING" else "DISABLED", error = null) }
                    }
                    if (version == policy.version && value.enabled && serviceActive.get()) connect()
                    if (!value.enabled) main.post { if (!policy.enabled) stopStartedService() }
                    complete(done)
                } catch (error: Exception) {
                    stateFailure((error as? AiFailure)?.code ?: "LOCAL_STATE_WRITE_FAILED", done)
                }
            }
        }
    }

    fun forget(done: (String?) -> Unit) {
        if (restoring.get()) {
            complete(done, "APP_RESTART_REQUIRED")
            return
        }
        policy.enqueueChange(::stopConnection) { version ->
            control.execute {
                if (restoring.get()) {
                    complete(done, "APP_RESTART_REQUIRED")
                    return@execute
                }
                try {
                    access.disable()
                    commands.submit {}.get(15, TimeUnit.SECONDS)
                    keys.deleteOwnedKeys()
                    policy.applyOrdered(version, { store.reset() }, { false }) { latest ->
                        publish { it.copy(state = latest, connection = "DISABLED", error = null) }
                    }
                    main.post {
                        stopStartedService()
                        done(null)
                    }
                } catch (_: Exception) {
                    stateFailure("LOCAL_STATE_WRITE_FAILED", done)
                }
            }
        }
    }

    fun serviceStarted() {
        serviceActive.set(true)
        control.execute {
            if (policy.enabled && snapshot.state?.enabled == true) {
                connect()
            } else {
                main.post { if (!policy.enabled) stopStartedService() }
            }
        }
    }

    fun serviceStartFailed() {
        setEnabled(false) { publish { it.copy(error = "BACKGROUND_START_NOT_ALLOWED") } }
    }

    fun serviceStopped() {
        serviceActive.set(false)
        stopConnection()
        publish { it.copy(connection = if (policy.enabled) "STOPPED" else "DISABLED") }
    }

    private fun stopStartedService() {
        // If a foreground start is pending, serviceStarted() stops only after notification setup.
        if (serviceActive.get() && !policy.enabled) app.stopService(Intent(app, AiControlService::class.java))
    }

    private fun stopConnection() {
        generation.incrementAndGet()
        AiExtractionNetwork.cancelActive()
        pairingCall.getAndSet(null)?.cancel()
        access.signalStop()
        relay?.close()
        relay = null
        commands.queue.clear()
        control.execute {
            reconnect?.cancel(false)
            reconnect = null
        }
    }

    private fun connect() {
        if (!policy.enabled || !serviceActive.get()) return
        reconnect?.cancel(false)
        reconnect = null
        val value = snapshot.state ?: return
        if (!value.enabled || !value.paired) return
        if (relay?.isReady == true) return
        relay?.close()
        access.signalStop()
        val next = AiRelayTransport(value, keys, this)
        relay = next
        val token = generation.get()
        publish { it.copy(connection = "CONNECTING", error = null) }
        network.execute { if (current(next, token)) next.connect() }
        control.schedule({ if (current(next, token) && !next.isReady) failed(next, "HANDSHAKE_TIMEOUT", false) }, 25, TimeUnit.SECONDS)
    }

    private fun current(value: AiRelayTransport, token: Long = generation.get()): Boolean {
        val state = snapshot.state ?: return false
        val expected = value.identity
        return !restoring.get() && relay === value && token == generation.get() && policy.enabled && serviceActive.get() &&
            state.enabled && state.paired && state.deviceId == expected.deviceId && state.catalogEpoch == expected.catalogEpoch &&
            state.gatewayOrigin == expected.gatewayOrigin && state.ownerId == expected.ownerId &&
            state.keyFingerprint == expected.keyFingerprint && state.permissions == expected.permissions
    }

    override fun accepted(transport: AiRelayTransport) {
        val token = generation.get()
        if (!current(transport, token)) {
            transport.close()
            return
        }
        try {
            commands.execute {
                try {
                    if (!current(transport, token)) return@execute
                    LibraryRepository.initialize(db, LibraryBindingStore(app))
                    control.execute {
                        if (current(transport, token)) {
                            access.enableGatewayFromLocalConsent(
                                transport.identity.deviceId,
                                transport.identity.catalogEpoch,
                                AiScope.entries.filter { it.wireName in transport.identity.permissions }.toSet()
                            )
                            if (current(transport, token)) transport.hello(CAPABILITIES) else access.signalStop()
                        }
                    }
                } catch (_: Exception) {
                    failed(transport, "LIBRARY_NOT_READY", true)
                }
            }
        } catch (_: RejectedExecutionException) {
            failed(transport, "DEVICE_BUSY", false)
        }
    }

    override fun ready(transport: AiRelayTransport) {
        control.execute {
            if (current(transport)) {
                reconnectAttempt = 0
                publish { it.copy(connection = "CONNECTED", error = null) }
            }
        }
    }

    override fun failed(transport: AiRelayTransport, code: String, permanent: Boolean) {
        // Closing only this transport immediately invalidates its leases, not a replacement.
        transport.close()
        control.execute {
            if (relay !== transport) return@execute
            access.signalStop()
            relay = null
            commands.queue.clear()
            publish { it.copy(connection = "DISCONNECTED", error = code) }
            if (permanent) {
                stateFailure(code) {}
            } else if (policy.enabled && serviceActive.get()) {
                val delay = (1L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30) * 1000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(500)
                reconnectAttempt++
                reconnect = control.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun command(transport: AiRelayTransport, command: AiIncomingCommand) {
        val token = generation.get()
        if (!current(transport, token) || !transport.isReady) return
        fun refuse(code: String) = transport.reply(command, AiResult(command.requestId, transport.identity.deviceId, AiOutcome.NOT_EXECUTED, code))
        if (!admitRate()) {
            refuse("RATE_LIMITED")
            return
        }
        // Admission and first-client persistence are serialized on the control lane. A client
        // cannot execute unless it is visible and revocable in the durable local policy store.
        control.execute {
            if (!current(transport, token)) return@execute
            val state = try {
                val old = store.read()
                if (command.clientId in old.blockedClients) {
                    refuse("CLIENT_REVOKED")
                    return@execute
                }
                if (command.clientId in old.clients) {
                    old
                } else {
                    if (old.clients.size >= 16) {
                        refuse("CLIENT_LIMIT")
                        return@execute
                    }
                    store.update { it.copy(clients = it.clients + (command.clientId to command.clientName)) }.also { saved ->
                        publish { it.copy(state = saved) }
                    }
                }
            } catch (_: Exception) {
                refuse("LOCAL_STATE_WRITE_FAILED")
                return@execute
            }
            if (!current(transport, token)) return@execute
            val session = try {
                access.openAuthenticatedGatewaySession(command.clientId, command.scopes) { current(transport, token) && transport.isReady }
            } catch (error: AiFailure) {
                refuse(error.code)
                return@execute
            }
            try {
                commands.execute {
                    if (!current(transport, token)) {
                        session.disconnect()
                        return@execute
                    }
                    val result = try {
                        val request = AiProtocol.decodeEnvelope(command.rawRequest)
                        if (AiProtocol.isPlayback(request.command)) playback.execute(command.rawRequest, session) else catalog.execute(command.rawRequest, session)
                    } catch (error: AiFailure) {
                        AiResult(command.requestId, state.deviceId, error.outcome, error.code)
                    } catch (_: Exception) {
                        AiResult(command.requestId, state.deviceId, AiOutcome.OUTCOME_UNKNOWN, "EXECUTION_FAILURE")
                    } finally {
                        session.disconnect()
                    }
                    if (current(transport, token)) transport.reply(command, result)
                    val operation = try {
                        val obj = AiProtocol.json.parseToJsonElement(command.rawRequest) as kotlinx.serialization.json.JsonObject
                        ((obj["command"] as kotlinx.serialization.json.JsonObject)["type"] as kotlinx.serialization.json.JsonPrimitive).content
                    } catch (_: Exception) {
                        "invalid_request"
                    }
                    publish { it.copy(lastOperation = operation.take(64), lastOutcome = "${result.outcome}: ${result.code}", lastOperationAtMs = System.currentTimeMillis()) }
                }
            } catch (_: RejectedExecutionException) {
                session.disconnect()
                refuse("DEVICE_BUSY")
            }
        }
    }

    @Synchronized
    private fun admitRate(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - rateWindow >= 60_000) {
            rateWindow = now
            rateCount = 0
        }
        if (rateCount >= 120) return false
        rateCount++
        return true
    }

    /** Executed on control IO. If disabled state cannot persist, destroy our key rather than resume later. */
    private fun stateFailure(code: String, done: (String?) -> Unit) {
        policyChange()
        access.disable()
        try {
            val value = store.update { it.copy(enabled = false) }
            publish { it.copy(state = value, connection = "DISABLED", error = code) }
        } catch (_: Exception) {
            try {
                keys.deleteOwnedKeys()
            } catch (_: Exception) { /* This process remains disabled. */ }
            publish { it.copy(connection = "DISABLED", error = code) }
        }
        main.post {
            if (!policy.enabled) stopStartedService()
            done(code)
        }
    }

    /** Never call on main or inside Room. Import only begins after this barrier completes. */
    fun beforeRestore(timeoutSeconds: Long = 20) {
        check(Looper.myLooper() != Looper.getMainLooper())
        restoring.set(true)
        val version = policyChange()
        control.submit {
            if (version != policy.version) throw AiFailure("SETUP_CHANGED", AiOutcome.NOT_EXECUTED)
            access.disable()
            policy.invalidate { stopConnection() }
            commands.submit {}.get(timeoutSeconds, TimeUnit.SECONDS)
            val value = store.update { it.copy(enabled = false, paired = false, catalogEpoch = UUID.randomUUID().toString(), clients = emptyMap()) }
            publish { it.copy(state = value, connection = "DISABLED", error = null) }
        }.get(timeoutSeconds, TimeUnit.SECONDS)
        main.post { if (!policy.enabled) stopStartedService() }
    }

    companion object {
        @Volatile private var instance: AiControlRuntime? = null
        fun get(context: Context): AiControlRuntime = instance ?: synchronized(this) {
            instance ?: AiControlRuntime(context).also { instance = it }
        }
        fun keepsProcessAlive(): Boolean = instance?.let { it.serviceActive.get() && it.policy.enabled } == true
        val CAPABILITIES = listOf(
            "get_catalog_status", "list_playlists", "list_playlist_items", "search_library", "get_stream", "create_playlist", "rename_playlist", "delete_playlist",
            "save_to_library", "add_to_playlist", "remove_from_playlist", "reorder_playlist_items", "move_items_between_playlists", "preview_library_removal",
            "remove_library_items", "get_command_result", "get_now_playing", "pause", "resume", "skip_if_current", "resolve_stream_url", "save_urls", "play_items", "append_to_queue"
        ) + AiTagProtocol.capabilities
    }
}
