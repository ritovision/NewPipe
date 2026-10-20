/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Exercises the real transport boundary; fake controller peers cannot catch a missing route. */
class CloudHttpRouteTest {
    @Test fun replacementRequestsReachConnectionAdmission() {
        val fenced = IllegalStateException("TEST_CONNECTION_FENCED")
        val client = CloudHttpClient("https://example.test", "unused") { throw fenced }
        for (action in listOf("status", "preview", "reserve", "publish", "settle", "abandon")) {
            assertSame(
                fenced,
                assertThrows(IllegalStateException::class.java) {
                    client.post("/v1/replacement/$action", emptyMap())
                }
            )
        }
    }

    @Test fun unrecognizedAndAlteredRoutesAreRejectedBeforeAdmission() {
        var admissions = 0
        val client = CloudHttpClient("https://example.test", "unused") { admissions++ }
        for (path in listOf("/v1/replacement/delete", "/v1/replacement/publish?force=true", "https://other.test/v1/replacement/publish")) {
            val error = assertThrows(IllegalStateException::class.java) { client.post(path, emptyMap()) }
            assertEquals("INVALID_CLOUD_ROUTE", error.message)
        }
        assertEquals(0, admissions)
    }
}
