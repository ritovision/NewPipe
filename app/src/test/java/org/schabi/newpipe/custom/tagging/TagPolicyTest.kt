/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPolicyTest {
    @Test fun tagNameNormalizationIsStable() {
        assertEquals("Hip-Hop", TagNames.clean(" Hip-Hop "))
        assertEquals("hip-hop", TagNames.key(" HIP-HOP "))
    }

    @Test fun emptyNamesAndOverlongNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TagNames.clean("   ") }
        assertThrows(IllegalArgumentException::class.java) { TagNames.clean("a".repeat(81)) }
    }

    @Test fun identityPreservesExactUrlAndService() {
        assertNotEquals(StreamKey(0, "https://x/A"), StreamKey(0, "https://x/a"))
        assertNotEquals(StreamKey(0, "https://x/A"), StreamKey(1, "https://x/A"))
    }

    @Test fun andOrNotAndUntaggedHaveExplicitSemantics() {
        val filter = TagFilter(included = setOf(1, 2), excluded = setOf(3))
        assertTrue(filter.matches("A", "B", setOf(1, 2)))
        assertFalse(filter.matches("A", "B", setOf(1)))
        assertTrue(filter.copy(matchAll = false).matches("A", "B", setOf(2)))
        assertFalse(filter.copy(matchAll = false).matches("A", "B", setOf(1, 3)))
        assertTrue(TagFilter(untagged = true).matches("A", "B", emptySet()))
        assertFalse(TagFilter(untagged = true).matches("A", "B", setOf(1)))
    }

    @Test fun searchMatchesTitleOrUploaderWithoutRegex() {
        assertTrue(TagFilter(query = "  echo ").matches("Studio ECHO", "X", emptySet()))
        assertTrue(TagFilter(query = "echo").matches("X", "Studio Echo", emptySet()))
        assertFalse(TagFilter(query = ".*").matches("Anything", "Everyone", emptySet()))
    }

    @Test fun largeSyntheticFilterHasExactCount() {
        val filter = TagFilter(included = setOf(1), excluded = setOf(2))
        val count = (0 until 20000).count {
            filter.matches(
                "Video $it",
                "Uploader",
                buildSet {
                    if (it % 2 == 0) add(1)
                    if (it % 3 == 0) add(2)
                }
            )
        }
        assertEquals(6666, count)
    }
}
