/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import org.junit.Test

class CloudProtocolTest {
    private fun suite() = CloudProtocolAssertions(checkNotNull(javaClass.getResourceAsStream("/cloud/golden-v1.json")).bufferedReader().use { it.readText() })

    @Test fun golden() = suite().golden()

    @Test fun rejectsInvalidCatalogs() = suite().rejectsInvalidCatalogs()

    @Test fun namesAndCounters() = suite().namesAndCounters()

    @Test fun policy() = suite().policy()

    @Test fun exactDifferences() = suite().exactDifferences()

    @Test fun previewCannotWiden() = suite().previewCannotWiden()

    @Test fun callerMutationAndLimits() = suite().callerMutationAndLimits()

    @Test fun largeCatalog() = suite().largeCatalog()
}
