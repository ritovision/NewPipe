/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RitoVisionDatabaseVersionTest {
    @Test
    fun forkNamespaceCannotCollideWithCanonicalUpstreamVersion() {
        assertEquals(9, RitoVisionDatabaseVersion.UPSTREAM_SCHEMA)
        assertEquals(1, RitoVisionDatabaseVersion.FORK_REVISION)
        assertEquals(9001, RitoVisionDatabaseVersion.CURRENT)
        assertTrue(RitoVisionDatabaseVersion.CURRENT > 9)
        assertTrue(RitoVisionDatabaseVersion.CURRENT != 10)
    }

    @Test
    fun importAcceptsUpstreamThrough9OrExactForkRevisionOnly() {
        for (upstream in 1..9) {
            assertTrue(RitoVisionDatabaseVersion.supportsImportSchema(upstream))
        }
        assertTrue(
            RitoVisionDatabaseVersion.supportsImportSchema(
                RitoVisionDatabaseVersion.CURRENT
            )
        )
        for (unsupported in listOf(-1, 0, 10, 11, 8999, 9000, 9002, 10001, 10002)) {
            assertFalse(RitoVisionDatabaseVersion.supportsImportSchema(unsupported))
        }
    }
}
