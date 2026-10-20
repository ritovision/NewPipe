/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.custom.tagging.CatalogFileSwap

/** Native captures, private durable records and baseline handling under transport interruptions. */
class CloudReplacementControllerTest {
    private lateinit var f: CloudApplyFixture
    private lateinit var peer: CloudFakeReplacementPeer
    private lateinit var sync: CloudSyncController
    private fun controller() = CloudReplacementController(f.context, sync, peer::client)
    private fun root() = File(sync.connection.directory, "remote-replacement")

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        f = CloudApplyFixture(app, Files.createTempDirectory(app.cacheDir.toPath(), "cloud-replacement-"))
        f.initialize()
        f.seed()
        peer = CloudFakeReplacementPeer(CloudFakeRecoveryPeer(CloudFakePeer(f.scope)))
        sync = CloudSyncController(f.context, CloudConnectionStore(f.context), { f.repo }, peer::client)
        sync.connect("https://example.test", peer.catalog.tokenA)
        peer.catalog.external(CloudCatalog())
    }

    @After fun cleanup() {
        f.close()
        CatalogFileSwap.deleteTree(f.dir)
    }

    @Test fun previewAndCancelAllocateNothingAndLeaveCloudUnchanged() {
        val c = controller()
        val head = peer.catalog.head
        c.prepare()
        c.cancelPreview()
        assertEquals(head, peer.catalog.head)
        assertEquals(0, peer.recovery.created)
        assertFalse(c.hasPending())
        assertFalse(sync.connection.read()!!.enabled)
    }

    @Test fun confirmationArchivesOutgoingCloudThenPublishesFrozenLocalCatalog() {
        val c = controller()
        val local = f.capture().catalog
        val p = c.prepare()
        val outgoing = peer.catalog.head
        c.confirm(p)
        assertEquals(local, peer.catalog.catalog())
        assertEquals(outgoing, peer.recovery.copies.single().manifest.source!!.head)
        assertEquals(p.nextEpoch, peer.catalog.head.epoch)
        assertEquals("1", peer.catalog.head.revision)
        assertEquals(local, f.capture().catalog)
        assertFalse(c.hasPending())
        assertFalse(sync.connection.read()!!.enabled)
        assertEquals(peer.catalog.head, sync.connection.read()!!.highest)
    }

    @Test fun changedNativeContentsInvalidateConfirmationBeforeReservation() {
        val c = controller()
        val p = c.prepare()
        f.media("Changed-before-confirmation")
        assertThrows(IllegalStateException::class.java) { c.confirm(p) }
        assertEquals(0, peer.recovery.created)
        assertNull(peer.attempt)
        assertFalse(c.hasPending())
    }

    @Test fun lostReservationReplyResumesSamePayloadAfterControllerRecreation() {
        val c = controller()
        val p = c.prepare()
        peer.fault = "reserve.after"
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        assertTrue(c.hasPending())
        controller().resume()
        assertEquals(1, peer.recovery.created)
        assertEquals(p.before.fingerprint, peer.catalog.head.fingerprint)
        assertFalse(c.hasPending())
    }

    @Test fun unconfirmedReservationAfterRecreationRequiresResolutionAndFreshPreview() {
        val c = controller()
        val p = c.prepare()
        peer.fault = "reserve.before"
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        assertThrows(IllegalStateException::class.java) { controller().resume() }
        assertEquals(0, peer.recovery.created)
        controller().cancel()
        assertFalse(c.hasPending())
        assertEquals(CloudCatalog(), peer.catalog.catalog())
        assertEquals(1L, peer.catalog.sequence())
    }

    @Test fun laterLocalEditsStayPendingWhileOnlyApprovedBytesArePublished() {
        val c = controller()
        val p = c.prepare()
        peer.fault = "reserve.after"
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        sync.disable()
        f.media("Made-after-reservation")
        controller().resume()
        assertEquals(p.before.fingerprint, peer.catalog.head.fingerprint)
        assertNotEquals(p.before.fingerprint, f.capture().fingerprint)
        assertTrue(f.capture().catalog.library.any { it.url.endsWith("Made-after-reservation") })
        assertFalse(sync.connection.read()!!.enabled)
    }

    @Test fun lostPublicationReplyResolvesWithoutPayloadOrAnotherEpoch() {
        val c = controller()
        val p = c.prepare()
        peer.fault = "publish.after"
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        val committed = peer.catalog.head
        assertTrue(File(root(), "outgoing.payload").delete())
        assertTrue(File(root(), "outbox/0.json").delete())
        controller().resume()
        assertEquals(committed, peer.catalog.head)
        assertEquals(1, peer.recovery.created)
        assertFalse(c.hasPending())
    }

    @Test fun corruptedOutgoingFileNeverReachesPublicationAndKeepsPendingEvidence() {
        val c = controller()
        val p = c.prepare()
        peer.fault = "reserve.after"
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        val file = File(root(), "outgoing.payload")
        val bytes = file.readBytes()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertThrows(IllegalStateException::class.java) { controller().resume() }
        assertTrue(c.hasPending())
        assertEquals(CloudCatalog(), peer.catalog.catalog())
        controller().cancel()
        assertFalse(c.hasPending())
    }

    @Test fun corruptedIncomingChunkCannotBeSubstitutedByCurrentLocalData() {
        val c = controller()
        val p = c.prepare()
        peer.fault = "reserve.after"
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        File(root(), "outbox/0.json").writeText("[]")
        assertThrows(IllegalStateException::class.java) { controller().resume() }
        assertTrue(c.hasPending())
        assertEquals(CloudCatalog(), peer.catalog.catalog())
        controller().cancel()
    }

    @Test fun unresolvedReplacementBlocksForgetOrdinaryPreviewAndRecoveryRestore() {
        val c = controller()
        val p = c.prepare()
        peer.fault = "reserve.after"
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        assertThrows(IllegalStateException::class.java) { sync.forget() }
        assertThrows(IllegalStateException::class.java) { sync.prepare(false) }
        val recovery = CloudRecoveryController(f.context, sync, peer::client)
        assertThrows(IllegalStateException::class.java) { recovery.prepareRestore("00000000-0000-0000-0000-000000000000") }
        c.cancel()
        sync.forget()
        assertNull(sync.connection.read())
    }

    @Test fun stalePublicationRetiresExactIncomingSequenceAndRetainsItsRecoveryCopy() {
        val c = controller()
        val p = c.prepare()
        peer.before = { path ->
            if (path == "/v1/replacement/publish") {
                peer.before = null
                peer.catalog.external(CloudCatalog())
            }
        }
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        assertEquals("ACTIVE", peer.attempt!!.getString("state"))
        c.resolveForRetry()
        assertEquals("STALE", peer.attempt!!.getString("state"))
        assertTrue(c.hasPending())
        assertEquals(1, peer.recovery.copies.size)
        val fresh = c.prepare()
        assertNotEquals(p.approval, fresh.approval)
        assertNotEquals(p.upload.header["sequence"], fresh.upload.header["sequence"])
        c.cancelPreview()
        c.cancel()
        assertEquals(1, peer.recovery.copies.size)
        assertFalse(c.hasPending())
    }

    @Test fun replacedNativeIdentityCannotResumeAnOldApprovedBranch() {
        val c = controller()
        val p = c.prepare()
        peer.fault = "reserve.after"
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        f.repo.store.transaction { CloudIdentityStore.discard(it) }
        assertThrows(IllegalStateException::class.java) { controller().resume() }
        assertEquals(CloudCatalog(), peer.catalog.catalog())
        c.cancel()
        assertFalse(c.hasPending())
    }

    @Test fun supersededRecoveryPreviewDoesNotTrapAnUnadmittedReplacement() {
        val c = controller()
        val p = c.prepare()
        val recovery = CloudRecoveryController(f.context, sync, peer::client)
        recovery.prepareUpload("cloud-catalog")
        assertThrows(IllegalStateException::class.java) { c.confirm(p) }
        assertNull(peer.attempt)
        controller().cancel()
        assertFalse(c.hasPending())
        assertEquals(0, peer.recovery.created)
        assertEquals(0L, peer.recovery.sequence)
        assertEquals(CloudCatalog(), peer.catalog.catalog())
        recovery.cancelUploadPreview()
        sync.forget()
        assertNull(sync.connection.read())
    }

    @Test fun cancelingUnadmittedRetryPreservesEarlierStaleAuthorityAcrossRecreation() {
        val c = controller()
        val p = c.prepare()
        peer.before = { path ->
            if (path == "/v1/replacement/publish") {
                peer.before = null
                peer.catalog.external(CloudCatalog())
            }
        }
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        c.resolveForRetry()
        val fresh = c.prepare()
        peer.fault = "reserve.before"
        assertThrows(CloudRemoteException::class.java) { c.confirm(fresh) }
        val retained = peer.recovery.copies.single().id
        val result = controller().cancel()
        assertTrue(result.pending)
        assertEquals(p.approval, peer.attempt!!.getString("attemptId"))
        assertEquals("STALE", controller().pendingPhase())
        assertFalse(File(root(), "pending.json").exists())
        assertTrue(File(root(), "observed.json").exists())
        assertThrows(IllegalStateException::class.java) { sync.forget() }
        assertThrows(IllegalStateException::class.java) { sync.prepare(false) }
        controller().cancel()
        assertEquals("CANCELED", peer.attempt!!.getString("state"))
        assertEquals(retained, peer.recovery.copies.single().id)
        assertFalse(controller().hasPending())
        sync.forget()
        assertNull(sync.connection.read())
    }

    @Test fun observedActiveAttemptBlocksForgetWithoutLocalPayloadAndCanBeResolved() {
        val c = controller()
        val p = c.prepare()
        peer.fault = "reserve.after"
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        sync.inspect()
        assertTrue(File(root(), "observed.json").exists())
        assertTrue(File(root(), "pending.json").delete())
        assertTrue(File(root(), "outgoing.payload").delete())
        assertTrue(File(root(), "outbox").deleteRecursively())
        assertTrue(controller().hasPending())
        assertEquals("ACTIVE", controller().pendingPhase())
        assertThrows(IllegalStateException::class.java) { sync.forget() }
        controller().cancel()
        assertEquals("CANCELED", peer.attempt!!.getString("state"))
        assertFalse(controller().hasPending())
        sync.forget()
        assertNull(sync.connection.read())
    }

    @Test fun failedAuthorityRefreshKeepsLastIntentUntilCancellationIsResolved() {
        val c = controller()
        val p = c.prepare()
        peer.fault = "reserve.after"
        assertThrows(CloudRemoteException::class.java) { c.confirm(p) }
        peer.before = { path ->
            if (path == "/v1/connect") {
                peer.before = null
                throw CloudRemoteException("CLOUD_NETWORK_UNAVAILABLE")
            }
        }
        assertThrows(CloudRemoteException::class.java) { controller().cancel() }
        assertEquals("CANCELED", peer.attempt!!.getString("state"))
        assertTrue(File(root(), "pending.json").exists())
        assertThrows(IllegalStateException::class.java) { sync.forget() }
        controller().cancel()
        assertFalse(controller().hasPending())
    }
}
