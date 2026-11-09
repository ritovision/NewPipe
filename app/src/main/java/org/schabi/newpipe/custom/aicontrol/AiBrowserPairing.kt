/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Pending enrollment is deliberately separate from the active AI Control configuration. */
@Serializable
internal data class AiBrowserPairingPending(
    val version: Int = 1,
    val base: AiLocalState,
    val proposed: AiLocalState,
    val publicKey: String,
    val clientNonce: String,
    val issuedAtMs: Long,
    val ownerId: String = "",
    val expiresAtMs: Long = 0,
    val serverStatus: String = "local",
    val finishSubmitted: Boolean = false,
    val cancelledLocally: Boolean = false,
    val nonceReady: Boolean = false
) {
    val requestId: String get() = AiProtocol.digest(message("begin"))
    val comparisonCode: String get() = requestId.take(4).uppercase() + "-" + requestId.substring(4, 8).uppercase()
    val approvalUrl: String get() = proposed.gatewayOrigin + "/pair/device?request=" + requestId
    val deadlineMs: Long get() = (expiresAtMs.takeIf { it > 0 } ?: (issuedAtMs + 360_000)) + if (finishSubmitted) 86_400_000 else 0

    fun message(operation: String): String {
        AiProtocol.checkInput(operation in setOf("begin", "status", "finish", "cancel"), "INVALID_PAIRING_OPERATION")
        return listOf(
            "newpipe-pairing-v1", operation, proposed.gatewayOrigin, proposed.deviceId, publicKey,
            clientNonce, issuedAtMs.toString(), proposed.deviceName, proposed.permissions.sorted().joinToString(","),
            if (operation == "begin") "" else requestId
        ).joinToString("\n")
    }

    fun request(operation: String, signature: String): JsonObject = buildJsonObject {
        put("pairing_version", 1)
        put("device_id", proposed.deviceId)
        put("public_key", publicKey)
        put("client_nonce", clientNonce)
        put("issued_at_ms", issuedAtMs)
        put("name", proposed.deviceName)
        put("permissions", JsonArray(proposed.permissions.sorted().map(::JsonPrimitive)))
        if (operation != "begin") put("request_id", requestId)
        put("signature", signature)
    }
}

/** Captured when a transaction is admitted, never refreshed by a later network dispatch.
 * Otherwise a queued status/finish could accidentally inherit a cancellation's newer generation.
 */
internal data class AiBrowserPairingAdmission(val requestId: String, val generation: Long, val policyVersion: Long) {
    fun matches(pending: AiBrowserPairingPending?, currentGeneration: Long, currentPolicy: Long): Boolean = pending?.requestId == requestId && generation == currentGeneration && policyVersion == currentPolicy
}

internal object AiBrowserPairingPolicy {
    private val statuses = setOf("local", "pending", "approved", "committed", "complete", "denied", "cancelled")
    private fun check(value: Boolean) = AiProtocol.checkInput(value, "PAIRING_RESPONSE_MISMATCH")

    fun create(base: AiLocalState, origin: String, name: String, permissions: Set<String>, publicKey: String, now: Long): AiBrowserPairingPending {
        AiProtocol.checkInput(!base.paired && !base.enabled, "ALREADY_PAIRED")
        val proposed = AiLocalStatePolicy.validate(base.copy(gatewayOrigin = AiLocalStatePolicy.origin(origin), deviceName = name.trim(), permissions = permissions))
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return validate(AiBrowserPairingPending(base = base, proposed = proposed, publicKey = publicKey, clientNonce = AiDeviceKeys.encode(nonce), issuedAtMs = now))
    }

    fun validate(value: AiBrowserPairingPending): AiBrowserPairingPending {
        AiLocalStatePolicy.validate(value.base)
        AiLocalStatePolicy.validate(value.proposed)
        check(value.version == 1 && !value.base.paired && !value.base.enabled && !value.proposed.paired && !value.proposed.enabled)
        check(value.base.deviceId == value.proposed.deviceId && value.base.catalogEpoch == value.proposed.catalogEpoch)
        check(value.proposed.gatewayOrigin.isNotEmpty() && value.publicKey.matches(Regex("[A-Za-z0-9_-]{80,256}")))
        check(value.clientNonce.matches(Regex("[A-Za-z0-9_-]{43}")) && value.issuedAtMs in 1..9_000_000_000_000_000L)
        check(value.serverStatus in statuses)
        if (value.ownerId.isNotEmpty()) AiProtocol.requireUuid(value.ownerId)
        check(value.expiresAtMs == 0L || (value.ownerId.isNotEmpty() && value.expiresAtMs in (value.issuedAtMs + 240_000)..(value.issuedAtMs + 360_000)))
        check(value.serverStatus == "local" || (value.nonceReady && value.ownerId.isNotEmpty() && value.expiresAtMs > 0))
        check(value.serverStatus !in setOf("committed", "complete") || value.finishSubmitted)
        return value
    }

    /** A changed permission, restore epoch, identity or enabled state makes every old callback stale. */
    fun matches(value: AiBrowserPairingPending, active: AiLocalState, now: Long): Boolean = !value.cancelledLocally && value.base == active && !active.paired && !active.enabled && now < value.deadlineMs

    fun acceptNonce(value: AiBrowserPairingPending, response: JsonObject): AiBrowserPairingPending {
        validate(value)
        check(!value.nonceReady && value.serverStatus == "local" && value.ownerId.isEmpty() && !value.cancelledLocally)
        AiRelayWire.keys(response, setOf("pairing_version", "client_nonce"))
        check((response["pairing_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull == 1L)
        val nonce = AiRelayWire.string(response, "client_nonce", 43)
        check(nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
        return validate(value.copy(clientNonce = nonce, nonceReady = true))
    }

    fun accept(value: AiBrowserPairingPending, response: JsonObject): AiBrowserPairingPending {
        validate(value)
        check(value.nonceReady)
        AiRelayWire.keys(
            response,
            setOf(
                "pairing_version", "protocol_version", "request_id", "status", "paired", "device_id",
                "owner_id", "client_nonce", "public_key_hash", "name", "permissions", "issued_at_ms", "expires_at_ms", "comparison_code"
            )
        )
        fun number(key: String): Long? = (response[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        fun text(key: String) = AiRelayWire.string(response, key, 256)
        check(number("pairing_version") == 1L && number("protocol_version") == 1L)
        check(text("request_id") == value.requestId && text("device_id") == value.proposed.deviceId)
        check(text("client_nonce") == value.clientNonce && text("public_key_hash") == AiProtocol.digest(value.publicKey))
        check(number("issued_at_ms") == value.issuedAtMs && text("name") == value.proposed.deviceName)
        check(response["permissions"] == JsonArray(value.proposed.permissions.sorted().map(::JsonPrimitive)))
        check(text("comparison_code") == value.comparisonCode)
        val owner = text("owner_id")
        AiProtocol.requireUuid(owner)
        check(value.ownerId.isEmpty() || value.ownerId == owner)
        val expires = number("expires_at_ms") ?: throw AiFailure("PAIRING_RESPONSE_MISMATCH")
        check(value.expiresAtMs == 0L || value.expiresAtMs == expires)
        val status = text("status")
        check(status in statuses && status != "local")
        check((response["paired"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == (status == "complete"))
        return validate(value.copy(ownerId = owner, expiresAtMs = expires, serverStatus = status))
    }

    fun completedState(value: AiBrowserPairingPending): AiLocalState {
        validate(value)
        check(!value.cancelledLocally && value.finishSubmitted && value.serverStatus == "complete")
        return AiLocalStatePolicy.validate(
            value.proposed.copy(
                ownerId = value.ownerId,
                keyFingerprint = AiProtocol.digest(value.publicKey),
                paired = true,
                enabled = false,
                clients = emptyMap(),
                blockedClients = if (value.base.ownerId.isEmpty() || value.base.ownerId == value.ownerId) value.base.blockedClients else emptySet()
            )
        )
    }
}

/** Excluded from Android Auto Backup and NewPipe ZIP export, like the active device credentials. */
internal class AiBrowserPairingStore(context: Context, filename: String = "rv-ai-pending-pairing.json") {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))

    @Synchronized fun read(): AiBrowserPairingPending? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val raw = file.openRead().use { AiRelayWire.read(it, 65_536) }
        return AiBrowserPairingPolicy.validate(AiProtocol.json.decodeFromString<AiBrowserPairingPending>(raw))
    }

    @Synchronized fun write(value: AiBrowserPairingPending) {
        val bytes = AiProtocol.json.encodeToString(AiBrowserPairingPolicy.validate(value)).toByteArray(Charsets.UTF_8)
        AiProtocol.checkInput(bytes.size <= 65_536, "INVALID_PENDING_PAIRING")
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized fun clear() {
        file.delete()
    }
}

internal interface AiBrowserPairingClient {
    fun request(
        operation: String,
        pending: AiBrowserPairingPending,
        keys: AiDeviceKeys,
        check: () -> Unit,
        registered: (Call) -> Unit,
        released: (Call) -> Unit
    ): AiBrowserPairingPending
}

/** Retry classification precedes parsing: proxies may return HTML or no body during outages. */
internal object AiBrowserPairingHttp {
    fun read(status: Int, body: () -> String): JsonObject {
        if (status == 429) throw AiFailure("RATE_LIMITED")
        if (status == 408 || status in 500..599) throw AiFailure("GATEWAY_UNAVAILABLE")
        if (status in 200..299) return AiRelayWire.parse(body(), 8192)
        val value = try {
            AiRelayWire.parse(body(), 8192)
        } catch (_: AiFailure) {
            null
        }
        val known = setOf(
            "PAIRING_EXPIRED_OR_UNKNOWN",
            "PAIRING_REJECTED",
            "PAIRING_NOT_APPROVED",
            "PAIRING_CONFLICT",
            "CLOCK_SKEW",
            "DEVICE_LIMIT",
            "PAIRING_RESTART_REQUIRED"
        )
        val supplied = (value?.get("error") as? JsonPrimitive)?.content
        throw AiFailure(
            when {
                supplied in known -> supplied!!
                status in setOf(401, 403) -> "PAIRING_AUTHORIZATION_ENDED"
                status == 404 -> "PAIRING_NOT_SUPPORTED"
                status == 410 -> "PAIRING_EXPIRED_OR_UNKNOWN"
                else -> "PAIRING_REQUEST_REJECTED"
            }
        )
    }
}

internal object AiBrowserPairingTransport : AiBrowserPairingClient {
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    override fun request(
        operation: String,
        pending: AiBrowserPairingPending,
        keys: AiDeviceKeys,
        check: () -> Unit,
        registered: (Call) -> Unit,
        released: (Call) -> Unit
    ): AiBrowserPairingPending {
        check()
        AiProtocol.checkInput(operation in setOf("nonce", "begin", "status", "finish", "cancel"), "INVALID_PAIRING_OPERATION")
        AiProtocol.checkInput(operation == "nonce" || pending.nonceReady, "PAIRING_NOT_PREPARED")
        AiProtocol.checkInput(keys.publicKey(pending.proposed.deviceId) == pending.publicKey, "DEVICE_KEY_CHANGED")
        val builder = Request.Builder().url(pending.proposed.gatewayOrigin + "/device/pairing/" + operation)
            .header("User-Agent", "NewPipe-AI-Control/1.0")
        if (operation == "nonce") {
            builder.get()
        } else {
            val body = pending.request(operation, keys.sign(pending.proposed.deviceId, pending.message(operation)))
            builder.post(body.toString().toRequestBody(mediaType))
        }
        val call = client.newCall(builder.build())
        try {
            registered(call)
            check()
            call.execute().use { response ->
                check()
                val value = AiBrowserPairingHttp.read(response.code) { AiRelayWire.read(response.body.byteStream(), 8192) }
                return if (operation == "nonce") AiBrowserPairingPolicy.acceptNonce(pending, value) else AiBrowserPairingPolicy.accept(pending, value)
            }
        } finally {
            call.cancel()
            released(call)
        }
    }
}
