/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

/** One atomic decision between local revocation and publication of a policy snapshot. */
internal class AiPolicyGate {
    private val mutex = Any()

    @Volatile var version: Long = 0
        private set

    @Volatile var enabled: Boolean = false
        private set

    fun invalidate(stop: () -> Unit = {}): Long = synchronized(mutex) {
        version++
        enabled = false
        stop()
        version
    }

    /** Queueing under the same short lock preserves invocation order across UI/notification threads. */
    fun enqueueChange(stop: () -> Unit, enqueue: (Long) -> Unit): Long = invalidate {
        stop()
        enqueue(version)
    }

    fun commit(expectedVersion: Long, enable: Boolean, publish: () -> Unit = {}): Boolean = synchronized(mutex) {
        if (expectedVersion != version) return@synchronized false
        enabled = enable
        publish()
        true
    }

    /** Run on the serialized IO lane. Never discard an older Stop/revoke intent merely because
     * another configuration change was queued later; only publication is latest-version-only. */
    fun <T> applyOrdered(expectedVersion: Long, persist: () -> T, enabled: (T) -> Boolean, publish: (T) -> Unit): T {
        val value = persist()
        commit(expectedVersion, enabled(value)) { publish(value) }
        return value
    }
}
