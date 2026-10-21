/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import java.util.UUID

/** Client interruption seam. Actual grant/CAS/rotation invariants run against D1/R2 separately. */
internal class CloudFakeReplacementPeer(val recovery: CloudFakeRecoveryPeer) {
    val catalog get() = recovery.catalog
    var allowed = true
    var cleanupPauses = 0
    var fault: String? = null
    var before: ((String) -> Unit)? = null
    var attempt: JsonObject? = null
    private var approval: JsonObject? = null
    private fun json(value: Any?) = CloudWireJson.obj(CloudWireJson.encode(value))
    fun client(origin: String, token: String, admit: () -> Unit): CloudRecoveryApi {
        val inner = recovery.client(origin, token, admit)
        return object : CloudRecoveryApi {
            override fun post(path: String, body: Map<String, Any?>): JsonObject {
                admit()
                before?.invoke(path)
                admit()
                if (fault == "reserve.before" && path == "/v1/replacement/reserve") {
                    fault = null
                    throw CloudRemoteException("CLOUD_NETWORK_UNAVAILABLE")
                }
                val value = when {
                    path in setOf("/v1/connect", "/v1/head") -> inner.post(path, body).also {
                        if (allowed) it["capabilities"] = JsonArray(it.getArray("capabilities") + "catalog.replace")
                        it.getObject("deviceState")["replacement"] = attempt?.takeIf { a -> a.getString("state") in setOf("ACTIVE", "STALE") }?.let { a ->
                            json(mapOf("attemptId" to a.getString("attemptId"), "state" to a.getString("state")))
                        }
                    }

                    path.startsWith("/v1/replacement/") -> replacement(inner, token, path.substringAfterLast('/'), body)

                    else -> inner.post(path, body)
                }
                if (fault == path.substringAfterLast('/') + ".after" && path.startsWith("/v1/replacement/")) {
                    fault = null
                    throw CloudRemoteException("CLOUD_NETWORK_UNAVAILABLE")
                }
                admit()
                return value
            }
            override fun uploadRecoveryPart(reference: Map<String, String>, number: Int, bytes: ByteArray) = inner.uploadRecoveryPart(reference, number, bytes)
            override fun downloadRecoveryPart(archiveId: String, pinId: String, number: Int, expected: CloudRecoveryFormat.Part) = inner.downloadRecoveryPart(archiveId, pinId, number, expected)
        }
    }
    private fun reference(a: JsonObject) = a.getObject("incoming").let { mapOf("sequence" to it.getString("sequence"), "digest" to it.getString("digest")) }
    private fun replacement(inner: CloudRecoveryApi, token: String, path: String, body: Map<String, Any?>): JsonObject {
        when (path) {
            "status" -> return json(mapOf("attempt" to attempt, "cooldownUntil" to "0"))

            "preview" -> {
                if (!allowed) throw CloudRemoteException("REPLACEMENT_NOT_AUTHORIZED", 403)
                val incoming = json(body["incoming"])
                check(CloudWireJson.head(incoming["before"]) == catalog.head)
                if (attempt?.getString("state") == "STALE") check(body["retryAttemptId"] == attempt!!.getString("attemptId"))
                approval = inner.post("/v1/recovery/preview", mapOf("manifest" to body["manifest"])).also {
                    it["incoming"] = incoming
                    it["nextEpoch"] = UUID.randomUUID().toString()
                }
                return json(approval)
            }

            "reserve" -> {
                val p = checkNotNull(approval)
                check(body["approvalId"] == p.getString("approvalId"))
                val op = inner.post("/v1/recovery/reserve", mapOf("approvalId" to body["approvalId"], "sequence" to body["recoverySequence"]))
                if (attempt?.getString("attemptId") != body["approvalId"]) {
                    attempt = json(
                        mapOf(
                            "attemptId" to body["approvalId"],
                            "incoming" to p["incoming"],
                            "nextEpoch" to p["nextEpoch"],
                            "recoverySequence" to body["recoverySequence"],
                            "archiveId" to op["archiveId"],
                            "slot" to 0,
                            "state" to "ACTIVE"
                        )
                    )
                }
                return json(mapOf("attempt" to attempt, "operation" to op))
            }

            "publish" -> {
                val a = checkNotNull(attempt)
                check(a.getString("attemptId") == body["attemptId"])
                if (!allowed) throw CloudRemoteException("REPLACEMENT_NOT_AUTHORIZED", 403)
                check(recovery.copies.single { it.id == a.getString("archiveId") }.manifest.source!!.head == CloudWireJson.head(a.getObject("incoming")["before"]))
                val result = catalog.replacement(token, reference(a), a.getString("nextEpoch"))
                a["state"] = "DONE"
                return json(mapOf("receipt" to result["receipt"], "attempt" to a))
            }

            "abandon", "settle" -> {
                if (path == "abandon" && attempt?.getString("attemptId") != body["approvalId"]) {
                    val p = checkNotNull(approval)
                    check(p.getString("approvalId") == body["approvalId"])
                    val r = inner.post("/v1/uploads/retire", reference(p))
                    return json(mapOf("receipt" to r["receipt"], "attempt" to null))
                }
                val a = checkNotNull(attempt)
                check(a.getString("attemptId") == body[if (path == "abandon") "approvalId" else "attemptId"])
                val r = inner.post("/v1/uploads/retire", reference(a))
                if (body["keepForRetry"] != true && cleanupPauses > 0) {
                    cleanupPauses--
                    return json(mapOf("receipt" to r["receipt"], "attempt" to a, "cleanupPending" to true))
                }
                if (r.getObject("receipt").getString("outcome") == "COMMITTED") {
                    a["state"] = "DONE"
                } else if (body["keepForRetry"] == true) {
                    check(recovery.copies.any { it.id == a.getString("archiveId") })
                    a["state"] = "STALE"
                } else {
                    inner.post("/v1/recovery/abandon", mapOf("approvalId" to a.getString("attemptId"), "sequence" to a.getString("recoverySequence")))
                    a["state"] = "CANCELED"
                }
                return json(mapOf("receipt" to r["receipt"], "attempt" to a))
            }

            else -> error("UNKNOWN_FAKE_REPLACEMENT_ROUTE")
        }
    }
}
