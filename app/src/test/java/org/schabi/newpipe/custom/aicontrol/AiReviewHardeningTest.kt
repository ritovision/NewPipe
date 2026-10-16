/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import io.reactivex.rxjava3.core.Single
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReviewHardeningTest {
    @Test fun stalePolicyPublicationCannotUndoAStop() {
        val gate = AiPolicyGate()
        val starting = gate.invalidate()
        gate.invalidate()
        var published = false
        assertFalse(gate.commit(starting, true) { published = true })
        assertFalse(gate.enabled)
        assertFalse(published)
    }

    @Test fun stopQueuedBeforeConfigurationIsPersistedInsteadOfDiscarded() {
        val gate = AiPolicyGate()
        var storedEnabled = true
        var storedName = "original"
        val queue = mutableListOf<() -> Unit>()
        gate.enqueueChange({}) { version ->
            queue.add {
                gate.applyOrdered<Boolean>(version, {
                    storedEnabled = false
                    storedEnabled
                }, { it }) {}
            }
        }
        gate.enqueueChange({}) { version ->
            queue.add {
                gate.applyOrdered<Boolean>(version, {
                    storedName = "changed"
                    storedEnabled
                }, { it }) {}
            }
        }
        queue.forEach { it() }
        assertEquals("changed", storedName)
        assertFalse(storedEnabled)
        assertFalse(gate.enabled)
    }

    @Test fun revokeQueuedBeforeConfigurationRemainsInStoredPolicy() {
        val gate = AiPolicyGate()
        val blocked = mutableSetOf<String>()
        val queue = mutableListOf<() -> Unit>()
        gate.enqueueChange({}) { version ->
            queue.add {
                gate.applyOrdered<Boolean>(version, {
                    blocked.add("client")
                    true
                }, { it }) {}
            }
        }
        gate.enqueueChange({}) { version -> queue.add { gate.applyOrdered<Boolean>(version, { true }, { it }) {} } }
        queue.forEach { it() }
        assertTrue(blocked.contains("client"))
        assertTrue(gate.enabled)
    }

    @Test fun onlyAnExplicitLaterEnableReversesStop() {
        val gate = AiPolicyGate()
        var stored = true
        val queue = mutableListOf<() -> Unit>()
        gate.enqueueChange({}) { v ->
            queue.add {
                gate.applyOrdered<Boolean>(v, {
                    stored = false
                    stored
                }, { it }) {}
            }
        }
        gate.enqueueChange({}) { v ->
            queue.add {
                gate.applyOrdered<Boolean>(v, {
                    stored = true
                    stored
                }, { it }) {}
            }
        }
        queue.forEach { it() }
        assertTrue(stored)
        assertTrue(gate.enabled)
    }

    @Test fun simultaneousPolicyEnqueueMatchesVersionOrder() {
        val gate = AiPolicyGate()
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val versions = mutableListOf<Long>()
        try {
            val tasks = (1..2).map {
                workers.submit {
                    start.await()
                    gate.enqueueChange({}) { version -> versions.add(version) }
                }
            }
            start.countDown()
            tasks.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(listOf(1L, 2L), versions)
            assertFalse(gate.enabled)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun stopAndPublicationAreOneLinearizedDecision() {
        val gate = AiPolicyGate()
        val version = gate.invalidate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newFixedThreadPool(2)
        try {
            val enable = worker.submit {
                gate.commit(version, true) {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stop = worker.submit { gate.invalidate() }
            release.countDown()
            enable.get(2, TimeUnit.SECONDS)
            stop.get(2, TimeUnit.SECONDS)
            assertFalse(gate.enabled)
            assertFalse(gate.commit(version, true))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun closedConnectionLeaseDoesNotDisableItsReplacement() {
        val gate = AiAccessController()
        val client = "c_" + "a".repeat(64)
        val active = AtomicBoolean(true)
        gate.enableGatewayFromLocalConsent(AiTestFixtures.DEVICE, AiTestFixtures.EPOCH, setOf(AiScope.LIBRARY_READ))
        val old = gate.openAuthenticatedGatewaySession(client, setOf(AiScope.LIBRARY_READ), active::get)
        val newer = gate.openAuthenticatedGatewaySession(client, setOf(AiScope.LIBRARY_READ))
        active.set(false)
        val request = AiTestFixtures.request().copy(clientId = client)
        AiTestFixtures.failure("SESSION_CLOSED") { gate.withAuthorization(request, old) {} }
        var accessed = false
        gate.withAuthorization(request, newer) {
            it.checkActive()
            accessed = true
        }
        assertTrue(accessed)
    }

    @Test fun unconfiguredMediaOriginsCannotEnterDiscoveryOrPlayback() {
        for (host in listOf("attacker.example", "youtube.com.attacker.example", "notyoutube.com", "private-bandcamp.example")) {
            assertFalse(AiMediaHostPolicy.trustedHost(host, emptySet()))
            AiTestFixtures.failure("UNTRUSTED_MEDIA_HOST") { AiMediaHostPolicy.validate("https://$host/video", emptySet()) }
        }
        assertTrue(AiMediaHostPolicy.trustedHost("www.youtube.com", emptySet()))
        assertTrue(AiMediaHostPolicy.trustedHost("artist.bandcamp.com", emptySet()))
        assertTrue(AiMediaHostPolicy.trustedHost("configured.example", setOf("configured.example")))
        assertFalse(AiMediaHostPolicy.trustedHost("other.configured.example", setOf("configured.example")))
    }

    @Test fun restrictedCookiesNeverLeakToNonYoutubeHosts() {
        assertEquals("PREF=f2=8000000", AiExtractionNetwork.restrictedCookies("https://www.youtube.com/watch?v=x", "PREF=f2=8000000"))
        assertEquals("", AiExtractionNetwork.restrictedCookies("https://soundcloud.com/x", "PREF=f2=8000000"))
        assertEquals("", AiExtractionNetwork.restrictedCookies("https://youtube.com.attacker.example/x", "PREF=f2=8000000"))
    }

    @Test fun capturedNetworkScopeRemainsRestrictedOnAnotherThread() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            AiExtractionNetwork.restricted({}) {
                val captured = AiExtractionNetwork.capture()
                assertTrue(captured != null)
                val result = worker.submit<Boolean> {
                    assertFalse(AiExtractionNetwork.isRestricted())
                    AiExtractionNetwork.withContext(captured) { AiExtractionNetwork.isRestricted() }
                }.get(2, TimeUnit.SECONDS)
                assertTrue(result)
            }
            assertFalse(AiExtractionNetwork.isRestricted())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun capturedNetworkScopeCannotBeReusedAfterItsOwnerFinishes() {
        lateinit var captured: AiExtractionNetwork.CapturedContext
        AiExtractionNetwork.restricted({}) { captured = AiExtractionNetwork.capture()!! }
        AiTestFixtures.failure("SESSION_CLOSED") { AiExtractionNetwork.withContext(captured) {} }
        assertFalse(AiExtractionNetwork.isRestricted())
    }

    @Test fun stopCancelsAnOtherwiseUnboundedTokenWait() {
        val entered = CountDownLatch(1)
        val disposed = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<String> {
                try {
                    AiExtractionNetwork.restricted({}) {
                        AiExtractionNetwork.awaitSingle(Single.never<String>().doOnSubscribe { entered.countDown() }.doOnDispose { disposed.set(true) })
                    }
                    "unexpected"
                } catch (error: AiFailure) {
                    error.code
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            AiExtractionNetwork.cancelActive()
            assertEquals("SESSION_CLOSED", result.get(2, TimeUnit.SECONDS))
            assertTrue(disposed.get())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun nat64PolicyChecksEmbeddedIpv4AndRejectsTransitionBypasses() {
        assertTrue(AiPublicUrlPolicy.isPublic(InetAddress.getByName("64:ff9b::808:808")))
        assertFalse(AiPublicUrlPolicy.isPublic(InetAddress.getByName("64:ff9b::7f00:1")))
        assertFalse(AiPublicUrlPolicy.isPublic(InetAddress.getByName("64:ff9b::c0a8:101")))
        assertFalse(AiPublicUrlPolicy.isPublic(InetAddress.getByName("2002:7f00:1::1")))
        assertFalse(AiPublicUrlPolicy.isPublic(InetAddress.getByName("2001:0:1::1")))
    }

    @Test fun extractionCanCancelWhileWaitingForTokenCacheLock() {
        val lock = java.util.concurrent.locks.ReentrantLock()
        val entered = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            val result = worker.submit<String> {
                try {
                    AiExtractionNetwork.restricted({ entered.countDown() }) {
                        AiExtractionNetwork.withLock(lock) { "should not enter" }
                    }
                } catch (error: AiFailure) {
                    error.code
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            AiExtractionNetwork.cancelActive()
            assertEquals("SESSION_CLOSED", result.get(2, TimeUnit.SECONDS))
        } finally {
            lock.unlock()
            worker.shutdownNow()
        }
    }

    @Test fun discardedGeneratorIsReleasedEvenIfSuccessWasNeverDelivered() {
        val released = AtomicBoolean(false)
        AiExtractionNetwork.restricted({}) {
            assertTrue(AiExtractionNetwork.trackUnclaimed(AiExtractionNetwork.capture(), Any()) { released.set(true) })
        }
        assertTrue(released.get())
    }

    @Test fun acceptedGeneratorOwnershipIsNotClosedByScopeCleanup() {
        val released = AtomicBoolean(false)
        val generator = Any()
        AiExtractionNetwork.restricted({}) {
            assertTrue(AiExtractionNetwork.trackUnclaimed(AiExtractionNetwork.capture(), generator) { released.set(true) })
            assertEquals(generator, AiExtractionNetwork.awaitSingle(Single.just(generator)))
        }
        assertFalse(released.get())
    }

    @Test fun generatorConstructedAfterCancellationIsImmediatelyReleased() {
        lateinit var captured: AiExtractionNetwork.CapturedContext
        AiExtractionNetwork.restricted({}) { captured = AiExtractionNetwork.capture()!! }
        val released = AtomicBoolean(false)
        assertFalse(AiExtractionNetwork.trackUnclaimed(captured, Any()) { released.set(true) })
        assertTrue(released.get())
    }

    @Test fun admittedRevocationPersistsBeforeMaintenanceWhilePublicationStaysDisabled() {
        val gate = AiPolicyGate()
        val blocked = mutableSetOf<String>()
        val queued = mutableListOf<() -> Unit>()
        gate.enqueueChange({}) { version ->
            queued.add {
                gate.applyOrdered<Boolean>(version, {
                    blocked.add("client")
                    false
                }, { it }) {}
            }
        }
        gate.invalidate() // entering the restore barrier must not erase the admitted intent
        queued.forEach { it() }
        assertTrue(blocked.contains("client"))
        assertFalse(gate.enabled)
    }
}
