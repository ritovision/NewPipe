/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.custom.library.UnavailableRepository
import org.schabi.newpipe.custom.tagging.StreamKey
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Explicit opt-in live acceptance, using this installation's real private stores and HTTP client.
 * No credential is an instrumentation argument or printed in a result. Install only in isolated QA
 * packages; the host writes live-qa.json through run-as and revokes these synthetic credentials.
 */
class CloudLiveSyncTest {
    @Test fun runExplicitStep() {
        val args = InstrumentationRegistry.getArguments()
        val step = args.getString("cloudLiveStep")
        assumeTrue("Live acceptance is opt-in", step != null)
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(
            app.packageName in setOf(
                "org.schabi.newpipe.debug.qacloudaccept20261021",
                "org.schabi.newpipe.debug.qacloudaccept20261021.peerb"
            )
        ) { "LIVE_QA_PACKAGE_REQUIRED" }
        val credentialFile = File(app.noBackupFilesDir, "live-qa.json")
        check(credentialFile.isFile) { "LIVE_QA_CREDENTIAL_REQUIRED" }
        val config = CloudWireJson.obj(credentialFile.readText())
        check(config.getBoolean("syntheticOnly")) { "SYNTHETIC_ONLY" }
        val origin = config.getString("origin")
        check(origin == "https://newpipe-accept-20261021a.ritorhymes.workers.dev") { "LIVE_QA_ORIGIN_REQUIRED" }
        val repo = TagRuntime.get(app)
        repo.definitions()
        val controller = CloudSyncController(app)
        fun snapshot(): CloudNativeSnapshot {
            val state = checkNotNull(controller.connection.read())
            return CloudNativeCatalog(repo).capture(state.scope, state.incarnation)
        }
        fun media(label: String, tags: Set<Long> = emptySet()): StreamEntity {
            val item = StreamEntity(
                serviceId = 0,
                url = "https://example.test/newpipe-live/$label",
                title = "Live QA $label",
                streamType = StreamType.VIDEO_STREAM,
                duration = 42,
                uploader = "Synthetic live acceptance",
                viewCount = 0
            )
            val uid = TagRuntime.mutate(
                repo.library,
                Callable {
                    repo.library.runInTransaction(
                        Callable {
                            val id = repo.library.streamDAO().upsert(item)
                            LibraryRepository.appendDirectly(
                                repo.library,
                                checkNotNull(LibraryRepository.boundLibraryId(repo.library)),
                                listOf(id)
                            )
                            id
                        }
                    )
                }
            )
            val saved = item.copy(uid = uid)
            if (tags.isNotEmpty()) repo.apply(saved, tags, repo.store.revision(StreamKey(0, saved.url)))
            return saved
        }
        fun waitForBaseline(expected: String) {
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val baseline = TagRuntime.lock.withLock { CloudApplyStore.baseline(repo.store.readableDatabase) }
                if (baseline?.head?.fingerprint == expected) return
                SystemClock.sleep(500)
            }
            error("LIVE_AUTOMATIC_SYNC_TIMEOUT")
        }
        fun checkExpected() {
            val expected = checkNotNull(args.getString("expectedFingerprint"))
            waitForBaseline(expected)
            assertEquals(expected, snapshot().fingerprint)
        }
        fun <T> idle(action: () -> T): T {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (true) {
                try {
                    return action()
                } catch (error: IllegalStateException) {
                    if (error.message != "CLOUD_OPERATION_BUSY" || SystemClock.elapsedRealtime() >= deadline) throw error
                    SystemClock.sleep(100)
                }
            }
        }
        val continuation = "Cloud continuation " + (config.getString("run") ?: "missing")
        fun continuationCategory() = repo.definitions().categories.single { it.name.startsWith("$continuation category") }
        fun continuationTag() = repo.definitions().tags.single { it.name.startsWith("$continuation tag") }
        fun requireOffline() {
            @Suppress("DEPRECATION")
            val connected = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).activeNetworkInfo?.isConnected == true
            assertFalse("Host must disable networking only on the dedicated QA emulator", connected)
        }
        when (step) {
            "continuationJoin" -> {
                check(config.getString("run")?.matches(Regex("[a-z0-9-]{1,48}")) == true)
                check(controller.connection.read() == null) { "LIVE_QA_ALREADY_CONNECTED" }
                controller.connect(origin, config.getString("token"))
                assertEquals(config.getString("generation"), controller.connection.read()!!.scope.service)
                controller.confirm(controller.prepare(true))
                assertTrue(controller.connection.read()!!.enabled)
                assertEquals(snapshot().fingerprint, idle { controller.inspect() }.head.fingerprint)
            }

            "continuationSeed" -> {
                val category = repo.createCategory("$continuation category")
                val tag = repo.createTag("$continuation tag", category)
                val a = media("${config.getString("run")}-A", setOf(tag))
                val b = media("${config.getString("run")}-B", setOf(tag))
                TagRuntime.mutate(
                    repo.library,
                    Callable {
                        repo.library.runInTransaction {
                            val id = repo.library.playlistDAO().insert(PlaylistEntity(name = "$continuation duplicates", isThumbnailPermanent = false, thumbnailStreamId = a.uid, displayIndex = 1))
                            repo.library.playlistStreamDAO().insertAll(listOf(PlaylistStreamEntity(id, a.uid, 0), PlaylistStreamEntity(id, a.uid, 1), PlaylistStreamEntity(id, b.uid, 2)))
                        }
                    }
                )
                UnavailableRepository.setMarked(app, b.uid, true).blockingAwait()
                val expected = snapshot().fingerprint
                CloudScheduler.reconcile(app)
                waitForBaseline(expected)
                assertEquals(expected, idle { controller.inspect() }.head.fingerprint)
            }

            "continuationPull" -> {
                CloudScheduler.reconcile(app)
                checkExpected()
                val current = snapshot().catalog
                val playlist = current.playlists.single { it.name == "$continuation duplicates" }
                assertEquals(3, playlist.items.size)
                assertEquals(playlist.items[0], playlist.items[1])
                assertTrue(current.unavailable.any { it.url.endsWith("${config.getString("run")}-B") })
                continuationTag()
            }

            "continuationOfflineEdit" -> {
                requireOffline()
                val before = TagRuntime.lock.withLock { checkNotNull(CloudApplyStore.baseline(repo.store.readableDatabase)).head.fingerprint }
                val category = continuationCategory()
                repo.renameCategory(category.id, "$continuation category offline")
                assertTrue(before != snapshot().fingerprint)
                CloudScheduler.reconcile(app)
                val error = assertThrows(CloudRemoteException::class.java) { idle { controller.syncNow() } }
                assertEquals("CLOUD_NETWORK_UNAVAILABLE", error.code)
                assertEquals(before, TagRuntime.lock.withLock { CloudApplyStore.baseline(repo.store.readableDatabase)!!.head.fingerprint })
                assertTrue(controller.connection.read()!!.enabled)
            }

            "continuationOfflineRestart" -> {
                requireOffline()
                assertEquals(args.getString("expectedLocalFingerprint"), snapshot().fingerprint)
                assertEquals(args.getString("expectedFingerprint"), TagRuntime.lock.withLock { CloudApplyStore.baseline(repo.store.readableDatabase)!!.head.fingerprint })
                CloudScheduler.reconcile(app)
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (true) {
                    val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork("ritovision-cloud-change-v1").get(10, TimeUnit.SECONDS)
                    if (work.any { it.state == androidx.work.WorkInfo.State.ENQUEUED }) break
                    check(SystemClock.elapsedRealtime() < deadline) { "OFFLINE_WORK_NOT_QUEUED" }
                    SystemClock.sleep(100)
                }
                assertTrue(controller.connection.read()!!.enabled)
            }

            "continuationResume" -> {
                CloudScheduler.reconcile(app)
                checkExpected()
                assertFalse(controller.hasPending())
                assertEquals(snapshot().fingerprint, idle { controller.inspect() }.head.fingerprint)
            }

            "continuationConflictEdit" -> {
                requireOffline()
                val category = continuationCategory()
                repo.renameCategory(category.id, "$continuation category conflict")
                assertTrue(args.getString("expectedFingerprint") != snapshot().fingerprint)
            }

            "continuationRemoteEdit" -> {
                val tag = continuationTag()
                repo.editTag(tag.id, "$continuation tag remote", continuationCategory().id)
                val expected = snapshot().fingerprint
                CloudScheduler.reconcile(app)
                waitForBaseline(expected)
                assertEquals(expected, idle { controller.inspect() }.head.fingerprint)
            }

            "continuationConflict" -> {
                assertEquals(args.getString("expectedLocalFingerprint"), snapshot().fingerprint)
                assertEquals("CLOUD_CONFLICT_REVIEW_REQUIRED", assertThrows(IllegalStateException::class.java) { idle { controller.syncNow() } }.message)
                assertEquals(args.getString("expectedLocalFingerprint"), snapshot().fingerprint)
                assertEquals(args.getString("expectedFingerprint"), idle { controller.inspect() }.head.fingerprint)
            }

            "continuationReconcile" -> {
                val preview = idle { controller.prepare(true) }
                assertEquals(args.getString("expectedLocalFingerprint"), preview.before.fingerprint)
                assertEquals(args.getString("expectedFingerprint"), preview.remote.head.fingerprint)
                idle { controller.confirm(preview) }
                assertEquals(args.getString("expectedFingerprint"), snapshot().fingerprint)
                assertTrue(File(controller.connection.directory, "recovery/before-last-cloud-restore.zip").isFile)
            }

            "continuationDisable" -> {
                controller.disable()
                CloudScheduler.reconcile(app)
                media("${config.getString("run")}-disabled")
                assertFalse(controller.connection.read()!!.enabled)
                assertEquals("ENABLE_SYNC_OR_USE_ONE_TIME_RESTORE", assertThrows(IllegalStateException::class.java) { controller.syncNow() }.message)
            }

            "continuationOneTime" -> {
                assertFalse(controller.connection.read()!!.enabled)
                idle { controller.confirm(controller.prepare(false)) }
                assertEquals(args.getString("expectedFingerprint"), snapshot().fingerprint)
                assertFalse(controller.connection.read()!!.enabled)
            }

            "initialize" -> {
                check(controller.connection.read() == null) { "LIVE_QA_ALREADY_CONNECTED" }
                val existing = repo.library.openHelper.readableDatabase.query("SELECT COUNT(*) FROM streams").use {
                    it.moveToFirst()
                    it.getLong(0)
                }
                check(existing == 0L && repo.definitions().tags.isEmpty()) { "LIVE_QA_NOT_EMPTY" }
                val category = repo.createCategory("Live QA genre")
                val tag = repo.createTag("Live QA keep", category)
                val a = media("A", setOf(tag))
                val b = media("B", setOf(tag))
                media("C")
                TagRuntime.mutate(
                    repo.library,
                    Callable {
                        repo.library.runInTransaction {
                            val id = repo.library.playlistDAO().insert(PlaylistEntity(name = "Live QA duplicates", isThumbnailPermanent = false, thumbnailStreamId = a.uid, displayIndex = 1))
                            repo.library.playlistStreamDAO().insertAll(listOf(PlaylistStreamEntity(id, a.uid, 0), PlaylistStreamEntity(id, a.uid, 1), PlaylistStreamEntity(id, b.uid, 2)))
                        }
                    }
                )
                UnavailableRepository.setMarked(app, b.uid, true).blockingAwait()
                controller.connect(origin, config.getString("token"))
                val preview = controller.prepare(true)
                assertEquals(CloudSyncController.Direction.INITIALIZE_CLOUD, preview.direction)
                controller.confirm(preview)
                assertEquals(snapshot().fingerprint, controller.inspect().head.fingerprint)
            }

            "join" -> {
                check(controller.connection.read() == null) { "LIVE_QA_ALREADY_CONNECTED" }
                controller.connect(origin, config.getString("token"))
                val preview = controller.prepare(true)
                assertEquals(CloudSyncController.Direction.TO_DEVICE, preview.direction)
                assertEquals(3, preview.target.library.size)
                assertEquals(3, preview.target.playlists.single().items.size)
                assertEquals(preview.target.playlists.single().items[0], preview.target.playlists.single().items[1])
                controller.confirm(preview)
                checkExpected()
                assertEquals(1, snapshot().catalog.unavailable.size)
                assertEquals(1, snapshot().catalog.tags.size)
                assertTrue(File(controller.connection.directory, "recovery/before-last-cloud-restore.zip").isFile)
            }

            "automaticEdit" -> {
                val category = repo.definitions().categories.single()
                repo.renameCategory(category.id, "Live QA automatic edit")
                val expected = snapshot().fingerprint
                CloudScheduler.reconcile(app)
                waitForBaseline(expected)
                assertEquals(expected, controller.inspect().head.fingerprint)
            }

            "automaticPull" -> {
                CloudScheduler.reconcile(app)
                checkExpected()
            }

            "disableAndEdit" -> {
                controller.disable()
                CloudScheduler.reconcile(app)
                WorkManager.getInstance(app).cancelAllWork().result.get(15, TimeUnit.SECONDS)
                val old = controller.inspect().head
                media("Detached")
                assertFalse(controller.connection.read()!!.enabled)
                assertEquals(
                    "ENABLE_SYNC_OR_USE_ONE_TIME_RESTORE",
                    assertThrows(IllegalStateException::class.java) { controller.syncNow() }.message
                )
                assertEquals(old, controller.inspect().head)
            }

            "oneTimeRestore" -> {
                assertFalse(controller.connection.read()!!.enabled)
                val preview = controller.prepare(false)
                assertTrue(preview.delta.removed.any { it.url.endsWith("/Detached") })
                controller.confirm(preview)
                assertFalse(controller.connection.read()!!.enabled)
                assertEquals(controller.inspect().head.fingerprint, snapshot().fingerprint)
            }

            "resumeForConflict" -> {
                assertFalse(controller.connection.read()!!.enabled)
                val preview = controller.prepare(true)
                controller.confirm(preview)
                assertTrue(controller.connection.read()!!.enabled)
                assertEquals(snapshot().fingerprint, idle { controller.inspect() }.head.fingerprint)
            }

            "holdOfflineEdit" -> {
                assertTrue(controller.connection.read()!!.enabled)
                // The host kills this process after a durable local edit, before the writer barrier
                // is released. It never disables the whole phone's networking or clears app data.
                TagRuntime.lock.withLock {
                    val before = snapshot().fingerprint
                    val category = repo.definitions().categories.single()
                    repo.renameCategory(category.id, "Live QA offline branch")
                    val after = snapshot().fingerprint
                    check(before != after)
                    val marker = CloudWireJson.encode(
                        linkedMapOf(
                            "package" to app.packageName,
                            "syntheticOnly" to true,
                            "before" to before,
                            "after" to after,
                            "enabled" to true
                        )
                    )
                    File(app.noBackupFilesDir, "live-qa-offline-ready.json").outputStream().use {
                        it.write(marker.toByteArray(Charsets.UTF_8))
                        it.fd.sync()
                    }
                    // Keep automatic capture behind the barrier until the explicit host kill.
                    SystemClock.sleep(120_000)
                    error("LIVE_QA_HOST_DID_NOT_KILL_PROCESS")
                }
            }

            "automaticRemoteEdit" -> {
                assertEquals(args.getString("expectedFingerprint"), idle { controller.inspect() }.head.fingerprint)
                val tag = repo.definitions().tags.single()
                repo.editTag(tag.id, "Live QA remote branch", repo.definitions().categories.single().id)
                val expected = snapshot().fingerprint
                CloudScheduler.reconcile(app)
                waitForBaseline(expected)
                assertEquals(expected, idle { controller.inspect() }.head.fingerprint)
            }

            "verifyConflict" -> {
                val local = checkNotNull(args.getString("expectedLocalFingerprint"))
                val remote = checkNotNull(args.getString("expectedFingerprint"))
                assertEquals(local, snapshot().fingerprint)
                assertEquals(
                    "CLOUD_CONFLICT_REVIEW_REQUIRED",
                    assertThrows(IllegalStateException::class.java) { idle { controller.syncNow() } }.message
                )
                assertEquals(local, snapshot().fingerprint)
                assertEquals(remote, idle { controller.inspect() }.head.fingerprint)
                assertTrue(controller.connection.read()!!.enabled)
            }

            "resolveConflictToDevice" -> {
                val local = checkNotNull(args.getString("expectedLocalFingerprint"))
                val remote = checkNotNull(args.getString("expectedFingerprint"))
                assertEquals(local, snapshot().fingerprint)
                val preview = idle { controller.prepare(true) }
                assertEquals(local, preview.before.fingerprint)
                assertEquals(remote, preview.remote.head.fingerprint)
                idle { controller.confirm(preview) }
                assertEquals(remote, snapshot().fingerprint)
                assertTrue(File(controller.connection.directory, "recovery/before-last-cloud-restore.zip").isFile)
            }

            "stop" -> {
                controller.disable()
                CloudScheduler.reconcile(app)
                WorkManager.getInstance(app).cancelAllWork().result.get(15, TimeUnit.SECONDS)
                credentialFile.delete()
            }

            else -> error("UNKNOWN_LIVE_QA_STEP")
        }
        val current = snapshot()
        val result = CloudWireJson.encode(
            linkedMapOf(
                "step" to step,
                "package" to app.packageName,
                "fingerprint" to current.fingerprint,
                "videos" to current.catalog.library.size,
                "playlists" to current.catalog.playlists.size,
                "tags" to current.catalog.tags.size,
                "enabled" to controller.connection.read()!!.enabled,
                "syntheticOnly" to true
            )
        )
        File(app.noBackupFilesDir, "live-qa-result.json").writeText(result)
        println("CLOUD_LIVE_QA_PASS $result")
    }
}
