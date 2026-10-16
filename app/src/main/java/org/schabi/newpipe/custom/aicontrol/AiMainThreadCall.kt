/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Cancellation before dispatch is a fact; cancellation after dispatch leaves the outcome unknown. */
internal class AiMainThreadCall<T> {
    enum class Phase { PENDING, RUNNING, FINISHED, CANCELLED }
    private val phase = AtomicReference(Phase.PENDING)
    private val cancelled = AtomicBoolean(false)
    private val done = CountDownLatch(1)

    @Volatile private var value: T? = null

    @Volatile private var error: Throwable? = null

    fun run(action: () -> T) {
        if (!phase.compareAndSet(Phase.PENDING, Phase.RUNNING)) return
        try {
            checkActive()
            value = action()
        } catch (failure: Throwable) {
            error = failure
        } finally {
            phase.set(Phase.FINISHED)
            done.countDown()
        }
    }

    fun checkActive() {
        if (cancelled.get()) throw AiFailure("CANCELLED", AiOutcome.NOT_EXECUTED)
    }

    fun await(timeoutMs: Long): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun cancel(): Phase {
        cancelled.set(true)
        val old = phase.getAndUpdate { if (it == Phase.PENDING) Phase.CANCELLED else it }
        if (old == Phase.PENDING) done.countDown()
        return old
    }

    fun result(): T {
        check(phase.get() == Phase.FINISHED)
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
