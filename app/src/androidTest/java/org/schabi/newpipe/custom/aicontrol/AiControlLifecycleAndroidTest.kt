/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.BuildConfig

/** Run in a separate QA instrumentation invocation. No real relay pairing or catalog changes. */
class AiControlLifecycleAndroidTest {
    @Test fun immediateStopDuringForegroundStartAndViewRecreationCannotCrashOrReenable() {
        check(BuildConfig.APPLICATION_ID == "org.schabi.newpipe.debug.featureaicontrol")
        val app = ApplicationProvider.getApplicationContext<Context>()
        val device = UUID.randomUUID().toString()
        val key = AiDeviceKeys().create(device)
        AiLocalStateStore(app).replace(
            AiLocalState(
                deviceId = device,
                deviceName = "Synthetic lifecycle QA",
                gatewayOrigin = "https://example.invalid",
                ownerId = UUID.randomUUID().toString(),
                keyFingerprint = AiProtocol.digest(key),
                paired = true,
                enabled = false,
                permissions = setOf("library.read")
            )
        )
        val scenario = ActivityScenario.launch(AiControlActivity::class.java)
        val runtime = AiControlRuntime.get(app)
        fun waitUntil(condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (!condition() && System.nanoTime() < until) Thread.sleep(50)
            assertTrue("Lifecycle state did not settle: ${runtime.snapshot.connection}", condition())
        }
        fun enable() {
            val done = CountDownLatch(1)
            var error: String? = null
            runtime.setEnabled(true) {
                error = it
                done.countDown()
            }
            assertTrue(done.await(8, TimeUnit.SECONDS))
            assertEquals(null, error)
        }
        try {
            waitUntil { runtime.snapshot.state?.deviceId == device }
            repeat(3) {
                enable()
                val done = CountDownLatch(1)
                scenario.onActivity { activity ->
                    ContextCompat.startForegroundService(activity, Intent(activity, AiControlService::class.java).setAction(AiControlService.ACTION_START))
                    // Same-main-loop cancellation deliberately races the service's onStartCommand.
                    runtime.setEnabled(false) { done.countDown() }
                }
                assertTrue(done.await(8, TimeUnit.SECONDS))
                waitUntil { runtime.snapshot.state?.enabled == false && runtime.snapshot.connection == "DISABLED" }
                scenario.recreate()
                assertFalse(runtime.snapshot.state!!.enabled)
            }
            // A completed stop followed by configuration must not restore the old enabled value.
            val changed = CountDownLatch(1)
            runtime.configure("Renamed while stopped", setOf("library.read")) { changed.countDown() }
            assertTrue(changed.await(8, TimeUnit.SECONDS))
            assertFalse(runtime.snapshot.state!!.enabled)
            assertEquals("Renamed while stopped", runtime.snapshot.state!!.deviceName)
        } finally {
            val forgotten = CountDownLatch(1)
            runtime.forget { forgotten.countDown() }
            assertTrue(forgotten.await(20, TimeUnit.SECONDS))
            scenario.close()
        }
    }
}
