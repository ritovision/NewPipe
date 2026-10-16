/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Outbound TLS only. Pairing uses proof of a device-held key, not a shared token embedded in an APK. */
internal class AiRelayTransport(
    val identity: AiLocalState,
    private val keys: AiDeviceKeys,
    private val listener: Listener
) {
    interface Listener {
        fun accepted(transport: AiRelayTransport)
        fun ready(transport: AiRelayTransport)
        fun command(transport: AiRelayTransport, command: AiIncomingCommand)
        fun failed(transport: AiRelayTransport, code: String, permanent: Boolean)
    }

    private val closed = AtomicBoolean(false)
    private val httpCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>()

    @Volatile private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null

    @Volatile private var lastPong = 0L

    @Volatile private var socket: WebSocket? = null

    @Volatile private var connectionId: String? = null

    @Volatile var isReady: Boolean = false
        private set

    /** Caller uses the background control thread. Never run challenge/Keystore work on the UI. */
    fun connect() {
        if (closed.get()) return
        try {
            AiProtocol.checkInput(identity.paired && identity.enabled, "NOT_PAIRED")
            AiProtocol.checkInput(keys.exists(identity.deviceId) && AiProtocol.digest(keys.publicKey(identity.deviceId)) == identity.keyFingerprint, "DEVICE_KEY_UNAVAILABLE")
            val clientNonce = AiDeviceKeys.encode(ByteArray(24).also(SecureRandom()::nextBytes))
            val issued = System.currentTimeMillis()
            val proof = "challenge\n${identity.gatewayOrigin}\n${identity.deviceId}\n$clientNonce\n$issued"
            val challenge = post(
                identity.gatewayOrigin + "/device/challenge",
                buildJsonObject {
                    put("device_id", identity.deviceId)
                    put("client_nonce", clientNonce)
                    put("timestamp_ms", issued)
                    put("signature", keys.sign(identity.deviceId, proof))
                },
                check = { if (closed.get()) throw AiFailure("CANCELLED", AiOutcome.NOT_EXECUTED) },
                registered = { httpCall.set(it) },
                released = { httpCall.compareAndSet(it, null) }
            )
            if (closed.get()) return
            val nonce = AiRelayWire.string(challenge, "nonce", 64)
            AiProtocol.checkInput(nonce.matches(Regex("[A-Za-z0-9_-]{32,64}")), "INVALID_RELAY_MESSAGE")
            val signature = keys.sign(identity.deviceId, "connect\n${identity.gatewayOrigin}\n${identity.deviceId}\n$nonce\n$clientNonce")
            val request = Request.Builder().url(identity.gatewayOrigin + "/device/connect")
                .header("User-Agent", "NewPipe-AI-Control/1.0")
                .header("X-NewPipe-Device", identity.deviceId)
                .header("X-NewPipe-Nonce", nonce)
                .header("X-NewPipe-Signature", signature).build()
            if (closed.get()) return
            socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (closed.get()) return
                        if (text == "pong") {
                            lastPong = android.os.SystemClock.elapsedRealtime()
                            return
                        }
                        try {
                            val frame = AiRelayWire.parse(text)
                            when (AiRelayWire.string(frame, "type")) {
                                "connected" -> {
                                    AiRelayWire.keys(frame, setOf("type", "connection_id", "owner_id", "server_time_ms"))
                                    AiProtocol.checkInput(connectionId == null && AiRelayWire.string(frame, "owner_id") == identity.ownerId, "WRONG_RELAY_SESSION")
                                    val id = AiRelayWire.string(frame, "connection_id")
                                    AiProtocol.requireUuid(id)
                                    connectionId = id
                                    socket = webSocket
                                    listener.accepted(this@AiRelayTransport)
                                }

                                "ready" -> {
                                    AiRelayWire.keys(frame, setOf("type", "connection_id"))
                                    AiProtocol.checkInput(connectionId != null && AiRelayWire.string(frame, "connection_id") == connectionId, "WRONG_RELAY_SESSION")
                                    isReady = true
                                    startHeartbeat()
                                    listener.ready(this@AiRelayTransport)
                                }

                                "command" -> {
                                    AiProtocol.checkInput(isReady, "RELAY_NOT_READY")
                                    try {
                                        listener.command(this@AiRelayTransport, AiRelayWire.incoming(frame, connectionId!!, identity.ownerId, identity.deviceId))
                                    } catch (error: AiFailure) {
                                        if (error.code in setOf("WRONG_RELAY_SESSION", "WRONG_RELAY_TARGET")) throw error
                                        AiRelayWire.rejection(frame, connectionId!!, identity.ownerId, identity.deviceId)?.let { rejected ->
                                            reply(rejected, AiResult(rejected.requestId, identity.deviceId, AiOutcome.NOT_EXECUTED, error.code))
                                        }
                                    }
                                }

                                else -> throw AiFailure("INVALID_RELAY_MESSAGE")
                            }
                        } catch (_: Exception) {
                            fail("INVALID_RELAY_MESSAGE", false)
                        }
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        fail("INVALID_RELAY_MESSAGE", false)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                        fail(if (code == 1008 && reason == "Revoked") "PAIRING_REJECTED" else "CONNECTION_CLOSED", code == 1008 && reason == "Revoked")
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        fail("CONNECTION_CLOSED", code == 1008 && reason == "Revoked")
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        fail(if (response?.code in setOf(401, 403)) "AUTHENTICATION_REJECTED" else "NETWORK_UNAVAILABLE", false)
                    }
                }
            )
            if (closed.get()) socket?.cancel()
        } catch (error: Exception) {
            val code = (error as? AiFailure)?.code ?: "NETWORK_UNAVAILABLE"
            fail(code, code in setOf("NOT_PAIRED", "DEVICE_KEY_UNAVAILABLE"))
        }
    }

    fun hello(capabilities: List<String>) {
        val id = connectionId ?: return
        send(
            buildJsonObject {
                put("type", "hello")
                put("connection_id", id)
                put(
                    "hello",
                    buildJsonObject {
                        put("version", AiProtocol.VERSION)
                        put("device_id", identity.deviceId)
                        put("catalog_epoch", identity.catalogEpoch)
                        put("playback_epoch", AiProcessIdentity.playbackEpoch)
                        put("permissions", JsonArray(identity.permissions.sorted().map(::JsonPrimitive)))
                        put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
                    }
                )
            }
        )
    }

    fun reply(command: AiIncomingCommand, result: AiResult) {
        val id = connectionId ?: return
        val corrected = if (result.requestId.isEmpty()) result.copy(requestId = command.requestId, deviceId = identity.deviceId) else result
        val data = buildJsonObject {
            put("type", "result")
            put("connection_id", id)
            put("client_id", command.clientId)
            put("result", AiProtocol.json.encodeToJsonElement(corrected))
        }
        if (data.toString().toByteArray(Charsets.UTF_8).size > AiProtocol.MAX_RESULT_BYTES) {
            val failure = AiResult(command.requestId, identity.deviceId, AiOutcome.OUTCOME_UNKNOWN, "RESULT_TOO_LARGE")
            send(
                buildJsonObject {
                    put("type", "result")
                    put("connection_id", id)
                    put("client_id", command.clientId)
                    put("result", AiProtocol.json.encodeToJsonElement(failure))
                }
            )
        } else {
            send(data)
        }
    }

    private fun send(frame: JsonObject) {
        if (!closed.get() && socket?.send(frame.toString()) != true) fail("CONNECTION_CLOSED", false)
    }

    private fun startHeartbeat() {
        lastPong = android.os.SystemClock.elapsedRealtime()
        heartbeat?.cancel(false)
        heartbeat = heartbeats.scheduleAtFixedRate({
            if (!closed.get() && isReady) {
                if (android.os.SystemClock.elapsedRealtime() - lastPong > 70_000L) {
                    fail("HEARTBEAT_TIMEOUT", false)
                } else if (socket?.send("ping") != true) {
                    fail("CONNECTION_CLOSED", false)
                }
            }
        }, 20, 20, TimeUnit.SECONDS)
        if (closed.get()) heartbeat?.cancel(false)
    }

    fun close() {
        closed.set(true)
        heartbeat?.cancel(false)
        httpCall.getAndSet(null)?.cancel()
        isReady = false
        socket?.cancel()
        socket = null
    }

    private fun fail(code: String, permanent: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        heartbeat?.cancel(false)
        httpCall.getAndSet(null)?.cancel()
        isReady = false
        socket?.cancel()
        socket = null
        listener.failed(this, code, permanent)
    }

    companion object {
        private val heartbeats = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "NewPipe-AI-heartbeat").apply { isDaemon = true }
        }
        private val mediaType = "application/json; charset=utf-8".toMediaType()
        private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build()

        private fun post(
            url: String,
            json: JsonObject,
            check: () -> Unit,
            registered: (okhttp3.Call) -> Unit,
            released: (okhttp3.Call) -> Unit
        ): JsonObject {
            check()
            val call = client.newCall(Request.Builder().url(url).header("User-Agent", "NewPipe-AI-Control/1.0").post(json.toString().toRequestBody(mediaType)).build())
            try {
                registered(call)
                check()
                call.execute().use { response ->
                    check()
                    if (response.code in setOf(401, 403)) throw AiFailure("AUTHENTICATION_REJECTED")
                    if (!response.isSuccessful) throw AiFailure(if (response.code == 429) "RATE_LIMITED" else "GATEWAY_UNAVAILABLE")
                    return AiRelayWire.parse(AiRelayWire.read(response.body.byteStream(), 8192), 8192)
                }
            } finally {
                call.cancel()
                released(call)
            }
        }

        fun pair(
            state: AiLocalState,
            code: String,
            keys: AiDeviceKeys,
            check: () -> Unit,
            registered: (okhttp3.Call) -> Unit,
            released: (okhttp3.Call) -> Unit
        ): AiLocalState {
            check()
            AiProtocol.checkInput(code.matches(Regex("[A-Za-z0-9_-]{24}")), "INVALID_PAIRING_CODE")
            val publicKey = keys.create(state.deviceId)
            val proof = "pair\n${state.gatewayOrigin}\n${state.deviceId}\n$code\n$publicKey\n${state.deviceName}"
            val result = post(
                state.gatewayOrigin + "/device/pair",
                buildJsonObject {
                    put("device_id", state.deviceId)
                    put("name", state.deviceName)
                    put("public_key", publicKey)
                    put("pairing_code", code)
                    put("signature", keys.sign(state.deviceId, proof))
                },
                check,
                registered,
                released
            )
            check()
            AiProtocol.checkInput(result["paired"]?.jsonPrimitive?.booleanOrNull == true && AiRelayWire.string(result, "device_id") == state.deviceId, "INVALID_RELAY_MESSAGE")
            val owner = AiRelayWire.string(result, "owner_id")
            AiProtocol.requireUuid(owner)
            return state.copy(ownerId = owner, keyFingerprint = AiProtocol.digest(publicKey), paired = true, enabled = false, clients = emptyMap(), blockedClients = if (state.ownerId.isEmpty() || state.ownerId == owner) state.blockedClients else emptySet())
        }
    }
}
