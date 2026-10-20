/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Cross-runtime recovery format. Digests bind fixed-size part hashes, never multipart ETags. */
internal object CloudRecoveryFormat {
    const val PART_BYTES = 8 * 1024 * 1024
    const val MAX_BYTES = 256 * 1024 * 1024
    const val MAX_PARTS = 32
    val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val HASH = Regex("[0-9a-f]{64}")
    data class Part(val bytes: Int, val sha256: String)
    data class Source(val head: CloudHead, val pages: Int, val bytes: Int, val manifest: String) {
        fun wire(): Map<String, Any?> = linkedMapOf("head" to CloudApplyPlanJson.head(head), "pages" to pages, "bytes" to bytes, "manifest" to manifest)
        fun tuple(): List<Any> = listOf(
            listOf(head.serviceGeneration, head.library, head.epoch, head.revision, head.headCounter, head.fingerprint),
            pages,
            bytes,
            manifest
        )
    }
    data class Native(val database: Int, val tags: Int, val uncompressedBytes: Long) {
        fun wire(): Map<String, Any> = linkedMapOf("database" to database, "tags" to tags, "uncompressedBytes" to uncompressedBytes)
        fun tuple(): List<Any> = listOf(database, tags, uncompressedBytes)
    }
    data class Manifest(val kind: String, val bytes: Int, val source: Source?, val native: Native?, val parts: List<Part>) {
        val validation: String get() = if (kind == "device-zip") "native-restore-v1" else "catalog-pages-v1"
        fun wire(): Map<String, Any?> = linkedMapOf(
            "version" to 1,
            "kind" to kind,
            "bytes" to bytes,
            "source" to source?.wire(),
            "native" to native?.wire(),
            "producerValidation" to validation,
            "parts" to parts.map { linkedMapOf("bytes" to it.bytes, "sha256" to it.sha256) }
        )
        fun digest(): String = CloudWireJson.sha256(CloudWireJson.encode(listOf("rv-recovery/1", kind, validation, bytes, source?.tuple(), native?.tuple(), parts.map { listOf(it.bytes, it.sha256) })))
    }
    fun read(raw: Any?): Manifest {
        val o = CloudWireJson.fields(raw, "version", "kind", "bytes", "source", "native", "producerValidation", "parts")
        check(CloudWireJson.integer(o["version"], 1, 1) == 1L)
        val kind = o["kind"] as String
        check(kind in setOf("device-zip", "cloud-catalog")) { "UNSUPPORTED_RECOVERY_FORMAT" }
        val size = CloudWireJson.integer(o["bytes"], 22, MAX_BYTES.toLong()).toInt()
        val source = if (kind == "cloud-catalog") {
            val s = CloudWireJson.fields(o["source"], "head", "pages", "bytes", "manifest")
            Source(
                CloudWireJson.head(s["head"]),
                CloudWireJson.integer(s["pages"], 1, CloudPages.MAX_PAGES.toLong()).toInt(),
                CloudWireJson.integer(s["bytes"], 1, CloudPages.MAX_BYTES.toLong()).toInt(),
                (s["manifest"] as String).also { check(HASH.matches(it)) }
            )
        } else {
            check(o["source"] == null)
            null
        }
        val rawParts = o["parts"] as List<*>
        check(rawParts.size == (size + PART_BYTES - 1) / PART_BYTES && rawParts.size <= MAX_PARTS) { "INVALID_RECOVERY_PARTS" }
        val parts = rawParts.mapIndexed { index, rawPart ->
            val p = CloudWireJson.fields(rawPart, "bytes", "sha256")
            Part(CloudWireJson.integer(p["bytes"], 1, PART_BYTES.toLong()).toInt(), (p["sha256"] as String).also { check(HASH.matches(it)) }).also {
                check(index == rawParts.lastIndex || it.bytes == PART_BYTES) { "INVALID_RECOVERY_PART_SIZE" }
            }
        }
        check(parts.sumOf { it.bytes.toLong() } == size.toLong()) { "RECOVERY_SIZE_MISMATCH" }
        val native = if (kind == "device-zip") {
            val n = CloudWireJson.fields(o["native"], "database", "tags", "uncompressedBytes")
            Native(
                CloudWireJson.integer(n["database"], 1).toInt(),
                CloudWireJson.integer(n["tags"], 1).toInt(),
                CloudWireJson.integer(n["uncompressedBytes"], 1, 1024L * 1024 * 1024)
            )
        } else {
            check(o["native"] == null)
            null
        }
        return Manifest(kind, size, source, native, parts).also { check(o["producerValidation"] == it.validation) { "RECOVERY_PRODUCER_VALIDATION_REQUIRED" } }
    }
    fun fromFile(file: File, source: Source? = null, native: Native? = null, maximum: Int = MAX_BYTES): Manifest {
        check(file.length() in 22..minOf(maximum, MAX_BYTES).toLong()) { "RECOVERY_ARCHIVE_TOO_LARGE" }
        val size = file.length().toInt()
        val parts = ArrayList<Part>()
        file.inputStream().use { input ->
            var remaining = size
            while (remaining > 0) {
                val bytes = ByteArray(minOf(remaining, PART_BYTES))
                java.io.DataInputStream(input).readFully(bytes)
                parts.add(Part(bytes.size, CloudWireJson.sha256(bytes)))
                remaining -= bytes.size
            }
            check(input.read() == -1) { "RECOVERY_LOCAL_FILE_CHANGED" }
        }
        return Manifest(if (source == null) "device-zip" else "cloud-catalog", size, source, native, parts).also { read(it.wire()) }
    }
    fun part(file: File, manifest: Manifest, number: Int): ByteArray {
        check(number in 1..manifest.parts.size && file.length() == manifest.bytes.toLong()) { "RECOVERY_LOCAL_FILE_CHANGED" }
        val expected = manifest.parts[number - 1]
        return RandomAccessFile(file, "r").use {
            it.seek((number - 1).toLong() * PART_BYTES)
            ByteArray(expected.bytes).also { bytes ->
                it.readFully(bytes)
                check(CloudWireJson.sha256(bytes) == expected.sha256) { "RECOVERY_LOCAL_FILE_CHANGED" }
            }
        }
    }
    fun writeCatalog(file: File, download: CloudDownload): Source {
        val s = download.status
        val source = Source(s.head, s.pages, s.bytes, s.manifest)
        FileOutputStream(file).use { output ->
            fun line(value: Any) = output.write((CloudWireJson.encode(value) + "\n").toByteArray(Charsets.UTF_8))
            line(listOf("rv-cloud-catalog/1") + source.tuple())
            download.pages.forEach { line(listOf(it.id, it.body)) }
            output.fd.sync()
        }
        return source
    }
    fun readCatalog(file: File, manifest: Manifest): CloudPages.Decoded {
        val source = checkNotNull(manifest.source)
        check(file.length() == manifest.bytes.toLong()) { "RECOVERY_SIZE_MISMATCH" }
        // Each canonical page is <=32 KiB before JSON escaping. Bound lines before decoding.
        val pages = ArrayList<CloudPages.Page>()
        file.inputStream().buffered().use { input ->
            fun line(): String? {
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b == -1) {
                        check(out.size() == 0) { "TRUNCATED_CLOUD_ARCHIVE" }
                        return null
                    }
                    if (b == 10) break
                    check(out.size() < 2 * 32768 + 256) { "INVALID_CLOUD_ARCHIVE_LINE" }
                    out.write(b)
                }
                return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
            }
            check(line() == CloudWireJson.encode(listOf("rv-cloud-catalog/1") + source.tuple())) { "CLOUD_ARCHIVE_SOURCE_MISMATCH" }
            var bytes = 0L
            while (true) {
                val text = line() ?: break
                check(pages.size < source.pages) { "CLOUD_ARCHIVE_PAGE_LIMIT" }
                val row = CloudWireJson.array(text)
                check(row.size == 2 && CloudWireJson.encode(row) == text) { "NONCANONICAL_CLOUD_ARCHIVE" }
                val page = CloudPages.Page(row.getString(0), row.getString(1))
                check(pages.isEmpty() || page.id > pages.last().id) { "CLOUD_ARCHIVE_PAGE_ORDER" }
                CloudPages.parsePage(page)
                bytes += CloudWireJson.bytes(page.body)
                check(bytes <= source.bytes) { "CLOUD_ARCHIVE_SIZE_MISMATCH" }
                pages.add(page)
            }
            check(pages.size == source.pages && bytes == source.bytes.toLong() && CloudPages.manifest(pages) == source.manifest) { "CLOUD_ARCHIVE_MANIFEST_MISMATCH" }
        }
        return CloudPages.decode(pages).also { check(CloudCatalogCodec.fingerprint(it.catalog) == source.head.fingerprint) { "CLOUD_ARCHIVE_CONTENT_MISMATCH" } }
    }
}
