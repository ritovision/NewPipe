/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.grack.nanojson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.concurrent.withLock
import org.schabi.newpipe.custom.tagging.TagRuntime

/** Explicit, frozen replacement. No scheduler or ordinary sync path invokes this controller. */
internal class CloudReplacementController(
    context: Context,
    private val sync: CloudSyncController = CloudSyncController(context),
    private val apiFactory: (String, String, () -> Unit) -> CloudRecoveryApi = { origin, token, admit -> CloudHttpClient(origin, token, admit) }
) {
    data class Status(
        val attempt: JsonObject?,
        val cooldownUntil: Long,
        val archives: List<CloudRecoveryController.Archive>,
        val pending: Boolean,
        val allowed: Boolean
    )
    class Preview(
        val id: String,
        val generation: String,
        val expires: Long,
        val before: CloudNativeSnapshot,
        val outgoing: CloudDownload,
        val upload: CloudPages.Upload,
        val manifest: CloudRecoveryFormat.Manifest,
        val approval: String,
        val nextEpoch: String,
        val recoverySequence: String,
        val evicted: CloudRecoveryController.Archive?,
        val retained: List<CloudRecoveryController.Archive>
    )
    private val connection = sync.connection
    private val root = File(connection.directory, "remote-replacement")
    private val marker = AtomicFile(File(root, "pending.json"))
    private val candidate = File(root, "candidate.payload")
    private val payload = File(root, "outgoing.payload")
    private val incoming = CloudTransferStore(root)
    private val recovery = CloudRecoveryController(context, sync, apiFactory)
    private val lock = CloudSyncController.operationLock(connection.directory)
    private fun <T> operation(body: () -> T): T {
        check(lock.tryLock()) { "CLOUD_OPERATION_BUSY" }
        var admittedGeneration: String? = null
        try {
            admittedGeneration = connection.read()?.generation
            check(!connection.detachPending()) { "CLOUD_DETACH_RECOVERY_REQUIRED" }
            check(!connection.reconnectPending()) { "CLOUD_RECONNECT_RECOVERY_REQUIRED" }
            return body()
        } catch (error: Exception) {
            if (admittedGeneration != null) sync.recordFailure(error, admittedGeneration)
            throw error
        } finally {
            lock.unlock()
        }
    }
    private fun connected() = checkNotNull(connection.read()) { "CLOUD_NOT_CONNECTED" }
    private fun api(s: CloudConnectionStore.State) = apiFactory(s.origin, connection.token(s)) { connection.requireCurrent(s.generation) }
    private fun markerExists() = marker.baseFile.exists() || File(marker.baseFile.path + ".bak").exists()
    fun hasPending() = hasPending(connection.directory)
    fun pendingPhase(): String? = operation {
        val s = connected()
        if (markerExists()) pending(s).getString("phase") else observedPhase(connection.directory, s)
    }
    private fun pending(s: CloudConnectionStore.State): JsonObject {
        val p = CloudWireJson.obj(CloudTransferStore.read(marker, 16384).toString(Charsets.UTF_8))
        check(
            p.getInt("version") == 1 && p.getString("origin") == s.origin && p.getString("device") == s.device &&
                p.getString("service") == s.scope.service && p.getString("library") == s.scope.library
        ) { "REPLACEMENT_LOCAL_AUTHORITY_CHANGED" }
        check(
            p.getString("phase") in setOf("ACTIVE", "STALE") && CloudRecoveryFormat.UUID.matches(p.getString("approvalId")) &&
                CloudRecoveryFormat.UUID.matches(p.getString("nextEpoch")) && CloudCounter.parse(p.getString("recoverySequence")) > 0
        ) { "REPLACEMENT_LOCAL_RECORD_CORRUPT" }
        val h = p.getObject("incoming")
        check(
            h.getString("mode") == "replace" && CloudCounter.parse(h.getString("sequence")) > 0 &&
                Regex("[0-9a-f]{64}").matches(h.getString("digest"))
        ) { "REPLACEMENT_LOCAL_RECORD_CORRUPT" }
        val m = CloudRecoveryFormat.read(p["manifest"])
        check(m.source?.head == CloudWireJson.head(h["before"]) && m.digest() == p.getString("outgoingDigest")) { "REPLACEMENT_LOCAL_RECORD_CORRUPT" }
        return p
    }
    private fun write(p: Map<String, Any?>) = CloudTransferStore.write(marker, CloudWireJson.encode(p).toByteArray(Charsets.UTF_8))
    private fun remote(api: CloudRecoveryApi): JsonObject = api.post("/v1/replacement/status", emptyMap())
    private fun attempt(o: JsonObject): JsonObject? = (o["attempt"] as? JsonObject)?.also {
        check(
            CloudRecoveryFormat.UUID.matches(it.getString("attemptId")) && CloudRecoveryFormat.UUID.matches(it.getString("archiveId")) &&
                it.getString("state") in setOf("ACTIVE", "STALE", "DONE", "CANCELED")
        ) { "INVALID_REPLACEMENT_STATUS" }
    }
    private fun inspectLocked(s: CloudConnectionStore.State): Status {
        val head = sync.inspect()
        val allowed = "catalog.replace" in head.capabilities
        val remote = if (allowed || hasPending() || head.replacement != null) remote(api(s)) else JsonObject()
        val own = attempt(remote)
        return Status(
            own,
            (remote["cooldownUntil"] as? String)?.toLong() ?: 0L,
            recovery.inspect().archives,
            hasPending() || own?.getString("state") in setOf("ACTIVE", "STALE"),
            allowed
        )
    }
    fun inspect(): Status = operation { inspectLocked(connected()) }
    fun prepare(): Preview = operation {
        check(!sync.hasPending() && !recovery.hasPending()) { "RESOLVE_PENDING_UPLOAD_BEFORE_REPLACEMENT" }
        val initial = connected()
        val old = if (markerExists()) pending(initial) else null
        check(old == null && !incoming.hasPending() || old?.getString("phase") == "STALE") { "CLOUD_REPLACEMENT_PENDING" }
        val existing = attempt(remote(api(initial)))
        check(existing?.getString("state") != "ACTIVE") { "CLOUD_REPLACEMENT_PENDING" }
        if (old != null) check(existing?.getString("attemptId") == old.getString("approvalId") && existing.getString("state") == "STALE") { "REPLACEMENT_IDENTITY_CHANGED" }
        // Preview/cancel does not alter the owner's mode. Durable pending
        // replacement identity blocks automatic sync once confirmation begins.
        val base = sync.prepare(false, forReplacement = true)
        val s = connected()
        check("catalog.replace" in base.remote.capabilities) { "REPLACEMENT_NOT_AUTHORIZED" }
        val outgoing = checkNotNull(base.download) { "CLOUD_LIBRARY_EMPTY_NOT_INITIALIZED" }
        val capacity = recovery.inspect()
        check(capacity.operation == null) { "RECOVERY_UPLOAD_PENDING" }
        check(root.isDirectory || root.mkdirs()) { "REPLACEMENT_LOCAL_STORAGE_UNAVAILABLE" }
        check(!candidate.exists() || candidate.delete()) { "REPLACEMENT_LOCAL_CLEANUP_FAILED" }
        try {
            val pages = CloudPages.encode(base.before.catalog, outgoing.pages, base.before.hints.values)
            check(pages.size <= base.remote.maxPages && pages.sumOf { CloudWireJson.bytes(it.body).toLong() } <= base.remote.maxBytes) { "CLOUD_SERVER_LIBRARY_LIMIT" }
            val changes = CloudPages.changes(outgoing.pages, pages)
            check(changes.isNotEmpty()) { "REPLACEMENT_NO_CHANGES" }
            val upload = CloudPages.upload(
                "replace",
                Math.addExact(CloudCounter.parse(base.remote.sequence), 1).toString(),
                base.remote.head,
                base.before.fingerprint,
                changes
            )
            check((upload.header["payloadBytes"] as Long) <= base.remote.maxBytes) { "CLOUD_SERVER_TRANSFER_LIMIT" }
            val source = CloudRecoveryFormat.writeCatalog(candidate, outgoing)
            val manifest = CloudRecoveryFormat.fromFile(candidate, source, null, capacity.maxBytes)
            val retryId = existing?.takeIf { it.getString("state") == "STALE" }?.getString("attemptId")
            val approval = api(s).post(
                "/v1/replacement/preview",
                linkedMapOf(
                    "incoming" to upload.header,
                    "manifest" to manifest.wire(),
                    "retryAttemptId" to retryId
                )
            )
            check(
                CloudWireJson.encode(approval["incoming"]) == CloudWireJson.encode(upload.header) &&
                    CloudRecoveryFormat.read(approval["manifest"]) == manifest && approval.getString("digest") == manifest.digest()
            ) { "REPLACEMENT_PREVIEW_MISMATCH" }
            val evicted = (approval["evictArchiveId"] as? String)?.let { id -> capacity.archives.single { it.id == id } }
            val retained = capacity.archives.filter { it.id != evicted?.id }
            check(approval.getArray("remainingArchives").map { it as String } == retained.map { it.id }) { "REPLACEMENT_PREVIEW_MISMATCH" }
            Preview(
                UUID.randomUUID().toString(), s.generation, SystemClock.elapsedRealtime() + 9 * 60_000,
                base.before, outgoing, upload, manifest,
                approval.getString("approvalId").also { check(CloudRecoveryFormat.UUID.matches(it)) },
                approval.getString("nextEpoch").also { check(CloudRecoveryFormat.UUID.matches(it)) },
                Math.addExact(CloudCounter.parse(capacity.sequence), 1).toString(), evicted, retained
            )
        } catch (error: Throwable) {
            candidate.delete()
            throw error
        }
    }
    fun cancelPreview() = operation { check(!candidate.exists() || candidate.delete()) { "REPLACEMENT_LOCAL_CLEANUP_FAILED" } }
    fun confirm(p: Preview): Status = operation {
        val s = connection.requireCurrent(p.generation)
        check(SystemClock.elapsedRealtime() < p.expires) { "CLOUD_PREVIEW_EXPIRED" }
        check(!sync.hasPending() && !recovery.hasPending()) { "RESOLVE_PENDING_UPLOAD_BEFORE_REPLACEMENT" }
        check(!markerExists() || pending(s).getString("phase") == "STALE") { "CLOUD_REPLACEMENT_PENDING" }
        check(CloudRecoveryFormat.fromFile(candidate, p.manifest.source, null) == p.manifest) { "RECOVERY_LOCAL_FILE_CHANGED" }
        TagRuntime.lock.withLock {
            connection.requireCurrent(p.generation)
            sync.requireReplacementSnapshot(p.before)
            if (markerExists()) incoming.clear()
            check(!incoming.hasPending()) { "CLOUD_REPLACEMENT_PENDING" }
            Files.move(candidate.toPath(), payload.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            incoming.save(CloudTransferStore.Pending(s.incarnation, s.generation, p.upload))
            write(
                linkedMapOf(
                    "version" to 1, "phase" to "ACTIVE", "origin" to s.origin, "device" to s.device,
                    "service" to s.scope.service, "library" to s.scope.library, "incarnation" to s.incarnation,
                    "approvalId" to p.approval, "nextEpoch" to p.nextEpoch, "recoverySequence" to p.recoverySequence,
                    "incoming" to p.upload.header, "manifest" to p.manifest.wire(), "outgoingDigest" to p.manifest.digest()
                )
            )
        }
        resumeLocked(s, p.before)
    }
    fun resume(): Status = operation { resumeLocked(connected(), null) }
    private fun reference(p: JsonObject) = mapOf("sequence" to p.getObject("incoming").getString("sequence"), "digest" to p.getObject("incoming").getString("digest"))
    private fun resolved(api: CloudRecoveryApi, p: JsonObject): JsonObject? = try {
        api.post("/v1/uploads/status", reference(p))["receipt"] as? JsonObject
    } catch (error: CloudRemoteException) {
        if (error.code != "UPLOAD_NOT_FOUND" || error.httpStatus != 404) throw error
        null
    }
    private fun finish(p: JsonObject, receipt: JsonObject) {
        if (receipt.getString("outcome") == "COMMITTED") check(CloudWireJson.head(receipt["head"]).epoch == p.getString("nextEpoch")) { "CLOUD_RECEIPT_CONTENT_MISMATCH" }
        sync.recordReplacementReceipt(p.getObject("incoming"), receipt, p.getString("incarnation"))
        // A canceled, unadmitted retry can leave an older STALE attempt on the server.
        // Persist that authority before deleting the last local intent; failures keep it retryable.
        sync.inspect()
        clearPending()
    }
    private fun resumeLocked(s: CloudConnectionStore.State, witness: CloudNativeSnapshot?): Status {
        check(markerExists()) { "REPLACEMENT_SOURCE_FILE_UNAVAILABLE" }
        val p = pending(s)
        check(p.getString("phase") == "ACTIVE") { "REPLACEMENT_FRESH_PREVIEW_REQUIRED" }
        val api = api(s)
        resolved(api, p)?.let {
            if (it.getString("outcome") == "COMMITTED") {
                finish(p, it)
                return inspectLocked(connected())
            }
            error("REPLACEMENT_RETIRED_RESOLVE_ATTEMPT")
        }
        check(p.getString("incarnation") == sync.replacementIncarnation()) { "REPLACEMENT_LOCAL_IDENTITY_CHANGED" }
        val upload = checkNotNull(incoming.load()) { "REPLACEMENT_SOURCE_FILE_UNAVAILABLE" }
        check(upload.incarnation == p.getString("incarnation") && CloudWireJson.encode(upload.upload.header) == CloudWireJson.encode(p["incoming"])) { "REPLACEMENT_LOCAL_RECORD_CORRUPT" }
        val existing = attempt(remote(api))
        if (existing?.getString("attemptId") != p.getString("approvalId")) {
            // A fresh process cannot infer that a lost reserve was sent. Fence it and re-preview.
            check(witness != null) { "REPLACEMENT_NOT_ADMITTED_REVIEW_AGAIN" }
            sync.requireReplacementSnapshot(witness)
        }
        val reserved = api.post("/v1/replacement/reserve", mapOf("approvalId" to p.getString("approvalId"), "recoverySequence" to p.getString("recoverySequence")))
        val a = checkNotNull(attempt(reserved)) { "INVALID_REPLACEMENT_STATUS" }
        check(
            a.getString("attemptId") == p.getString("approvalId") && a.getString("nextEpoch") == p.getString("nextEpoch") &&
                CloudWireJson.encode(a["incoming"]) == CloudWireJson.encode(p["incoming"])
        ) { "REPLACEMENT_IDENTITY_CHANGED" }
        uploadOutgoing(api, p, reserved.getObject("operation"))
        val started = api.post("/v1/uploads/start", upload.upload.header)
        var receipt = started["receipt"] as? JsonObject
        if (receipt == null) {
            upload.upload.chunks.forEachIndexed { index, chunk ->
                api.post("/v1/uploads/chunk", mapOf("sequence" to upload.upload.header["sequence"], "index" to index, "changes" to chunk))
            }
            receipt = api.post("/v1/uploads/seal", upload.upload.reference)["receipt"] as? JsonObject
        }
        if (receipt == null) receipt = api.post("/v1/replacement/publish", mapOf("attemptId" to p.getString("approvalId")))["receipt"] as? JsonObject
        val terminal = checkNotNull(receipt) { "INVALID_PUBLICATION_RECEIPT" }
        check(terminal.getString("outcome") == "COMMITTED") { "REPLACEMENT_RETIRED_RESOLVE_ATTEMPT" }
        finish(p, terminal)
        return inspectLocked(connected())
    }
    private fun uploadOutgoing(api: CloudRecoveryApi, p: JsonObject, first: JsonObject) {
        var op = first
        val m = CloudRecoveryFormat.read(p["manifest"])
        repeat(100) {
            check(
                op.getString("approvalId") == p.getString("approvalId") && op.getString("sequence") == p.getString("recoverySequence") &&
                    op.getString("digest") == p.getString("outgoingDigest") && CloudRecoveryFormat.read(op["manifest"]) == m
            ) { "RECOVERY_OPERATION_MISMATCH" }
            val ref = mapOf("sequence" to op.getString("sequence"), "archiveId" to op.getString("archiveId"))
            check(CloudRecoveryFormat.UUID.matches(ref.getValue("archiveId"))) { "RECOVERY_OPERATION_MISMATCH" }
            when (op.getString("state")) {
                "DONE" -> return

                "CANCELED", "ABORTING" -> error("REPLACEMENT_RETIRED_RESOLVE_ATTEMPT")

                "UPLOADING" -> {
                    val next = CloudWireJson.integer(op["nextPart"], 1, (m.parts.size + 1).toLong()).toInt()
                    op = if (next <= m.parts.size) {
                        api.uploadRecoveryPart(ref, next, CloudRecoveryFormat.part(payload, m, next))
                    } else {
                        api.post("/v1/recovery/complete", ref)
                    }
                }

                else -> op = api.post("/v1/recovery/advance", ref)
            }
        }
        error("RECOVERY_STEP_LIMIT")
    }
    fun resolveForRetry(): Status = operation {
        val s = connected()
        check(markerExists()) { "REPLACEMENT_SOURCE_FILE_UNAVAILABLE" }
        val p = pending(s)
        val api = api(s)
        val response = api.post("/v1/replacement/settle", mapOf("attemptId" to p.getString("approvalId"), "keepForRetry" to true))
        val receipt = response["receipt"] as? JsonObject ?: checkNotNull(resolved(api, p)) { "INVALID_RETIREMENT_RECEIPT" }
        if (receipt.getString("outcome") == "COMMITTED") {
            finish(p, receipt)
        } else {
            check(attempt(response)?.getString("state") == "STALE" && attempt(response)?.getString("attemptId") == p.getString("approvalId")) { "REPLACEMENT_IDENTITY_CHANGED" }
            sync.recordReplacementReceipt(p.getObject("incoming"), receipt, p.getString("incarnation"))
            p["phase"] = "STALE"
            write(p)
            TagRuntime.lock.withLock { incoming.clear() }
            check(!payload.exists() || payload.delete()) { "REPLACEMENT_LOCAL_CLEANUP_FAILED" }
        }
        inspectLocked(connected())
    }
    private fun finishCancellation(api: CloudApi, path: String, body: Map<String, Any?>, id: String): JsonObject {
        repeat(4) {
            val response = api.post(path, body)
            val current = attempt(response)
            check(current == null || current.getString("attemptId") == id) { "REPLACEMENT_IDENTITY_CHANGED" }
            if (response["cleanupPending"] == true) {
                check(current != null) { "REPLACEMENT_IDENTITY_CHANGED" }
            } else {
                check(current == null || current.getString("state") in setOf("CANCELED", "DONE")) { "REPLACEMENT_CLEANUP_UNCONFIRMED" }
                return response
            }
        }
        // Preserve the durable local marker and exact remote identity for later continuation.
        throw CloudRemoteException("REPLACEMENT_CLEANUP_PENDING", 503)
    }
    fun cancel(): Status = operation {
        val s = connected()
        val api = api(s)
        if (markerExists()) {
            val p = pending(s)
            val response = finishCancellation(api, "/v1/replacement/abandon", mapOf("approvalId" to p.getString("approvalId"), "recoverySequence" to p.getString("recoverySequence")), p.getString("approvalId"))
            val receipt = response["receipt"] as? JsonObject ?: checkNotNull(resolved(api, p)) { "INVALID_RETIREMENT_RECEIPT" }
            finish(p, receipt)
        } else {
            val a = attempt(remote(api))
            if (a?.getString("state") in setOf("ACTIVE", "STALE")) {
                val response = finishCancellation(api, "/v1/replacement/settle", mapOf("attemptId" to a!!.getString("attemptId"), "keepForRetry" to false), a.getString("attemptId"))
                val h = a.getObject("incoming")
                val receipt = response["receipt"] as? JsonObject ?: api.post("/v1/uploads/status", mapOf("sequence" to h.getString("sequence"), "digest" to h.getString("digest")))["receipt"] as? JsonObject
                sync.recordReplacementReceipt(h, checkNotNull(receipt) { "INVALID_RETIREMENT_RECEIPT" }, null)
            }
            // Resolve any observed server authority before removing orphan staging files.
            sync.inspect()
            clearPending()
        }
        inspectLocked(connected())
    }
    private fun clearPending() {
        TagRuntime.lock.withLock { incoming.clear() }
        check(!payload.exists() || payload.delete()) { "REPLACEMENT_LOCAL_CLEANUP_FAILED" }
        marker.delete()
        check(!markerExists()) { "REPLACEMENT_LOCAL_CLEANUP_FAILED" }
    }
    companion object {
        private fun observed(directory: File) = AtomicFile(File(directory, "remote-replacement/observed.json"))
        private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
        private fun observedPhase(directory: File, s: CloudConnectionStore.State): String? {
            val file = observed(directory)
            if (!exists(file)) return null
            val o = CloudWireJson.obj(CloudTransferStore.read(file, 4096).toString(Charsets.UTF_8))
            check(
                o.getInt("version") == 1 && o.getString("origin") == s.origin && o.getString("device") == s.device &&
                    o.getString("service") == s.scope.service && o.getString("library") == s.scope.library
            ) { "REPLACEMENT_LOCAL_AUTHORITY_CHANGED" }
            check(CloudRecoveryFormat.UUID.matches(o.getString("attemptId")) && o.getString("state") in setOf("ACTIVE", "STALE")) { "REPLACEMENT_LOCAL_RECORD_CORRUPT" }
            return o.getString("state")
        }

        /** Called only for a current, authenticated and rollback-checked device status. */
        fun observeRemote(directory: File, s: CloudConnectionStore.State, attempt: JsonObject?) {
            check(TagRuntime.lock.isHeldByCurrentThread) { "REPLACEMENT_OBSERVATION_REQUIRES_BARRIER" }
            val file = observed(directory)
            if (attempt == null) {
                file.delete()
                check(!exists(file)) { "REPLACEMENT_LOCAL_CLEANUP_FAILED" }
                return
            }
            val id = attempt.getString("attemptId")
            val state = attempt.getString("state")
            check(CloudRecoveryFormat.UUID.matches(id) && state in setOf("ACTIVE", "STALE")) { "INVALID_REPLACEMENT_STATUS" }
            val bytes = CloudWireJson.encode(
                linkedMapOf(
                    "version" to 1,
                    "origin" to s.origin,
                    "device" to s.device,
                    "service" to s.scope.service,
                    "library" to s.scope.library,
                    "attemptId" to id,
                    "state" to state
                )
            ).toByteArray(Charsets.UTF_8)
            if (!exists(file) || !CloudTransferStore.read(file, 4096).contentEquals(bytes)) CloudTransferStore.write(file, bytes)
        }
        fun hasPending(directory: File): Boolean {
            val root = File(directory, "remote-replacement")
            return listOf("pending.json", "pending.json.bak", "outbox/manifest.json", "outbox/manifest.json.bak", "observed.json", "observed.json.bak").any { File(root, it).exists() }
        }
    }
}
