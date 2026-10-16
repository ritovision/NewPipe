/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import java.util.concurrent.Executor

/**
 * A coalescing save queue whose lifetime is independent of a Fragment view/subscription.
 * Every submitted snapshot survives detachment. Consecutive saves compare against the last
 * actual committed membership, not the timing of a UI callback. Conflicts block the chain.
 */
class PlaylistSaveQueue(private val executor: Executor, private val writer: Writer) {
    fun interface Writer {
        fun save(expected: List<Long>, desired: List<Long>): List<Long>
    }

    fun interface Listener {
        fun onResult(result: Result)
    }

    data class Result(val submitted: List<Long>, val committed: List<Long>?, val error: Throwable?)
    private data class Snapshot(val expected: List<Long>, val desired: List<Long>)

    private val lock = Any()
    private var baseline: List<Long>? = null
    private var pending: List<Long>? = null
    private var running = false
    private var blocked: Throwable? = null
    private var listener: Listener? = null
    private var lastResult: Result? = null
    private var reload: Runnable? = null

    /** Called only when the UI accepts a fresh unmodified database snapshot. */
    fun acceptLoadedBaseline(values: List<Long>) {
        synchronized(lock) {
            if (!running && reload == null && (pending == null || blocked != null)) {
                // startLoading also represents an explicit fresh UI snapshot after an error.
                // Its caller has discarded dirty state; do not leave an invisible blocked queue.
                baseline = values.toList()
                pending = null
                blocked = null
                lastResult = null
            }
        }
    }

    fun setListener(value: Listener?) {
        val previous = synchronized(lock) {
            listener = value
            lastResult
        }
        if (value != null && previous != null) notifySafely(value, previous)
    }

    fun submit(expected: List<Long>, desired: List<Long>) {
        synchronized(lock) {
            if (reload != null) return
            if (baseline == null) baseline = expected.toList()
            pending = desired.toList()
            if (running || blocked != null) return
            running = true
        }
        try {
            executor.execute(::drain)
        } catch (error: Exception) {
            val result = Result(desired.toList(), null, error)
            val target = synchronized(lock) {
                running = false
                blocked = error
                lastResult = result
                listener
            }
            target?.let { notifySafely(it, result) }
        }
    }

    /** Explicit user discard waits for an already-started database operation; never cancels it. */
    fun discardAndWhenIdle(action: Runnable) {
        val immediate = synchronized(lock) {
            pending = null
            blocked = null
            lastResult = null
            if (running) {
                reload = action
                false
            } else {
                baseline = null
                true
            }
        }
        if (immediate) action.run()
    }

    private fun drain() {
        while (true) {
            var afterReload: Runnable? = null
            val snapshot = synchronized(lock) {
                if (reload != null || pending == null) {
                    running = false
                    if (reload != null) {
                        afterReload = reload
                        reload = null
                        baseline = null
                        pending = null
                        blocked = null
                        lastResult = null
                    }
                    null
                } else {
                    Snapshot(checkNotNull(baseline), checkNotNull(pending)).also { pending = null }
                }
            }
            if (snapshot == null) {
                afterReload?.run()
                return
            }
            val result = try {
                Result(snapshot.desired, writer.save(snapshot.expected, snapshot.desired).toList(), null)
            } catch (error: Exception) {
                Result(snapshot.desired, null, error)
            }
            val target = synchronized(lock) {
                if (result.committed != null) baseline = result.committed
                if (result.error != null) blocked = result.error
                lastResult = result
                if (reload == null) listener else null
            }
            target?.let { notifySafely(it, result) }
            if (result.error != null) {
                val restartForReload = synchronized(lock) {
                    if (reload != null) {
                        true
                    } else {
                        running = false
                        false
                    }
                }
                if (!restartForReload) return
            }
        }
    }

    private fun notifySafely(target: Listener, result: Result) {
        try {
            target.onResult(result.copy(submitted = result.submitted.toList(), committed = result.committed?.toList()))
        } catch (error: Exception) {
            // A detached or broken presentation callback must never stop committed/queued saves.
        }
    }
}
