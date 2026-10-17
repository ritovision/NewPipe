/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.playqueue.SinglePlayQueue

@RunWith(AndroidJUnit4::class)
class AiPlaybackAndroidTest {
    private fun queue() = SinglePlayQueue(
        listOf("A", "A", "C").map {
            StreamEntity(
                serviceId = 0,
                url = "https://www.youtube.com/watch?v=$it",
                title = it,
                streamType = StreamType.VIDEO_STREAM,
                duration = 60,
                uploader = "Synthetic QA"
            ).toStreamInfoItem()
        },
        0
    )

    @Test fun movingAwayAndBackInvalidatesTheOldQueueRevision() {
        val q = queue()
        val old = q.stateRevisionToken
        q.setIndex(1)
        q.setIndex(0)
        assertNotEquals(old, q.stateRevisionToken)
        assertEquals(0, q.index)
    }

    @Test fun replacingAQueueWithIdenticalContentsChangesIdentity() {
        assertNotEquals(queue().stateRevisionToken, queue().stateRevisionToken)
    }

    @Test fun deserializedQueueGetsANewRuntimeIdentity() {
        val q = queue()
        val old = q.stateRevisionToken
        val bytes = ByteArrayOutputStream().apply { ObjectOutputStream(this).use { it.writeObject(q) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as SinglePlayQueue }
        assertNotEquals(old, restored.stateRevisionToken)
    }

    @Test fun explicitSnapshotAppendPreservesOrderThroughShuffleAndUnshuffle() {
        val queue = queue()
        queue.shuffle()
        val prefix = queue.streams.toList()
        val incoming = queue().streams.toList()
        val previousRevision = queue.stateRevisionToken
        queue.appendInOrder(incoming)
        assertEquals(prefix, queue.streams.take(prefix.size))
        assertEquals(incoming, queue.streams.drop(prefix.size))
        assertNotEquals(previousRevision, queue.stateRevisionToken)
        queue.unshuffle()
        assertEquals(incoming, queue.streams.drop(prefix.size))
    }

    @Test fun orderedAppendNeverReplacesTheSelectedAutomaticTail() {
        for (shuffled in listOf(false, true)) {
            val queue = queue()
            if (shuffled) queue.shuffle()
            queue.setIndex(queue.streams.lastIndex)
            val playing = queue.item!!
            playing.isAutoQueued = true
            val prefix = queue.streams.toList()
            val incoming = queue().streams.toList()
            queue.appendInOrder(incoming)
            assertSame(playing, queue.item)
            assertEquals(prefix, queue.streams.take(prefix.size))
            assertEquals(incoming, queue.streams.drop(prefix.size))
            if (shuffled) {
                queue.unshuffle()
                assertSame(playing, queue.item)
                assertEquals(incoming, queue.streams.takeLast(incoming.size))
            }
        }
    }

    @Test fun orderedAppendReplacesOnlyAFutureSuggestionAndRemovesItFromShuffleBackup() {
        val queue = queue()
        queue.shuffle()
        queue.setIndex(0)
        val playing = queue.item!!
        val suggestion = queue.streams.last()
        suggestion.isAutoQueued = true
        val remaining = queue.streams.dropLast(1)
        val incoming = queue().streams.toList()
        queue.appendInOrder(incoming)
        assertSame(playing, queue.item)
        assertEquals(remaining + incoming, queue.streams)
        queue.unshuffle()
        assertSame(playing, queue.item)
        assertFalse(queue.streams.any { it === suggestion })
        assertEquals(incoming, queue.streams.takeLast(incoming.size))
    }

    @Test fun playerReadDoesNotStartAService() {
        val result = AiAndroidPlaybackPort(ApplicationProvider.getApplicationContext()).execute(AiCatalogCommand.NowPlaying) {}
        assertEquals("false", result.getValue("active").jsonPrimitive.content)
    }
}
