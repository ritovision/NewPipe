/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import org.junit.Test

class CloudWireTest {
    private fun suite() = CloudWireAssertions(checkNotNull(javaClass.getResourceAsStream("/cloud/transport-v1.json")).bufferedReader().use { it.readText() })

    @Test fun pageParity() = suite().pageParity()

    @Test fun uploadParity() = suite().uploadParity()

    @Test fun sequenceParity() = suite().sequenceParity()

    @Test fun jsonRoundTrip() = suite().jsonRoundTrip()

    @Test fun corruptionRejected() = suite().corruptionRejected()

    @Test fun hintsRemainOutsideSemanticContent() = suite().hintsRemainOutsideSemanticContent()

    @Test fun repeatedInsertionIsBounded() = suite().repeatedInsertionIsBounded()
}
