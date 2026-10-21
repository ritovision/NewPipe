/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.preference.PreferenceManager
import com.grack.nanojson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.schabi.newpipe.custom.tagging.TagBackupCoordinator
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.streams.io.StoredFileHelper

/** Actual app orchestration. Network runs outside native writer locks; confirmation is exact and expiring. */
internal class CloudSyncController(
    private val context: Context,
    val connection: CloudConnectionStore = CloudConnectionStore(context.applicationContext),
    private val repository: () -> TagRepository = { TagRuntime.get(context) },
    private val apiFactory: (String, String, () -> Unit) -> CloudApi = { origin, token, admit -> CloudHttpClient(origin, token, admit) },
    private val safetyBackup: ((CloudNativeSnapshot) -> Unit)? = null,
    private val reconnectCheckpoint: ((String) -> Unit)? = null,
    private val forgetCheckpoint: ((String) -> Unit)? = null,
    private val detachCheckpoint: ((String) -> Unit)? = null
) {
    enum class Direction { TO_DEVICE, INITIALIZE_CLOUD }
    class Preview(
        val id: String,
        val generation: String,
        val expires: Long,
        val before: CloudNativeSnapshot,
        val remote: CloudRemoteStatus,
        val download: CloudDownload?,
        val direction: Direction,
        val enableAfter: Boolean
    ) {
        val target: CloudCatalog get() = download?.decoded?.catalog ?: before.catalog
        val delta: CloudDelta get() = if (direction == Direction.TO_DEVICE) CloudCatalogCodec.diff(before.catalog, target) else CloudCatalogCodec.diff(CloudCatalog(), target)
    }
    private val transfers = CloudTransferStore(connection.directory)
    private val lock = operationLock(connection.directory)
    private inline fun <T> operation(recoverReconnection: Boolean = false, stopOnAuthorityFailure: Boolean = true, body: () -> T): T {
        check(lock.tryLock()) { "CLOUD_OPERATION_BUSY" }
        var admittedGeneration: String? = null
        try {
            admittedGeneration = connection.read()?.generation
            check(recoverReconnection || !connection.reconnectPending()) { "CLOUD_RECONNECT_RECOVERY_REQUIRED" }
            check(recoverReconnection || !connection.detachPending()) { "CLOUD_DETACH_RECOVERY_REQUIRED" }
            return body()
        } catch (error: Exception) {
            if (stopOnAuthorityFailure && admittedGeneration != null) recordFailure(error, admittedGeneration)
            throw error
        } finally {
            lock.unlock()
        }
    }
    fun connect(rawOrigin: String, token: String): CloudConnectionStore.State = operation(stopOnAuthorityFailure = false) {
        check(connection.read() == null) { "FORGET_EXISTING_CONNECTION_FIRST" }
        val origin = CloudHttpClient.normalizeOrigin(rawOrigin)
        require(Regex("[A-Za-z0-9_-]{1,128}\\.[0-9a-f]{64}").matches(token)) { "INVALID_DEVICE_TOKEN" }
        val api = apiFactory(origin, token) { check(connection.read() == null) { "CLOUD_CONNECTION_CHANGED" } }
        val remote = CloudRemoteCatalog.status(api)
        check(remote.device == token.substringBefore('.')) { "CLOUD_DEVICE_MISMATCH" }
        connection.requireFreshDevice(origin, remote.scope, remote.device)
        check(remote.upload == null && remote.replacement == null) { "CLOUD_TOKEN_HAS_UNRESOLVED_UPLOAD" }
        val r = repository()
        r.definitions()
        val snapshot = TagRuntime.lock.withLock {
            val existing = CloudIdentityStore.context(r.store.readableDatabase)
            if (existing == null) {
                CloudNativeCatalog(r).enroll(remote.scope)
            } else {
                check(existing.scope == remote.scope) { "CLOUD_OTHER_SCOPE_REQUIRES_FORGET" }
                CloudNativeCatalog(r).capture(remote.scope, existing.incarnation)
            }
        }
        connection.create(origin, token, remote.device, remote.scope, snapshot.identities.incarnation, remote.head)
    }

    /** Explicit recovery enrollment: no old upload is resumed or silently discarded. */
    fun reconnect(token: String): CloudConnectionStore.State = operation(stopOnAuthorityFailure = false) {
        check(!connection.reconnectPending()) { "CLOUD_RECONNECT_RECOVERY_REQUIRED" }
        val s = checkNotNull(connection.read()) { "CLOUD_NOT_CONNECTED" }
        connection.requireCurrent(s.generation)
        require(Regex("[A-Za-z0-9_-]{1,128}\\.[0-9a-f]{64}").matches(token)) { "INVALID_DEVICE_TOKEN" }
        val remote = CloudRemoteCatalog.status(apiFactory(s.origin, token) { connection.requireCurrent(s.generation) })
        check(remote.device == token.substringBefore('.')) { "CLOUD_DEVICE_MISMATCH" }
        check(remote.scope.library == s.scope.library && remote.scope.service != s.scope.service && remote.device != s.device) { "CLOUD_RECOVERED_SCOPE_REQUIRED" }
        check(remote.initialized && remote.sequence == "0" && remote.upload == null && remote.replacement == null) { "CLOUD_FRESH_RECOVERY_TOKEN_REQUIRED" }
        val before = native(s)
        localRecovery(before)
        TagRuntime.lock.withLock {
            connection.requireCurrent(s.generation)
            sameLocal(before, native(s))
            connection.beginReconnect(s.generation, token, remote.device, remote.scope, remote.head)
            reconnectCheckpoint?.invoke("journal")
        }
        finishReconnect()
    }
    fun resumeReconnect(): CloudConnectionStore.State = operation(recoverReconnection = true) { finishReconnect() }
    private fun finishReconnect(): CloudConnectionStore.State = TagRuntime.lock.withLock {
        val p = checkNotNull(connection.pendingReconnect()) { "CLOUD_RECONNECT_NOT_PENDING" }
        check(connection.read()?.generation in setOf(p.previous.generation, p.next.generation)) { "CLOUD_CONNECTION_CHANGED" }
        val r = repository()
        CloudNativeApply.recoverLocked(r)
        val identities = checkNotNull(CloudIdentityStore.context(r.store.readableDatabase)) { "CLOUD_RECONNECT_LOCAL_IDENTITY_CHANGED" }
        check(identities.incarnation == p.previous.incarnation && identities.scope in setOf(p.previous.scope, p.next.scope)) { "CLOUD_RECONNECT_LOCAL_IDENTITY_CHANGED" }
        // A supported restore or changed native binding must still fail the ordinary capture guards.
        CloudNativeCatalog(r).capture(identities.scope, identities.incarnation)
        val archive = File(connection.directory, "retired-connections/${p.next.generation}")
        check(archive.isDirectory || archive.mkdirs()) { "CLOUD_RECONNECT_STORAGE_UNAVAILABLE" }
        for (name in listOf("outbox", "remote-recovery", "remote-replacement", "catalog-cache.json", "catalog-cache.json.bak", "catalog-cache.json.new")) {
            val source = File(connection.directory, name)
            val target = File(archive, name)
            if (source.exists()) {
                check(!target.exists()) { "CLOUD_RECONNECT_ARCHIVE_CONFLICT" }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            reconnectCheckpoint?.invoke("archived-$name")
        }
        // Commit witness removal before clearing its baseline. A crash in between
        // leaves a valid old baseline with no witness, never a lost apply journal.
        r.library.runInTransaction { r.library.openHelper.writableDatabase.execSQL("DELETE FROM rv_cloud_apply_receipt") }
        reconnectCheckpoint?.invoke("witness")
        r.store.transaction { side ->
            check(CloudApplyStore.pending(side) == null) { "LOCAL_CLOUD_RECOVERY_REQUIRED" }
            side.execSQL(
                "UPDATE cloud_catalog_context SET service=? WHERE slot=1 AND incarnation=? AND library=?",
                arrayOf<Any>(p.next.scope.service, p.next.incarnation, p.next.scope.library)
            )
            CloudApplyStore.discard(side)
        }
        reconnectCheckpoint?.invoke("metadata")
        CloudNativeCatalog(r).capture(p.next.scope, p.next.incarnation)
        connection.completeReconnect(p) { reconnectCheckpoint?.invoke("connection") }
        p.next
    }
    fun disable(): CloudConnectionStore.State? = connection.disable()
    fun forget() = operation {
        check(!connection.reconnectPending()) { "CLOUD_RECONNECT_RECOVERY_REQUIRED" }
        val previous = connection.read() ?: return@operation
        TagRuntime.lock.withLock {
            connection.requireCurrent(previous.generation)
            check(!transfers.hasPending() && !CloudRecoveryController.hasPending(connection.directory) && !CloudReplacementController.hasPending(connection.directory)) { "RESOLVE_PENDING_UPLOAD_BEFORE_FORGET" }
            val r = repository()
            CloudNativeApply.recoverLocked(r)
            // OFF is durable before any independent database commit. Retrying an
            // interrupted Forget is safe even after identity metadata was removed.
            val stopped = checkNotNull(connection.disable())
            CloudScheduler.reconcile(context)
            forgetCheckpoint?.invoke("off")
            r.library.runInTransaction {
                r.library.openHelper.writableDatabase.execSQL("DELETE FROM rv_cloud_apply_receipt")
                forgetCheckpoint?.invoke("before-witness-commit")
            }
            forgetCheckpoint?.invoke("witness")
            r.store.transaction { side ->
                check(CloudApplyStore.pending(side) == null) { "LOCAL_CLOUD_RECOVERY_REQUIRED" }
                CloudIdentityStore.discard(side)
                CloudApplyStore.discard(side)
            }
            forgetCheckpoint?.invoke("metadata")
            r.binding.read()?.let { r.binding.write(it.copy(cloudIncarnation = null, cloudPlaylistSequence = null)) }
            forgetCheckpoint?.invoke("binding")
            transfers.discardCache()
            connection.forget(stopped.generation)
            forgetCheckpoint?.invoke("connection")
        }
    }

    /** Explicit local-only escape from permanently rejected authority. This never
     * cancels a remote request or releases a server hold. Evidence is archived,
     * not deleted, and that device identity cannot be enrolled again locally.
     */
    fun detachRejectedConnection() = operation(recoverReconnection = true, stopOnAuthorityFailure = false) {
        check(!connection.reconnectPending()) { "CLOUD_RECONNECT_RECOVERY_REQUIRED" }
        if (!connection.detachPending()) {
            val old = connection.read() ?: return@operation
            check(old.authorityRejected && !old.enabled) { "CLOUD_DETACH_REQUIRES_REJECTED_AUTHORITY" }
            TagRuntime.lock.withLock {
                native(old)
                connection.beginDetach(old.generation)
                detachCheckpoint?.invoke("journal")
            }
        }
        CloudScheduler.reconcile(context)
        TagRuntime.lock.withLock {
            val p = checkNotNull(connection.pendingDetach())
            check(connection.read() == null || connection.read()!!.generation == p.previous.generation) { "CLOUD_CONNECTION_CHANGED" }
            val r = repository()
            CloudNativeApply.recoverLocked(r)
            val archive = File(connection.directory, "retired-connections/${p.archive}")
            check(archive.isDirectory || archive.mkdirs()) { "CLOUD_DETACH_STORAGE_UNAVAILABLE" }
            connection.archiveDetachedState(p, archive)
            for (name in listOf("outbox", "remote-recovery", "remote-replacement", "catalog-cache.json", "catalog-cache.json.bak", "catalog-cache.json.new")) {
                val source = File(connection.directory, name)
                val target = File(archive, name)
                if (source.exists()) {
                    check(!target.exists()) { "CLOUD_DETACH_ARCHIVE_CONFLICT" }
                    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                detachCheckpoint?.invoke("archived-$name")
            }
            r.library.runInTransaction { r.library.openHelper.writableDatabase.execSQL("DELETE FROM rv_cloud_apply_receipt") }
            detachCheckpoint?.invoke("witness")
            r.store.transaction { side ->
                check(CloudApplyStore.pending(side) == null) { "LOCAL_CLOUD_RECOVERY_REQUIRED" }
                CloudIdentityStore.discard(side)
                CloudApplyStore.discard(side)
            }
            detachCheckpoint?.invoke("metadata")
            r.binding.read()?.let { r.binding.write(it.copy(cloudIncarnation = null, cloudPlaylistSequence = null)) }
            detachCheckpoint?.invoke("binding")
            connection.completeDetach(p)
            detachCheckpoint?.invoke("connection")
        }
    }
    private fun api(s: CloudConnectionStore.State, automatic: Boolean = false): CloudApi = apiFactory(s.origin, connection.token(s)) {
        connection.requireCurrent(s.generation, automatic)
    }
    private fun native(s: CloudConnectionStore.State): CloudNativeSnapshot = CloudNativeCatalog(repository()).capture(s.scope, s.incarnation)
    private fun verifiedStatus(s: CloudConnectionStore.State, api: CloudApi): CloudRemoteStatus {
        val remote = CloudRemoteCatalog.status(api)
        check(remote.scope == s.scope && remote.device == s.device) { "CLOUD_SCOPE_MISMATCH" }
        val current = connection.requireCurrent(s.generation)
        current.highest?.let { h ->
            check(
                h.library == remote.head.library && h.serviceGeneration == remote.head.serviceGeneration &&
                    CloudCounter.parse(remote.head.headCounter) >= CloudCounter.parse(h.headCounter) &&
                    (remote.head.headCounter != h.headCounter || remote.head == h) &&
                    (remote.head.epoch != h.epoch || CloudCounter.parse(remote.head.revision) >= CloudCounter.parse(h.revision))
            ) { "CLOUD_REMOTE_ROLLBACK_SUSPECTED" }
        }
        TagRuntime.lock.withLock {
            connection.requireCurrent(s.generation)
            CloudReplacementController.observeRemote(connection.directory, s, remote.replacement)
            connection.update(s.generation) { it.copy(highest = remote.head) }
        }
        return remote
    }
    fun inspect(): CloudRemoteStatus = operation {
        val s = checkNotNull(connection.read()) { "CLOUD_NOT_CONNECTED" }
        verifiedStatus(s, api(s))
    }
    fun prepare(enableAfter: Boolean, forReplacement: Boolean = false): Preview = operation {
        check(forReplacement || !CloudReplacementController.hasPending(connection.directory)) { "CLOUD_REPLACEMENT_PENDING" }
        var s = checkNotNull(connection.read()) { "CLOUD_NOT_CONNECTED" }
        val api = api(s)
        // Explicit reconciliation discards a not-yet-committed old request, never silently resumes a detached branch.
        settle(s, api, resume = false)
        val remote = verifiedStatus(s, api)
        check(forReplacement || remote.replacement == null) { "CLOUD_REPLACEMENT_PENDING" }
        check(remote.upload == null) { "CLOUD_TOKEN_HAS_UNRESOLVED_UPLOAD" }
        val r = repository()
        r.definitions()
        val before = TagRuntime.lock.withLock {
            val existing = CloudIdentityStore.context(r.store.readableDatabase)
            if (existing == null) {
                // An explicit preview after a supported full restore is a new local enrollment.
                val fresh = CloudNativeCatalog(r).enroll(s.scope)
                s = connection.update(s.generation) { it.copy(incarnation = fresh.identities.incarnation) }
                fresh
            } else {
                native(s)
            }
        }
        val download = if (remote.initialized) download(api, remote) else null
        if (!remote.initialized) check(enableAfter && "catalog.initialize" in remote.capabilities) { "CLOUD_LIBRARY_EMPTY_NOT_INITIALIZED" }
        Preview(
            UUID.randomUUID().toString(),
            s.generation,
            SystemClock.elapsedRealtime() + 10 * 60_000,
            before,
            remote,
            download,
            if (remote.initialized) Direction.TO_DEVICE else Direction.INITIALIZE_CLOUD,
            enableAfter
        )
    }
    fun confirm(preview: Preview): CloudConnectionStore.State = operation {
        check(!CloudReplacementController.hasPending(connection.directory)) { "CLOUD_REPLACEMENT_PENDING" }
        check(SystemClock.elapsedRealtime() < preview.expires) { "CLOUD_PREVIEW_EXPIRED" }
        var s = connection.requireCurrent(preview.generation)
        val api = api(s)
        check(!transfers.hasPending()) { "CLOUD_UPLOAD_ALREADY_PENDING" }
        val remote = verifiedStatus(s, api)
        check(remote.replacement == null) { "CLOUD_REPLACEMENT_PENDING" }
        check(remote.head == preview.remote.head && remote.initialized == preview.remote.initialized && remote.manifest == preview.remote.manifest) { "CLOUD_PREVIEW_CHANGED" }
        val current = native(s)
        sameLocal(preview.before, current)
        when (preview.direction) {
            Direction.INITIALIZE_CLOUD -> check(publish(s, api, preview.before, remote, emptyList(), "initialize")) { "CLOUD_PREVIEW_CHANGED" }

            Direction.TO_DEVICE -> {
                localRecovery(preview.before)
                check(verifiedStatus(s, api).head == preview.remote.head) { "CLOUD_PREVIEW_CHANGED" }
                TagRuntime.lock.withLock {
                    connection.requireCurrent(preview.generation)
                    sameLocal(preview.before, native(s))
                    if (!preview.enableAfter) {
                        s = checkNotNull(connection.disable("Restoring once. Automatic sync is off."))
                        CloudScheduler.reconcile(context)
                    }
                    CloudNativeApply(repository()).apply(
                        preview.before,
                        preview.remote.head,
                        preview.target,
                        preview.download!!.decoded.hints,
                        reconcileOrphans = true
                    ) {
                        val admitted = connection.requireCurrent(s.generation)
                        check(preview.enableAfter || !admitted.enabled) { "CLOUD_CONNECTION_CHANGED" }
                        check(SystemClock.elapsedRealtime() < preview.expires) { "CLOUD_PREVIEW_EXPIRED" }
                    }
                }
            }
        }
        connection.update(s.generation) {
            it.copy(
                enabled = preview.enableAfter,
                generation = if (preview.enableAfter != it.enabled && preview.enableAfter) UUID.randomUUID().toString() else it.generation,
                message = if (preview.enableAfter) "Synchronized. Automatic sync is on." else "Cloud library restored once. Automatic sync remains off.",
                lastSuccess = System.currentTimeMillis()
            )
        }
    }
    fun syncNow(): String = operation {
        check(!CloudReplacementController.hasPending(connection.directory)) { "CLOUD_REPLACEMENT_PENDING" }
        val s = checkNotNull(connection.read()) { "CLOUD_NOT_CONNECTED" }
        check(s.enabled) { "ENABLE_SYNC_OR_USE_ONE_TIME_RESTORE" }
        val api = api(s, automatic = true)
        // A definitive refusal is resolved by its exact receipt before making one
        // fresh decision. Never loop indefinitely or discard an ambiguous upload.
        for (attempt in 0..1) {
            settle(s, api, resume = true)
            val remote = verifiedStatus(s, api)
            val before = native(s)
            check(remote.replacement == null) { "CLOUD_REPLACEMENT_PENDING" }
            check(remote.upload == null) { "CLOUD_TOKEN_HAS_UNRESOLVED_UPLOAD" }
            val baseline = TagRuntime.lock.withLock { CloudApplyStore.baseline(repository().store.readableDatabase) }
            check(baseline == null || baseline.incarnation == s.incarnation) { "CLOUD_BASELINE_IDENTITY_CHANGED" }
            val decision = CloudSyncPolicy.decide(CloudSyncInput(true, true, false, before.fingerprint, baseline?.acknowledgedHead, remote.head, connection.read()?.highest))
            when (decision) {
                CloudDecision.NOOP -> Unit

                CloudDecision.ADOPT_BASELINE -> acknowledge(s, remote.head, "equal-content", null)

                CloudDecision.PUSH -> {
                    check(remote.initialized) { "CLOUD_PREVIEW_REQUIRED" }
                    val cloud = download(api, remote)
                    if (!publish(s, api, before, remote, cloud.pages, "sync")) {
                        if (attempt == 0) continue
                        error("CLOUD_CONFLICT_REVIEW_REQUIRED")
                    }
                }

                CloudDecision.PULL -> {
                    val incoming = download(api, remote)
                    // Automatic apply cannot discard divergent orphan metadata; only explicit reviewed confirmation can.
                    localRecovery(before, replacePrevious = false)
                    check(verifiedStatus(s, api).head == remote.head) { "CLOUD_CHANGED" }
                    CloudNativeApply(repository()).apply(before, remote.head, incoming.decoded.catalog, incoming.decoded.hints) {
                        connection.requireCurrent(s.generation, true)
                    }
                }

                CloudDecision.CONFLICT -> error("CLOUD_CONFLICT_REVIEW_REQUIRED")

                else -> error("CLOUD_PREVIEW_REQUIRED")
            }
            connection.update(s.generation) { it.copy(message = "Synchronized.", lastSuccess = System.currentTimeMillis()) }
            return@operation "Synchronized"
        }
        error("CLOUD_CONFLICT_REVIEW_REQUIRED")
    }

    fun resolvePending(): String = operation {
        val s = checkNotNull(connection.read()) { "CLOUD_NOT_CONNECTED" }
        settle(s, api(s), resume = false)
        connection.update(s.generation) { it.copy(message = "Pending upload resolved. Local changes were kept.") }
        "Pending upload resolved"
    }
    private fun download(api: CloudApi, status: CloudRemoteStatus): CloudDownload {
        transfers.cached(status)?.let { return it }
        return CloudRemoteCatalog.download(api, status).also { transfers.cache(it) }
    }
    private fun publish(s: CloudConnectionStore.State, api: CloudApi, before: CloudNativeSnapshot, remote: CloudRemoteStatus, previous: List<CloudPages.Page>, mode: String): Boolean {
        check("catalog.sync" in remote.capabilities || mode == "initialize" && "catalog.initialize" in remote.capabilities) { "CLOUD_TOKEN_READ_ONLY" }
        val pages = CloudPages.encode(before.catalog, previous, before.hints.values)
        check(pages.size <= remote.maxPages && pages.sumOf { CloudWireJson.bytes(it.body).toLong() } <= remote.maxBytes) { "CLOUD_SERVER_LIBRARY_LIMIT" }
        val changes = CloudPages.changes(previous, pages)
        if (changes.isEmpty()) {
            acknowledge(s, remote.head, "same-pages", null)
            return true
        }
        val sequence = Math.addExact(CloudCounter.parse(remote.sequence), 1).toString()
        val upload = CloudPages.upload(mode, sequence, remote.head, before.fingerprint, changes)
        check((upload.header["payloadBytes"] as Long) <= remote.maxBytes) { "CLOUD_SERVER_TRANSFER_LIMIT" }
        val pending = CloudTransferStore.Pending(s.incarnation, s.generation, upload)
        TagRuntime.lock.withLock {
            connection.requireCurrent(s.generation)
            sameLocal(before, native(s))
            transfers.save(pending)
        }
        val receipt = sendOrRetire(api, upload)
        finishReceipt(s, pending, receipt)
        if (receipt.getString("outcome") != "COMMITTED") return false
        val head = CloudWireJson.head(receipt["head"])
        transfers.cache(
            CloudDownload(
                remote.copy(
                    head = head,
                    initialized = true,
                    pages = pages.size,
                    bytes = pages.sumOf { CloudWireJson.bytes(it.body) },
                    manifest = CloudPages.manifest(pages),
                    sequence = sequence,
                    upload = null
                ),
                pages,
                CloudPages.decode(pages)
            )
        )
        return true
    }
    private fun sendOrRetire(api: CloudApi, upload: CloudPages.Upload): JsonObject {
        try {
            return send(api, upload)
        } catch (error: CloudRemoteException) {
            val definite = error.httpStatus in setOf(409, 410) && error.code in setOf(
                "CLOUD_CHANGED",
                "INITIALIZATION_STATE_CHANGED",
                "PRECONDITION_FAILED",
                "UPLOAD_EXPIRED",
                "UPLOAD_NOT_SEALED_OR_EXPIRED"
            )
            if (!definite) throw error
            // Retirement is transactional and may instead reveal a racing COMMITTED
            // receipt. No receipt, timeout or failed retire is permission to clear.
            return api.post("/v1/uploads/retire", upload.reference)["receipt"] as? JsonObject
                ?: error("INVALID_RETIREMENT_RECEIPT")
        }
    }
    private fun send(api: CloudApi, upload: CloudPages.Upload): JsonObject {
        val started = api.post("/v1/uploads/start", upload.header)
        (started["receipt"] as? JsonObject)?.let { return it }
        upload.chunks.forEachIndexed { index, chunk ->
            api.post("/v1/uploads/chunk", mapOf("sequence" to upload.header["sequence"], "index" to index, "changes" to chunk))
        }
        val sealed = api.post("/v1/uploads/seal", upload.reference)
        (sealed["receipt"] as? JsonObject)?.let { return it }
        return api.post("/v1/uploads/publish", upload.reference)["receipt"] as? JsonObject ?: error("INVALID_PUBLICATION_RECEIPT")
    }
    private fun settle(s: CloudConnectionStore.State, api: CloudApi, resume: Boolean) {
        val pending = transfers.load() ?: return
        val before = CloudWireJson.head(pending.upload.header["before"])
        check(before.serviceGeneration == s.scope.service && before.library == s.scope.library) { "OUTBOX_SCOPE_MISMATCH" }
        val status = try {
            api.post("/v1/uploads/status", pending.upload.reference)
        } catch (error: CloudRemoteException) {
            if (error.code != "UPLOAD_NOT_FOUND" || error.httpStatus != 404) throw error
            JsonObject() // Journal can be durable before the very first upload request.
        }
        val receipt = status["receipt"] as? JsonObject ?: if (resume && pending.generation == s.generation && pending.incarnation == s.incarnation) {
            sendOrRetire(api, pending.upload)
        } else {
            api.post("/v1/uploads/retire", pending.upload.reference)["receipt"] as? JsonObject ?: error("INVALID_RETIREMENT_RECEIPT")
        }
        finishReceipt(s, pending, receipt)
    }
    private fun finishReceipt(s: CloudConnectionStore.State, pending: CloudTransferStore.Pending, receipt: JsonObject) {
        check(receipt.getString("sequence") == pending.upload.header["sequence"] && receipt.getString("digest") == pending.upload.header["digest"]) { "CLOUD_RECEIPT_MISMATCH" }
        val outcome = receipt.getString("outcome")
        check(outcome in setOf("COMMITTED", "RETIRED")) { "INVALID_RECEIPT_OUTCOME" }
        val head = CloudWireJson.head(receipt["head"])
        check(head.serviceGeneration == s.scope.service && head.library == s.scope.library) { "CLOUD_RECEIPT_SCOPE_MISMATCH" }
        if (outcome == "COMMITTED") {
            check(head.fingerprint == pending.upload.header["targetFingerprint"]) { "CLOUD_RECEIPT_CONTENT_MISMATCH" }
            acknowledge(s, head, receipt.getString("digest"), pending.incarnation)
        }
        TagRuntime.lock.withLock {
            connection.requireCurrent(s.generation)
            transfers.clear()
        }
    }
    private fun acknowledge(s: CloudConnectionStore.State, head: CloudHead, operation: String, pendingIncarnation: String?) {
        TagRuntime.lock.withLock {
            connection.requireCurrent(s.generation)
            val r = repository()
            CloudNativeApply.recoverLocked(r)
            val identities = CloudIdentityStore.context(r.store.readableDatabase)
            if (identities?.incarnation != s.incarnation || pendingIncarnation != null && pendingIncarnation != identities.incarnation) return@withLock
            check(identities.scope == s.scope) { "CLOUD_BASELINE_SCOPE_CHANGED" }
            r.store.transaction { side ->
                val old = CloudApplyStore.baseline(side)
                check(old == null || old.incarnation == s.incarnation) { "CLOUD_BASELINE_IDENTITY_CHANGED" }
                val acknowledged = old?.acknowledgedHead
                if (acknowledged == null || CloudCounter.parse(acknowledged.headCounter) <= CloudCounter.parse(head.headCounter)) {
                    if (acknowledged?.headCounter == head.headCounter) check(acknowledged == head) { "CLOUD_ROLLBACK_SUSPECTED" }
                    CloudApplyStore.accept(side, s.incarnation, old?.operation ?: operation, head)
                }
            }
        }
    }
    fun requireReplacementSnapshot(expected: CloudNativeSnapshot) = operation {
        val s = checkNotNull(connection.read()) { "CLOUD_NOT_CONNECTED" }
        sameLocal(expected, native(s))
    }
    fun replacementIncarnation(): String? = operation {
        TagRuntime.lock.withLock {
            val r = repository()
            CloudNativeApply.recoverLocked(r)
            CloudIdentityStore.context(r.store.readableDatabase)?.incarnation
        }
    }
    fun recordReplacementReceipt(header: Map<String, Any?>, receipt: JsonObject, incarnation: String?) = operation {
        val s = checkNotNull(connection.read()) { "CLOUD_NOT_CONNECTED" }
        check(receipt.getString("sequence") == header["sequence"] && receipt.getString("digest") == header["digest"]) { "CLOUD_RECEIPT_MISMATCH" }
        val head = CloudWireJson.head(receipt["head"])
        check(head.serviceGeneration == s.scope.service && head.library == s.scope.library) { "CLOUD_RECEIPT_SCOPE_MISMATCH" }
        val outcome = receipt.getString("outcome")
        check(outcome in setOf("COMMITTED", "RETIRED")) { "INVALID_RECEIPT_OUTCOME" }
        if (outcome == "COMMITTED") {
            val before = CloudWireJson.head(header["before"])
            check(
                head.fingerprint == header["targetFingerprint"] && head.epoch != before.epoch && head.revision == "1" &&
                    CloudCounter.parse(head.headCounter) == Math.addExact(CloudCounter.parse(before.headCounter), 1)
            ) { "CLOUD_RECEIPT_CONTENT_MISMATCH" }
            if (incarnation != null) acknowledge(s, head, receipt.getString("digest"), incarnation)
        }
        connection.update(s.generation) {
            val old = it.highest
            if (old != null && old.headCounter == head.headCounter) check(old == head) { "CLOUD_REMOTE_ROLLBACK_SUSPECTED" }
            it.copy(
                highest = if (old == null || CloudCounter.parse(old.headCounter) < CloudCounter.parse(head.headCounter)) head else old,
                message = if (outcome == "COMMITTED") "Cloud replaced with the approved catalog. Automatic sync remains ${if (it.enabled) "on" else "off"}." else "Replacement stopped. Local changes were kept."
            )
        }
        transfers.discardCache()
    }
    private fun sameLocal(expected: CloudNativeSnapshot, actual: CloudNativeSnapshot) {
        check(
            expected.runtimeGeneration == actual.runtimeGeneration && expected.identities == actual.identities &&
                expected.fingerprint == actual.fingerprint && expected.tagRevision == actual.tagRevision &&
                expected.nativeIds == actual.nativeIds && expected.orphaned == actual.orphaned
        ) { "CLOUD_LOCAL_PREVIEW_CHANGED" }
    }
    private fun localRecovery(before: CloudNativeSnapshot, replacePrevious: Boolean = true) {
        safetyBackup?.let {
            it(before)
            return
        }
        val directory = File(connection.directory, "recovery")
        check(directory.isDirectory || directory.mkdirs()) { "LOCAL_RECOVERY_STORAGE_UNAVAILABLE" }
        if (!replacePrevious && File(directory, "before-last-cloud-restore.zip").isFile) return
        val staging = File(directory, "pending.zip")
        if (staging.exists()) check(staging.delete()) { "LOCAL_RECOVERY_CLEANUP_FAILED" }
        check(staging.createNewFile())
        val helper = StoredFileHelper(context, null, Uri.fromFile(staging), "cloud-local-recovery")
        try {
            TagBackupCoordinator.export(context, PreferenceManager.getDefaultSharedPreferences(context), helper)
            TagBackupCoordinator.prepare(context, helper).use { check(it.directory.toFile().isDirectory) }
            Files.move(staging.toPath(), File(directory, "before-last-cloud-restore.zip").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }
    fun saveLocalRecovery(before: CloudNativeSnapshot) = operation {
        sameLocal(before, native(checkNotNull(connection.read())))
        localRecovery(before)
    }
    fun hasPending(): Boolean = transfers.hasPending()
    fun recordFailure(error: Throwable, failedGeneration: String? = null) {
        val message = CloudUserMessages.error(error)
        val rejectedIdentity = error is CloudRemoteException && (
            error.httpStatus in setOf(401, 403) && error.code in setOf("INVALID_CREDENTIAL", "AUTH_REJECTED", "AUTH_OR_LIBRARY_UNAVAILABLE") ||
                error.httpStatus == 409 && error.code in setOf("REPLAY_MISMATCH", "RECOVERY_REPLAY_MISMATCH", "CHUNK_REPLAY_MISMATCH")
            )
        val terminal = rejectedIdentity || error.message in setOf("CLOUD_SCOPE_MISMATCH", "CLOUD_DEVICE_MISMATCH", "CLOUD_REMOTE_ROLLBACK_SUSPECTED", "CLOUD_CREDENTIAL_UNAVAILABLE_RECONNECT")
        val paused = TagRuntime.lock.withLock {
            val state = connection.read() ?: return@withLock false
            if (failedGeneration != null && state.generation != failedGeneration) return@withLock false
            if (connection.reconnectPending() || connection.detachPending()) return@withLock false
            val stop = failedGeneration != null && terminal
            runCatching {
                connection.update(state.generation) {
                    if (stop) {
                        it.copy(
                            generation = UUID.randomUUID().toString(),
                            enabled = false,
                            message = message,
                            authorityRejected = it.authorityRejected || rejectedIdentity
                        )
                    } else {
                        it.copy(message = message)
                    }
                }
            }.isSuccess && stop
        }
        if (paused) CloudScheduler.reconcile(context)
    }
    companion object {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        fun operationLock(directory: File): ReentrantLock = locks.getOrPut(directory.canonicalPath) { ReentrantLock() }
    }
}

internal object CloudUserMessages {
    fun error(error: Throwable): String = when (error.message) {
        "CLOUD_DETACH_RECOVERY_REQUIRED" -> "Finish the saved local disconnect. Pending records remain archived; remote outcomes are not cleared."
        "CLOUD_FRESH_DEVICE_REQUIRED" -> "Use a fresh device token. This installation has archived unresolved work under the old device identity."
        "CLOUD_DETACH_REQUIRES_REJECTED_AUTHORITY" -> "This local escape is available only after permanent credential or device-operation identity rejection. Resolve pending work normally while authority remains valid."
        "CLOUD_DETACH_STORAGE_UNAVAILABLE", "CLOUD_DETACH_ARCHIVE_CONFLICT", "CLOUD_DETACH_RECORD_INVALID", "CLOUD_RETIRED_DEVICE_LIMIT", "CLOUD_RETIRED_DEVICE_RECORD_INVALID" -> "Local disconnect could not safely preserve its records. Keep the app data and finish recovery before connecting again."
        "CLOUD_RECONNECT_RECOVERY_REQUIRED" -> "Finish reconnecting after cloud recovery before using other cloud actions. Local edits are safe."
        "CLOUD_RECOVERED_SCOPE_REQUIRED" -> "Use a fresh device token for the recovered version of this same cloud library."
        "CLOUD_FRESH_RECOVERY_TOKEN_REQUIRED" -> "This token is not a fresh enrollment for an initialized recovered library. Ask the operator for a new token."
        "CLOUD_RECONNECT_LOCAL_IDENTITY_CHANGED", "CLOUD_RECONNECT_RECORD_INVALID", "CLOUD_RECONNECT_ARCHIVE_CONFLICT" -> "The saved reconnection no longer matches this installation. Local content and pending files have been kept for recovery."
        "CLOUD_RECONNECT_STORAGE_UNAVAILABLE" -> "Reconnection could not preserve the old pending files. Free local storage and resume reconnecting."
        "RECOVERY_CLEANUP_PENDING", "REPLACEMENT_CLEANUP_PENDING" -> "Cleanup is still pending. Continue the same cancellation; its identity and local edits are retained."
        "EXPORT_SNAPSHOT_CHANGED" -> "The local backup snapshot changed or is missing. No complete backup was produced; retry before applying cloud changes."
        "PREPARED_IMPORT_CHANGED" -> "The prepared backup changed or is missing. Prepare a fresh restore preview; installed data was not replaced."
        "CLOUD_REPLACEMENT_PENDING", "REPLACEMENT_PENDING_OR_NOT_AUTHORIZED" -> "Resolve the current cloud replacement before starting another catalog action."
        "REPLACEMENT_NOT_AUTHORIZED", "REPLACEMENT_API_DISABLED" -> "Cloud replacement is not enabled for this connection."
        "RESOLVE_PENDING_UPLOAD_BEFORE_REPLACEMENT" -> "Resolve the existing catalog or backup upload before replacing the cloud."
        "REPLACEMENT_NO_CHANGES" -> "This device already has the same cloud catalog. No replacement is needed."
        "REPLACEMENT_NOT_ADMITTED_REVIEW_AGAIN", "REPLACEMENT_LOCAL_IDENTITY_CHANGED" -> "Stop and resolve this attempt, then review a new replacement. The saved approval cannot be resumed against this local state."
        "REPLACEMENT_FRESH_PREVIEW_REQUIRED", "REPLACEMENT_RETIRED_RESOLVE_ATTEMPT", "REPLACEMENT_APPROVAL_EXPIRED" -> "Resolve this attempt, then review the current cloud again. A changed or expired approval cannot publish."
        "REPLACEMENT_RETRY_REQUIRES_SURVIVOR" -> "Keep the last verified recovery copy. Create a separate backup in a free slot before retrying this replacement."
        "REPLACEMENT_SOURCE_FILE_UNAVAILABLE", "REPLACEMENT_LOCAL_RECORD_CORRUPT" -> "The saved replacement data is unavailable or damaged. Stop and resolve the attempt before starting again."
        "REPLACEMENT_RECOVERY_NOT_READY" -> "The outgoing cloud backup is not verified yet. Resume that attempt or stop it before retrying."
        "CLOUD_CONFLICT_REVIEW_REQUIRED" -> "Both local and cloud libraries changed. Review the cloud copy before choosing which version to keep."
        "CLOUD_PREVIEW_REQUIRED", "ENABLE_SYNC_OR_USE_ONE_TIME_RESTORE" -> "Review a cloud restore or enable sync to choose the starting library."
        "CLOUD_NETWORK_UNAVAILABLE" -> "Cloud is unreachable. Local changes are safe; retry when connected."
        "CLOUD_CONNECTION_CHANGED" -> "The connection or sync setting changed. No further local changes were applied."
        "CLOUD_LOCAL_PREVIEW_CHANGED", "CLOUD_PREVIEW_CHANGED", "CLOUD_CHANGED" -> "The library changed after this preview. Refresh it before confirming."
        "CLOUD_PREVIEW_EXPIRED" -> "This preview expired. Generate a fresh comparison."
        "CLOUD_REMOTE_ROLLBACK_SUSPECTED", "CLOUD_ROLLBACK_SUSPECTED" -> "The cloud revision moved backwards or changed identity. Sync is blocked for recovery."
        "CLOUD_ORPHAN_RECONCILIATION_REQUIRED" -> "Recoverable local tags conflict with the cloud copy. Use the detailed restore preview."
        "CLOUD_SERVER_LIBRARY_LIMIT", "CLOUD_SERVER_TRANSFER_LIMIT" -> "This library exceeds the server's configured size limit. Nothing was uploaded."
        "CLOUD_API_DISABLED" -> "The Worker is connected but its catalog API is disabled."
        "REPLAY_MISMATCH", "RECOVERY_REPLAY_MISMATCH", "CHUNK_REPLAY_MISMATCH" -> "This device identity conflicts with existing cloud work. Automatic sync is off; preserve the records and use local disconnect with a fresh device token. Earlier requests may still need operator reconciliation."
        "INVALID_CREDENTIAL", "AUTH_REJECTED", "AUTH_OR_LIBRARY_UNAVAILABLE" -> "This device token was rejected or revoked. Check the connection credentials."
        "REQUEST_LIMIT" -> "Cloud request limit reached. Retry later; your local library is unchanged."
        "BUDGET_OR_MAINTENANCE_UNAVAILABLE", "MAINTENANCE_REQUIRED" -> "Cloud sync is paused by a resource limit or maintenance. Local edits are retained; retry later."
        "BUDGET_ADMISSION_UNAVAILABLE" -> "Cloud resource accounting is unavailable. Local edits are retained; retry later."
        "BUDGET_ESTIMATE_EXCEEDED", "BUDGET_ACCOUNTING_BOUND_EXCEEDED", "BUDGET_METER_UNAVAILABLE", "BUDGET_RESERVATION_BLOCKED" -> "A cloud safety limit needs operator review. Local edits and pending operations are retained; do not discard or repeat an unresolved upload."
        "BUDGET_ESTIMATE_INVALID", "BUDGET_ROUTE_UNAVAILABLE", "REQUEST_STATEMENT_BUDGET_EXCEEDED" -> "This cloud operation exceeds the configured safety limits. Local edits and pending work are retained; ask the operator to review the configuration."
        "CLOUD_LIBRARY_EMPTY_NOT_INITIALIZED" -> "The cloud has no saved catalog. Enable sync to preview uploading this device's library first."
        "CLOUD_OPERATION_BUSY" -> "Another cloud operation is running."
        "RECOVERY_API_DISABLED" -> "Cloud recovery is not enabled on this Worker."
        "RECOVERY_UPLOAD_PENDING" -> "Resume or stop the unfinished recovery upload before starting another."
        "RECOVERY_CREATION_OUTCOME_UNKNOWN", "RECOVERY_OPERATOR_CLEANUP_REQUIRED", "RECOVERY_INDEX_RECONCILIATION_REQUIRED" -> "The backup's storage state needs operator reconciliation. Its reserved space remains occupied."
        "RECOVERY_RELEASE_UNCONFIRMED" -> "The restore download finished, but its temporary protection could not be released. Check the connection and retry; protection expires after ten minutes."
        "RECOVERY_ARCHIVE_TOO_LARGE" -> "This backup exceeds the Worker's configured archive size limit. Nothing was uploaded."
        "RECOVERY_SLOT_PROTECTED" -> "A backup is protected by an active restore or cleanup. Wait for it to finish."
        "RECOVERY_SOURCE_FILE_UNAVAILABLE" -> "The original upload file is unavailable on this installation. Stop the unfinished upload to release its space safely."
        "RECOVERY_LOCAL_FILE_CHANGED", "RECOVERY_DOWNLOAD_CHECKSUM_MISMATCH", "RECOVERY_NATIVE_METADATA_MISMATCH" -> "The backup did not match its integrity record. Restore was blocked."
        "RECOVERY_APPROVAL_CHANGED", "CLOUD_ARCHIVE_SOURCE_CHANGED" -> "The cloud or retained backups changed. Cancel this attempt and review a fresh backup preview."
        "CLOUD_TOKEN_HAS_UNRESOLVED_UPLOAD" -> "This device token has an unfinished upload. Resolve it on the original installation or use a separate device token."
        "RESOLVE_PENDING_UPLOAD_BEFORE_FORGET" -> "Resolve the pending upload before forgetting this connection."
        "FORGET_EXISTING_CONNECTION_FIRST" -> "Forget the current connection before using different credentials."
        "HTTPS_WORKER_ORIGIN_REQUIRED" -> "Enter only the HTTPS Worker origin, without a path, username, query or nonstandard port."
        "INVALID_DEVICE_TOKEN" -> "Enter the Worker-issued device token, not a Cloudflare account API key."
        else -> "Cloud operation could not finish. " + (error.message?.takeIf { Regex("[A-Z0-9_:]{1,100}").matches(it) } ?: "Review the connection and local recovery state.")
    }
}
