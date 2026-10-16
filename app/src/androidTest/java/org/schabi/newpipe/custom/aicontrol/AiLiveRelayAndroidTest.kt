/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.testUtil.TestDatabase

/** Explicitly opted-in live test. Uses the feature QA package and an in-memory synthetic Library. */
class AiLiveRelayAndroidTest {
    @Test fun liveGatewayControlsOnlySyntheticCatalog() {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("qa_gateway").orEmpty()
        val code = args.getString("qa_pairing_code").orEmpty()
        assumeTrue("Live credentials are only supplied for the separate explicit integration run", origin.isNotEmpty() && code.isNotEmpty())
        check(BuildConfig.APPLICATION_ID == "org.schabi.newpipe.debug.featureaicontrol")
        val app = ApplicationProvider.getApplicationContext<Context>()
        val ready = File(app.cacheDir, "ai-live-ready.json")
        val finish = File(app.cacheDir, "ai-live-finish")
        ready.delete()
        finish.delete()
        LibraryRepository.forgetSessionForTest()
        val db = TestDatabase.createReplacingNewPipeDatabase()
        val binding = app.cacheDir.toPath().resolve("live-test-${UUID.randomUUID()}.db")
        val library = LibraryRepository.initialize(db, LibraryBindingStore(binding))
        db.runInTransaction {
            val ids = (1..3).map { number ->
                db.streamDAO().upsert(
                    StreamEntity(
                        serviceId = 0,
                        url = "https://www.youtube.com/watch?v=qa_synthetic_$number",
                        title = "AI QA synthetic track $number",
                        streamType = StreamType.VIDEO_STREAM,
                        duration = 60,
                        uploader = "Synthetic QA"
                    )
                )
            }
            LibraryRepository.appendDirectly(db, library, ids)
        }
        val runtime = AiControlRuntime.get(app)
        var scenario: ActivityScenario<AiControlActivity>? = null
        try {
            val paired = CountDownLatch(1)
            var pairingError: String? = null
            runtime.pair(origin, "Pixel 7 isolated AI QA", code, AiScope.entries.map { it.wireName }.toSet()) { error ->
                pairingError = error
                paired.countDown()
            }
            assertTrue("Pairing callback timed out", paired.await(35, TimeUnit.SECONDS))
            assertEquals(null, pairingError)
            scenario = ActivityScenario.launch(AiControlActivity::class.java)
            val enabled = CountDownLatch(1)
            var enableError: String? = null
            runtime.setEnabled(true) { error ->
                enableError = error
                enabled.countDown()
            }
            assertTrue(enabled.await(10, TimeUnit.SECONDS))
            assertEquals(null, enableError)
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, AiControlService::class.java).setAction(AiControlService.ACTION_START))
            }
            val connectedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (runtime.snapshot.connection != "CONNECTED" && System.nanoTime() < connectedBy) Thread.sleep(100)
            assertEquals("Connected state, fixed error=" + runtime.snapshot.error, "CONNECTED", runtime.snapshot.connection)
            val state = runtime.snapshot.state!!
            ready.writeText(
                buildJsonObject {
                    put("device_id", state.deviceId)
                    put("catalog_epoch", state.catalogEpoch)
                    put("library_id", library)
                    put("synthetic_items", 3)
                }.toString()
            )
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(180)
            while (!finish.exists() && System.nanoTime() < until) Thread.sleep(100)
            assertTrue("Host integration did not finish", finish.exists())
            assertEquals("host_verified", finish.readText().trim())
        } finally {
            val done = CountDownLatch(1)
            runtime.forget { done.countDown() }
            assertTrue("Disconnect cleanup did not finish", done.await(30, TimeUnit.SECONDS))
            scenario?.close()
            LibraryRepository.forgetSessionForTest()
            db.close()
            LibraryBindingStore.deleteForDatabase(binding)
        }
    }

    @Test fun optionalRealPublicUrlExtraction() {
        val url = InstrumentationRegistry.getArguments().getString("qa_public_url").orEmpty()
        assumeTrue("Network extraction smoke test is opt-in", url.isNotEmpty())
        check(BuildConfig.APPLICATION_ID == "org.schabi.newpipe.debug.featureaicontrol")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        val resolved = AiNativeUrlResolver { error ->
            val chain = generateSequence<Throwable>(error) { it.cause }.take(8).joinToString(" -> ") { it.javaClass.name }
            val locations = error.stackTrace.take(6).joinToString("; ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            throw AssertionError("Extraction diagnostic (no source payload): $chain; $locations")
        }.resolve(url) {
            if (System.nanoTime() >= deadline) throw AiFailure("EXPIRED", AiOutcome.NOT_EXECUTED)
        }
        assertTrue(resolved.entity.title.isNotBlank())
        assertTrue(resolved.entity.url.startsWith("https://"))
        assertTrue(resolved.service.isNotBlank())
        // Read-only extraction: no catalog or playlist write, no media playback.
    }
}
