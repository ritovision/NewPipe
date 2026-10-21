/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.custom.tagging.TagRuntime

/** Opt-in acceptance against the dedicated Worker/R2 bucket. Never run in an owner installation. */
class CloudLiveRecoveryTest {
    @Test fun explicitStep() {
        val args = InstrumentationRegistry.getArguments()
        val action = args.getString("recoveryStep")
        assumeTrue("Live recovery acceptance is opt-in", action != null)
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName in setOf("org.schabi.newpipe.debug.qacloudaccept20261021", "org.schabi.newpipe.debug.qacloudaccept20261021.peerb"))
        val secretFile = File(app.noBackupFilesDir, "live-qa.json")
        val config = CloudWireJson.obj(secretFile.readText())
        check(config.getBoolean("syntheticOnly") && config.getString("origin") == "https://newpipe-accept-20261021a.ritorhymes.workers.dev")
        val sync = CloudSyncController(app)
        val recovery = CloudRecoveryController(app, sync)
        val replacement = CloudReplacementController(app, sync)
        val repo = TagRuntime.get(app)
        repo.definitions()
        val expectedFile = File(app.noBackupFilesDir, "live-recovery-expected.json")
        val result = linkedMapOf<String, Any?>("step" to action, "package" to app.packageName, "syntheticOnly" to true)
        fun snapshot(): CloudNativeSnapshot {
            val s = checkNotNull(sync.connection.read())
            val identity = CloudIdentityStore.context(repo.store.readableDatabase)
            return if (identity == null) {
                CloudNativeCatalog(repo).enroll(s.scope)
            } else {
                CloudNativeCatalog(repo).capture(s.scope, identity.incarnation)
            }
        }
        fun content(): String {
            val c = snapshot().catalog
            val categories = c.categories.associate { it.id to it.name }
            val tags = c.tags.associate { it.id to it.name }
            return CloudWireJson.sha256(
                CloudWireJson.encode(
                    listOf(
                        c.library.map { listOf(it.service, it.url) },
                        c.unavailable.map { listOf(it.service, it.url) },
                        c.playlists.map { p -> listOf(p.name, p.artwork?.let { listOf(it.service, it.url) }, p.items.map { listOf(it.service, it.url) }) },
                        c.categories.map { it.name }.sorted(),
                        c.tags.map { listOf(it.name, categories[it.category]) }.sortedBy { it[0] },
                        c.assignments.map { a -> listOf(a.key.service, a.key.url, a.tags.map { tags.getValue(it) }.sorted()) }.sortedBy { it[1] as String }
                    )
                )
            )
        }
        fun backup(kind: String) {
            val before = recovery.inspect()
            val p = recovery.prepareUpload(kind)
            val after = recovery.confirmUpload(p)
            assertEquals(minOf(3, before.occupied + 1), after.occupied)
            assertTrue(after.archives.all { it.usable })
            val made = after.archives.single { a -> before.archives.none { it.id == a.id } }
            if (before.occupied == 3) {
                assertEquals(before.archives.first().id, p.evicted!!.id)
                assertFalse(after.archives.any { it.id == p.evicted.id })
                assertTrue(after.archives.map { it.id }.containsAll(p.retained.map { it.id }))
            }
            result["archiveId"] = made.id
            result["evicted"] = p.evicted?.id
            result["bytes"] = made.bytes
            result["kind"] = kind
            result["retained"] = after.archives.map { it.id }
            result["content"] = content()
            result["fingerprint"] = snapshot().fingerprint
        }
        // Synthetic acceptance must enter the cloud writer gate before requesting
        // any user-initiated recovery/backup action. Automatic WorkManager sync
        // intentionally uses a nonblocking tryLock; don't classify a healthy
        // concurrent worker as an end-to-end product failure. This changes only
        // Android instrumentation harness coordination, not production behaviour.
        CloudSyncController.operationLock(sync.connection.directory).withLock {
            when (action) {
                "connect" -> {
                    // QA-only deterministic restart from an existing synthetic installation.
                    // Wait for the prior WorkManager operation before disable/Forget/connect;
                    // the underlying controller's nonblocking tryLock is intentional.
                    CloudSyncController.operationLock(sync.connection.directory).withLock {
                        val current = sync.connection.read()
                        if (current != null) {
                            sync.disable()
                            CloudScheduler.reconcile(app)
                            check(!sync.hasPending() && !recovery.hasPending())
                            sync.forget()
                        }
                        sync.connect(config.getString("origin"), config.getString("token"))
                        val snap = snapshot()
                        check(snap.catalog.library.size in 1..10 && snap.catalog.library.all { it.url.startsWith("https://example.test/newpipe-live/") })
                        result["fingerprint"] = snap.fingerprint
                    }
                }

                "initialize" -> {
                    check(!sync.inspect().initialized)
                    sync.confirm(sync.prepare(true))
                    result["fingerprint"] = snapshot().fingerprint
                }

                "resolve" -> {
                    val status = recovery.resume()
                    assertTrue(status.operation == null)
                    assertFalse(recovery.hasPending())
                    result["occupied"] = status.occupied
                }

                "deviceBackup" -> backup("device-zip")

                "catalogBackup" -> backup("cloud-catalog")

                "changeCloud" -> {
                    val old = sync.inspect().head
                    check(sync.connection.read()!!.enabled)
                    val tag = repo.definitions().tags.single()
                    repo.editTag(tag.id, "Live QA R2 new version", tag.categoryId)
                    sync.syncNow()
                    assertEquals(snapshot().fingerprint, sync.inspect().head.fingerprint)
                    assertTrue(old != sync.inspect().head)
                    sync.disable()
                    CloudScheduler.reconcile(app)
                    result["fingerprint"] = snapshot().fingerprint
                    result["content"] = content()
                }

                "join" -> {
                    sync.confirm(sync.prepare(false))
                    assertEquals(sync.inspect().head.fingerprint, snapshot().fingerprint)
                    result["fingerprint"] = snapshot().fingerprint
                }

                "enable" -> {
                    sync.confirm(sync.prepare(true))
                    assertTrue(sync.connection.read()!!.enabled)
                    result["fingerprint"] = snapshot().fingerprint
                }

                "holdDirtyReplacement" -> {
                    assertTrue(sync.connection.read()!!.enabled)
                    TagRuntime.lock.withLock {
                        val before = snapshot().fingerprint
                        val category = repo.definitions().categories.single()
                        repo.renameCategory(category.id, "Live QA dirty replacement " + UUID.randomUUID().toString().take(8))
                        val after = snapshot().fingerprint
                        check(before != after)
                        val ready = CloudWireJson.encode(
                            mapOf(
                                "package" to app.packageName,
                                "syntheticOnly" to true,
                                "before" to before,
                                "after" to after,
                                "enabled" to true
                            )
                        )
                        File(app.noBackupFilesDir, "live-replacement-dirty.json").outputStream().use {
                            it.write(ready.toByteArray(Charsets.UTF_8))
                            it.fd.sync()
                        }
                        // The host stops only this isolated QA process after reading the durable witness.
                        SystemClock.sleep(120_000)
                        error("LIVE_QA_HOST_DID_NOT_KILL_PROCESS")
                    }
                }

                "replaceCloud" -> {
                    CloudSyncController.operationLock(sync.connection.directory).withLock {
                        sync.disable()
                        CloudScheduler.reconcile(app)
                        val outgoing = sync.inspect().head
                        val retainedBefore = recovery.inspect().archives
                        val tag = repo.definitions().tags.single()
                        repo.editTag(tag.id, "Live QA replaced cloud " + UUID.randomUUID().toString().take(8), tag.categoryId)
                        val incoming = snapshot().fingerprint
                        val preview = replacement.prepare()
                        assertEquals(outgoing, preview.outgoing.status.head)
                        val done = replacement.confirm(preview)
                        val head = sync.inspect().head
                        assertEquals(preview.nextEpoch, head.epoch)
                        assertEquals("1", head.revision)
                        assertEquals(Math.addExact(CloudCounter.parse(outgoing.headCounter), 1).toString(), head.headCounter)
                        assertEquals(incoming, head.fingerprint)
                        assertEquals(incoming, snapshot().fingerprint)
                        assertFalse(replacement.hasPending())
                        assertFalse(sync.connection.read()!!.enabled)
                        assertEquals("DONE", done.attempt!!.getString("state"))
                        assertEquals(3, done.archives.size)
                        assertTrue(done.archives.all { it.usable })
                        assertEquals(retainedBefore.first().id, preview.evicted!!.id)
                        assertFalse(done.archives.any { it.id == preview.evicted.id })
                        result["archiveId"] = done.attempt.getString("archiveId")
                        result["evicted"] = preview.evicted.id
                        result["retained"] = done.archives.map { it.id }
                        result["outgoingFingerprint"] = outgoing.fingerprint
                        result["oldEpoch"] = outgoing.epoch
                        result["epoch"] = head.epoch
                        result["headCounter"] = head.headCounter
                        result["fingerprint"] = incoming
                    }
                }

                "verifyDirtyEpoch" -> {
                    val expectedLocal = checkNotNull(args.getString("expectedLocalFingerprint"))
                    assertEquals(expectedLocal, snapshot().fingerprint)
                    val lock = CloudSyncController.operationLock(sync.connection.directory)
                    lock.withLock {
                        val error = assertThrows(IllegalStateException::class.java) { sync.syncNow() }
                        assertTrue(error.message in setOf("CLOUD_PREVIEW_REQUIRED", "CLOUD_CONFLICT_REVIEW_REQUIRED"))
                        val head = sync.inspect().head
                        assertEquals(args.getString("expectedFingerprint"), head.fingerprint)
                        assertEquals(args.getString("expectedEpoch"), head.epoch)
                        assertEquals(expectedLocal, snapshot().fingerprint)
                        result["pauseReason"] = error.message
                        result["localFingerprint"] = expectedLocal
                        result["cloudFingerprint"] = head.fingerprint
                        result["epoch"] = head.epoch
                        sync.disable()
                        CloudScheduler.reconcile(app)
                    }
                }

                "restoreCatalog" -> {
                    val before = sync.inspect().head
                    val id = checkNotNull(args.getString("archiveId"))
                    recovery.prepareRestore(id).use { p ->
                        check(p.decoded != null)
                        assertEquals(args.getString("expectedFingerprint"), p.manifest.source!!.head.fingerprint)
                        recovery.confirmRestore(p)
                    }
                    assertEquals(args.getString("expectedFingerprint"), snapshot().fingerprint)
                    assertEquals(before, sync.inspect().head)
                    assertFalse(sync.connection.read()!!.enabled)
                    result["fingerprint"] = snapshot().fingerprint
                    result["cloudFingerprint"] = before.fingerprint
                }

                "restoreNative" -> {
                    val id = checkNotNull(args.getString("archiveId"))
                    val expected = checkNotNull(args.getString("expectedContent"))
                    val marker = UUID.randomUUID().toString()
                    expectedFile.writeText(CloudWireJson.encode(mapOf("content" to expected, "marker" to marker)))
                    recovery.prepareRestore(id).use { p ->
                        check(p.prepared != null)
                        recovery.confirmRestore(p)
                    }
                    assertTrue(TagRuntime.restoring)
                    result["restartRequired"] = true
                    result["marker"] = marker
                }

                "verifyNativeAfterRestart" -> {
                    assertFalse(TagRuntime.restoring)
                    assertFalse(sync.connection.read()!!.enabled)
                    val expected = CloudWireJson.obj(expectedFile.readText())
                    assertEquals(expected.getString("content"), content())
                    result["content"] = content()
                    result["marker"] = expected.getString("marker")
                    result["restartVerified"] = true
                    expectedFile.delete()
                }

                "stop" -> {
                    sync.disable()
                    CloudScheduler.reconcile(app)
                    secretFile.delete()
                    result["enabled"] = false
                }

                else -> error("UNKNOWN_LIVE_RECOVERY_STEP")
            }
        }
        if (!TagRuntime.restoring) result["enabled"] = sync.connection.read()!!.enabled
        val encoded = CloudWireJson.encode(result)
        File(app.noBackupFilesDir, "live-qa-result.json").outputStream().use { it.write(encoded.toByteArray()) }
        println("CLOUD_RECOVERY_QA_PASS $encoded")
    }
}
