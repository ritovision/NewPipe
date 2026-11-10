/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R

/** Opt-in real TLS/Keystore checks on an isolated QA Worker and QA APK only.
 * No injected transport, production host, real Library, private-key export or biometric simulation.
 * Host approval uses normal gateway authentication separately; this is not physical passkey acceptance.
 */
class AiBrowserPairingLiveAndroidTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(label: String, seconds: Long = 45, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition() && System.nanoTime() < until) Thread.sleep(100)
        assertTrue(label, condition())
    }
    private fun operation(action: ((String?) -> Unit) -> Unit): String? {
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>()
        action {
            error.set(it)
            done.countDown()
        }
        assertTrue("Operation callback timed out", done.await(35, TimeUnit.SECONDS))
        return error.get()
    }
    private fun write(file: File, value: JSONObject) {
        val temporary = File(file.path + ".tmp")
        temporary.writeText(value.toString())
        check(temporary.renameTo(file)) { "Could not commit public QA coordination record" }
    }

    @Test fun explicitIsolatedLiveStage() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live QA is opt-in", args.getString("qa_live") == "isolated-stage")
        check(BuildConfig.APPLICATION_ID == "org.schabi.newpipe.debug.featureaipasskeys20261109") { "Wrong package: no live test allowed" }
        val origin = args.getString("qa_gateway").orEmpty()
        check(origin.matches(Regex("https://np-passkeys-qa-20261109-[0-9a-f]{4}\\.matthewjpellerito\\.workers\\.dev"))) { "Only the disposable QA hostname is allowed" }
        val mode = args.getString("qa_mode").orEmpty()
        check(mode in setOf("begin", "denied", "cancel", "complete", "relay"))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val display = args.getString("qa_display_id")?.toIntOrNull()
        check(display != null && display > 0 && app.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(display)?.isValid == true) { "Live QA needs an explicit valid virtual display" }
        val directory = File(app.filesDir, "ai-pairing-live").apply { mkdirs() }
        val runtime = AiControlRuntime.get(app)
        await("Runtime did not load") { runtime.snapshot.state != null }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()
        val intent = Intent(app, AiControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<AiControlActivity>(intent, options).use { scenario ->
            scenario.onActivity { assertEquals(display, it.display?.displayId) }
            when (mode) {
                "begin" -> {
                    assertFalse(runtime.snapshot.state!!.paired)
                    assertNull(runtime.snapshot.pendingPairing)
                    await("Setup UI unavailable") {
                        var ready = false
                        scenario.onActivity { activity -> ready = views(activity.window.decorView).filterIsInstance<Button>().any { it.text == activity.getString(R.string.rv_ai_connect_browser) && it.isEnabled } }
                        ready
                    }
                    scenario.onActivity { activity ->
                        val all = views(activity.window.decorView)
                        all.filterIsInstance<EditText>().single { it.hint == activity.getString(R.string.rv_ai_gateway) }.setText(origin)
                        all.filterIsInstance<EditText>().single { it.hint == activity.getString(R.string.rv_ai_device_name) }.setText("Isolated live Pixel QA")
                        all.filterIsInstance<CheckBox>().forEach { it.isChecked = it.text == activity.getString(R.string.rv_ai_scope_read) }
                        all.filterIsInstance<Button>().single { it.text == activity.getString(R.string.rv_ai_connect_browser) }.performClick()
                    }
                    await("Real signed pairing begin failed: " + runtime.snapshot.pairingError) { runtime.snapshot.pendingPairing?.serverStatus == "pending" }
                    val pending = runtime.snapshot.pendingPairing!!
                    assertEquals(origin, pending.proposed.gatewayOrigin)
                    assertEquals(setOf("library.read"), pending.proposed.permissions)
                    assertFalse(runtime.snapshot.state!!.paired)
                    write(File(directory, "begin.json"), JSONObject().put("request_id", pending.requestId).put("approval_url", pending.approvalUrl).put("comparison_code", pending.comparisonCode).put("device_id", pending.proposed.deviceId).put("catalog_epoch", pending.proposed.catalogEpoch).put("permissions", JSONArray(pending.proposed.permissions.toList())))
                }

                "denied" -> {
                    await("Denied result was not delivered") { runtime.snapshot.pendingPairing?.serverStatus == "denied" }
                    assertFalse(runtime.snapshot.state!!.paired)
                    assertFalse(runtime.snapshot.state!!.enabled)
                    write(File(directory, "denied.json"), JSONObject().put("denial_preserved_unpaired", true))
                }

                "cancel" -> {
                    assertNull(operation { runtime.cancelBrowserPairing(it) })
                    await("Cancelled transaction remains staged") { runtime.snapshot.pendingPairing == null }
                    assertFalse(runtime.snapshot.state!!.paired)
                    write(File(directory, "cancel.json"), JSONObject().put("pending_cleared", true))
                }

                "complete" -> {
                    val expected = JSONObject(File(directory, "begin.json").readText())
                    // The host force-stops only this QA package before launching this mode.
                    val pending = runtime.snapshot.pendingPairing
                    if (pending != null) assertEquals(expected.getString("request_id"), pending.requestId)
                    await("Live pairing completion failed: " + runtime.snapshot.pairingError, 70) { runtime.snapshot.state?.paired == true }
                    val state = runtime.snapshot.state!!
                    assertEquals(expected.getString("device_id"), state.deviceId)
                    assertEquals(expected.getString("catalog_epoch"), state.catalogEpoch)
                    assertEquals(origin, state.gatewayOrigin)
                    assertEquals(setOf("library.read"), state.permissions)
                    assertFalse(state.enabled)
                    assertNull(runtime.snapshot.pendingPairing)
                    assertEquals("ALREADY_PAIRED", operation { runtime.beginBrowserPairing(origin, "Must not replace", setOf("playback.control"), it) })
                    assertEquals(state, runtime.snapshot.state)
                    write(File(directory, "complete.json"), JSONObject().put("device_id", state.deviceId).put("catalog_epoch", state.catalogEpoch).put("paired", true).put("enabled", false).put("existing_identity_preserved", true))
                }

                "relay" -> {
                    val state = runtime.snapshot.state!!
                    assertTrue(state.paired)
                    assertEquals(origin, state.gatewayOrigin)
                    assertEquals(setOf("library.read"), state.permissions)
                    val finish = File(directory, "finish")
                    finish.delete()
                    try {
                        assertNull(operation { runtime.setEnabled(true, it) })
                        scenario.onActivity { activity -> ContextCompat.startForegroundService(activity, Intent(activity, AiControlService::class.java).setAction(AiControlService.ACTION_START)) }
                        await("Real relay did not connect: " + runtime.snapshot.error, 60) { runtime.snapshot.connection == "CONNECTED" }
                        write(File(directory, "ready.json"), JSONObject().put("device_id", state.deviceId).put("catalog_epoch", state.catalogEpoch).put("connected", true).put("permissions", JSONArray(state.permissions.toList())))
                        await("Host never completed its live MCP checks", 180) { finish.exists() }
                        assertEquals("host_verified", finish.readText().trim())
                    } finally {
                        assertNull(operation { runtime.setEnabled(false, it) })
                        await("Relay did not stop") { runtime.snapshot.state?.enabled == false && runtime.snapshot.connection != "CONNECTED" }
                        write(File(directory, "stopped.json"), JSONObject().put("device_id", state.deviceId).put("enabled", false))
                    }
                }
            }
        }
    }
}
