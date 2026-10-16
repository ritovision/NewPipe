/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryEditPolicyTest {
    @Test
    fun committedRemovalCannotBeRestoredByStaleEditor() {
        val saved = setOf(2L)
        assertEquals(listOf(2L), LibraryEditPolicy.keepSaved(listOf(1L, 2L, 1L), saved::contains))
    }

    @Test
    fun rolledBackRemovalHasNoResidualMarker() {
        // After rollback, the database still contains the original membership.
        val savedAfterRollback = setOf(1L, 2L)
        assertEquals(
            listOf(2L, 1L),
            LibraryEditPolicy.keepSaved(listOf(2L, 1L), savedAfterRollback::contains)
        )
    }

    @Test
    fun explicitReaddAllowsEditingTheSavedItemAgain() {
        val saved = mutableSetOf(2L)
        assertEquals(listOf(2L), LibraryEditPolicy.keepSaved(listOf(1L, 2L), saved::contains))
        saved.add(1L)
        assertEquals(listOf(1L, 2L), LibraryEditPolicy.keepSaved(listOf(1L, 2L), saved::contains))
    }

    @Test
    fun survivingOrdinaryDuplicatesArePreserved() {
        val saved = setOf(1L, 2L)
        assertEquals(listOf(1L, 1L, 2L), LibraryEditPolicy.keepSaved(listOf(1L, 1L, 2L), saved::contains))
    }

    @Test
    fun orderIsNotChanged() {
        val saved = setOf(1L, 2L, 3L)
        assertEquals(listOf(3L, 1L, 2L), LibraryEditPolicy.keepSaved(listOf(3L, 1L, 2L), saved::contains))
    }

    @Test
    fun emptyLibraryCannotBePopulatedByAnEditorRewrite() {
        assertEquals(emptyList<Long>(), LibraryEditPolicy.keepSaved(listOf(1L, 2L)) { false })
    }

    @Test
    fun emptyEditorDoesNotAddSavedItems() {
        assertEquals(emptyList<Long>(), LibraryEditPolicy.keepSaved(emptyList()) { true })
    }
}
