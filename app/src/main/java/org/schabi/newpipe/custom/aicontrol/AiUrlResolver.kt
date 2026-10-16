/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo

/** Data comes only from the selected device's extractor, never from JSON supplied by the AI. */
internal data class AiResolvedMedia(val entity: StreamEntity, val service: String)
internal fun interface AiUrlResolver {
    fun resolve(url: String, checkActive: () -> Unit): AiResolvedMedia
}

internal class AiNativeUrlResolver(private val failureObserver: (Exception) -> Unit = {}) : AiUrlResolver {
    override fun resolve(url: String, checkActive: () -> Unit): AiResolvedMedia {
        checkActive()
        val input = AiMediaHostPolicy.validate(url)
        return try {
            AiExtractionNetwork.restricted(checkActive) {
                val service = NewPipe.getServiceByUrl(input)
                val canonical = AiMediaHostPolicy.validate(service.streamLHFactory.fromUrl(input).url)
                checkActive()
                val info = StreamInfo.getInfo(service, canonical)
                checkActive()
                AiProtocol.checkInput(info.serviceId == service.serviceId, "UNSUPPORTED_SERVICE")
                AiMediaHostPolicy.validate(info.url)
                AiProtocol.checkInput(info.name.length <= 4096 && info.uploaderName.length <= 2048, "SOURCE_METADATA_TOO_LARGE")
                AiResolvedMedia(StreamEntity(info), service.serviceInfo.name)
            }
        } catch (error: AiFailure) {
            throw error
        } catch (error: Exception) {
            failureObserver(error)
            // Extraction has not modified a catalog or started playback.
            throw AiFailure("URL_EXTRACTION_FAILED", AiOutcome.NOT_EXECUTED)
        }
    }
}

internal object AiPublicUrlPolicy {
    fun validate(raw: String): String {
        val value = raw.trim()
        AiProtocol.checkInput(value.length in 1..2048 && value.none(Char::isISOControl), "INVALID_URL")
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw AiFailure("INVALID_URL")
        }
        val host = uri.host?.lowercase()?.trimEnd('.') ?: throw AiFailure("INVALID_URL")
        AiProtocol.checkInput(
            uri.scheme in setOf("http", "https") && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443 && uri.scheme == "https" || uri.port == 80 && uri.scheme == "http"),
            "INVALID_URL"
        )
        AiProtocol.checkInput(
            host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") &&
                !host.endsWith(".internal") && '%' !in host && !host.isBlank(),
            "PRIVATE_NETWORK_URL"
        )
        // Numeric literals do not need DNS and must obey the same public-address policy.
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
            val address = try {
                InetAddress.getByName(host.removeSurrounding("[", "]"))
            } catch (_: Exception) {
                throw AiFailure("INVALID_URL")
            }
            AiProtocol.checkInput(isPublic(address), "PRIVATE_NETWORK_URL")
        }
        return value
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            val a = b[0]
            val c = b[1]
            val d = b[2]
            return a !in setOf(0, 10, 127) && a < 224 &&
                !(a == 100 && c in 64..127) && !(a == 169 && c == 254) &&
                !(a == 172 && c in 16..31) && !(a == 192 && c == 168) &&
                !(a == 192 && c == 0 && d in setOf(0, 2)) &&
                !(a == 198 && c in 18..19) && !(a == 198 && c == 51 && d == 100) && !(a == 203 && c == 0 && d == 113)
        }
        if (b.size == 16 && b.take(12) == listOf(0, 100, 255, 155, 0, 0, 0, 0, 0, 0, 0, 0)) {
            return isPublic(InetAddress.getByAddress(address.address.copyOfRange(12, 16)))
        }
        if (b.size == 16 && b[0] == 0x20 && b[1] == 0x02) return false // 6to4
        if (b.size == 16 && b.take(4) == listOf(0x20, 0x01, 0, 0)) return false // Teredo
        // Restrict IPv6 to globally routable unicast, excluding documentation addresses.
        return b.size == 16 && b[0] in 0x20..0x3f && !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
    }
}

/**
 * NewPipe's synchronous extractor uses the shared Downloader. This narrowly scoped hook selects
 * a separate public-network-only OkHttp client for AI extraction, not for ordinary UI playback.
 * DNS is checked for every new connection; redirects and the connected peer are checked too.
 */
object AiExtractionNetwork {
    internal class Scope(private val guard: () -> Unit) {
        private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        @Volatile var client: OkHttpClient? = null
        private val unclaimed = java.util.IdentityHashMap<Any, () -> Unit>()
        fun track(value: Any, release: () -> Unit): Boolean {
            val accepted = synchronized(unclaimed) {
                if (cancelled.get()) {
                    false
                } else {
                    unclaimed[value] = release
                    true
                }
            }
            if (!accepted) release()
            return accepted
        }
        fun claim(value: Any) {
            synchronized(unclaimed) {
                if (cancelled.get()) throw AiFailure("SESSION_CLOSED", AiOutcome.NOT_EXECUTED)
                unclaimed.remove(value)
            }
        }
        fun check() {
            if (cancelled.get()) throw AiFailure("SESSION_CLOSED", AiOutcome.NOT_EXECUTED)
            guard()
        }
        fun cancel() {
            cancelled.set(true)
            client?.dispatcher?.cancelAll()
            client?.connectionPool?.evictAll()
            val releases = synchronized(unclaimed) { unclaimed.values.toList().also { unclaimed.clear() } }
            releases.forEach { release ->
                try {
                    release()
                } catch (_: Exception) { /* Release other resources too. */ }
            }
        }
    }
    class CapturedContext internal constructor(internal val state: Scope)
    private val scope = ThreadLocal<Scope>()
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<Scope>()

    internal fun <T> restricted(check: () -> Unit, action: () -> T): T {
        kotlin.check(scope.get() == null)
        val current = Scope(check)
        active.add(current)
        scope.set(current)
        return try {
            current.check()
            action()
        } finally {
            current.cancel()
            active.remove(current)
            scope.remove()
        }
    }

    @JvmStatic fun cancelActive() {
        active.forEach(Scope::cancel)
    }

    @JvmStatic fun isRestricted(): Boolean = scope.get() != null

    @JvmStatic fun capture(): CapturedContext? = scope.get()?.let(::CapturedContext)

    fun <T> withContext(captured: CapturedContext?, action: () -> T): T {
        if (captured == null) return action()
        val old = scope.get()
        scope.set(captured.state)
        return try {
            captured.state.check()
            action()
        } finally {
            if (old == null) scope.remove() else scope.set(old)
        }
    }

    fun trackUnclaimed(captured: CapturedContext?, value: Any, release: () -> Unit): Boolean = captured?.state?.track(value, release) ?: true

    fun <T> withLock(lock: java.util.concurrent.locks.ReentrantLock, action: () -> T): T {
        val current = scope.get()
        if (current == null) {
            lock.lock()
            return try {
                action()
            } finally {
                lock.unlock()
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var acquired = false
        try {
            while (!acquired) {
                current.check()
                if (System.nanoTime() >= deadline) throw AiFailure("SOURCE_TIMEOUT", AiOutcome.NOT_EXECUTED)
                acquired = lock.tryLock(100, TimeUnit.MILLISECONDS)
            }
            current.check()
            return action()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AiFailure("INTERRUPTED", AiOutcome.NOT_EXECUTED)
        } finally {
            if (acquired) lock.unlock()
        }
    }

    /** A PoToken factory may move work to main/IO threads. Bound the caller's wait and propagate
     * its network scope explicitly rather than losing the checks with ThreadLocal scheduling. */
    fun <T : Any> awaitSingle(single: io.reactivex.rxjava3.core.Single<T>): T {
        val current = scope.get() ?: return single.blockingGet()
        val future = single.toFuture()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        try {
            while (true) {
                current.check()
                if (System.nanoTime() >= deadline) throw AiFailure("SOURCE_TIMEOUT", AiOutcome.NOT_EXECUTED)
                try {
                    val value = future.get(100, TimeUnit.MILLISECONDS)
                    current.check()
                    current.claim(value)
                    return value
                } catch (_: java.util.concurrent.TimeoutException) { /* Recheck cancellation. */ }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AiFailure("INTERRUPTED", AiOutcome.NOT_EXECUTED)
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }

    @JvmStatic
    fun restrictedCookies(url: String, youtubePreference: String?): String {
        val host = try {
            URI(url).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
        }
        // Recaptcha cookies have no reliable stored origin in upstream; never forward them here.
        return if (AiMediaHostPolicy.youtube(host)) youtubePreference.orEmpty() else ""
    }

    @JvmStatic
    fun clientFor(original: OkHttpClient): OkHttpClient {
        val current = scope.get() ?: return original
        current.check()
        return synchronized(current) {
            current.client ?: original.newBuilder()
                .dispatcher(okhttp3.Dispatcher())
                .connectionPool(ConnectionPool())
                .proxy(Proxy.NO_PROXY)
                .addInterceptor { chain ->
                    current.check()
                    val selected = original.proxy?.let(::listOf) ?: original.proxySelector.select(chain.request().url.toUri())
                    if (selected.any { it.type() != Proxy.Type.DIRECT }) throw IOException("AI_EXPLICIT_PROXY_NOT_SUPPORTED")
                    chain.proceed(chain.request())
                }
                .dns(
                    Dns { hostname ->
                        current.check()
                        val addresses = Dns.SYSTEM.lookup(hostname)
                        if (addresses.isEmpty() || addresses.any { !AiPublicUrlPolicy.isPublic(it) }) throw IOException("AI_PUBLIC_NETWORK_ONLY")
                        addresses
                    }
                )
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followSslRedirects(false)
                .addNetworkInterceptor { chain ->
                    current.check()
                    AiPublicUrlPolicy.validate(chain.request().url.toString())
                    val remote = chain.connection()?.route()?.socketAddress?.address
                    if (remote == null || !AiPublicUrlPolicy.isPublic(remote)) throw IOException("AI_PUBLIC_NETWORK_ONLY")
                    val safe = if (AiMediaHostPolicy.youtube(chain.request().url.host)) {
                        chain.request()
                    } else {
                        chain.request().newBuilder().removeHeader("Cookie").build()
                    }
                    chain.proceed(safe)
                }.build().also {
                    current.client = it
                    current.check()
                }
        }
    }

    @JvmStatic
    fun readBody(body: ResponseBody): String {
        val current = scope.get() ?: return body.string()
        current.check()
        val buffer = okio.Buffer()
        val source = body.source()
        while (true) {
            current.check()
            val count = source.read(buffer, 8192L)
            if (count < 0) break
            if (buffer.size > 16_777_216L) throw IOException("AI_RESPONSE_TOO_LARGE")
        }
        current.check()
        return buffer.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }
}
