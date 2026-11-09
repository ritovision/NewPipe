/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.BuildConfig

/** Separate QA package only. Tests use synthetic state and an injected transport; no live gateway,
 * Library changes, foreground-service startup, actual owner credentials or biometric enrollment.
 */
class AiBrowserPairingAndroidTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var original: AiLocalState
    private val keys = AiDeviceKeys()
    private var runtime: AiControlRuntime? = null
    private val client = SyntheticClient()

    private class SyntheticClient : AiBrowserPairingClient {
        val count = AtomicInteger()
        val finishEntered = CountDownLatch(1)
        val releaseFinish = CountDownLatch(1)
        val finishReturned = CountDownLatch(1)
        override fun request(
            operation: String,
            pending: AiBrowserPairingPending,
            keys: AiDeviceKeys,
            check: () -> Unit,
            registered: (Call) -> Unit,
            released: (Call) -> Unit
        ): AiBrowserPairingPending {
            check()
            count.incrementAndGet()
            if (operation == "nonce") return pending.copy(clientNonce = "N".repeat(43), nonceReady = true)
            val status = when (operation) {
                "begin" -> "pending"

                "status" -> "approved"

                "cancel" -> "cancelled"

                "finish" -> {
                    finishEntered.countDown()
                    if (!releaseFinish.await(15, TimeUnit.SECONDS)) throw AssertionError("Test did not release completion")
                    // Deliberately return an already-dispatched successful response after cancellation:
                    // the runtime, not this fake network, must reject stale completion.
                    finishReturned.countDown()
                    "complete"
                }

                else -> throw AssertionError("Unexpected operation")
            }
            return AiBrowserPairingPolicy.validate(
                pending.copy(
                    ownerId = "50000000-0000-0000-0000-000000000001",
                    expiresAtMs = pending.issuedAtMs + 300_000,
                    serverStatus = status
                )
            )
        }
    }

    @Before fun isolate() {
        check(BuildConfig.APPLICATION_ID == "org.schabi.newpipe.debug.featureaipasskeys20261109") { "Requires isolated passkeys QA package" }
        val app = ApplicationProvider.getApplicationContext<Context>()
        directory = File(app.noBackupFilesDir, "pairing-test-" + UUID.randomUUID()).apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        original = AiLocalState(deviceName = "Isolated pairing QA")
        AiLocalStateStore(context).replace(original)
    }

    @After fun clean() {
        client.releaseFinish.countDown()
        runtime?.closeIsolatedForTest()
        if (::original.isInitialized) keys.delete(original.deviceId)
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun launch(): AiControlRuntime {
        val r = AiControlRuntime.isolatedForTest(context, client)
        runtime = r
        waitUntil { r.snapshot.state != null }
        return r
    }
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!condition() && System.nanoTime() < end) Thread.sleep(30)
        assertTrue("Pairing state did not settle", condition())
    }
    private fun completed(action: ((String?) -> Unit) -> Unit): String? {
        val signal = CountDownLatch(1)
        val error = AtomicReference<String?>()
        action {
            error.set(it)
            signal.countDown()
        }
        assertTrue("Control callback timed out", signal.await(10, TimeUnit.SECONDS))
        return error.get()
    }
    private fun begin(r: AiControlRuntime) {
        assertEquals(null, completed { r.beginBrowserPairing("https://gateway.test", "Approved phone", setOf("library.read", "playback.control"), it) })
        assertEquals("pending", r.snapshot.pendingPairing?.serverStatus)
    }
    private fun reachBlockedFinish(r: AiControlRuntime) {
        begin(r)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (client.finishEntered.count > 0 && System.nanoTime() < end) {
            r.pollBrowserPairing()
            Thread.sleep(100)
        }
        assertEquals(0L, client.finishEntered.count)
        assertTrue(r.snapshot.pendingPairing!!.finishSubmitted)
    }

    @Test fun androidKeystoreSignsTheExactVersionedPairingTranscript() {
        val public = keys.create(original.deviceId)
        val pending = AiBrowserPairingPolicy.create(original, "https://gateway.test", original.deviceName, original.permissions, public, System.currentTimeMillis())
        val message = pending.message("begin")
        val signature = Base64.decode(keys.sign(original.deviceId, message), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(public, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        fun verify(text: String) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(text.toByteArray(Charsets.UTF_8))
            verify(signature)
        }
        assertTrue(verify(message))
        assertFalse(verify(pending.message("finish")))
        assertEquals(43, pending.clientNonce.length)
    }

    @Test fun pendingSetupUsesSeparateDurableStorageAndSurvivesRuntimeRecreation() {
        val public = keys.create(original.deviceId)
        val pending = AiBrowserPairingPolicy.create(original, "https://gateway.test", "Pending phone", original.permissions, public, System.currentTimeMillis())
            .copy(ownerId = "50000000-0000-0000-0000-000000000001", serverStatus = "pending", nonceReady = true)
        val saved = pending.copy(expiresAtMs = pending.issuedAtMs + 300_000)
        AiBrowserPairingStore(context).write(saved)
        assertEquals(saved, AiBrowserPairingStore(context).read())
        val r = launch()
        assertEquals(saved, r.snapshot.pendingPairing)
        assertEquals(original, AiLocalStateStore(context).read())
        Thread.sleep(300)
        assertEquals("No polling before the screen requests it", 0, client.count.get())
    }

    @Test fun cancelledOrPreRestoreStagingCannotResume() {
        val public = keys.create(original.deviceId)
        val pending = AiBrowserPairingPolicy.create(original, "https://gateway.test", "Old setup", original.permissions, public, System.currentTimeMillis())
        AiBrowserPairingStore(context).write(pending.copy(cancelledLocally = true))
        val r = launch()
        assertEquals(null, r.snapshot.pendingPairing)
        assertEquals(0, client.count.get())
        assertEquals(original, AiLocalStateStore(context).read())
    }

    @Test fun changedRestoreEpochRejectsPersistedPendingRecord() {
        val public = keys.create(original.deviceId)
        val pending = AiBrowserPairingPolicy.create(original, "https://gateway.test", "Old setup", original.permissions, public, System.currentTimeMillis())
        AiBrowserPairingStore(context).write(pending)
        val changed = original.copy(catalogEpoch = UUID.randomUUID().toString())
        AiLocalStateStore(context).replace(changed)
        val r = launch()
        assertEquals(null, r.snapshot.pendingPairing)
        assertEquals(changed, r.snapshot.state)
        assertEquals(0, client.count.get())
    }

    @Test fun existingPairedInstallationIsNotTouchedByNewSetup() {
        val public = keys.create(original.deviceId)
        val paired = original.copy(
            gatewayOrigin = "https://gateway.test",
            ownerId = UUID.randomUUID().toString(),
            keyFingerprint = AiProtocol.digest(public),
            paired = true,
            enabled = true
        )
        AiLocalStateStore(context).replace(paired)
        val r = launch()
        assertEquals("ALREADY_PAIRED", completed { r.beginBrowserPairing("https://other.test", "Replacement", setOf("library.delete"), it) })
        assertEquals(paired, AiLocalStateStore(context).read())
        assertEquals(paired, r.snapshot.state)
        assertEquals(0, client.count.get())
    }

    @Test fun successfulCompletionPreservesIdentityAndScopesButDoesNotStartService() {
        val r = launch()
        reachBlockedFinish(r)
        assertEquals(original, AiLocalStateStore(context).read())
        client.releaseFinish.countDown()
        waitUntil { r.snapshot.state?.paired == true }
        val state = r.snapshot.state!!
        assertFalse(state.enabled)
        assertEquals("DISABLED", r.snapshot.connection)
        assertEquals(original.deviceId, state.deviceId)
        assertEquals(original.catalogEpoch, state.catalogEpoch)
        assertEquals(setOf("library.read", "playback.control"), state.permissions)
        assertEquals(null, r.snapshot.pendingPairing)
        assertEquals(null, AiBrowserPairingStore(context).read())
        assertNotNull(keys.publicKey(original.deviceId))
    }

    @Test fun cancellationFencesASuccessfulResponseAlreadyInFlight() {
        val r = launch()
        reachBlockedFinish(r)
        assertEquals(null, completed { r.cancelBrowserPairing(it) })
        client.releaseFinish.countDown()
        assertTrue(client.finishReturned.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(original, AiLocalStateStore(context).read())
        assertEquals(null, r.snapshot.pendingPairing)
        assertFalse(r.snapshot.state!!.paired)
        assertFalse(r.snapshot.state!!.enabled)
    }

    @Test fun queuedPollCannotBorrowTheCancellationGenerationBeforeCleanupRuns() {
        val r = launch()
        begin(r)
        Thread.sleep(2100)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val field = AiControlRuntime::class.java.getDeclaredField("control").apply { isAccessible = true }
        val lane = field.get(r) as java.util.concurrent.ScheduledExecutorService
        lane.execute {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val before = client.count.get()
        val cancelled = CountDownLatch(1)
        val error = AtomicReference<String?>()
        try {
            r.pollBrowserPairing() // Queued before cancellation cleanup, after the lane barrier.
            r.cancelBrowserPairing {
                error.set(it)
                cancelled.countDown()
            } // Immediate generation increment.
        } finally {
            release.countDown()
        }
        assertTrue(cancelled.await(10, TimeUnit.SECONDS))
        assertEquals(null, error.get())
        Thread.sleep(250)
        // The only additional request permitted is the best-effort signed cancellation.
        assertEquals(before + 1, client.count.get())
        assertEquals(null, r.snapshot.pendingPairing)
        assertEquals(original, AiLocalStateStore(context).read())
    }

    @Test fun permissionChangeWinsOverAnOlderSuccessfulPairingResponse() {
        val r = launch()
        reachBlockedFinish(r)
        assertEquals(null, completed { r.configure("Changed while pending", setOf("library.read"), it) })
        client.releaseFinish.countDown()
        assertTrue(client.finishReturned.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        val state = AiLocalStateStore(context).read()
        assertEquals("Changed while pending", state.deviceName)
        assertEquals(setOf("library.read"), state.permissions)
        assertFalse(state.paired)
        assertFalse(state.enabled)
        assertEquals(null, r.snapshot.pendingPairing)
    }
}
