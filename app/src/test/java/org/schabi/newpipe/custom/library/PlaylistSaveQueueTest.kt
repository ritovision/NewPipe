/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSaveQueueTest {
    private class ManualExecutor : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            work.add(command)
        }
        fun runAll() {
            while (work.isNotEmpty()) work.removeFirst().run()
        }
    }

    @Test
    fun submittedEditsCommitAfterTheViewListenerDetaches() {
        val tasks = ManualExecutor()
        var stored = listOf(1L, 2L, 3L)
        val writes = mutableListOf<List<Long>>()
        var callbacks = 0
        lateinit var queue: PlaylistSaveQueue
        queue = PlaylistSaveQueue(tasks) { expected, desired ->
            assertEquals(stored, expected)
            stored = desired.toList()
            writes.add(stored)
            if (writes.size == 1) {
                queue.submit(listOf(1, 2, 3), listOf(3))
                queue.setListener(null)
            }
            stored
        }
        queue.setListener { callbacks++ }
        queue.submit(stored, listOf(2, 3))
        tasks.runAll()
        assertEquals(listOf(listOf(2L, 3L), listOf(3L)), writes)
        assertEquals(listOf(3L), stored)
        assertEquals(0, callbacks)
    }

    @Test
    fun aDelayedUiAcknowledgementCannotGiveTheNextSaveAnOldBaseline() {
        val tasks = ManualExecutor()
        var stored = listOf(1L, 2L)
        val queue = PlaylistSaveQueue(tasks) { expected, desired ->
            assertEquals(stored, expected)
            desired.toList().also { stored = it }
        }
        queue.submit(listOf(1, 2), listOf(2))
        tasks.runAll()
        // The UI has not processed its old completion event yet.
        queue.submit(listOf(1, 2), emptyList())
        tasks.runAll()
        assertTrue(stored.isEmpty())
    }

    @Test
    fun multiplePendingSnapshotsCoalesceToTheLatestWithoutGrowingAQueue() {
        val tasks = ManualExecutor()
        var calls = 0
        var saved = emptyList<Long>()
        val queue = PlaylistSaveQueue(tasks) { expected, desired ->
            assertEquals(listOf(1L, 2L, 3L), expected)
            calls++
            desired.also { saved = it }
        }
        queue.submit(listOf(1, 2, 3), listOf(1, 2))
        queue.submit(listOf(1, 2, 3), listOf(1))
        queue.submit(listOf(1, 2, 3), emptyList())
        assertEquals(1, tasks.work.size)
        tasks.runAll()
        assertEquals(1, calls)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun externalMarkIsSequencedAfterPendingEditorSaveAcknowledgement() {
        val worker = ManualExecutor()
        val ui = ArrayDeque<Runnable>()
        var stored = listOf(1L, 2L, 3L)
        var modified = true
        var markRan = false
        val reordered = listOf(3L, 2L, 1L)
        val queue = PlaylistSaveQueue(worker) { expected, desired ->
            assertEquals(stored, expected)
            stored = desired.toList()
            stored
        }
        queue.setListener { result ->
            ui.add {
                assertEquals(reordered, result.committed)
                modified = false
            }
        }
        queue.submit(stored, reordered)
        // The user chooses Mark Available while the first reorder is running.
        // Marking must occur after the save-result UI acknowledgment, not drop it.
        queue.afterSavesForMutation {
            ui.add {
                assertFalse("Cannot mark before the pending reorder settles", modified)
                stored = stored.filterNot { it == 2L }
                markRan = true
            }
        }
        assertFalse(markRan)
        worker.runAll()
        assertFalse(markRan)
        while (ui.isNotEmpty()) ui.removeFirst().run()
        assertTrue(markRan)
        assertEquals(listOf(3L, 1L), stored)
    }

    @Test
    fun failedEditorSaveCannotBecomeAnImplicitMarkOrDiscard() {
        val worker = ManualExecutor()
        val ui = ArrayDeque<Runnable>()
        var modified = true
        var marked = false
        val queue = PlaylistSaveQueue(worker) { _, _ ->
            throw PlaylistEditConflictException()
        }
        queue.setListener { result ->
            ui.add {
                assertTrue(result.error is PlaylistEditConflictException)
                // Preserve dirty state so the user can explicitly review/reload.
            }
        }
        queue.submit(listOf(1L, 2L), listOf(2L, 1L))
        queue.afterSavesForMutation { ui.add { if (!modified) marked = true } }
        worker.runAll()
        while (ui.isNotEmpty()) ui.removeFirst().run()
        assertFalse(marked)
        assertTrue(modified)
    }

    @Test
    fun twoDeferredUnavailableMarksAreBothDeliveredAfterOnePendingReorder() {
        val worker = ManualExecutor()
        val ui = ArrayDeque<Runnable>()
        val events = mutableListOf<String>()
        val queue = PlaylistSaveQueue(worker) { _, desired -> desired }
        queue.setListener { ui.add { events.add("save acknowledged") } }
        queue.submit(listOf(1L, 2L, 3L), listOf(3L, 2L, 1L))
        queue.afterSavesForMutation { ui.add { events.add("mark available 1") } }
        queue.afterSavesForMutation { ui.add { events.add("mark unavailable 2") } }
        worker.runAll()
        assertTrue(events.isEmpty())
        while (ui.isNotEmpty()) ui.removeFirst().run()
        assertEquals(
            listOf("save acknowledged", "mark available 1", "mark unavailable 2"),
            events
        )
    }

    @Test
    fun secondScheduledMarkSurvivesFirstMarksViewReload() {
        val worker = ManualExecutor()
        val scheduler = Schedulers.from(worker)
        val viewDisposables = CompositeDisposable()
        val written = mutableListOf<Long>()
        // The first mark's UI completion refreshes the list and clears view
        // subscriptions while the second mark's IO task remains scheduled.
        Completable.fromAction { written.add(1L) }
            .subscribeOn(scheduler)
            .subscribe({ viewDisposables.clear() }, { throw AssertionError(it) })
        Completable.fromAction { written.add(2L) }
            .subscribeOn(scheduler)
            .subscribe({}, { throw AssertionError(it) })
        viewDisposables.add(Completable.never().subscribe())
        worker.runAll()
        assertEquals(listOf(1L, 2L), written)
        assertEquals(0, viewDisposables.size())
    }

    @Test
    fun latestViewReloadCoalescesButEveryMutationSurvives() {
        val worker = ManualExecutor()
        val events = mutableListOf<String>()
        val queue = PlaylistSaveQueue(worker) { _, desired -> desired }
        queue.submit(listOf(1L, 2L), listOf(2L))
        queue.whenIdle { events.add("old reload") }
        queue.afterSavesForMutation { events.add("first marking") }
        queue.whenIdle { events.add("latest reload") }
        queue.afterSavesForMutation { events.add("second marking") }
        worker.runAll()
        assertEquals(
            listOf("latest reload", "first marking", "second marking"),
            events
        )
    }

    @Test
    fun reloadWaitsForTheStartedCommitThenDiscardsThePendingSnapshot() {
        val tasks = ManualExecutor()
        val events = mutableListOf<String>()
        lateinit var queue: PlaylistSaveQueue
        queue = PlaylistSaveQueue(tasks) { _, desired ->
            events.add("start")
            queue.submit(listOf(1, 2), emptyList())
            queue.discardAndWhenIdle { events.add("reload") }
            assertFalse(events.contains("reload"))
            events.add("commit")
            desired
        }
        queue.submit(listOf(1, 2), listOf(2))
        tasks.runAll()
        assertEquals(listOf("start", "commit", "reload"), events)
    }

    @Test
    fun reloadBeforeWorkerStartsDiscardsWithoutWritingAndAllowsLaterSaves() {
        val tasks = ManualExecutor()
        var calls = 0
        var reloaded = false
        val queue = PlaylistSaveQueue(tasks) { expected, desired ->
            calls++
            assertEquals(listOf(5L), expected)
            desired
        }
        queue.submit(listOf(1), emptyList())
        queue.discardAndWhenIdle { reloaded = true }
        tasks.runAll()
        assertEquals(0, calls)
        assertTrue(reloaded)
        queue.acceptLoadedBaseline(listOf(5))
        queue.submit(listOf(5), emptyList())
        tasks.runAll()
        assertEquals(1, calls)
    }

    @Test
    fun failedComparisonBlocksNewWritesUntilExplicitReload() {
        val tasks = ManualExecutor()
        var calls = 0
        var fail = true
        val queue = PlaylistSaveQueue(tasks) { _, desired ->
            calls++
            if (fail) throw PlaylistEditConflictException()
            desired
        }
        queue.submit(listOf(1), emptyList())
        tasks.runAll()
        queue.submit(listOf(1), listOf(2))
        tasks.runAll()
        assertEquals(1, calls)
        queue.discardAndWhenIdle { fail = false }
        queue.acceptLoadedBaseline(listOf(3))
        queue.submit(listOf(3), emptyList())
        tasks.runAll()
        assertEquals(2, calls)
    }

    @Test
    fun newBaselineMayBeAdoptedAfterAFreshUnmodifiedRead() {
        val tasks = ManualExecutor()
        var expectedByWriter = listOf(1L)
        val queue = PlaylistSaveQueue(tasks) { expected, desired ->
            assertEquals(expectedByWriter, expected)
            desired
        }
        queue.submit(listOf(1), emptyList())
        tasks.runAll()
        expectedByWriter = listOf(2)
        queue.acceptLoadedBaseline(expectedByWriter)
        queue.submit(expectedByWriter, emptyList())
        tasks.runAll()
    }

    @Test
    fun staleObserverCannotReplaceTheBaselineOfAnActiveSave() {
        val tasks = ManualExecutor()
        val queue = PlaylistSaveQueue(tasks) { expected, desired ->
            assertEquals(listOf(1L), expected)
            desired
        }
        queue.submit(listOf(1), emptyList())
        queue.acceptLoadedBaseline(listOf(999))
        tasks.runAll()
    }

    @Test
    fun inputSnapshotsAreDefensiveCopies() {
        val tasks = ManualExecutor()
        val expected = mutableListOf(1L, 2L)
        val desired = mutableListOf(2L)
        val queue = PlaylistSaveQueue(tasks) { before, after ->
            assertEquals(listOf(1L, 2L), before)
            assertEquals(listOf(2L), after)
            after
        }
        queue.submit(expected, desired)
        expected.clear()
        desired.clear()
        tasks.runAll()
    }

    @Test
    fun presentationFailureCannotStopTheSaveChain() {
        val tasks = ManualExecutor()
        var calls = 0
        lateinit var queue: PlaylistSaveQueue
        queue = PlaylistSaveQueue(tasks) { _, desired ->
            if (++calls == 1) queue.submit(listOf(1, 2), emptyList())
            desired
        }
        queue.setListener { throw IllegalStateException("Broken view") }
        queue.submit(listOf(1, 2), listOf(2))
        tasks.runAll()
        assertEquals(2, calls)
    }

    @Test
    fun attachingANewListenerReceivesTheLastOutcome() {
        val tasks = ManualExecutor()
        val queue = PlaylistSaveQueue(tasks) { _, desired -> desired }
        queue.submit(listOf(1), emptyList())
        tasks.runAll()
        var delivered = false
        queue.setListener { result ->
            assertEquals(emptyList<Long>(), result.committed)
            delivered = true
        }
        assertTrue(delivered)
    }

    @Test
    fun freshUnmodifiedReloadRecoversABlockedQueueAfterAStorageError() {
        val tasks = ManualExecutor()
        var failing = true
        var calls = 0
        val queue = PlaylistSaveQueue(tasks) { expected, desired ->
            calls++
            if (failing) throw IllegalStateException("Synthetic storage error")
            assertEquals(listOf(9L), expected)
            desired
        }
        queue.submit(listOf(1), emptyList())
        tasks.runAll()
        queue.submit(listOf(1), listOf(2))
        failing = false
        queue.acceptLoadedBaseline(listOf(9))
        queue.submit(listOf(9), emptyList())
        tasks.runAll()
        assertEquals(2, calls)
    }

    @Test
    fun recreatedViewWaitsForRetainedDeletionBeforeAcceptingAnEditableSnapshot() {
        val tasks = ManualExecutor()
        var stored = listOf(1L, 2L, 3L)
        var displayed: List<Long>? = null
        val queue = PlaylistSaveQueue(tasks) { expected, desired ->
            assertEquals(stored, expected)
            desired.toList().also { stored = it }
        }
        queue.submit(stored, listOf(2, 3))
        queue.setListener(null)
        queue.whenIdle { displayed = stored.toList() }
        assertEquals(null, displayed)
        tasks.runAll()
        assertEquals(listOf(2L, 3L), displayed)
        // The recreated editor now removes 3 from the correct committed baseline.
        queue.acceptLoadedBaseline(checkNotNull(displayed))
        queue.submit(checkNotNull(displayed), listOf(2))
        tasks.runAll()
        assertEquals(listOf(2L), stored)
    }

    @Test
    fun idleObserverWaitsThroughAllCoalescedSavesRatherThanOnlyTheFirst() {
        val tasks = ManualExecutor()
        val events = mutableListOf<String>()
        lateinit var queue: PlaylistSaveQueue
        queue = PlaylistSaveQueue(tasks) { _, desired ->
            events.add("commit:$desired")
            if (desired.size == 2) {
                queue.whenIdle { events.add("load") }
                queue.submit(listOf(1, 2, 3), listOf(3))
            }
            desired
        }
        queue.submit(listOf(1, 2, 3), listOf(2, 3))
        tasks.runAll()
        assertEquals(listOf("commit:[2, 3]", "commit:[3]", "load"), events)
    }

    @Test
    fun aRecreatedViewCanReloadAfterARetainedSaveConflicts() {
        val tasks = ManualExecutor()
        var loaded = false
        val queue = PlaylistSaveQueue(tasks) { _, _ -> throw PlaylistEditConflictException() }
        queue.submit(listOf(1), emptyList())
        queue.whenIdle { loaded = true }
        assertFalse(loaded)
        tasks.runAll()
        assertTrue(loaded)
    }

    @Test
    fun onlyTheNewestWaitingViewReceivesTheIdleNotification() {
        val tasks = ManualExecutor()
        val events = mutableListOf<String>()
        val queue = PlaylistSaveQueue(tasks) { _, desired -> desired }
        queue.submit(listOf(1), emptyList())
        queue.whenIdle { events.add("old view") }
        queue.whenIdle { events.add("current view") }
        tasks.runAll()
        assertEquals(listOf("current view"), events)
    }

    @Test
    fun idleViewLoadingDoesNotStartAnyBackgroundWork() {
        val tasks = ManualExecutor()
        val queue = PlaylistSaveQueue(tasks) { _, desired -> desired }
        var loaded = false
        queue.whenIdle { loaded = true }
        assertTrue(loaded)
        assertTrue(tasks.work.isEmpty())
    }
}
