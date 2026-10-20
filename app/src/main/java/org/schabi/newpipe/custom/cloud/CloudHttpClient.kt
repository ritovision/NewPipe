/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import com.grack.nanojson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class CloudRemoteException(val code: String, val httpStatus: Int = 0) : IOException(code) {
    val retryable: Boolean get() = httpStatus == 0 || httpStatus == 429 || httpStatus >= 500
}
internal interface CloudApi {
    fun post(path: String, body: Map<String, Any?>): JsonObject
}
internal interface CloudRecoveryApi : CloudApi {
    fun uploadRecoveryPart(reference: Map<String, String>, number: Int, bytes: ByteArray): JsonObject
    fun downloadRecoveryPart(archiveId: String, pinId: String, number: Int, expected: CloudRecoveryFormat.Part): ByteArray
}

/** A private client: no shared cookies, logging interceptor, redirects or extractor networking. */
internal class CloudHttpClient(rawOrigin: String, private val credential: String, private val admit: () -> Unit) : CloudRecoveryApi {
    val origin = normalizeOrigin(rawOrigin)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    override fun post(path: String, body: Map<String, Any?>): JsonObject {
        check(path in PATHS) { "INVALID_CLOUD_ROUTE" }
        admit()
        val encoded = CloudWireJson.encode(body).toByteArray(Charsets.UTF_8)
        require(encoded.size <= 65536) { "REQUEST_BYTE_LIMIT" }
        val request = Request.Builder().url(origin + path).header("Authorization", "Bearer $credential")
            .header("Cache-Control", "no-store").post(encoded.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return execute(request, ::json)
    }
    override fun uploadRecoveryPart(reference: Map<String, String>, number: Int, bytes: ByteArray): JsonObject {
        require(reference.keys == setOf("sequence", "archiveId") && number in 1..32 && bytes.size in 1..CloudRecoveryFormat.PART_BYTES)
        require(CloudRecoveryFormat.UUID.matches(reference.getValue("archiveId")) && CloudCounter.parse(reference.getValue("sequence")) > 0)
        val payload = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                var offset = 0
                while (offset < bytes.size) {
                    admit()
                    val count = minOf(16384, bytes.size - offset)
                    sink.write(bytes, offset, count)
                    offset += count
                }
            }
        }
        val request = Request.Builder().url(origin + "/v1/recovery/part").header("Authorization", "Bearer $credential")
            .header("Cache-Control", "no-store").header("X-Recovery-Sequence", reference.getValue("sequence"))
            .header("X-Recovery-Archive", reference.getValue("archiveId")).header("X-Recovery-Part", number.toString()).post(payload).build()
        return execute(request, ::json)
    }
    override fun downloadRecoveryPart(archiveId: String, pinId: String, number: Int, expected: CloudRecoveryFormat.Part): ByteArray {
        require(CloudRecoveryFormat.UUID.matches(archiveId) && CloudRecoveryFormat.UUID.matches(pinId) && number in 1..32 && expected.bytes in 1..CloudRecoveryFormat.PART_BYTES)
        val payload = CloudWireJson.encode(mapOf("archiveId" to archiveId, "pinId" to pinId, "part" to number))
        val request = Request.Builder().url(origin + "/v1/recovery/download").header("Authorization", "Bearer $credential")
            .header("Cache-Control", "no-store").post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return execute(request) { response ->
            if (!response.isSuccessful) json(response)
            val content = response.body ?: throw CloudRemoteException("EMPTY_CLOUD_RESPONSE")
            check(
                content.contentLength() == expected.bytes.toLong() && response.header("Content-Type") == "application/octet-stream" &&
                    response.header("X-Recovery-Sha256") == expected.sha256
            ) { "RECOVERY_RESPONSE_MISMATCH" }
            read(content, expected.bytes).also {
                check(it.size == expected.bytes && CloudWireJson.sha256(it) == expected.sha256) { "RECOVERY_DOWNLOAD_CHECKSUM_MISMATCH" }
            }
        }
    }
    private fun read(content: okhttp3.ResponseBody, maximum: Int): ByteArray {
        if (content.contentLength() > maximum) throw CloudRemoteException("CLOUD_RESPONSE_TOO_LARGE")
        return content.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                admit()
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size().toLong() + n > maximum) throw CloudRemoteException("CLOUD_RESPONSE_TOO_LARGE")
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    }
    private fun json(response: okhttp3.Response): JsonObject {
        val bytes = read(response.body ?: throw CloudRemoteException("EMPTY_CLOUD_RESPONSE"), MAX_RESPONSE)
        val value = try {
            CloudWireJson.obj(
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            )
        } catch (_: Exception) {
            throw CloudRemoteException("INVALID_CLOUD_RESPONSE", response.code)
        }
        if (!response.isSuccessful) {
            val code = (value["error"] as? String)?.takeIf { Regex("[A-Z0-9_]{1,90}").matches(it) } ?: "CLOUD_REQUEST_FAILED"
            throw CloudRemoteException(code, response.code)
        }
        return value
    }
    private fun <T> execute(request: Request, readResponse: (okhttp3.Response) -> T): T {
        admit()
        try {
            client.newCall(request).execute().use { response ->
                admit()
                if (response.code in 300..399) throw CloudRemoteException("CLOUD_REDIRECT_REJECTED", response.code)
                return readResponse(response).also { admit() }
            }
        } catch (e: CloudRemoteException) {
            throw e
        } catch (_: IOException) {
            throw CloudRemoteException("CLOUD_NETWORK_UNAVAILABLE")
        }
    }
    fun cancel() {
        client.dispatcher.cancelAll()
    }
    companion object {
        private const val MAX_RESPONSE = 1024 * 1024
        private val PATHS = setOf(
            "/v1/connect", "/v1/head", "/v1/pages", "/v1/uploads/start", "/v1/uploads/chunk", "/v1/uploads/seal",
            "/v1/uploads/publish", "/v1/uploads/status", "/v1/uploads/retire",
            "/v1/recovery/status", "/v1/recovery/preview", "/v1/recovery/reserve", "/v1/recovery/advance",
            "/v1/recovery/complete", "/v1/recovery/abort", "/v1/recovery/pin", "/v1/recovery/release", "/v1/recovery/abandon",
            "/v1/replacement/status", "/v1/replacement/preview", "/v1/replacement/reserve",
            "/v1/replacement/publish", "/v1/replacement/settle", "/v1/replacement/abandon"
        )
        fun normalizeOrigin(raw: String): String {
            val url = raw.trim().toHttpUrl()
            require(
                url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.port == 443 &&
                    url.encodedPath == "/" && url.query == null && url.fragment == null && url.host.isNotBlank()
            ) { "HTTPS_WORKER_ORIGIN_REQUIRED" }
            return url.toString().removeSuffix("/")
        }
    }
}

internal data class CloudRemoteStatus(
    val device: String,
    val scope: CloudIdentityStore.Scope,
    val head: CloudHead,
    val initialized: Boolean,
    val pages: Int,
    val bytes: Int,
    val manifest: String,
    val maxBytes: Int,
    val maxPages: Int,
    val sequence: String,
    val upload: JsonObject?,
    val capabilities: Set<String>,
    val replacement: JsonObject? = null
)
internal data class CloudDownload(val status: CloudRemoteStatus, val pages: List<CloudPages.Page>, val decoded: CloudPages.Decoded)
internal object CloudRemoteCatalog {
    fun status(api: CloudApi): CloudRemoteStatus {
        val o = api.post("/v1/connect", emptyMap())
        require(CloudWireJson.integer(o["protocol"], 1, 1) == 1L) { "UNSUPPORTED_CLOUD_PROTOCOL" }
        val scope = o.getObject("scope")
        val head = CloudWireJson.head(o["head"])
        val identity = CloudIdentityStore.Scope(scope.getString("service"), scope.getString("library"))
        require(identity.service == head.serviceGeneration && identity.library == head.library) { "CLOUD_SCOPE_MISMATCH" }
        val limits = o.getObject("limits")
        val deviceState = o["deviceState"] as? JsonObject ?: error("CLOUD_SERVER_UPGRADE_REQUIRED")
        val sequence = deviceState.getString("resolvedSequence")
        CloudCounter.parse(sequence)
        require(o["initialized"] is Boolean && Regex("[0-9a-f]{64}").matches(o.getString("manifest"))) { "INVALID_CLOUD_STATUS" }
        return CloudRemoteStatus(
            o.getString("device"), identity, head, o.getBoolean("initialized"),
            CloudWireJson.integer(o["pages"], 0, CloudPages.MAX_PAGES.toLong()).toInt(), CloudWireJson.integer(o["bytes"], 0, CloudPages.MAX_BYTES.toLong()).toInt(),
            o.getString("manifest"), CloudWireJson.integer(limits["maxCatalogBytes"], 32768, CloudPages.MAX_BYTES.toLong()).toInt(),
            CloudWireJson.integer(limits["maxPages"], 1, CloudPages.MAX_PAGES.toLong()).toInt(), sequence, deviceState["upload"] as? JsonObject,
            o.getArray("capabilities").map { it as String }.toSet(),
            (deviceState["replacement"] as? JsonObject)?.also {
                check(CloudRecoveryFormat.UUID.matches(it.getString("attemptId")) && it.getString("state") in setOf("ACTIVE", "STALE")) { "INVALID_REPLACEMENT_STATUS" }
            }
        )
    }
    fun download(api: CloudApi, expected: CloudRemoteStatus): CloudDownload {
        check(expected.initialized) { "CLOUD_LIBRARY_EMPTY_NOT_INITIALIZED" }
        val pages = ArrayList<CloudPages.Page>()
        val seen = HashSet<String>()
        var cursor: String? = null
        var bytes = 0L
        do {
            val o = api.post("/v1/pages", mapOf("head" to CloudApplyPlanJson.head(expected.head), "cursor" to cursor))
            check(CloudWireJson.head(o["head"]) == expected.head) { "CLOUD_CHANGED" }
            val data = o.getArray("data")
            check(data.size <= 8) { "INVALID_CLOUD_PAGE_COUNT" }
            for (entry in data) {
                val p = CloudWireJson.fields(entry, "id", "body")
                val page = CloudPages.Page(p["id"] as String, p["body"] as String)
                check(seen.add(page.id) && (cursor == null || page.id > cursor!!)) { "DUPLICATE_OR_STALE_CLOUD_PAGE" }
                bytes += CloudWireJson.bytes(page.body)
                check(bytes <= expected.bytes && pages.size < expected.pages) { "CLOUD_PAGE_LIMIT" }
                CloudPages.parsePage(page)
                pages.add(page)
            }
            val next = o["nextCursor"] as? String
            check(o["nextCursor"] == null || next != null && Regex("[0-9a-f]{64}").matches(next)) { "INVALID_CLOUD_CURSOR" }
            check(next == null || data.isNotEmpty() && next == pages.last().id && next != cursor) { "CLOUD_CURSOR_NOT_ADVANCING" }
            cursor = next
        } while (cursor != null)
        check(pages.size == expected.pages && bytes == expected.bytes.toLong() && CloudPages.manifest(pages) == expected.manifest) { "CLOUD_MANIFEST_MISMATCH" }
        val decoded = CloudPages.decode(pages)
        check(CloudCatalogCodec.fingerprint(decoded.catalog) == expected.head.fingerprint) { "CLOUD_CONTENT_MISMATCH" }
        return CloudDownload(expected, pages, decoded)
    }
}
