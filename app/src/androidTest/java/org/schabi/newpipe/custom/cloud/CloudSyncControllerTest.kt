/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.concurrent.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.custom.tagging.CatalogFileSwap
import org.schabi.newpipe.custom.tagging.StreamKey
import org.schabi.newpipe.custom.tagging.TagRuntime

/** Real Android persistence/encryption/native apply with a deterministic peer for network faults. */
class CloudSyncControllerTest {
    private lateinit var f: CloudApplyFixture
    private lateinit var peer: CloudFakePeer
    private lateinit var connection: CloudConnectionStore
    private lateinit var controller: CloudSyncController
    private var backups = 0

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        f = CloudApplyFixture(app, Files.createTempDirectory(app.cacheDir.toPath(), "cloud-controller-"))
        f.initialize()
        peer = CloudFakePeer(f.scope)
        connection = CloudConnectionStore(f.context, f.dir.resolve("connection").toFile())
        controller = newController()
    }
    private fun newController(realBackup: Boolean = false) = CloudSyncController(
        f.context,
        connection,
        { f.repo },
        peer::client,
        if (realBackup) null else { _: CloudNativeSnapshot -> backups++ }
    )

    @After fun cleanup() {
        f.close()
        CatalogFileSwap.deleteTree(f.dir)
    }
    private fun connect() = controller.connect("https://example.test", peer.tokenA)
    private fun enable() {
        if (connection.read() == null) connect()
        controller.confirm(controller.prepare(true))
        assertTrue(connection.read()!!.enabled)
    }
    private fun same(): Boolean = f.capture().fingerprint == CloudCatalogCodec.fingerprint(peer.catalog())
    private fun reject(code: String, action: () -> Unit) {
        assertEquals(code, assertThrows(IllegalStateException::class.java, action).message)
    }

    @Test fun connectRetainsLocalContentAndStartsDisabledWithEncryptedCredential() {
        val before = f.seed()
        val s = connect()
        assertFalse(s.enabled)
        assertFalse(peer.initialized)
        assertEquals(before.fingerprint, f.capture().fingerprint)
        val raw = File(connection.directory, "connection.json").readText()
        assertFalse(raw.contains(peer.tokenA))
        assertFalse(raw.contains("a".repeat(64)))
        assertEquals(peer.tokenA, connection.token(s))
        assertEquals(s.generation, CloudConnectionStore(f.context, connection.directory).read()!!.generation)
        assertFalse(s.toString().contains(peer.tokenA))
    }

    @Test fun encryptedCredentialIsBoundToTheDestinationAndScope() {
        connect()
        val file = AtomicFile(File(connection.directory, "connection.json"))
        val o = CloudWireJson.obj(String(CloudTransferStore.read(file, 65536)))
        o["origin"] = "https://attacker.example"
        CloudTransferStore.write(file, CloudWireJson.encode(o).toByteArray())
        reject("CLOUD_CREDENTIAL_UNAVAILABLE_RECONNECT") { connection.token(connection.read()!!) }
    }

    @Test fun initialEnableUsesExplicitPreviewThenPublishesAndAcceptsBaseline() {
        val before = f.seed()
        connect()
        val p = controller.prepare(true)
        assertEquals(CloudSyncController.Direction.INITIALIZE_CLOUD, p.direction)
        assertFalse(peer.initialized)
        controller.confirm(p)
        assertTrue(same())
        assertEquals(1L, peer.sequence())
        assertTrue(connection.read()!!.enabled)
        assertEquals(before.fingerprint, f.capture().fingerprint)
        assertNotNull(CloudApplyStore.baseline(f.repo.store.readableDatabase))
    }

    @Test fun oneTimeRestoreDoesNotEnableAutomaticSync() {
        val before = f.seed()
        peer.external(f.target(before))
        connect()
        val p = controller.prepare(false)
        controller.confirm(p)
        assertTrue(same())
        assertFalse(connection.read()!!.enabled)
        assertEquals(1, backups)
    }

    @Test fun enabledLocalEditUploadsAndRemoteEditAppliesWithoutAnotherEnrollment() {
        f.seed()
        enable()
        f.media("Local-add")
        controller.syncNow()
        assertTrue(same())
        val current = peer.catalog()
        peer.external(current.copy(library = current.library.reversed()))
        controller.syncNow()
        assertTrue(same())
        assertEquals(1, backups)
        val baseline = CloudApplyStore.baseline(f.repo.store.readableDatabase)!!
        f.media("After-pull")
        controller.syncNow()
        assertTrue(same())
        assertEquals(baseline.operation, CloudApplyStore.baseline(f.repo.store.readableDatabase)!!.operation)
        f.repo.definitions() // A push acknowledgement must not invalidate the earlier native witness.
    }

    @Test fun syncOffKeepsCredentialAndDivergesWithoutNetworkCalls() {
        f.seed()
        enable()
        val old = connection.read()!!
        controller.disable()
        f.media("Detached")
        val count = peer.calls
        reject("ENABLE_SYNC_OR_USE_ONE_TIME_RESTORE") { controller.syncNow() }
        assertEquals(count, peer.calls)
        assertEquals(peer.tokenA, connection.token(connection.read()!!))
        assertNotEquals(old.generation, connection.read()!!.generation)
        assertFalse(same())
    }

    @Test fun enableAfterDetachedEditsRequiresCloudReplacementPreview() {
        f.seed()
        enable()
        controller.disable()
        f.media("Detached")
        val p = controller.prepare(true)
        assertEquals(1, p.delta.removed.size)
        assertFalse(connection.read()!!.enabled)
        controller.confirm(p)
        assertTrue(same())
        assertTrue(connection.read()!!.enabled)
    }

    @Test fun localAndCloudDivergencePausesWithoutOverwritingEitherSide() {
        f.seed()
        enable()
        f.media("Local-only")
        val local = f.capture().fingerprint
        val cloud = peer.catalog()
        peer.external(cloud.copy(library = cloud.library.reversed()))
        val remote = peer.head
        reject("CLOUD_CONFLICT_REVIEW_REQUIRED") { controller.syncNow() }
        assertEquals(local, f.capture().fingerprint)
        assertEquals(remote, peer.head)
        assertFalse(controller.hasPending())
    }

    @Test fun cloudChangeAfterPreviewInvalidatesConfirmationBeforeBackupOrApply() {
        val before = f.seed()
        peer.external(f.target(before))
        connect()
        val p = controller.prepare(false)
        peer.external(before.catalog)
        reject("CLOUD_PREVIEW_CHANGED") { controller.confirm(p) }
        assertEquals(before.fingerprint, f.capture().fingerprint)
        assertEquals(0, backups)
    }

    @Test fun localChangeAfterPreviewInvalidatesConfirmation() {
        val before = f.seed()
        peer.external(f.target(before))
        connect()
        val p = controller.prepare(false)
        f.media("After-preview")
        reject("CLOUD_LOCAL_PREVIEW_CHANGED") { controller.confirm(p) }
        assertTrue(f.capture().catalog.library.any { it.url.endsWith("After-preview") })
        assertEquals(0, backups)
    }

    @Test fun disablingWhilePreviewIsOpenPreventsAnyConfirmationRequest() {
        val before = f.seed()
        peer.external(f.target(before))
        connect()
        val p = controller.prepare(true)
        controller.disable()
        val requests = peer.calls
        reject("CLOUD_CONNECTION_CHANGED") { controller.confirm(p) }
        assertEquals(requests, peer.calls)
        assertEquals(before.fingerprint, f.capture().fingerprint)
    }

    @Test fun expiringPreviewCannotBeConfirmed() {
        val before = f.seed()
        peer.external(f.target(before))
        connect()
        val original = controller.prepare(false)
        val old = CloudSyncController.Preview(original.id, original.generation, -1, original.before, original.remote, original.download, original.direction, false)
        reject("CLOUD_PREVIEW_EXPIRED") { controller.confirm(old) }
        assertEquals(before.fingerprint, f.capture().fingerprint)
    }

    @Test fun missingFirstRequestCanBeRetiredAfterDisableWithoutUploadingDetachedContent() {
        f.seed()
        enable()
        f.media("Unsent")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        assertTrue(controller.hasPending())
        assertFalse(peer.pending())
        controller.disable()
        val original = peer.head
        controller.resolvePending()
        assertFalse(controller.hasPending())
        assertEquals(original, peer.head)
        assertEquals(2L, peer.sequence())
        assertTrue(f.capture().catalog.library.any { it.url.endsWith("Unsent") })
    }

    @Test fun durableOutboxSurvivesClientRecreationAndLostAcknowledgment() {
        f.seed()
        enable()
        f.media("Sent")
        peer.fault = "afterPublish"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        assertTrue(controller.hasPending())
        val published = peer.head
        controller = newController()
        controller.syncNow()
        assertFalse(controller.hasPending())
        assertEquals(published, peer.head)
        assertEquals(2L, peer.sequence())
        assertTrue(same())
    }

    @Test fun localChangesAfterAnUnknownRequestRemainDirtyAndEventuallyUpload() {
        f.seed()
        enable()
        f.media("First")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        f.media("Second")
        controller = newController()
        controller.syncNow()
        assertFalse(controller.hasPending())
        assertTrue(same())
        assertEquals(3L, peer.sequence())
    }

    @Test fun interruptedUploadCannotBeForgottenWithoutResolvingItsSequence() {
        f.seed()
        enable()
        f.media("Unsent")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        reject("RESOLVE_PENDING_UPLOAD_BEFORE_FORGET") { controller.forget() }
        controller.disable()
        controller.resolvePending()
        val before = f.capture().catalog
        controller.forget()
        assertEquals(null, connection.read())
        assertEquals(before.library, f.capture().catalog.library)
    }

    @Test fun connectionCanBeForgottenAfterNativeApplyWithoutLeavingAWitnessMismatch() {
        val before = f.seed()
        peer.external(f.target(before))
        connect()
        controller.confirm(controller.prepare(false))
        assertNotNull(f.db.cloudApplyReceiptDao().current())
        controller.forget()
        f.repo.definitions()
        assertEquals(null, f.db.cloudApplyReceiptDao().current())
        assertEquals(null, connection.read())
    }

    @Test fun serverLimitsRejectBeforeCreatingOutboxOrPublishing() {
        val a = f.media("A")
        f.playlist("Big", List(700) { a })
        connect()
        peer.maxPages = 1
        peer.maxBytes = 32768
        val p = controller.prepare(true)
        reject("CLOUD_SERVER_LIBRARY_LIMIT") { controller.confirm(p) }
        assertFalse(controller.hasPending())
        assertFalse(peer.initialized)
        assertFalse(connection.read()!!.enabled)
    }

    @Test fun corruptOutboxIsRetainedRatherThanSkippedOrReidentified() {
        f.seed()
        enable()
        f.media("Unsent")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        File(connection.directory, "outbox/0.json").writeText("[]")
        reject("OUTBOX_CORRUPT") { controller.resolvePending() }
        assertTrue(controller.hasPending())
        assertEquals(1L, peer.sequence())
    }

    @Test fun remoteCounterRollbackNeverAppliesOrPublishes() {
        val before = f.seed()
        peer.external(before.catalog)
        connect()
        controller.confirm(controller.prepare(true))
        peer.rewind()
        val local = f.capture().fingerprint
        reject("CLOUD_REMOTE_ROLLBACK_SUSPECTED") { controller.syncNow() }
        assertEquals(local, f.capture().fingerprint)
    }

    @Test fun explicitPreviewAccountsForDisplacedRecoverableTags() {
        val before = f.seed()
        val orphan = StreamKey(0, "https://example.test/orphan")
        val nativeTags = f.repo.definitions().tags.map { it.id }.toSet()
        f.repo.store.setTags(orphan, nativeTags, 0)
        peer.external(before.catalog.copy(tags = emptyList(), categories = emptyList(), assignments = emptyList()))
        connect()
        val p = controller.prepare(false)
        val groups = CloudChangeExplorer.groups(p.before.catalog, p.target, p.before.hints, p.before.orphaned)
        assertTrue(groups.any { it.id == "orphaned-tags" })
        controller.confirm(p)
        assertEquals(1, backups)
        assertTrue(f.repo.store.assigned(orphan).isEmpty())
        assertTrue(same())
    }

    @Test fun automaticOrphanConflictDoesNotSilentlyDiscardMetadata() {
        val before = f.seed()
        enable()
        val orphan = StreamKey(0, "https://example.test/orphan")
        f.repo.store.setTags(orphan, f.repo.definitions().tags.map { it.id }.toSet(), 0)
        peer.external(before.catalog.copy(tags = emptyList(), categories = emptyList(), assignments = emptyList()))
        reject("CLOUD_ORPHAN_RECONCILIATION_REQUIRED") { controller.syncNow() }
        assertFalse(f.repo.store.assigned(orphan).isEmpty())
    }

    @Test fun realSafetyBackupExistsAndValidatesBeforeOneTimeRestore() {
        val before = f.seed()
        peer.external(f.target(before))
        connect()
        controller = newController(realBackup = true)
        controller.confirm(controller.prepare(false))
        val backup = File(connection.directory, "recovery/before-last-cloud-restore.zip")
        assertTrue(backup.isFile && backup.length() > 0)
        assertTrue(same())
        assertFalse(connection.read()!!.enabled)
        val helper = org.schabi.newpipe.streams.io.StoredFileHelper(f.context, null, android.net.Uri.fromFile(backup), "test")
        org.schabi.newpipe.custom.tagging.TagBackupCoordinator.prepare(f.context, helper).use { assertTrue(it.directory.toFile().isDirectory) }
    }

    @Test fun nativeRestoreDisablesConnectionButRetainsItsCredential() {
        f.seed()
        enable()
        // Model the real coordinator's beforeImport call against this isolated connection root.
        val state = connection.read()!!
        connection.disable("Native restore")
        assertFalse(connection.read()!!.enabled)
        assertEquals(peer.tokenA, connection.token(connection.read()!!))
        assertNotEquals(state.generation, connection.read()!!.generation)
    }

    @Test fun failedNetworkDoesNotChangeTheNativeCatalogOrEnableState() {
        f.seed()
        connect()
        val before = f.capture()
        peer.fault = "offline"
        assertThrows(CloudRemoteException::class.java) { controller.prepare(true) }
        assertEquals(before.fingerprint, f.capture().fingerprint)
        assertFalse(connection.read()!!.enabled)
    }
}
