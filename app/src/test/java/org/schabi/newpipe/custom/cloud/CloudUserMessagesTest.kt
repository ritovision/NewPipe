/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudUserMessagesTest {
    @Test fun budgetAdmissionExplainsTemporaryPauseWithoutExposingInternalCodes() {
        for (code in listOf("BUDGET_OR_MAINTENANCE_UNAVAILABLE", "MAINTENANCE_REQUIRED", "BUDGET_ADMISSION_UNAVAILABLE")) {
            val message = CloudUserMessages.error(IllegalStateException(code))
            assertTrue(message.contains("Local edits are retained"))
            assertTrue(message.contains("retry later"))
            assertFalse(message.contains(code))
        }
    }

    @Test fun uncertainMeteringNeverClaimsTheCloudWriteWasRolledBack() {
        for (code in listOf("BUDGET_ESTIMATE_EXCEEDED", "BUDGET_ACCOUNTING_BOUND_EXCEEDED", "BUDGET_METER_UNAVAILABLE", "BUDGET_RESERVATION_BLOCKED")) {
            val message = CloudUserMessages.error(IllegalStateException(code))
            assertTrue(message.contains("operator review"))
            assertTrue(message.contains("do not discard or repeat"))
            assertFalse(message.contains("Nothing was uploaded"))
            assertFalse(message.contains(code))
        }
    }

    @Test fun invalidBudgetConfigurationNeedsOperatorActionAndRetainsPendingWork() {
        for (code in listOf("BUDGET_ESTIMATE_INVALID", "BUDGET_ROUTE_UNAVAILABLE", "REQUEST_STATEMENT_BUDGET_EXCEEDED")) {
            val message = CloudUserMessages.error(IllegalStateException(code))
            assertTrue(message.contains("pending work are retained"))
            assertTrue(message.contains("operator"))
            assertFalse(message.contains(code))
        }
    }
}
