/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlaybackExecutorTest {
    private fun configuredAccess(scopes: Set<AiScope> = setOf(AiScope.PLAYBACK_CONTROL)) = AiAccessController().apply {
        enableFromLocalConsent(
            AiTestFixtures.DEVICE,
            AiTestFixtures.EPOCH,
            mapOf("test-client" to scopes)
        )
    }

    private fun raw(command: AiCatalogCommand) = AiProtocol.json.encodeToString(AiTestFixtures.request(command))

    @Test
    fun pauseRunsOnceForIdenticalRetransmission() {
        val access = configuredAccess()
        var calls = 0
        val port = AiPlaybackPort { command, check ->
            check()
            assertEquals(AiCatalogCommand.Pause, command)
            calls++
            buildJsonObject { put("paused", true) }
        }
        val executor = AiPlaybackExecutor(access, port) { AiTestFixtures.NOW }
        val session = access.openAuthenticatedSession("test-client")
        val original = executor.execute(raw(AiCatalogCommand.Pause), session)
        val replay = executor.execute(raw(AiCatalogCommand.Pause), session)
        assertEquals(AiOutcome.COMPLETED, original.outcome)
        assertEquals(AiOutcome.COMPLETED, replay.outcome)
        assertFalse(original.replayed)
        assertTrue(replay.replayed)
        assertEquals(1, calls)
    }

    @Test
    fun reusedRequestIdWithDifferentEffectIsRejected() {
        val access = configuredAccess()
        var calls = 0
        val executor = AiPlaybackExecutor(
            access,
            AiPlaybackPort { _, _ ->
                calls++
                buildJsonObject {}
            }
        ) { AiTestFixtures.NOW }
        val session = access.openAuthenticatedSession("test-client")
        executor.execute(raw(AiCatalogCommand.Pause), session)
        val result = executor.execute(raw(AiCatalogCommand.Resume), session)
        assertEquals("REQUEST_ID_REUSED", result.code)
        assertEquals(AiOutcome.REJECTED, result.outcome)
        assertEquals(1, calls)
    }

    @Test
    fun unknownOutcomeCannotBeRetriedToCauseAnotherSkip() {
        val access = configuredAccess()
        var calls = 0
        val executor = AiPlaybackExecutor(
            access,
            AiPlaybackPort { _, _ ->
                calls++
                throw AiFailure("PLAYER_TIMEOUT", AiOutcome.OUTCOME_UNKNOWN)
            }
        ) { AiTestFixtures.NOW }
        val session = access.openAuthenticatedSession("test-client")
        val command = AiCatalogCommand.SkipIfCurrent(3, "YouTube", "https://example.org/a", AiTestFixtures.OTHER + ":0")
        val first = executor.execute(raw(command), session)
        val replay = executor.execute(raw(command), session)
        assertEquals(AiOutcome.OUTCOME_UNKNOWN, first.outcome)
        assertEquals(AiOutcome.OUTCOME_UNKNOWN, replay.outcome)
        assertTrue(replay.replayed)
        assertEquals(1, calls)
    }

    @Test
    fun readOnlyNowPlayingRunsWithoutMutationReceipt() {
        val access = configuredAccess()
        var calls = 0
        val executor = AiPlaybackExecutor(
            access,
            AiPlaybackPort { _, check ->
                check()
                calls++
                buildJsonObject { put("active", false) }
            }
        ) { AiTestFixtures.NOW }
        val session = access.openAuthenticatedSession("test-client")
        val first = executor.execute(raw(AiCatalogCommand.NowPlaying), session)
        val second = executor.execute(raw(AiCatalogCommand.NowPlaying), session)
        assertEquals(AiOutcome.COMPLETED, first.outcome)
        assertEquals(AiOutcome.COMPLETED, second.outcome)
        assertEquals(2, calls)
        assertFalse(second.replayed)
    }

    @Test
    fun blockedPermissionCannotReadOrControlPlayer() {
        val access = configuredAccess(emptySet())
        var calls = 0
        val executor = AiPlaybackExecutor(
            access,
            AiPlaybackPort { _, _ ->
                calls++
                buildJsonObject {}
            }
        ) { AiTestFixtures.NOW }
        val session = access.openAuthenticatedSession("test-client")
        val result = executor.execute(raw(AiCatalogCommand.NowPlaying), session)
        assertEquals("PERMISSION_DENIED", result.code)
        assertEquals(AiOutcome.NOT_EXECUTED, result.outcome)
        assertEquals(0, calls)
    }

    @Test
    fun disconnectedSessionCannotReplayPriorCommands() {
        val access = configuredAccess()
        var calls = 0
        val executor = AiPlaybackExecutor(
            access,
            AiPlaybackPort { _, _ ->
                calls++
                buildJsonObject {}
            }
        ) { AiTestFixtures.NOW }
        val session = access.openAuthenticatedSession("test-client")
        executor.execute(raw(AiCatalogCommand.Pause), session)
        session.disconnect()
        val result = executor.execute(raw(AiCatalogCommand.Pause), session)
        assertEquals(AiOutcome.NOT_EXECUTED, result.outcome)
        assertEquals("SESSION_CLOSED", result.code)
        assertEquals(1, calls)
    }

    @Test
    fun lateCommandCannotReachPlayer() {
        val access = configuredAccess()
        var calls = 0
        val executor = AiPlaybackExecutor(
            access,
            AiPlaybackPort { _, _ ->
                calls++
                buildJsonObject {}
            }
        ) { AiTestFixtures.NOW + 31_000 }
        val result = executor.execute(
            raw(AiCatalogCommand.SkipIfCurrent(0, "YouTube", "https://example.org/a", AiTestFixtures.OTHER + ":0")),
            access.openAuthenticatedSession("test-client")
        )
        assertEquals("EXPIRED", result.code)
        assertEquals(AiOutcome.NOT_EXECUTED, result.outcome)
        assertEquals(0, calls)
    }

    @Test
    fun catalogCommandsAreNotAcceptedByThePlaybackExecutor() {
        val access = configuredAccess(setOf(AiScope.LIBRARY_READ))
        val executor = AiPlaybackExecutor(access, AiPlaybackPort { _, _ -> buildJsonObject {} }) {
            AiTestFixtures.NOW
        }
        val result = executor.execute(raw(AiCatalogCommand.Status), access.openAuthenticatedSession("test-client"))
        assertEquals("INVALID_DISPATCH", result.code)
    }

    @Test
    fun checkedSkipRejectsTheNextOccurrenceOfTheSameTrack() {
        assertTrue(AiSkipGuard.permits(2, "YouTube", "https://example.org/x", 2, "YouTube", "https://example.org/x", 5))
        assertFalse(AiSkipGuard.permits(2, "YouTube", "https://example.org/x", 3, "YouTube", "https://example.org/x", 5))
        assertFalse(AiSkipGuard.permits(2, "YouTube", "https://example.org/x", 2, "YouTube", "https://example.org/y", 5))
        assertFalse(AiSkipGuard.permits(0, "YouTube", "https://example.org/x", 0, "YouTube", "https://example.org/x", 1))
    }
}
