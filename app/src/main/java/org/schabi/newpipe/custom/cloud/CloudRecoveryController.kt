/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import androidx.preference.PreferenceManager
import com.grack.nanojson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile
import org.schabi.newpipe.custom.tagging.TagBackupCoordinator
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.custom.tagging.TagStore
import org.schabi.newpipe.streams.io.StoredFileHelper

/** Explicit recovery actions only. Automatic synchronization never calls this controller. */
internal class CloudRecoveryController(
    private val context: Context,
    private val sync: CloudSyncController = CloudSyncController(context),
    private val apiFactory: (String, String, () -> Unit) -> CloudRecoveryApi = { origin, token, admit -> CloudHttpClient(origin, token, admit) },
    private val installNative: (Context, TagBackupCoordinator.Prepared, () -> Unit) -> Unit = TagBackupCoordinator::installReviewed
) {
    data class Archive(val id: String, val kind: String, val created: Long, val bytes: Int, val sourceDevice: String, val state: String, val usable: Boolean, val error: String?)
    data class Status(val archives: List<Archive>, val occupied: Int, val maxBytes: Int, val sequence: String, val operation: JsonObject?)
    class UploadPreview(
        val id: String,
        val generation: String,
        val expires: Long,
        val manifest: CloudRecoveryFormat.Manifest,
        val approval: String,
        val sequence: String,
        val evicted: Archive?,
        val retained: List<Archive>
    )
    class RestorePreview(
        val id: String,
        val generation: String,
        val expires: Long,
        val archive: Archive,
        val manifest: CloudRecoveryFormat.Manifest,
        val before: CloudNativeSnapshot,
        val decoded: CloudPages.Decoded?,
        val prepared: TagBackupCoordinator.Prepared?,
        val file: File
    ) : AutoCloseable {
        override fun close() {
            prepared?.close()
            file.delete()
        }
    }
    private val connection = sync.connection
    private val root = File(connection.directory, "remote-recovery")
    private val candidate = File(root, "candidate.payload")
    private val payload = File(root, "pending.payload")
    private val marker = AtomicFile(File(root, "pending.json"))
    private val lock = CloudSyncController.operationLock(connection.directory)
    private fun <T> operation(body: () -> T): T {
        check(lock.tryLock()) { "CLOUD_OPERATION_BUSY" }
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }
    private fun connected() = checkNotNull(connection.read()) { "CLOUD_NOT_CONNECTED" }
    private fun api(s: CloudConnectionStore.State): CloudRecoveryApi = apiFactory(s.origin, connection.token(s)) { connection.requireCurrent(s.generation) }
    private fun file(file: File) = StoredFileHelper(context, null, Uri.fromFile(file), "cloud-recovery")
    private fun storage() {
        check(root.isDirectory || root.mkdirs()) { "RECOVERY_LOCAL_STORAGE_UNAVAILABLE" }
    }
    private fun status(api: CloudRecoveryApi): Status {
        val o = api.post("/v1/recovery/status", emptyMap())
        check(o.getInt("maxSlots") == 3 && o.getInt("partBytes") == CloudRecoveryFormat.PART_BYTES) { "UNSUPPORTED_RECOVERY_FORMAT" }
        val archives = o.getArray("archives").map {
            val a = it as JsonObject
            Archive(
                a.getString("archiveId").also { id -> check(CloudRecoveryFormat.UUID.matches(id)) },
                a.getString("kind"),
                a.getString("createdAt").toLong(),
                CloudWireJson.integer(a["bytes"], 22, CloudRecoveryFormat.MAX_BYTES.toLong()).toInt(),
                a.getString("sourceDevice"),
                a.getString("state"),
                a.getBoolean("usable"),
                a["error"] as? String
            )
        }
        check(archives.size <= 3 && archives.map { it.id }.toSet().size == archives.size && o.getInt("occupiedSlots") == archives.size) { "INVALID_RECOVERY_STATUS" }
        return Status(
            archives,
            archives.size,
            CloudWireJson.integer(o["maxBytes"], 22, CloudRecoveryFormat.MAX_BYTES.toLong()).toInt(),
            o.getString("lastSequence").also { CloudCounter.parse(it) },
            o["operation"] as? JsonObject
        )
    }
    fun inspect(): Status = operation {
        val s = connected()
        sync.inspect()
        status(api(s))
    }
    fun hasPending(): Boolean = hasPending(connection.directory)
    fun prepareUpload(kind: String): UploadPreview = operation {
        check(kind in setOf("device-zip", "cloud-catalog"))
        check(!hasPending()) { "RECOVERY_UPLOAD_PENDING" }
        check(!sync.hasPending()) { "CLOUD_UPLOAD_ALREADY_PENDING" }
        val s = connected()
        val remote = sync.inspect()
        check("backup.r2" in remote.capabilities) { "RECOVERY_API_DISABLED" }
        val api = api(s)
        val current = status(api)
        check(current.operation == null) { "RECOVERY_UPLOAD_PENDING" }
        storage()
        check(!candidate.exists() || candidate.delete()) { "RECOVERY_LOCAL_CLEANUP_FAILED" }
        try {
            var source: CloudRecoveryFormat.Source? = null
            var native: CloudRecoveryFormat.Native? = null
            if (kind == "device-zip") {
                check(candidate.createNewFile())
                TagBackupCoordinator.export(context, PreferenceManager.getDefaultSharedPreferences(context), file(candidate))
                TagBackupCoordinator.prepare(context, file(candidate)).use { native = nativeMetadata(candidate, it) }
                java.io.RandomAccessFile(candidate, "rw").use { it.fd.sync() }
            } else {
                source = CloudRecoveryFormat.writeCatalog(candidate, CloudRemoteCatalog.download(api, remote))
            }
            val manifest = CloudRecoveryFormat.fromFile(candidate, source, native, current.maxBytes)
            val approval = api.post("/v1/recovery/preview", mapOf("manifest" to manifest.wire()))
            check(CloudRecoveryFormat.read(approval["manifest"]) == manifest && approval.getString("digest") == manifest.digest()) { "RECOVERY_PREVIEW_MISMATCH" }
            val evicted = (approval["evictArchiveId"] as? String)?.let { id -> current.archives.single { it.id == id } }
            val retained = current.archives.filter { it.id != evicted?.id }
            check(approval.getArray("remainingArchives").map { it as String } == retained.map { it.id }) { "RECOVERY_PREVIEW_MISMATCH" }
            UploadPreview(
                UUID.randomUUID().toString(),
                s.generation,
                SystemClock.elapsedRealtime() + 9 * 60_000,
                manifest,
                approval.getString("approvalId").also { check(CloudRecoveryFormat.UUID.matches(it)) },
                Math.addExact(CloudCounter.parse(current.sequence), 1).toString(),
                evicted,
                retained
            )
        } catch (e: Throwable) {
            candidate.delete()
            throw e
        }
    }
    fun cancelUploadPreview() = operation { check(!candidate.exists() || candidate.delete()) { "RECOVERY_LOCAL_CLEANUP_FAILED" } }
    fun confirmUpload(preview: UploadPreview): Status = operation {
        val s = connection.requireCurrent(preview.generation)
        check(SystemClock.elapsedRealtime() < preview.expires) { "CLOUD_PREVIEW_EXPIRED" }
        check(!hasPending() && !sync.hasPending()) { "RECOVERY_UPLOAD_PENDING" }
        check(CloudRecoveryFormat.fromFile(candidate, preview.manifest.source, preview.manifest.native) == preview.manifest) { "RECOVERY_LOCAL_FILE_CHANGED" }
        Files.move(candidate.toPath(), payload.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        CloudTransferStore.write(
            marker,
            CloudWireJson.encode(
                linkedMapOf(
                    "version" to 1, "origin" to s.origin, "device" to s.device, "service" to s.scope.service, "library" to s.scope.library,
                    "approvalId" to preview.approval, "sequence" to preview.sequence, "manifest" to preview.manifest.wire(), "digest" to preview.manifest.digest()
                )
            ).toByteArray(Charsets.UTF_8)
        )
        resumeLocked(s)
    }
    private fun pending(s: CloudConnectionStore.State): JsonObject {
        val p = CloudWireJson.obj(CloudTransferStore.read(marker, 16384).toString(Charsets.UTF_8))
        check(
            p.getInt("version") == 1 && p.getString("origin") == s.origin && p.getString("device") == s.device &&
                p.getString("service") == s.scope.service && p.getString("library") == s.scope.library
        ) { "RECOVERY_LOCAL_AUTHORITY_CHANGED" }
        val m = CloudRecoveryFormat.read(p["manifest"])
        check(m.digest() == p.getString("digest") && CloudRecoveryFormat.UUID.matches(p.getString("approvalId")) && CloudCounter.parse(p.getString("sequence")) > 0) { "RECOVERY_LOCAL_RECORD_CORRUPT" }
        return p
    }
    fun resume(): Status = operation { resumeLocked(connected()) }
    private fun resumeLocked(s: CloudConnectionStore.State): Status {
        val api = api(s)
        val p = if (hasPending()) pending(s) else null
        var op = if (p != null) {
            api.post("/v1/recovery/reserve", mapOf("approvalId" to p.getString("approvalId"), "sequence" to p.getString("sequence")))
        } else {
            status(api).operation ?: return status(api)
        }
        repeat(100) {
            val m = CloudRecoveryFormat.read(op["manifest"])
            check(op.getString("digest") == m.digest()) { "RECOVERY_OPERATION_MISMATCH" }
            if (p != null) {
                check(
                    op.getString("sequence") == p.getString("sequence") && op.getString("approvalId") == p.getString("approvalId") &&
                        op.getString("digest") == p.getString("digest")
                ) { "RECOVERY_OPERATION_MISMATCH" }
            }
            val ref = mapOf("sequence" to op.getString("sequence"), "archiveId" to op.getString("archiveId"))
            check(CloudRecoveryFormat.UUID.matches(ref.getValue("archiveId")))
            when (op.getString("state")) {
                "DONE", "CANCELED" -> {
                    clearPending()
                    return status(api)
                }

                "UPLOADING" -> {
                    val next = CloudWireJson.integer(op["nextPart"], 1, (m.parts.size + 1).toLong()).toInt()
                    op = if (next <= m.parts.size) {
                        check(p != null) { "RECOVERY_SOURCE_FILE_UNAVAILABLE" }
                        api.uploadRecoveryPart(ref, next, CloudRecoveryFormat.part(payload, m, next))
                    } else {
                        api.post("/v1/recovery/complete", ref)
                    }
                }

                "ABORTING" -> op = api.post("/v1/recovery/abort", ref)

                else -> op = api.post("/v1/recovery/advance", ref)
            }
        }
        error("RECOVERY_STEP_LIMIT")
    }
    fun abort(): Status = operation {
        val s = connected()
        val api = api(s)
        val op = if (hasPending()) {
            val p = pending(s)
            api.post("/v1/recovery/abandon", mapOf("approvalId" to p.getString("approvalId"), "sequence" to p.getString("sequence")))
        } else {
            val existing = status(api).operation ?: return@operation status(api)
            api.post("/v1/recovery/abort", mapOf("sequence" to existing.getString("sequence"), "archiveId" to existing.getString("archiveId")))
        }
        check(op.getString("state") in setOf("DONE", "CANCELED")) { "RECOVERY_CLEANUP_UNCONFIRMED" }
        clearPending()
        status(api)
    }
    private fun clearPending() {
        marker.delete()
        check(!payload.exists() || payload.delete()) { "RECOVERY_LOCAL_CLEANUP_FAILED" }
    }
    fun prepareRestore(archiveId: String): RestorePreview = operation {
        check(!hasPending() && !sync.hasPending()) { "RECOVERY_UPLOAD_PENDING" }
        // The change in generation immediately fences any already-admitted automatic request.
        val s = checkNotNull(sync.disable())
        CloudScheduler.reconcile(context)
        val live = sync.inspect()
        check("backup.read" in live.capabilities) { "RECOVERY_API_DISABLED" }
        val api = api(s)
        val archive = status(api).archives.single { it.id == archiveId }
        check(archive.usable) { "RECOVERY_NOT_AVAILABLE" }
        val pin = api.post("/v1/recovery/pin", mapOf("archiveId" to archiveId))
        val pinId = pin.getString("pinId")
        val m = CloudRecoveryFormat.read(pin["manifest"])
        check(
            pin.getString("archiveId") == archiveId && pin.getString("digest") == m.digest() && CloudRecoveryFormat.UUID.matches(pinId) &&
                m.kind == archive.kind && m.bytes == archive.bytes
        ) { "RECOVERY_PIN_MISMATCH" }
        storage()
        val target = File(root, "restore.payload")
        var prepared: TagBackupCoordinator.Prepared? = null
        try {
            FileOutputStream(target).use { out ->
                m.parts.forEachIndexed { index, expected -> out.write(api.downloadRecoveryPart(archiveId, pinId, index + 1, expected)) }
                out.fd.sync()
            }
            check(CloudRecoveryFormat.fromFile(target, m.source, m.native) == m) { "RECOVERY_DOWNLOAD_CHECKSUM_MISMATCH" }
            val decoded = if (m.kind == "cloud-catalog") {
                check(m.source!!.head.serviceGeneration == s.scope.service && m.source.head.library == s.scope.library) { "CLOUD_SCOPE_MISMATCH" }
                CloudRecoveryFormat.readCatalog(target, m)
            } else {
                prepared = TagBackupCoordinator.prepare(context, file(target))
                check(nativeMetadata(target, prepared!!) == m.native) { "RECOVERY_NATIVE_METADATA_MISMATCH" }
                null
            }
            val before = CloudNativeCatalog(TagRuntime.get(context)).capture(s.scope, s.incarnation)
            RestorePreview(UUID.randomUUID().toString(), s.generation, SystemClock.elapsedRealtime() + 10 * 60_000, archive, m, before, decoded, prepared, target)
        } catch (e: Throwable) {
            prepared?.close()
            target.delete()
            throw e
        } finally {
            try {
                api.post("/v1/recovery/release", mapOf("archiveId" to archiveId, "pinId" to pinId))
            } catch (_: Exception) {
                prepared?.close()
                target.delete()
                error("RECOVERY_RELEASE_UNCONFIRMED")
            }
        }
    }
    fun confirmRestore(preview: RestorePreview, includeSettings: Boolean = false) = operation {
        val s = connection.requireCurrent(preview.generation)
        check(!s.enabled && SystemClock.elapsedRealtime() < preview.expires) { "CLOUD_PREVIEW_EXPIRED" }
        check(!hasPending() && !sync.hasPending()) { "RECOVERY_UPLOAD_PENDING" }
        check(CloudRecoveryFormat.fromFile(preview.file, preview.manifest.source, preview.manifest.native) == preview.manifest) { "RECOVERY_LOCAL_FILE_CHANGED" }
        val before = CloudNativeCatalog(TagRuntime.get(context)).capture(s.scope, s.incarnation)
        check(
            before.identities == preview.before.identities && before.runtimeGeneration == preview.before.runtimeGeneration &&
                before.fingerprint == preview.before.fingerprint && before.nativeIds == preview.before.nativeIds &&
                before.tagRevision == preview.before.tagRevision && before.orphaned == preview.before.orphaned
        ) { "CLOUD_LOCAL_PREVIEW_CHANGED" }
        sync.saveLocalRecovery(preview.before)
        if (preview.decoded != null) {
            CloudNativeApply(TagRuntime.get(context)).restoreHistorical(
                preview.before,
                preview.manifest.source!!.head,
                preview.decoded.catalog,
                preview.decoded.hints
            ) {
                val current = connection.requireCurrent(preview.generation)
                check(!current.enabled && SystemClock.elapsedRealtime() < preview.expires) { "CLOUD_PREVIEW_EXPIRED" }
            }
            CloudTransferStore(connection.directory).discardCache()
            connection.update(s.generation) { it.copy(enabled = false, message = "Recovery catalog restored on this device. Automatic sync is off.") }
        } else {
            val prepared = checkNotNull(preview.prepared)
            val preferences = if (includeSettings) TagBackupCoordinator.readPreferences(prepared) else null
            // install() retains native AI unpairing, writer fencing, fresh identity and restart requirements.
            installNative(context, prepared) {
                connection.requireCurrent(preview.generation)
                check(!connected().enabled && SystemClock.elapsedRealtime() < preview.expires) { "CLOUD_PREVIEW_EXPIRED" }
                val current = CloudNativeCatalog(TagRuntime.get(context)).capture(s.scope, s.incarnation)
                check(
                    current.identities == preview.before.identities && current.fingerprint == preview.before.fingerprint &&
                        current.tagRevision == preview.before.tagRevision && current.nativeIds == preview.before.nativeIds &&
                        current.orphaned == preview.before.orphaned
                ) { "CLOUD_LOCAL_PREVIEW_CHANGED" }
            }
            if (preferences != null) TagBackupCoordinator.applyPreferences(PreferenceManager.getDefaultSharedPreferences(context), preferences)
        }
    }
    private fun nativeMetadata(zip: File, prepared: TagBackupCoordinator.Prepared): CloudRecoveryFormat.Native {
        check(prepared.directory.toFile().isDirectory)
        // Use the archive's schema versions. prepare() may have migrated its temporary copies.
        return ZipFile(zip).use { z ->
            fun version(name: String): Int {
                val header = ByteArray(100)
                java.io.DataInputStream(z.getInputStream(checkNotNull(z.getEntry(name)))).use { it.readFully(header) }
                check(header.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) { "INVALID_NATIVE_ZIP" }
                return java.nio.ByteBuffer.wrap(header).getInt(60).also { check(it > 0) { "INVALID_NATIVE_SCHEMA" } }
            }
            val bytes = z.entries().asSequence().sumOf { entry -> entry.size.also { check(it >= 0) } }
            CloudRecoveryFormat.Native(version("newpipe.db"), version(TagStore.FILE_NAME), bytes)
        }
    }
    companion object {
        fun hasPending(directory: File): Boolean {
            val marker = File(directory, "remote-recovery/pending.json")
            return marker.exists() || File(marker.path + ".bak").exists()
        }
    }
}
