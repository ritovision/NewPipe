/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Local permission ceiling, disabled by default. No remote JSON command can change this state.
 * Configure only from the eventual local consent/no-backup policy store. Authentication belongs
 * to the transport; a claimed client_id in an envelope is NOT sufficient to open a session.
 * Lock order is authorization gate, then Room. Enable/disable must never be called inside a
 * database transaction or on the UI thread: they may wait for an already-authorized operation.
 */
internal class AiAccessController {
    private val lock = ReentrantReadWriteLock(true)
    private val stopping = AtomicBoolean(true)

    @Volatile private var generation = 0L
    private var deviceId = ""
    private var catalogEpoch = ""

    @Volatile private var grants: Map<String, Set<AiScope>> = emptyMap()

    fun enableFromLocalConsent(deviceId: String, catalogEpoch: String, grants: Map<String, Set<AiScope>>) {
        AiProtocol.requireUuid(deviceId)
        AiProtocol.requireUuid(catalogEpoch)
        AiProtocol.checkInput(grants.size in 1..16)
        AiProtocol.checkInput(grants.keys.all { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) })
        val grantCopy = grants.mapValues { it.value.toSet() }.toMap()
        lock.write {
            generation++
            this.deviceId = deviceId
            this.catalogEpoch = catalogEpoch
            this.grants = grantCopy
            stopping.set(false)
        }
    }

    /** Signal stop immediately, then wait for an already-committing operation to finish. */
    fun disable() {
        stopping.set(true)
        lock.write {
            generation++
            grants = emptyMap()
        }
    }

    /** Only a transport that independently verified this client may call this method. */
    fun openAuthenticatedSession(authenticatedClientId: String): Session = lock.read {
        if (stopping.get() || !grants.containsKey(authenticatedClientId)) throw AiFailure("NOT_AUTHORIZED", AiOutcome.NOT_EXECUTED)
        Session(this, authenticatedClientId, generation)
    }

    fun <T> withAuthorization(request: AiRequest, session: Session, action: (Lease) -> T): T = lock.read {
        checkSession(session)
        if (request.deviceId != deviceId) throw AiFailure("WRONG_DEVICE", AiOutcome.NOT_EXECUTED)
        if (request.catalogEpoch != catalogEpoch) throw AiFailure("STALE_CATALOG", AiOutcome.NOT_EXECUTED)
        if (request.clientId != session.clientId) throw AiFailure("WRONG_CLIENT", AiOutcome.NOT_EXECUTED)
        val lease = Lease(this, session, grants[session.clientId].orEmpty())
        lease.requireScopes(AiProtocol.requiredScopes(request.command))
        action(lease)
    }

    private fun checkSession(session: Session) {
        if (stopping.get() || session.owner !== this || session.generation != generation || session.closed.get()) {
            throw AiFailure("SESSION_CLOSED", AiOutcome.NOT_EXECUTED)
        }
        if (!grants.containsKey(session.clientId)) throw AiFailure("NOT_AUTHORIZED", AiOutcome.NOT_EXECUTED)
    }

    class Session internal constructor(internal val owner: AiAccessController, internal val clientId: String, internal val generation: Long) {
        internal val closed = AtomicBoolean(false)

        /** Reconnect creates a NEW session; old queued work can never regain authorization. */
        fun disconnect() {
            closed.set(true)
        }
    }

    class Lease internal constructor(private val owner: AiAccessController, private val session: Session, private val scopes: Set<AiScope>) {
        fun checkActive() = owner.checkSession(session)

        fun requireScopes(required: Set<AiScope>) {
            checkActive()
            if (!scopes.containsAll(required)) throw AiFailure("PERMISSION_DENIED", AiOutcome.NOT_EXECUTED)
        }
    }
}
