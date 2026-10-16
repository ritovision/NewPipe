/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal data class AiIncomingCommand(
    val clientId: String,
    val clientName: String,
    val scopes: Set<AiScope>,
    val requestId: String,
    val rawRequest: String
)

internal object AiRelayWire {
    const val MAX_FRAME_BYTES = 98_304

    fun parse(raw: String, maximum: Int = MAX_FRAME_BYTES): JsonObject {
        AiProtocol.checkInput(raw.length <= maximum && raw.toByteArray(Charsets.UTF_8).size <= maximum, "RELAY_PAYLOAD_TOO_LARGE")
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (quoted) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true

                    '{', '[' -> {
                        depth++
                        AiProtocol.checkInput(depth <= 18, "INVALID_RELAY_MESSAGE")
                    }

                    '}', ']' -> depth--
                }
            }
        }
        return try {
            AiProtocol.json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            throw AiFailure("INVALID_RELAY_MESSAGE")
        }
    }

    fun string(objectValue: JsonObject, key: String, limit: Int = 128): String {
        val value = objectValue[key] as? JsonPrimitive ?: throw AiFailure("INVALID_RELAY_MESSAGE")
        AiProtocol.checkInput(value.isString && value.content.length <= limit, "INVALID_RELAY_MESSAGE")
        return value.content
    }

    fun keys(value: JsonObject, allowed: Set<String>) {
        AiProtocol.checkInput(value.keys.all { it in allowed }, "INVALID_RELAY_MESSAGE")
    }

    fun incoming(frame: JsonObject, connectionId: String, ownerId: String, deviceId: String): AiIncomingCommand {
        keys(frame, setOf("type", "connection_id", "owner_id", "client_id", "client_name", "scopes", "request"))
        AiProtocol.checkInput(string(frame, "type") == "command" && string(frame, "connection_id") == connectionId && string(frame, "owner_id") == ownerId, "WRONG_RELAY_SESSION")
        val client = string(frame, "client_id")
        AiProtocol.checkInput(client.matches(Regex("c_[0-9a-f]{64}")), "INVALID_CLIENT")
        val name = string(frame, "client_name", 2048).take(80).filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        val names = frame["scopes"] as? JsonArray ?: throw AiFailure("INVALID_RELAY_MESSAGE")
        AiProtocol.checkInput(names.size <= 16, "INVALID_RELAY_MESSAGE")
        val known = AiScope.entries.associateBy { it.wireName }
        val scopes = names.map { element ->
            val primitive = element as? JsonPrimitive ?: throw AiFailure("INVALID_RELAY_MESSAGE")
            AiProtocol.checkInput(primitive.isString && primitive.content.length <= 128, "INVALID_RELAY_MESSAGE")
            primitive.content
        }.mapNotNull(known::get).toSet()
        val request = frame["request"] as? JsonObject ?: throw AiFailure("INVALID_RELAY_MESSAGE")
        AiProtocol.checkInput(string(request, "client_id") == client && string(request, "device_id") == deviceId, "WRONG_RELAY_TARGET")
        val id = string(request, "request_id")
        AiProtocol.requireUuid(id)
        val raw = request.toString()
        AiProtocol.checkInput(raw.toByteArray(Charsets.UTF_8).size <= AiProtocol.MAX_REQUEST_BYTES, "PAYLOAD_TOO_LARGE")
        return AiIncomingCommand(client, name, scopes, id, raw)
    }

    /** Correlate a single rejected request only when its enclosing session and identities match. */
    fun rejection(frame: JsonObject, connectionId: String, ownerId: String, deviceId: String): AiIncomingCommand? = try {
        if (string(frame, "connection_id") != connectionId || string(frame, "owner_id") != ownerId) {
            null
        } else {
            val client = string(frame, "client_id")
            val request = frame["request"] as? JsonObject ?: throw AiFailure("INVALID_REQUEST")
            val id = string(request, "request_id")
            AiProtocol.requireUuid(id)
            if (!client.matches(Regex("c_[0-9a-f]{64}")) || string(request, "client_id") != client || string(request, "device_id") != deviceId) {
                null
            } else {
                AiIncomingCommand(client, "", emptySet(), id, "{}")
            }
        }
    } catch (_: Exception) {
        null
    }

    fun read(input: InputStream, maximum: Int): String = input.use {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            if (out.size() + count > maximum) throw AiFailure("RELAY_PAYLOAD_TOO_LARGE")
            out.write(buffer, 0, count)
        }
        out.toString(Charsets.UTF_8.name())
    }
}
