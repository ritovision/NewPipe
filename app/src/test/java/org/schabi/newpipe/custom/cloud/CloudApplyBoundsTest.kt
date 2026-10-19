/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudApplyBoundsTest {
    @Test fun emptyAndExactlyFullCombinedNativeStoreAreAllowed() {
        CloudApplyBounds.requireAssignmentBudget(0, 0)
        CloudApplyBounds.requireAssignmentBudget(500_000, 0)
        CloudApplyBounds.requireAssignmentBudget(0, 500_000)
        CloudApplyBounds.requireAssignmentBudget(499_999, 1)
    }

    @Test fun validPortableTargetCannotOverflowRetainedNativeOrphanRelations() {
        assertEquals(
            "CLOUD_NATIVE_ASSIGNMENT_LIMIT",
            assertThrows(IllegalStateException::class.java) {
                CloudApplyBounds.requireAssignmentBudget(500_000, 1)
            }.message
        )
    }

    @Test fun malformedOrOverflowingCountsNeverWrapAroundIntoAdmission() {
        for ((orphan, target) in listOf(
            -1L to 0L,
            0L to -1L,
            Long.MAX_VALUE to 1L,
            1L to Long.MAX_VALUE,
            Long.MAX_VALUE to Long.MAX_VALUE,
            0L to 500_001L
        )) {
            assertThrows(IllegalStateException::class.java) { CloudApplyBounds.requireAssignmentBudget(orphan, target) }
        }
    }
}
