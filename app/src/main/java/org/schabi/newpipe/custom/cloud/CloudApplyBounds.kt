/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

/** Bound the resulting native store, not only the portable active-library projection. */
internal object CloudApplyBounds {
    fun requireAssignmentBudget(retainedOrphanRelations: Long, targetActiveRelations: Long) {
        val maximum = CloudCatalogCodec.MAX_MEMBERSHIPS.toLong()
        check(
            targetActiveRelations in 0..maximum && retainedOrphanRelations >= 0 &&
                retainedOrphanRelations <= maximum - targetActiveRelations
        ) { "CLOUD_NATIVE_ASSIGNMENT_LIMIT" }
    }
}

/** A selected complete native backup may replace both stores when this recovery state is blocked. */
internal class CloudRecoveryBlockedException(cause: Throwable) : IllegalStateException(cause.message ?: "CLOUD_RECOVERY_BLOCKED", cause)
