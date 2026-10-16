/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Local permission ceiling, disabled by default. Wire JSON never configures this controller.
 * Lock order: access gate, then Room. Full disable/configure runs on IO, never inside a Room
 * transaction or on the UI thread. signalStop() is non-blocking and may be called from any thread.
 */
internal class AiAccessController {
    private val lock = ReentrantReadWriteLock(true)
    private val stopping = AtomicBoolean(true)

    @Volatile private var generation = 0L
    private var deviceId = ""
    private var catalogEpoch = ""

    @Volatile private var grants: Map<String, Set<AiScope>> = emptyMap()

    @Volatile private var gatewayCeiling: Set<AiScope>? = null

    fun enableFromLocalConsent(deviceId: String, catalogEpoch: String, grants: Map<String, Set<AiScope>>) {
        validateIdentity(deviceId, catalogEpoch)
        AiProtocol.checkInput(grants.size in 1..16)
        AiProtocol.checkInput(grants.keys.all { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) })
        val copy = grants.mapValues { it.value.toSet() }.toMap()
        lock.write {
            generation++
            this.deviceId = deviceId
            this.catalogEpoch = catalogEpoch
            this.grants = copy
            gatewayCeiling = null
            stopping.set(false)
        }
    }

    /** Only the locally enabled, securely paired runtime may configure this ceiling. */
    fun enableGatewayFromLocalConsent(deviceId: String, catalogEpoch: String, ceiling: Set<AiScope>) {
        validateIdentity(deviceId, catalogEpoch)
        val copy = ceiling.toSet()
        lock.write {
            generation++
            this.deviceId = deviceId
            this.catalogEpoch = catalogEpoch
            grants = emptyMap()
            gatewayCeiling = copy
            stopping.set(false)
        }
    }

    fun signalStop() {
        stopping.set(true)
    }

    fun disable() {
        signalStop()
        lock.write {
            generation++
            grants = emptyMap()
            gatewayCeiling = null
        }
    }

    fun openAuthenticatedSession(authenticatedClientId: String): Session = lock.read {
        if (stopping.get() || !grants.containsKey(authenticatedClientId)) throw AiFailure("NOT_AUTHORIZED", AiOutcome.NOT_EXECUTED)
        Session(this, authenticatedClientId, generation)
    }

    /** Authenticated gateway identity and OAuth scopes, independently capped by local consent. */
    fun openAuthenticatedGatewaySession(authenticatedClientId: String, oauthScopes: Set<AiScope>, connectionActive: () -> Boolean = { true }): Session = lock.read {
        val ceiling = gatewayCeiling
        if (stopping.get() || ceiling == null || !authenticatedClientId.matches(Regex("c_[0-9a-f]{64}"))) {
            throw AiFailure("NOT_AUTHORIZED", AiOutcome.NOT_EXECUTED)
        }
        Session(this, authenticatedClientId, generation, oauthScopes.intersect(ceiling).toSet(), connectionActive)
    }

    fun <T> withAuthorization(request: AiRequest, session: Session, action: (Lease) -> T): T = lock.read {
        checkSession(session)
        if (request.deviceId != deviceId) throw AiFailure("WRONG_DEVICE", AiOutcome.NOT_EXECUTED)
        if (request.catalogEpoch != catalogEpoch) throw AiFailure("STALE_CATALOG", AiOutcome.NOT_EXECUTED)
        if (request.clientId != session.clientId) throw AiFailure("WRONG_CLIENT", AiOutcome.NOT_EXECUTED)
        val lease = Lease(this, session, session.gatewayScopes ?: grants[session.clientId].orEmpty())
        lease.requireScopes(AiProtocol.requiredScopes(request.command))
        action(lease)
    }

    private fun checkSession(session: Session) {
        if (stopping.get() || session.owner !== this || session.generation != generation || session.closed.get() || !session.connectionActive()) {
            throw AiFailure("SESSION_CLOSED", AiOutcome.NOT_EXECUTED)
        }
        if ((session.gatewayScopes == null && !grants.containsKey(session.clientId)) ||
            (session.gatewayScopes != null && gatewayCeiling == null)
        ) {
            throw AiFailure("NOT_AUTHORIZED", AiOutcome.NOT_EXECUTED)
        }
    }

    private fun validateIdentity(device: String, epoch: String) {
        AiProtocol.requireUuid(device)
        AiProtocol.requireUuid(epoch)
    }

    class Session internal constructor(
        internal val owner: AiAccessController,
        internal val clientId: String,
        internal val generation: Long,
        internal val gatewayScopes: Set<AiScope>? = null,
        internal val connectionActive: () -> Boolean = { true }
    ) {
        internal val closed = AtomicBoolean(false)
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
