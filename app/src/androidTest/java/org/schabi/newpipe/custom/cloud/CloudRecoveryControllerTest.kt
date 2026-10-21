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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.custom.tagging.CatalogFileSwap
import org.schabi.newpipe.custom.tagging.TagBackupCoordinator
import org.schabi.newpipe.custom.tagging.TagRuntime

/** Real native export/validation/apply and durable private records, with injected transport failures. */
class CloudRecoveryControllerTest {
    private lateinit var f: CloudApplyFixture
    private lateinit var peer: CloudFakeRecoveryPeer
    private lateinit var sync: CloudSyncController
    private lateinit var recovery: CloudRecoveryController
    private var shutdowns = 0

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        f = CloudApplyFixture(app, Files.createTempDirectory(app.noBackupFilesDir.toPath(), "cloud-recovery-"))
        f.initialize()
        f.seed()
        peer = CloudFakeRecoveryPeer(CloudFakePeer(f.scope))
        sync = CloudSyncController(f.context, CloudConnectionStore(f.context), { f.repo }, peer::client)
        recovery = controller()
        sync.connect("https://example.test", peer.catalog.tokenA)
    }
    private fun controller() = CloudRecoveryController(f.context, sync, peer::client) { context, prepared, admit ->
        TagBackupCoordinator.installReviewedWithShutdown(context, prepared, admit) { shutdowns++ }
    }

    @After fun cleanup() {
        f.close()
        CatalogFileSwap.deleteTree(f.dir)
    }
    private fun archiveCloud(): String {
        if (!peer.catalog.initialized) peer.catalog.external(f.capture().catalog)
        recovery.confirmUpload(recovery.prepareUpload("cloud-catalog"))
        return peer.copies.last().id
    }

    @Test fun nativeZipPassesRealDatabaseValidationAndContainsNoCloudCredential() {
        val p = recovery.prepareUpload("device-zip")
        assertNotNull(p.manifest.native)
        assertTrue(p.manifest.native!!.uncompressedBytes > 0)
        assertEquals("native-restore-v1", p.manifest.validation)
        recovery.confirmUpload(p)
        assertEquals(1, peer.created)
        assertFalse(recovery.hasPending())
        val restore = recovery.prepareRestore(peer.copies.single().id)
        restore.use {
            assertNotNull(it.prepared)
            assertFalse(it.file.readBytes().toString(Charsets.ISO_8859_1).contains(peer.catalog.tokenA))
            assertFalse(sync.connection.read()!!.enabled)
        }
    }

    @Test fun lostReservationReplyResumesAfterControllerRecreationWithoutAnotherBackup() {
        val p = recovery.prepareUpload("device-zip")
        peer.fault = "reserve.after"
        assertThrows(CloudRemoteException::class.java) { recovery.confirmUpload(p) }
        assertTrue(recovery.hasPending())
        recovery = controller()
        recovery.resume()
        assertEquals(1, peer.created)
        assertEquals(1, peer.copies.size)
        assertFalse(recovery.hasPending())
    }

    @Test fun lostPartReplyResumesTheSameImmutablePayloadAfterSyncIsDisabled() {
        val p = recovery.prepareUpload("device-zip")
        peer.fault = "part.after"
        assertThrows(CloudRemoteException::class.java) { recovery.confirmUpload(p) }
        sync.disable()
        f.media("Edited-after-backup")
        controller().resume()
        assertEquals(1, peer.created)
        assertEquals(p.manifest, peer.copies.single().manifest)
        assertTrue(f.capture().catalog.library.any { it.url.endsWith("Edited-after-backup") })
    }

    @Test fun modifiedLocalUploadIsRejectedAndPendingEvidenceIsKept() {
        val p = recovery.prepareUpload("device-zip")
        peer.fault = "reserve.after"
        assertThrows(CloudRemoteException::class.java) { recovery.confirmUpload(p) }
        val file = File(sync.connection.directory, "remote-recovery/pending.payload")
        val bytes = file.readBytes()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertThrows(IllegalStateException::class.java) { controller().resume() }
        assertTrue(recovery.hasPending())
        recovery.abort()
        assertFalse(recovery.hasPending())
    }

    @Test fun pendingRecoveryPreventsForgettingItsCredential() {
        peer.fault = "reserve.after"
        val p = recovery.prepareUpload("device-zip")
        assertThrows(CloudRemoteException::class.java) { recovery.confirmUpload(p) }
        assertThrows(IllegalStateException::class.java) { sync.forget() }
        recovery.abort()
        sync.forget()
        assertNull(sync.connection.read())
    }

    @Test fun historicalCatalogRestoreStaysLocalAndOffWithoutLoweringTheHighestSeenCloudHead() {
        val original = f.capture().catalog
        val archive = archiveCloud()
        peer.catalog.external(f.target(f.capture()))
        sync.confirm(sync.prepare(true))
        val high = sync.connection.read()!!.highest
        assertNotEquals(CloudCatalogCodec.fingerprint(original), f.capture().fingerprint)
        val cloud = peer.catalog.head
        recovery.prepareRestore(archive).use { recovery.confirmRestore(it) }
        assertEquals(CloudCatalogCodec.fingerprint(original), f.capture().fingerprint)
        assertEquals(cloud, peer.catalog.head)
        assertEquals(high, sync.connection.read()!!.highest)
        assertFalse(sync.connection.read()!!.enabled)
        assertEquals(0, shutdowns)
        sync.confirm(sync.prepare(true))
        assertEquals(cloud.fingerprint, f.capture().fingerprint)
    }

    @Test fun changedLocalContentsInvalidateArchiveRestoreBeforeSafetyBackupOrApply() {
        val archive = archiveCloud()
        recovery.prepareRestore(archive).use {
            f.media("After-preview")
            val before = f.capture().fingerprint
            assertEquals("CLOUD_LOCAL_PREVIEW_CHANGED", assertThrows(IllegalStateException::class.java) { recovery.confirmRestore(it) }.message)
            assertEquals(before, f.capture().fingerprint)
        }
    }

    @Test fun downloadedCorruptionNeverReachesNativeApply() {
        val archive = archiveCloud()
        peer.fault = "download.corrupt"
        val before = f.capture().fingerprint
        assertThrows(IllegalStateException::class.java) { recovery.prepareRestore(archive) }
        assertEquals(before, f.capture().fingerprint)
        assertFalse(sync.connection.read()!!.enabled)
    }

    @Test fun interruptedReleaseKeepsRestoreUnappliedAndDiscardsPreparedFiles() {
        val archive = archiveCloud()
        peer.fault = "release.after"
        assertEquals("RECOVERY_RELEASE_UNCONFIRMED", assertThrows(IllegalStateException::class.java) { recovery.prepareRestore(archive) }.message)
        assertFalse(File(sync.connection.directory, "remote-recovery/restore.payload").exists())
        assertFalse(sync.connection.read()!!.enabled)
    }

    @Test fun nativeWriterBarrierRejectsAnEditRacingAfterTheSafetyBackup() {
        recovery.confirmUpload(recovery.prepareUpload("device-zip"))
        val race = CloudRecoveryController(f.context, sync, peer::client) { context, prepared, admit ->
            f.media("Raced-at-install")
            TagBackupCoordinator.installReviewedWithShutdown(context, prepared, admit) { shutdowns++ }
        }
        race.prepareRestore(peer.copies.single().id).use {
            assertEquals("CLOUD_LOCAL_PREVIEW_CHANGED", assertThrows(IllegalStateException::class.java) { race.confirmRestore(it) }.message)
        }
        assertEquals(0, shutdowns)
        assertFalse(TagRuntime.restoring)
        assertTrue(f.capture().catalog.library.any { it.url.endsWith("Raced-at-install") })
    }

    @Test fun fullZipRestoresNativeStoresAndRequiresRestartWithCloudDisabled() {
        val before = f.capture().catalog
        recovery.confirmUpload(recovery.prepareUpload("device-zip"))
        f.media("After-backup")
        val p = recovery.prepareRestore(peer.copies.single().id)
        try {
            recovery.confirmRestore(p)
        } finally {
            p.close()
        }
        assertEquals(1, shutdowns)
        assertTrue(TagRuntime.restoring)
        f.reopen()
        assertEquals(before.library, f.capture().catalog.library)
        assertFalse(sync.connection.read()?.enabled == true)
        assertEquals(0L, f.sideScalar("SELECT COUNT(*) FROM cloud_sync_baseline"))
    }
}
