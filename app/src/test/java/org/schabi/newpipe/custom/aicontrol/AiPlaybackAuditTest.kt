/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlaybackAuditTest {
    private fun access() = AiAccessController().apply {
        enableFromLocalConsent(AiTestFixtures.DEVICE, AiTestFixtures.EPOCH, mapOf("test-client" to setOf(AiScope.PLAYBACK_CONTROL)))
    }
    private fun raw(command: AiCatalogCommand = AiCatalogCommand.Pause) = AiProtocol.json.encodeToString(AiTestFixtures.request(command))

    @Test fun cancelBeforeDispatchPreventsAnyEffect() {
        val call = AiMainThreadCall<Int>()
        assertEquals(AiMainThreadCall.Phase.PENDING, call.cancel())
        var effects = 0
        call.run {
            effects++
            5
        }
        assertEquals(0, effects)
    }

    @Test fun cancelledStartedCallFailsAtLastEffectCheck() {
        val call = AiMainThreadCall<Int>()
        var effects = 0
        call.run {
            assertEquals(AiMainThreadCall.Phase.RUNNING, call.cancel())
            call.checkActive()
            effects++
            5
        }
        assertEquals(0, effects)
        AiTestFixtures.failure("CANCELLED") { call.result() }
    }

    @Test fun aMainThreadCallCannotRunTwice() {
        val call = AiMainThreadCall<Int>()
        var effects = 0
        call.run {
            effects++
            4
        }
        call.run {
            effects++
            8
        }
        assertEquals(4, call.result())
        assertEquals(1, effects)
    }

    @Test fun validationFailureKeepsCorrelationIdentifiers() {
        val gate = access()
        val executor = AiPlaybackExecutor(gate, AiPlaybackPort { _, _ -> buildJsonObject {} }) { AiTestFixtures.NOW }
        val command = AiCatalogCommand.SkipIfCurrent(-1, "YouTube", "https://example.org/a", AiTestFixtures.OTHER + ":0")
        val result = executor.execute(raw(command), gate.openAuthenticatedSession("test-client"))
        assertEquals("INVALID_ARGUMENT", result.code)
        assertEquals(AiTestFixtures.REQUEST, result.requestId)
        assertEquals(AiTestFixtures.DEVICE, result.deviceId)
    }

    @Test fun forgottenCompletionIsUnknownNotUnexecuted() {
        var now = AiTestFixtures.NOW
        val gate = access()
        val calls = AtomicInteger()
        val executor = AiPlaybackExecutor(
            gate,
            AiPlaybackPort { _, _ ->
                calls.incrementAndGet()
                buildJsonObject {}
            }
        ) { now }
        val session = gate.openAuthenticatedSession("test-client")
        assertEquals(AiOutcome.COMPLETED, executor.execute(raw(), session).outcome)
        now += 181_000
        assertEquals(AiOutcome.OUTCOME_UNKNOWN, executor.execute(raw(), session).outcome)
        assertEquals(1, calls.get())
    }

    @Test fun previousProcessEpochCannotExecuteAgain() {
        val gate = access()
        val calls = AtomicInteger()
        val executor = AiPlaybackExecutor(
            gate,
            AiPlaybackPort { _, _ ->
                calls.incrementAndGet()
                buildJsonObject {}
            },
            AiTestFixtures.OTHER
        ) { AiTestFixtures.NOW }
        val result = executor.execute(raw(), gate.openAuthenticatedSession("test-client"))
        assertEquals("STALE_PLAYBACK_SESSION", result.code)
        assertEquals(AiOutcome.OUTCOME_UNKNOWN, result.outcome)
        assertEquals(0, calls.get())
    }

    @Test fun concurrentRetriesHaveOneEffect() {
        val gate = access()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = AiPlaybackExecutor(
            gate,
            AiPlaybackPort { _, check ->
                entered.countDown()
                kotlin.check(release.await(5, TimeUnit.SECONDS))
                check()
                calls.incrementAndGet()
                buildJsonObject {}
            }
        ) { AiTestFixtures.NOW }
        val session = gate.openAuthenticatedSession("test-client")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<AiResult> { executor.execute(raw(), session) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val b = pool.submit<AiResult> { executor.execute(raw(), session) }
            release.countDown()
            assertEquals(AiOutcome.COMPLETED, a.get(5, TimeUnit.SECONDS).outcome)
            assertTrue(b.get(5, TimeUnit.SECONDS).replayed)
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun disconnectBetweenAdmissionAndEffectPreventsAction() {
        val gate = access()
        val session = gate.openAuthenticatedSession("test-client")
        var calls = 0
        val executor = AiPlaybackExecutor(
            gate,
            AiPlaybackPort { _, checked ->
                session.disconnect()
                checked()
                calls++
                buildJsonObject {}
            }
        ) { AiTestFixtures.NOW }
        val result = executor.execute(raw(), session)
        assertEquals("SESSION_CLOSED", result.code)
        assertEquals(0, calls)
    }
}
