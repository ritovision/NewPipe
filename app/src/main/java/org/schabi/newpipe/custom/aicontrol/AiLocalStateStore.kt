/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class AiLocalState(
    val version: Int = 1,
    val deviceId: String = UUID.randomUUID().toString(),
    val catalogEpoch: String = UUID.randomUUID().toString(),
    val deviceName: String,
    val gatewayOrigin: String = "",
    val ownerId: String = "",
    val keyFingerprint: String = "",
    val paired: Boolean = false,
    val enabled: Boolean = false,
    val permissions: Set<String> = setOf(AiScope.LIBRARY_READ.wireName),
    val clients: Map<String, String> = emptyMap(),
    val blockedClients: Set<String> = emptySet()
)

internal object AiLocalStatePolicy {
    fun origin(value: String): String {
        val uri = try {
            URI(value.trim())
        } catch (_: Exception) {
            throw AiFailure("INVALID_GATEWAY")
        }
        AiProtocol.checkInput(
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.port in setOf(-1, 443) && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in setOf("", "/"),
            "INVALID_GATEWAY"
        )
        AiProtocol.checkInput(value.length <= 2048, "INVALID_GATEWAY")
        return "https://${uri.host.lowercase()}"
    }

    fun validate(state: AiLocalState): AiLocalState {
        AiProtocol.checkInput(state.version == 1, "INVALID_LOCAL_STATE")
        AiProtocol.requireUuid(state.deviceId)
        AiProtocol.requireUuid(state.catalogEpoch)
        AiProtocol.checkInput(state.deviceName.trim().isNotEmpty() && state.deviceName.length <= 80 && state.deviceName.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }, "INVALID_DEVICE_NAME")
        AiProtocol.checkInput(state.permissions.all { name -> AiScope.entries.any { it.wireName == name } }, "INVALID_LOCAL_STATE")
        if (state.gatewayOrigin.isNotEmpty()) AiProtocol.checkInput(origin(state.gatewayOrigin) == state.gatewayOrigin, "INVALID_LOCAL_STATE")
        AiProtocol.checkInput(state.clients.size <= 16 && state.blockedClients.size <= 128, "CLIENT_LIMIT")
        AiProtocol.checkInput((state.clients.keys + state.blockedClients).all { it.matches(Regex("c_[0-9a-f]{64}")) }, "INVALID_LOCAL_STATE")
        AiProtocol.checkInput(state.clients.values.all { it.length <= 80 && it.none(Char::isISOControl) }, "INVALID_LOCAL_STATE")
        if (state.paired) {
            AiProtocol.requireUuid(state.ownerId)
            AiProtocol.checkInput(state.gatewayOrigin.isNotEmpty() && state.keyFingerprint.matches(Regex("[0-9a-f]{64}")), "INVALID_LOCAL_STATE")
        }
        AiProtocol.checkInput(!state.enabled || state.paired, "NOT_PAIRED")
        return state
    }
}

/** This file is excluded from Android Auto Backup and from NewPipe's explicit ZIP exporter. */
internal class AiLocalStateStore(context: Context, filename: String = "rv-ai-control.json") {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))
    private val defaultName = Build.MODEL.orEmpty().filterNot(Char::isISOControl).trim().take(80).ifBlank { "Android device" }

    @Synchronized
    fun read(): AiLocalState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return replace(AiLocalState(deviceName = defaultName))
        try {
            val raw = file.openRead().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 65_536) throw AiFailure("INVALID_LOCAL_STATE")
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
            return AiLocalStatePolicy.validate(AiProtocol.json.decodeFromString<AiLocalState>(raw))
        } catch (_: Exception) {
            // Fail closed: corrupt state must never silently recreate an enabled pairing.
            throw AiFailure("INVALID_LOCAL_STATE", AiOutcome.NOT_EXECUTED)
        }
    }

    @Synchronized
    fun update(transform: (AiLocalState) -> AiLocalState): AiLocalState = replace(transform(read()))

    @Synchronized
    fun replace(value: AiLocalState): AiLocalState {
        val checked = AiLocalStatePolicy.validate(value)
        val bytes = AiProtocol.json.encodeToString(checked).toByteArray(Charsets.UTF_8)
        if (bytes.size > 65_536) throw AiFailure("INVALID_LOCAL_STATE")
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        return checked
    }

    @Synchronized
    fun reset(): AiLocalState = replace(AiLocalState(deviceName = defaultName))
}
