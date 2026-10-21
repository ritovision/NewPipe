/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import com.grack.nanojson.JsonObject

/** Deterministic test-only protocol peer for client fault injection; real D1 tests are separate. */
internal class CloudFakePeer(val scope: CloudIdentityStore.Scope) {
    private data class Device(var resolved: Long = 0, val receipts: MutableMap<String, Map<String, Any?>> = HashMap(), var stage: Stage? = null)
    private data class Stage(val header: Map<String, Any?>, val chunks: MutableMap<Int, List<CloudPages.Change>> = sortedMapOf(), var sealed: Boolean = false)
    val tokenA = "device-a." + "a".repeat(64)
    val tokenB = "device-b." + "b".repeat(64)
    var calls = 0
    val requests = ArrayList<String>()
    var expireNextStart = false
    var fault: String? = null
    var beforeRequest: ((String) -> Unit)? = null
    var maxBytes = CloudPages.MAX_BYTES
    var maxPages = CloudPages.MAX_PAGES
    private val devices = mapOf("device-a" to Device(), "device-b" to Device())
    var initialized = false
        private set
    var pages: List<CloudPages.Page> = emptyList()
        private set
    var head = CloudHead(scope.service, scope.library, "epoch-peer", "0", "0", CloudCatalogCodec.fingerprint(CloudCatalog()))
        private set
    fun client(origin: String, token: String, admit: () -> Unit): CloudApi {
        require(origin == "https://example.test")
        if (token != tokenA && token != tokenB) throw CloudRemoteException("INVALID_CREDENTIAL", 401)
        val id = token.substringBefore('.')
        val device = devices.getValue(id)
        return object : CloudApi {
            override fun post(path: String, body: Map<String, Any?>): JsonObject = synchronized(this@CloudFakePeer) {
                admit()
                beforeRequest?.invoke(path)
                admit()
                calls++
                requests.add(path)
                if (path == "/v1/uploads/start" && expireNextStart) {
                    expireNextStart = false
                    throw CloudRemoteException("UPLOAD_EXPIRED", 410)
                }
                if (fault == "offline" || fault == "beforeStart" && path == "/v1/uploads/start") {
                    if (fault == "beforeStart") fault = null
                    throw CloudRemoteException("CLOUD_NETWORK_UNAVAILABLE")
                }
                val result = handle(id, device, path, body)
                if (fault == "definiteAfterPublish" && path == "/v1/uploads/publish") {
                    fault = null
                    throw CloudRemoteException("PRECONDITION_FAILED", 409)
                }
                if (fault == "afterPublish" && path == "/v1/uploads/publish") {
                    fault = null
                    throw CloudRemoteException("CLOUD_NETWORK_UNAVAILABLE")
                }
                admit()
                CloudWireJson.obj(CloudWireJson.encode(result))
            }
        }
    }
    fun external(catalog: CloudCatalog, hints: List<CloudMediaHint> = emptyList()) = synchronized(this) {
        pages = CloudPages.encode(catalog, pages, hints)
        initialized = true
        head = head.copy(revision = (head.revision.toLong() + 1).toString(), headCounter = (head.headCounter.toLong() + 1).toString(), fingerprint = CloudCatalogCodec.fingerprint(catalog))
    }
    fun catalog(): CloudCatalog = if (initialized) CloudPages.decode(pages).catalog else CloudCatalog()
    fun rewind() {
        head = head.copy(headCounter = (head.headCounter.toLong() - 1).coerceAtLeast(0).toString())
    }
    fun sequence(device: String = "device-a"): Long = devices.getValue(device).resolved
    fun pending(device: String = "device-a"): Boolean = devices.getValue(device).stage != null
    private fun status(): Map<String, Any?> = linkedMapOf(
        "head" to CloudApplyPlanJson.head(head),
        "initialized" to initialized,
        "pages" to pages.size,
        "bytes" to pages.sumOf { CloudWireJson.bytes(it.body) },
        "manifest" to CloudPages.manifest(pages)
    )
    private fun receipt(device: Device, sequence: String, digest: String): Map<String, Any?>? {
        val old = device.receipts[sequence]
        if (old != null && old["digest"] != digest) throw CloudRemoteException("REPLAY_MISMATCH", 409)
        return old
    }
    fun replacement(token: String, body: Map<String, Any?>, epoch: String): JsonObject = synchronized(this) {
        val id = token.substringBefore('.')
        CloudWireJson.obj(CloudWireJson.encode(handle(id, devices.getValue(id), "/v1/uploads/publish", body, epoch)))
    }
    private fun handle(id: String, d: Device, path: String, body: Map<String, Any?>, replacementEpoch: String? = null): Map<String, Any?> {
        if (path in setOf("/v1/connect", "/v1/head")) {
            return linkedMapOf(
                "protocol" to 1,
                "device" to id,
                "scope" to mapOf("service" to scope.service, "library" to scope.library)
            ) + status() + mapOf(
                "limits" to mapOf("maxCatalogBytes" to maxBytes, "maxPages" to maxPages),
                "deviceState" to mapOf("resolvedSequence" to d.resolved.toString(), "upload" to d.stage?.let { mapOf("sequence" to it.header["sequence"], "digest" to it.header["digest"], "status" to if (it.sealed) "SEALED" else "UPLOADING") }),
                "capabilities" to listOf("catalog.read", "catalog.initialize", "catalog.sync", "operation.retire")
            )
        }
        if (path == "/v1/pages") {
            if (CloudWireJson.head(body["head"]) != head) throw CloudRemoteException("CLOUD_CHANGED", 409)
            val cursor = body["cursor"] as? String
            val matching = pages.filter { cursor == null || it.id > cursor }.take(9)
            return status() + mapOf("data" to matching.take(8).map { mapOf("id" to it.id, "body" to it.body) }, "nextCursor" to if (matching.size > 8) matching[7].id else null)
        }
        val sequence = body["sequence"] as String
        if (path == "/v1/uploads/start") {
            val digest = body["digest"] as String
            receipt(d, sequence, digest)?.let { return mapOf("receipt" to it) }
            if (CloudWireJson.head(body["before"]) != head) throw CloudRemoteException("CLOUD_CHANGED", 409)
            if ((body["mode"] == "initialize") == initialized) throw CloudRemoteException("INITIALIZATION_STATE_CHANGED", 409)
            if (d.stage != null) {
                check(d.stage!!.header == body) { "FAKE_REPLAY_MISMATCH" }
                return stageStatus(d.stage!!)
            }
            check(sequence.toLong() == d.resolved + 1)
            d.stage = Stage(body.toMap())
            return stageStatus(d.stage!!)
        }
        if (path == "/v1/uploads/chunk") {
            val s = d.stage ?: throw CloudRemoteException("UPLOAD_NOT_FOUND", 404)
            check(sequence == s.header["sequence"])
            val index = (body["index"] as Number).toInt()
            val changes = (body["changes"] as List<*>).map { raw ->
                val v = raw as Map<*, *>
                CloudPages.Change(v["id"] as String, v["body"] as String?)
            }
            s.chunks[index]?.let { check(it == changes) } ?: run {
                check(!s.sealed)
                s.chunks[index] = changes
            }
            return stageStatus(s)
        }
        val digest = body["digest"] as String
        receipt(d, sequence, digest)?.let { return mapOf("receipt" to it) }
        if (path == "/v1/uploads/retire") {
            check(sequence.toLong() == d.resolved + 1)
            d.stage?.let { check(it.header["digest"] == digest) }
            val r = mapOf("sequence" to sequence, "digest" to digest, "outcome" to "RETIRED", "head" to CloudApplyPlanJson.head(head))
            d.resolved++
            d.receipts[sequence] = r
            d.stage = null
            return mapOf("receipt" to r)
        }
        val stage = d.stage ?: throw CloudRemoteException("UPLOAD_NOT_FOUND", 404)
        check(stage.header["sequence"] == sequence && stage.header["digest"] == digest)
        if (path == "/v1/uploads/status") return stageStatus(stage)
        if (path == "/v1/uploads/seal") {
            check(stage.chunks.size == (stage.header["chunkCount"] as Number).toInt())
            stage.sealed = true
            return stageStatus(stage)
        }
        if (path == "/v1/uploads/publish") {
            check(stage.sealed)
            if (CloudWireJson.head(stage.header["before"]) != head) throw CloudRemoteException("CLOUD_CHANGED", 409)
            val map = pages.associateBy { it.id }.toMutableMap()
            stage.chunks.toSortedMap().values.flatten().forEach { if (it.body == null) map.remove(it.id) else map[it.id] = CloudPages.Page(it.id, it.body) }
            val target = map.values.sortedBy { it.id }
            val decoded = CloudPages.decode(target)
            check(CloudCatalogCodec.fingerprint(decoded.catalog) == stage.header["targetFingerprint"])
            pages = target
            initialized = true
            if (stage.header["mode"] == "replace") check(replacementEpoch != null)
            head = head.copy(epoch = replacementEpoch ?: head.epoch, revision = if (replacementEpoch != null) "1" else (head.revision.toLong() + 1).toString(), headCounter = (head.headCounter.toLong() + 1).toString(), fingerprint = stage.header["targetFingerprint"] as String)
            val r = mapOf("sequence" to sequence, "digest" to digest, "outcome" to "COMMITTED", "head" to CloudApplyPlanJson.head(head))
            d.resolved++
            d.receipts[sequence] = r
            d.stage = null
            return mapOf("receipt" to r)
        }
        error("UNKNOWN_FAKE_ROUTE")
    }
    private fun stageStatus(s: Stage): Map<String, Any?> = mapOf("sequence" to s.header["sequence"], "digest" to s.header["digest"], "status" to if (s.sealed) "SEALED" else "UPLOADING")
}
