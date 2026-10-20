/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        check(app.packageName in setOf("org.schabi.newpipe.debug.featurecloudsyncv120261019", "org.schabi.newpipe.debug.featurecloudsyncv120261019.peerb"))
        val secretFile = File(app.noBackupFilesDir, "live-qa.json")
        val config = CloudWireJson.obj(secretFile.readText())
        check(config.getBoolean("syntheticOnly") && config.getString("origin") == "https://newpipe-cloud-sync-v1.ritorhymes.workers.dev")
        val sync = CloudSyncController(app)
        val recovery = CloudRecoveryController(app, sync)
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
        when (action) {
            "connect" -> {
                // Reuse the already synthetic installation without deleting its native data.
                val current = sync.connection.read()
                if (current != null) {
                    sync.disable()
                    check(!sync.hasPending() && !recovery.hasPending())
                    sync.forget()
                }
                sync.connect(config.getString("origin"), config.getString("token"))
                val s = snapshot()
                check(s.catalog.library.size in 1..10 && s.catalog.library.all { it.url.startsWith("https://example.test/newpipe-live/") })
                result["fingerprint"] = s.fingerprint
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
        if (!TagRuntime.restoring) result["enabled"] = sync.connection.read()!!.enabled
        val encoded = CloudWireJson.encode(result)
        File(app.noBackupFilesDir, "live-qa-result.json").outputStream().use { it.write(encoded.toByteArray()) }
        println("CLOUD_RECOVERY_QA_PASS $encoded")
    }
}
