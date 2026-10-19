/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.util.AtomicFile
import com.grack.nanojson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import org.schabi.newpipe.custom.tagging.TagRuntime

/** Bounded private transfer state. Persist an exact outbox before the first request. */
internal class CloudTransferStore(private val root: File) {
    class Pending(val incarnation: String, val generation: String, val upload: CloudPages.Upload) {
        override fun toString(): String = "PendingCloudTransfer(${upload.header["sequence"]})"
    }
    private val directory = File(root, "outbox")
    private val marker = AtomicFile(File(directory, "manifest.json"))
    private val cache = AtomicFile(File(root, "catalog-cache.json"))
    fun hasPending(): Boolean = marker.baseFile.exists() || File(marker.baseFile.path + ".bak").exists()
    fun save(pending: Pending) {
        check(TagRuntime.lock.isHeldByCurrentThread) { "OUTBOX_REQUIRES_BARRIER" }
        check(!hasPending()) { "CLOUD_UPLOAD_ALREADY_PENDING" }
        if (directory.exists()) check(directory.deleteRecursively()) { "CLOUD_STAGING_CLEANUP_FAILED" }
        check(directory.mkdirs()) { "CLOUD_STAGING_UNAVAILABLE" }
        val hashes = ArrayList<String>()
        var total = 0L
        pending.upload.chunks.forEachIndexed { index, chunk ->
            val bytes = CloudWireJson.encode(chunk).toByteArray(Charsets.UTF_8)
            require(bytes.size <= 65536) { "OUTBOX_CHUNK_LIMIT" }
            total += bytes.size
            require(total <= 64L * 1024 * 1024) { "OUTBOX_TOTAL_LIMIT" }
            write(AtomicFile(File(directory, "$index.json")), bytes)
            hashes.add(CloudWireJson.sha256(bytes))
        }
        write(
            marker,
            CloudWireJson.encode(
                linkedMapOf(
                    "version" to 1,
                    "incarnation" to pending.incarnation,
                    "generation" to pending.generation,
                    "header" to pending.upload.header,
                    "chunks" to hashes
                )
            ).toByteArray(Charsets.UTF_8)
        )
    }
    fun load(): Pending? {
        if (!hasPending()) return null
        val o = CloudWireJson.obj(read(marker, 512 * 1024).toString(Charsets.UTF_8))
        check(o.getInt("version") == 1) { "INVALID_OUTBOX_VERSION" }
        val rawHeader = o.getObject("header")
        val hashes = o.getArray("chunks")
        check(hashes.size in 1..4096) { "OUTBOX_CHUNK_LIMIT" }
        var total = 0L
        val changes = ArrayList<CloudPages.Change>()
        for (i in hashes.indices) {
            val bytes = read(AtomicFile(File(directory, "$i.json")), 65536)
            total += bytes.size
            check(total <= 64L * 1024 * 1024 && CloudWireJson.sha256(bytes) == hashes.getString(i)) { "OUTBOX_CORRUPT" }
            val chunk = CloudWireJson.array(bytes.toString(Charsets.UTF_8))
            for (row in chunk) {
                val p = CloudWireJson.fields(row, "id", "body")
                changes.add(CloudPages.Change(p["id"] as String, p["body"] as String?))
                check(changes.size <= 4096) { "OUTBOX_CHANGE_LIMIT" }
            }
        }
        val upload = CloudPages.upload(rawHeader.getString("mode"), rawHeader.getString("sequence"), CloudWireJson.head(rawHeader["before"]), rawHeader.getString("targetFingerprint"), changes)
        check(CloudWireJson.encode(upload.header) == CloudWireJson.encode(rawHeader)) { "OUTBOX_IDENTITY_MISMATCH" }
        return Pending(o.getString("incarnation"), o.getString("generation"), upload)
    }
    fun clear() {
        check(TagRuntime.lock.isHeldByCurrentThread) { "OUTBOX_REQUIRES_BARRIER" }
        marker.delete()
        check(!directory.exists() || directory.deleteRecursively()) { "OUTBOX_CLEANUP_FAILED" }
    }
    fun cached(status: CloudRemoteStatus): CloudDownload? {
        if (!cache.baseFile.exists()) return null
        return try {
            val o = CloudWireJson.obj(read(cache, 64 * 1024 * 1024).toString(Charsets.UTF_8))
            if (CloudWireJson.head(o["head"]) != status.head || o.getString("manifest") != status.manifest) return null
            val pages = o.getArray("pages").map { raw ->
                val p = raw as JsonObject
                CloudPages.Page(p.getString("id"), p.getString("body"))
            }
            check(pages.size == status.pages && pages.sumOf { CloudWireJson.bytes(it.body).toLong() } == status.bytes.toLong())
            check(CloudPages.manifest(pages) == status.manifest)
            val decoded = CloudPages.decode(pages)
            check(CloudCatalogCodec.fingerprint(decoded.catalog) == status.head.fingerprint)
            CloudDownload(status, pages, decoded)
        } catch (_: Exception) {
            null
        } // Cache has no authority; reload from the authenticated exact head.
    }
    fun cache(download: CloudDownload) {
        val encoded = CloudWireJson.encode(
            linkedMapOf(
                "head" to CloudApplyPlanJson.head(download.status.head),
                "manifest" to download.status.manifest,
                "pages" to download.pages.map { linkedMapOf("id" to it.id, "body" to it.body) }
            )
        ).toByteArray(Charsets.UTF_8)
        check(encoded.size <= 64 * 1024 * 1024) { "CACHE_BYTE_LIMIT" }
        write(cache, encoded)
    }
    fun discardCache() = cache.delete()
    companion object {
        fun read(file: AtomicFile, limit: Int): ByteArray = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                check(output.size().toLong() + n <= limit) { "PRIVATE_FILE_SIZE_LIMIT" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        fun write(file: AtomicFile, bytes: ByteArray) {
            check(file.baseFile.parentFile!!.isDirectory || file.baseFile.parentFile!!.mkdirs()) { "PRIVATE_STORAGE_UNAVAILABLE" }
            val out = file.startWrite()
            try {
                out.write(bytes)
                file.finishWrite(out)
            } catch (e: Throwable) {
                file.failWrite(out)
                throw e
            }
        }
    }
}
