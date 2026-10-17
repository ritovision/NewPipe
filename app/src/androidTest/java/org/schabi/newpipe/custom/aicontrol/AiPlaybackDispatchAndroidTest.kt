/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.testUtil.TestDatabase

/** Native service/queue integration without playing real media or changing any user catalog. */
class AiPlaybackDispatchAndroidTest {
    @Test fun boundOnlyServiceIsActuallyStartedAndAppendRetryHasOneEffect() {
        check(BuildConfig.APPLICATION_ID in setOf("org.schabi.newpipe.debug.featureaicontrol", "org.schabi.newpipe.debug.featuretagginguispike"))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        LibraryRepository.forgetSessionForTest()
        val db = TestDatabase.createReplacingNewPipeDatabase()
        val binding = app.cacheDir.toPath().resolve("playback-qa-${UUID.randomUUID()}.db")
        val library = LibraryRepository.initialize(db, LibraryBindingStore(binding))
        val refs = db.runInTransaction(
            java.util.concurrent.Callable {
                // One-letter YouTube identifiers cannot resolve to real media; no audio can start.
                val items = listOf("x", "y", "z").map { letter ->
                    val url = "https://www.youtube.com/watch?v=$letter"
                    val id = db.streamDAO().upsert(StreamEntity(serviceId = 0, url = url, title = "Synthetic $letter", streamType = StreamType.VIDEO_STREAM, duration = 1, uploader = "Synthetic QA"))
                    AiStreamRef(id, "YouTube", url)
                }
                LibraryRepository.appendDirectly(db, library, items.map { it.streamId })
                items
            }
        )
        val device = UUID.randomUUID().toString()
        val epoch = UUID.randomUUID().toString()
        val access = AiAccessController().apply { enableFromLocalConsent(device, epoch, mapOf("qa" to setOf(AiScope.PLAYBACK_CONTROL, AiScope.LIBRARY_READ))) }
        val session = access.openAuthenticatedSession("qa")
        val executor = AiPlaybackExecutor(access, AiAndroidPlaybackPort(app))
        fun encode(command: AiCatalogCommand): String {
            val now = System.currentTimeMillis()
            return AiProtocol.json.encodeToString(AiRequest(1, device, "qa", epoch, UUID.randomUUID().toString(), now, now + 30_000, command, AiProcessIdentity.playbackEpoch))
        }
        val scenario = ActivityScenario.launch(AiControlActivity::class.java)
        val connected = CountDownLatch(1)
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        try {
            instrumentation.runOnMainSync {
                bound = app.bindService(Intent(app, PlayerService::class.java).setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection, Context.BIND_AUTO_CREATE)
            }
            assertTrue(bound)
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertEquals(null, AiPlayerRegistry.player()) }
            val play = executor.execute(encode(AiCatalogCommand.PlayItems(refs.take(2))), session)
            assertEquals("play result=${play.code}", AiOutcome.COMPLETED, play.outcome)
            instrumentation.runOnMainSync {
                // The Player consumes a SerializedCache clone. Return its actual revision.
                assertEquals(
                    play.data.getValue("queue_revision").jsonPrimitive.content,
                    AiPlayerRegistry.player()!!.playQueue!!.stateRevisionToken
                )
            }
            val firstQueue = AtomicReference<PlayQueue>()
            instrumentation.runOnMainSync { firstQueue.set(AiPlayerRegistry.player()!!.playQueue) }
            // A new request ID with the same selected streams should reuse the current queue,
            // not falsely claim that NewPipe refused the command.
            val repeatedPlay = executor.execute(encode(AiCatalogCommand.PlayItems(refs.take(2))), session)
            assertEquals("identical play result=${repeatedPlay.code}", AiOutcome.COMPLETED, repeatedPlay.outcome)
            instrumentation.runOnMainSync {
                val activeQueue = AiPlayerRegistry.player()!!.playQueue!!
                assertSame(firstQueue.get(), activeQueue)
                assertEquals(
                    repeatedPlay.data.getValue("queue_revision").jsonPrimitive.content,
                    activeQueue.stateRevisionToken
                )
            }
            val append = encode(AiCatalogCommand.AppendQueue(listOf(refs.last())))
            val first = executor.execute(append, session)
            assertEquals("append result=${first.code}", AiOutcome.COMPLETED, first.outcome)
            val repeated = executor.execute(append, session)
            assertTrue(repeated.replayed)
            assertFalse(first.replayed)
            instrumentation.runOnMainSync {
                val player = AiPlayerRegistry.player()!!
                player.pause()
                assertEquals(3, player.playQueue!!.size())
                assertEquals(refs.last().url, player.playQueue!!.getItem(2)!!.url)
            }
            // The test process must survive Android's foreground-promotion deadline.
            Thread.sleep(5500)
            scenario.onActivity { assertFalse(it.isFinishing) }
        } finally {
            session.disconnect()
            access.disable()
            instrumentation.runOnMainSync {
                AiPlayerRegistry.serviceInstance()?.destroyPlayerAndStopService()
                if (bound) app.unbindService(connection)
            }
            scenario.close()
            LibraryRepository.forgetSessionForTest()
            db.close()
            LibraryBindingStore.deleteForDatabase(binding)
        }
    }
}
