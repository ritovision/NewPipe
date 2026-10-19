/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

/** JSON counters never pass through Double or a JavaScript Number. */
object CloudCounter {
    private val decimal = Regex("0|[1-9][0-9]{0,18}")
    fun parse(value: String): Long {
        require(decimal.matches(value)) { "INVALID_COUNTER" }
        return value.toLongOrNull() ?: throw IllegalArgumentException("INVALID_COUNTER")
    }
}

data class CloudHead(
    val serviceGeneration: String,
    val library: String,
    val epoch: String,
    val revision: String,
    val headCounter: String,
    val fingerprint: String
) {
    init {
        val identity = Regex("[A-Za-z0-9_-]{1,128}")
        require(listOf(serviceGeneration, library, epoch).all { identity.matches(it) }) { "INVALID_HEAD_IDENTITY" }
        CloudCounter.parse(revision)
        CloudCounter.parse(headCounter)
        require(Regex("[0-9a-f]{64}").matches(fingerprint)) { "INVALID_FINGERPRINT" }
    }
}

enum class CloudDecision {
    DISABLED,
    IDENTITY_REPAIR_REQUIRED,
    REENROLL_REQUIRED,
    ROLLBACK_SUSPECTED,
    RESOLVE_OPERATION,
    ENROLLMENT_REQUIRED,
    NOOP,
    ADOPT_BASELINE,
    PULL,
    PUSH,
    CONFLICT
}

data class CloudSyncInput(
    val enabled: Boolean,
    val identityReady: Boolean,
    val unresolvedOperation: Boolean,
    val localFingerprint: String,
    val baseline: CloudHead?,
    val remote: CloudHead?,
    val highestObserved: CloudHead?
)
data class CloudPreview(
    val action: String,
    val connectionGeneration: String,
    val incarnation: String,
    val localFingerprint: String,
    val remote: CloudHead,
    val candidateDigest: String,
    val expiresAt: String
)

/** Pure policy; does not acquire credentials, submit work or mutate any local/remote database. */
object CloudSyncPolicy {
    fun decide(s: CloudSyncInput): CloudDecision {
        if (!s.enabled) return CloudDecision.DISABLED
        if (!s.identityReady) return CloudDecision.IDENTITY_REPAIR_REQUIRED
        val remote = requireNotNull(s.remote) { "A validated remote head is required" }
        require(Regex("[0-9a-f]{64}").matches(s.localFingerprint)) { "INVALID_FINGERPRINT" }
        for (known in listOfNotNull(s.baseline, s.highestObserved)) {
            if (known.serviceGeneration != remote.serviceGeneration || known.library != remote.library) return CloudDecision.REENROLL_REQUIRED
            val nextCounter = CloudCounter.parse(remote.headCounter)
            val oldCounter = CloudCounter.parse(known.headCounter)
            if (nextCounter < oldCounter || nextCounter == oldCounter && known != remote ||
                known.epoch == remote.epoch && CloudCounter.parse(remote.revision) < CloudCounter.parse(known.revision)
            ) {
                return CloudDecision.ROLLBACK_SUSPECTED
            }
        }
        if (s.unresolvedOperation) return CloudDecision.RESOLVE_OPERATION
        val base = s.baseline ?: return CloudDecision.ENROLLMENT_REQUIRED
        if (s.localFingerprint == remote.fingerprint) return if (base == remote) CloudDecision.NOOP else CloudDecision.ADOPT_BASELINE
        val localChanged = s.localFingerprint != base.fingerprint
        val cloudChanged = base != remote
        return if (localChanged) {
            if (cloudChanged) CloudDecision.CONFLICT else CloudDecision.PUSH
        } else {
            if (cloudChanged) CloudDecision.PULL else CloudDecision.NOOP
        }
    }

    /** Caller measures actual local content while holding the same barrier as native apply. */
    fun previewStillMatches(expected: CloudPreview, actual: CloudPreview, now: String): Boolean {
        CloudCounter.parse(expected.connectionGeneration)
        CloudCounter.parse(actual.connectionGeneration)
        return CloudCounter.parse(expected.expiresAt) > CloudCounter.parse(now) && expected == actual
    }
}
