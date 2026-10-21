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
        f = CloudApplyFixture(app, Files.createTempDirectory(app.noBackupFilesDir.toPath(), "cloud-controller-"))
        f.initialize()
        peer = CloudFakePeer(f.scope)
        connection = CloudConnectionStore(f.context, f.dir.resolve("connection").toFile())
        controller = newController()
    }
    private fun newController(realBackup: Boolean = false, checkpoint: ((String) -> Unit)? = null) = CloudSyncController(
        f.context,
        connection,
        { f.repo },
        peer::client,
        if (realBackup) null else { _: CloudNativeSnapshot -> backups++ },
        checkpoint
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

    @Test fun aDefiniteHeadRaceRetiresItsExactUploadAndReachesConflictWithoutRetryingForever() {
        val before = f.seed()
        enable()
        f.media("local-race")
        peer.beforeRequest = { path ->
            if (path == "/v1/uploads/start") {
                peer.beforeRequest = null
                peer.external(f.target(before))
            }
        }
        reject("CLOUD_CONFLICT_REVIEW_REQUIRED") { controller.syncNow() }
        assertFalse(controller.hasPending())
        assertEquals(1, peer.requests.count { it == "/v1/uploads/retire" })
        val starts = peer.requests.count { it == "/v1/uploads/start" }
        f.reopen()
        reject("CLOUD_CONFLICT_REVIEW_REQUIRED") { newController().syncNow() }
        assertEquals(starts, peer.requests.count { it == "/v1/uploads/start" })
        assertTrue(connection.read()!!.enabled)
    }

    @Test fun expiredPendingUploadIsRetiredThenReplannedWithAFreshSequence() {
        f.seed()
        enable()
        f.media("expired-local")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        assertTrue(controller.hasPending())
        val oldSequence = peer.sequence()
        peer.expireNextStart = true
        controller.syncNow()
        assertFalse(controller.hasPending())
        assertTrue(same())
        assertEquals(oldSequence + 2, peer.sequence()) // Retired identity, then fresh committed upload.
        assertEquals(1, peer.requests.count { it == "/v1/uploads/retire" })
    }

    @Test fun receiptFromRacingCommitWinsOverDefinitiveRefusalAndNeverDiscardsCommittedContent() {
        f.seed()
        enable()
        f.media("racing-commit")
        peer.fault = "definiteAfterPublish"
        controller.syncNow()
        assertFalse(controller.hasPending())
        assertTrue(same())
        assertEquals(peer.head, CloudApplyStore.baseline(f.repo.store.readableDatabase)!!.acknowledgedHead)
        assertEquals(1, peer.requests.count { it == "/v1/uploads/retire" })
    }

    @Test fun uncertainSendStillPreservesIdentityInsteadOfAutoRetiring() {
        f.seed()
        enable()
        f.media("unknown-send")
        peer.fault = "afterPublish"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        assertTrue(controller.hasPending())
        assertEquals(0, peer.requests.count { it == "/v1/uploads/retire" })
        controller.syncNow()
        assertFalse(controller.hasPending())
        assertTrue(same())
    }

    @Test fun hintOnlyStreamUpdatesDoNotScheduleSyncButSemanticPlaylistEditsStillDo() {
        connection = CloudConnectionStore(f.context)
        controller = newController()
        f.seed()
        enable()
        CloudScheduler.reconcile(f.context)
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!CloudScheduler.observes(f.db)) {
            check(android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(50)
        }
        Thread.sleep(400)
        val baselineTicket = CloudScheduler.ticket()
        repeat(12) { f.main("UPDATE streams SET title=title || ' metadata' WHERE uid=(SELECT MIN(uid) FROM streams)") }
        f.db.invalidationTracker.refreshVersionsAsync()
        Thread.sleep(600)
        assertEquals("History/feed hint changes must not trigger catalog capture/network work", baselineTicket, CloudScheduler.ticket())
        f.main("UPDATE playlists SET name=name || ' changed' WHERE name NOT IN('Library','Unavailable')")
        f.db.invalidationTracker.refreshVersionsAsync()
        val changedDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (CloudScheduler.ticket() == baselineTicket) {
            check(android.os.SystemClock.elapsedRealtime() < changedDeadline) { "SEMANTIC_CHANGE_NOT_OBSERVED" }
            Thread.sleep(50)
        }
        controller.disable()
        CloudScheduler.reconcile(f.context)
    }

    private fun rejectedPendingUpload() {
        f.seed()
        enable()
        f.media("pending-before-revoke")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        peer.beforeRequest = { throw CloudRemoteException("INVALID_CREDENTIAL", 401) }
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        assertTrue(connection.read()!!.authorityRejected)
        assertFalse(connection.read()!!.enabled)
    }

    @Test fun rejectedDeviceCanDetachLocallyWithAllPendingEvidenceThenRequiresFreshDeviceEnrollment() {
        rejectedPendingUpload()
        val pending = File(connection.directory, "outbox").walkTopDown().filter { it.isFile }.associate { it.relativeTo(connection.directory).path to it.readBytes().toList() }
        check(pending.isNotEmpty())
        for (name in listOf("remote-recovery", "remote-replacement")) {
            val root = File(connection.directory, name).also { it.mkdirs() }
            File(root, "pending.json").writeText("opaque previous operation, not permission to discard")
            File(root, "payload").writeBytes(byteArrayOf(4, 5, 6))
        }
        val digest = CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase)
        val definitions = f.repo.definitions()
        peer.beforeRequest = { throw AssertionError("LOCAL_DETACH_MUST_NOT_CONTACT_SERVER") }
        controller.detachRejectedConnection()
        assertEquals(null, connection.read())
        assertFalse(connection.detachPending())
        assertEquals(digest, CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        assertEquals(definitions, f.repo.definitions())
        val archived = File(connection.directory, "retired-connections").listFiles()!!.single()
        for ((path, bytes) in pending) assertEquals(bytes, File(archived, path).readBytes().toList())
        for (name in listOf("remote-recovery", "remote-replacement")) {
            assertTrue(File(archived, "$name/pending.json").isFile)
            assertEquals(listOf<Byte>(4, 5, 6), File(archived, "$name/payload").readBytes().toList())
        }
        assertFalse(File(archived, "connection.json").readText().contains(peer.tokenA))
        peer.beforeRequest = null
        reject("CLOUD_FRESH_DEVICE_REQUIRED") { newController().connect("https://example.test", peer.tokenA) }
        val next = newController().connect("https://example.test", peer.tokenB)
        assertFalse(next.enabled)
        assertEquals(peer.scope, next.scope)
        assertEquals(digest, CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        assertTrue(archived.isDirectory)
    }

    private fun interruptDetach(point: String) {
        rejectedPendingUpload()
        val digest = CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase)
        val interrupted = CloudSyncController(
            f.context,
            connection,
            { f.repo },
            peer::client,
            { _: CloudNativeSnapshot -> },
            detachCheckpoint = { if (it == point) throw AssertionError("INTERRUPTED_DETACH") }
        )
        assertThrows(AssertionError::class.java) { interrupted.detachRejectedConnection() }
        f.reopen()
        peer.beforeRequest = { throw AssertionError("LOCAL_DETACH_MUST_NOT_CONTACT_SERVER") }
        newController().detachRejectedConnection()
        assertEquals(null, connection.read())
        assertFalse(connection.detachPending())
        assertEquals(digest, CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        assertTrue(File(connection.directory, "retired-connections").listFiles()!!.single().resolve("outbox").isDirectory)
        f.repo.definitions()
    }

    @Test fun detachRecoversAfterItsJournalIsDurable() = interruptDetach("journal")

    @Test fun detachRecoversAfterArchivingTheOutbox() = interruptDetach("archived-outbox")

    @Test fun detachRecoversAfterIndependentMetadataCommit() = interruptDetach("metadata")

    @Test fun detachCompletionIsIdempotentAfterConnectionRemoval() = interruptDetach("connection")

    @Test fun replayIdentityRejectionAllowsExplicitDetachButNeverClaimsOldRemoteWorkWasRetired() {
        f.seed()
        enable()
        f.media("conflicting-device-sequence")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        peer.beforeRequest = { throw CloudRemoteException("REPLAY_MISMATCH", 409) }
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        assertTrue(connection.read()!!.authorityRejected)
        assertFalse(connection.read()!!.enabled)
        val retired = peer.requests.count { it == "/v1/uploads/retire" }
        controller.detachRejectedConnection()
        assertEquals(retired, peer.requests.count { it == "/v1/uploads/retire" })
        assertEquals(null, connection.read())
        assertTrue(File(connection.directory, "retired-connections").listFiles()!!.single().resolve("outbox").isDirectory)
    }

    @Test fun transientNetworkFailureDoesNotAuthorizeLocalDetachOfAnUnresolvedUpload() {
        f.seed()
        enable()
        f.media("network-unknown")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        controller.disable()
        reject("CLOUD_DETACH_REQUIRES_REJECTED_AUTHORITY") { controller.detachRejectedConnection() }
        assertTrue(controller.hasPending())
        assertFalse(connection.detachPending())
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

    private fun recoveredPeer(catalog: CloudCatalog, library: String = f.scope.library, service: String = "recovered-service") {
        peer = CloudFakePeer(CloudIdentityStore.Scope(service, library))
        peer.external(catalog)
        controller = newController()
    }
    private fun currentCapture(): CloudNativeSnapshot {
        val s = connection.read()!!
        return CloudNativeCatalog(f.repo).capture(s.scope, s.incarnation)
    }
    private fun interruptedOldUpload(): CloudNativeSnapshot {
        f.seed()
        enable()
        f.media("UnsentAfterRecovery")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        return f.capture()
    }

    @Test fun reconnectPreservesLocalContentIdsAndEveryOldPendingFileUntilExplicitPreview() {
        val before = interruptedOldUpload()
        val old = connection.read()!!
        val manifest = File(connection.directory, "outbox/manifest.json").readText()
        for (path in listOf("remote-recovery/pending.json", "remote-recovery/payload.zip", "remote-replacement/pending.json", "remote-replacement/outgoing.payload")) {
            File(connection.directory, path).apply {
                parentFile!!.mkdirs()
                writeText("opaque old pending bytes: $path")
            }
        }
        recoveredPeer(peer.catalog())
        val s = controller.reconnect(peer.tokenB)
        assertFalse(s.enabled)
        assertFalse(connection.reconnectPending())
        assertFalse(controller.hasPending())
        assertFalse(CloudRecoveryController.hasPending(connection.directory))
        assertFalse(CloudReplacementController.hasPending(connection.directory))
        assertEquals(before.fingerprint, currentCapture().fingerprint)
        assertEquals(before.nativeIds, currentCapture().nativeIds)
        assertEquals(old.incarnation, s.incarnation)
        assertNotEquals(old.generation, s.generation)
        assertEquals(null, CloudApplyStore.baseline(f.repo.store.readableDatabase))
        val archive = File(connection.directory, "retired-connections/${s.generation}")
        assertEquals(manifest, File(archive, "outbox/manifest.json").readText())
        for (path in listOf("remote-recovery/pending.json", "remote-recovery/payload.zip", "remote-replacement/pending.json", "remote-replacement/outgoing.payload")) {
            assertEquals("opaque old pending bytes: $path", File(archive, path).readText())
        }
        assertEquals(peer.tokenB, connection.token(s))
        assertFalse(File(connection.directory, "connection.json").readText().contains(peer.tokenB))
        reject("CLOUD_CONNECTION_CHANGED") { connection.requireCurrent(old.generation) }
        val preview = controller.prepare(true)
        assertEquals(before.fingerprint, preview.before.fingerprint)
        assertFalse(connection.read()!!.enabled)
        assertEquals(before.fingerprint, currentCapture().fingerprint)
        controller.confirm(preview)
        assertTrue(connection.read()!!.enabled)
        assertEquals(CloudCatalogCodec.fingerprint(peer.catalog()), currentCapture().fingerprint)
    }

    @Test fun reconnectRejectsSameGenerationOtherLibraryAndOldDeviceBeforeChangingAnything() {
        val before = interruptedOldUpload()
        val old = connection.read()!!
        val catalog = peer.catalog()
        for ((library, service, token) in listOf(
            Triple(f.scope.library, f.scope.service, peer.tokenB),
            Triple("other-library", "recovered-service", peer.tokenB),
            Triple(f.scope.library, "recovered-service", peer.tokenA)
        )) {
            recoveredPeer(catalog, library, service)
            reject("CLOUD_RECOVERED_SCOPE_REQUIRED") { controller.reconnect(token) }
            assertEquals(old.generation, connection.read()!!.generation)
            assertTrue(controller.hasPending())
            assertFalse(connection.reconnectPending())
            assertEquals(before.fingerprint, f.capture().fingerprint)
        }
    }

    @Test fun reconnectRejectsAnEmptyRecoveredLibraryAndKeepsOldWork() {
        val before = interruptedOldUpload()
        peer = CloudFakePeer(CloudIdentityStore.Scope("recovered-service", f.scope.library))
        controller = newController()
        reject("CLOUD_FRESH_RECOVERY_TOKEN_REQUIRED") { controller.reconnect(peer.tokenB) }
        assertTrue(controller.hasPending())
        assertFalse(connection.reconnectPending())
        assertEquals(before.fingerprint, f.capture().fingerprint)
    }

    @Test fun reconnectRechecksLocalEditsAfterTheSafetyBackupBeforeStarting() {
        interruptedOldUpload()
        recoveredPeer(peer.catalog())
        controller = CloudSyncController(f.context, connection, { f.repo }, peer::client, { f.media("LateDuringReconnectBackup") })
        reject("CLOUD_LOCAL_PREVIEW_CHANGED") { controller.reconnect(peer.tokenB) }
        assertTrue(controller.hasPending())
        assertFalse(connection.reconnectPending())
        assertEquals(f.scope, connection.read()!!.scope)
        assertTrue(f.capture().catalog.library.any { it.url.endsWith("/LateDuringReconnectBackup") })
    }

    private fun reconnectCrash(phase: String) {
        val first = f.seed()
        peer.external(f.target(first))
        connect()
        controller.confirm(controller.prepare(true))
        assertNotNull(f.repo.library.cloudApplyReceiptDao().current())
        f.media("PendingBeforeReconnect")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        val before = f.capture()
        val catalog = peer.catalog()
        recoveredPeer(catalog)
        controller = newController(checkpoint = { if (it == phase) error("SIMULATED_RECONNECT_CRASH") })
        reject("SIMULATED_RECONNECT_CRASH") { controller.reconnect(peer.tokenB) }
        assertTrue(connection.reconnectPending())
        assertFalse(connection.read()!!.enabled)
        val record = File(connection.directory, "reconnect.json").readText()
        assertFalse(record.contains(peer.tokenA))
        assertFalse(record.contains(peer.tokenB))
        val dir = connection.directory
        f.reopen()
        connection = CloudConnectionStore(f.context, dir)
        controller = newController()
        val generation = connection.read()!!.generation
        controller.disable()
        assertEquals(generation, connection.read()!!.generation)
        reject("CLOUD_RECONNECT_RECOVERY_REQUIRED") { controller.forget() }
        reject("CLOUD_RECONNECT_RECOVERY_REQUIRED") { controller.inspect() }
        reject("CLOUD_RECONNECT_RECOVERY_REQUIRED") { connection.token(connection.read()!!) }
        TagRuntime.lock.withLock { CloudNativeApply.recoverLocked(f.repo) }
        peer.fault = "offline"
        val calls = peer.calls
        val s = controller.resumeReconnect()
        assertEquals(calls, peer.calls)
        assertFalse(s.enabled)
        assertFalse(connection.reconnectPending())
        assertFalse(controller.hasPending())
        assertEquals(before.fingerprint, currentCapture().fingerprint)
        assertEquals(before.nativeIds, currentCapture().nativeIds)
        assertTrue(File(dir, "retired-connections/${s.generation}/outbox/manifest.json").isFile)
        assertEquals(null, f.repo.library.cloudApplyReceiptDao().current())
        assertEquals(null, CloudApplyStore.baseline(f.repo.store.readableDatabase))
    }

    @Test fun reconnectResumesAfterJournalCommit() = reconnectCrash("journal")

    @Test fun reconnectResumesAfterPartialTransferArchival() = reconnectCrash("archived-outbox")

    @Test fun reconnectResumesAfterWitnessRemoval() = reconnectCrash("witness")

    @Test fun reconnectResumesAfterScopeAndBaselineCommit() = reconnectCrash("metadata")

    @Test fun reconnectResumesAfterCredentialCommit() = reconnectCrash("connection")

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

    @Test fun oneTimeRestoreFromEnabledInvalidatesAutomaticWorkAndStaysOffAfterRestart() {
        f.seed()
        enable()
        val old = connection.read()!!
        val preview = controller.prepare(false)
        assertTrue(connection.read()!!.enabled) // Merely opening/canceling a preview does not disable.
        controller.confirm(preview)
        assertFalse(connection.read()!!.enabled)
        assertNotEquals(old.generation, connection.read()!!.generation)
        reject("CLOUD_CONNECTION_CHANGED") { connection.requireCurrent(old.generation, true) }
        f.reopen()
        f.media("After-one-time")
        val calls = peer.calls
        reject("ENABLE_SYNC_OR_USE_ONE_TIME_RESTORE") { newController().syncNow() }
        assertEquals(calls, peer.calls)
        assertFalse(CloudConnectionStore(f.context, connection.directory).read()!!.enabled)
        controller = newController()
        controller.confirm(controller.prepare(true))
        assertTrue(connection.read()!!.enabled)
    }

    @Test fun aPermanentRevocationStopsOnlyTheFailedGenerationAndKeepsCredentialEvidence() {
        f.seed()
        enable()
        val old = connection.read()!!
        peer.beforeRequest = { throw CloudRemoteException("INVALID_CREDENTIAL", 401) }
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        val stopped = connection.read()!!
        assertFalse(stopped.enabled)
        assertNotEquals(old.generation, stopped.generation)
        assertEquals(peer.tokenA, connection.token(stopped))
        f.reopen()
        f.media("While-revoked")
        val calls = peer.calls
        reject("ENABLE_SYNC_OR_USE_ONE_TIME_RESTORE") { newController().syncNow() }
        assertEquals(calls, peer.calls)
        peer.beforeRequest = null
        controller = newController()
        controller.confirm(controller.prepare(true))
        val resumed = connection.read()!!
        controller.recordFailure(CloudRemoteException("INVALID_CREDENTIAL", 401), old.generation)
        assertEquals(resumed.generation, connection.read()!!.generation)
        assertTrue(connection.read()!!.enabled)
    }

    @Test fun terminalRejectionCancelsRealWorkManagerRequestsAndDoesNotRescheduleOnNativeEdit() {
        connection = CloudConnectionStore(f.context)
        controller = newController()
        f.seed()
        enable()
        val manager = androidx.work.WorkManager.getInstance(f.context)
        val names = listOf("ritovision-cloud-periodic-v1", "ritovision-cloud-change-v1")
        fun active(name: String) = manager.getWorkInfosForUniqueWork(name).get(10, java.util.concurrent.TimeUnit.SECONDS).any { !it.state.isFinished }
        fun awaitState(predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (!predicate()) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "WORK_MANAGER_STATE_TIMEOUT" }
                Thread.sleep(100)
            }
        }
        CloudScheduler.reconcile(f.context)
        awaitState { active(names.first()) }
        peer.beforeRequest = { throw CloudRemoteException("INVALID_CREDENTIAL", 401) }
        assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
        awaitState { names.none(::active) }
        val calls = peer.calls
        f.media("Native-edit-after-rejection")
        CloudScheduler.request(f.context)
        CloudScheduler.reconcile(f.context)
        Thread.sleep(2300) // Past the actual debounced enqueue window.
        assertTrue(names.none(::active))
        assertEquals(calls, peer.calls)
        assertFalse(CloudConnectionStore(f.context).read()!!.enabled)
    }

    @Test fun offlineRateLimitsAndTemporaryServiceFailuresDoNotPermanentlyDisableSync() {
        f.seed()
        enable()
        val generation = connection.read()!!.generation
        for (status in listOf(0, 429, 503)) {
            peer.beforeRequest = { throw CloudRemoteException("TEMPORARY", status) }
            assertThrows(CloudRemoteException::class.java) { controller.syncNow() }
            assertTrue(connection.read()!!.enabled)
            assertEquals(generation, connection.read()!!.generation)
        }
        peer.beforeRequest = null
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

    @Test fun interruptedForgetKeepsLocalContentUsableAndCanBeRepeated() {
        val initial = f.seed()
        peer.external(f.target(initial))
        connect()
        controller.confirm(controller.prepare(true))
        assertNotNull(f.db.cloudApplyReceiptDao().current())
        val digest = CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase)
        val definitions = f.repo.definitions()
        val old = connection.read()!!.generation
        for (boundary in listOf("off", "before-witness-commit", "witness", "metadata", "binding", "connection")) {
            if (connection.read() == null) break
            val interrupted = CloudSyncController(
                f.context,
                connection,
                { f.repo },
                peer::client,
                { _: CloudNativeSnapshot -> },
                forgetCheckpoint = { if (it == boundary) throw AssertionError("interrupted") }
            )
            assertThrows(AssertionError::class.java) { interrupted.forget() }
            f.reopen()
            assertFalse(connection.read()?.enabled == true)
            if (connection.read() != null) assertNotEquals(old, connection.read()!!.generation)
            f.repo.definitions() // Normal guarded operations must not report journal loss.
            assertEquals(definitions, f.repo.definitions())
            assertEquals(digest, CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        }
        newController().forget()
        assertEquals(null, connection.read())
        assertEquals(null, f.db.cloudApplyReceiptDao().current())
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
