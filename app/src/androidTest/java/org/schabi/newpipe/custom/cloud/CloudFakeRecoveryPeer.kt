/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import com.grack.nanojson.JsonObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Client fault seam only; rotation/storage invariants are exercised with Miniflare D1/R2. */
internal class CloudFakeRecoveryPeer(val catalog: CloudFakePeer) {
    data class Copy(val id: String, val manifest: CloudRecoveryFormat.Manifest, val bytes: ByteArray)
    val copies = ArrayList<Copy>()
    var fault: String? = null
    var before: ((String) -> Unit)? = null
    var created = 0
    var cleanupPauses = 0
    var sequence = 0L
    var op: JsonObject? = null
    private var approval: String? = null
    private var manifest: CloudRecoveryFormat.Manifest? = null
    private var payload = ByteArrayOutputStream()
    private fun json(value: Any?) = CloudWireJson.obj(CloudWireJson.encode(value))
    fun client(origin: String, token: String, admit: () -> Unit): CloudRecoveryApi {
        val peer = catalog.client(origin, token, admit)
        return object : CloudRecoveryApi {
            override fun post(path: String, body: Map<String, Any?>): JsonObject {
                admit()
                before?.invoke(path)
                admit()
                val value = when {
                    path in setOf("/v1/connect", "/v1/head") -> peer.post(path, body).also {
                        it["capabilities"] = com.grack.nanojson.JsonArray(listOf("catalog.read", "catalog.initialize", "catalog.sync", "operation.retire", "backup.r2", "backup.read"))
                    }

                    !path.startsWith("/v1/recovery/") -> peer.post(path, body)

                    else -> handle(path.substringAfterLast('/'), body)
                }
                if (fault == path.substringAfterLast('/') + ".after") {
                    fault = null
                    throw CloudRemoteException("CLOUD_NETWORK_UNAVAILABLE")
                }
                admit()
                return value
            }
            override fun uploadRecoveryPart(reference: Map<String, String>, number: Int, bytes: ByteArray): JsonObject {
                admit()
                val operation = checkNotNull(op)
                check(operation.getString("archiveId") == reference["archiveId"] && operation.getString("sequence") == reference["sequence"])
                val m = checkNotNull(manifest)
                check(CloudWireJson.sha256(bytes) == m.parts[number - 1].sha256)
                if (operation.getInt("nextPart") == number) {
                    payload.write(bytes)
                    operation["nextPart"] = number + 1
                }
                if (fault == "part.after") {
                    fault = null
                    throw CloudRemoteException("CLOUD_NETWORK_UNAVAILABLE")
                }
                admit()
                return json(operation)
            }
            override fun downloadRecoveryPart(archiveId: String, pinId: String, number: Int, expected: CloudRecoveryFormat.Part): ByteArray {
                admit()
                val copy = copies.single { it.id == archiveId }
                val start = (number - 1) * CloudRecoveryFormat.PART_BYTES
                val bytes = copy.bytes.copyOfRange(start, start + expected.bytes)
                if (fault == "download.corrupt") {
                    fault = null
                    bytes[0] = (bytes[0].toInt() xor 1).toByte()
                }
                admit()
                return bytes
            }
        }
    }
    private fun handle(path: String, body: Map<String, Any?>): JsonObject {
        when (path) {
            "status" -> return json(
                mapOf(
                    "maxSlots" to 3,
                    "partBytes" to CloudRecoveryFormat.PART_BYTES,
                    "maxBytes" to CloudRecoveryFormat.MAX_BYTES,
                    "occupiedSlots" to copies.size,
                    "lastSequence" to sequence.toString(),
                    "operation" to op?.takeUnless { it.getString("state") in setOf("DONE", "CANCELED") },
                    "archives" to copies.map {
                        mapOf(
                            "archiveId" to it.id,
                            "kind" to it.manifest.kind,
                            "createdAt" to "1700000000000",
                            "bytes" to it.bytes.size,
                            "sourceDevice" to "device-a",
                            "state" to "READY",
                            "usable" to true,
                            "error" to null
                        )
                    }
                )
            )

            "preview" -> {
                manifest = CloudRecoveryFormat.read(body["manifest"])
                approval = UUID.randomUUID().toString()
                return json(
                    mapOf(
                        "approvalId" to approval,
                        "manifest" to manifest!!.wire(),
                        "digest" to manifest!!.digest(),
                        "evictArchiveId" to null,
                        "remainingArchives" to copies.map { it.id }
                    )
                )
            }

            "reserve" -> {
                op?.takeIf { it.getString("sequence") == body["sequence"] }?.let { return json(it) }
                check(body["approvalId"] == approval && (body["sequence"] as String).toLong() == sequence + 1)
                sequence++
                created++
                payload = ByteArrayOutputStream()
                op = json(
                    mapOf(
                        "sequence" to sequence.toString(),
                        "approvalId" to approval,
                        "archiveId" to UUID.randomUUID().toString(),
                        "state" to "RESERVED",
                        "manifest" to manifest!!.wire(),
                        "digest" to manifest!!.digest(),
                        "nextPart" to 1
                    )
                )
                return json(op)
            }

            "advance" -> {
                val operation = checkNotNull(op)
                when (operation.getString("state")) {
                    "RESERVED" -> operation["state"] = "UPLOADING"

                    "VERIFYING" -> {
                        val m = checkNotNull(manifest)
                        check(payload.size() == m.bytes)
                        copies.add(Copy(operation.getString("archiveId"), m, payload.toByteArray()))
                        operation["state"] = "DONE"
                    }
                }
                return json(operation)
            }

            "complete" -> {
                op!!["state"] = "VERIFYING"
                return json(op)
            }

            "abandon", "abort" -> {
                val existing = op
                if (existing == null) return json(mapOf("state" to "CANCELED", "sequence" to body["sequence"], "approvalId" to body["approvalId"]))
                if (cleanupPauses > 0) {
                    cleanupPauses--
                    return json(existing + mapOf("cleanupPending" to true))
                }
                if (existing.getString("state") != "DONE") existing["state"] = "CANCELED"
                return json(existing)
            }

            "pin" -> {
                val copy = copies.single { it.id == body["archiveId"] }
                return json(mapOf("archiveId" to copy.id, "pinId" to UUID.randomUUID().toString(), "manifest" to copy.manifest.wire(), "digest" to copy.manifest.digest()))
            }

            "release" -> return json(mapOf("released" to true))

            else -> error("UNKNOWN_FAKE_RECOVERY_ROUTE")
        }
    }
}
