/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIdentityPolicyTest {
    @Test
    fun knownIdDoesNotProtectOrdinaryPlaylistNamedLibrary() {
        assertFalse(LibraryIdentityPolicy.protects(1, 2, "Library"))
        assertFalse(LibraryIdentityPolicy.protects(1, 2, " library "))
    }

    @Test
    fun knownIdProtectsCanonicalPlaylistRegardlessOfDisplayLabel() {
        assertTrue(LibraryIdentityPolicy.protects(1, 1, "Library"))
        assertTrue(LibraryIdentityPolicy.protects(1, 1, "Old label"))
        assertTrue(LibraryIdentityPolicy.protects(1, 1, null))
    }

    @Test
    fun unboundCandidateIsProtectedButNotAdopted() {
        assertTrue(LibraryIdentityPolicy.protects(null, 2, "Library"))
        assertTrue(LibraryIdentityPolicy.protects(null, 2, "  liBrArY  "))
    }

    @Test
    fun unboundOrdinaryPlaylistsRemainOrdinary() {
        assertFalse(LibraryIdentityPolicy.protects(null, 2, "Favorites"))
        assertFalse(LibraryIdentityPolicy.protects(null, 2, null))
        assertFalse(LibraryIdentityPolicy.protects(null, 2, ""))
    }

    @Test
    fun startupTransitionReleasesNameOnlyProtection() {
        assertTrue(LibraryIdentityPolicy.protects(null, 2, "Library"))
        assertFalse(LibraryIdentityPolicy.protects(1, 2, "Library"))
        assertTrue(LibraryIdentityPolicy.protects(1, 1, "Library"))
    }

    @Test
    fun missingTargetCannotMatchAnActiveId() {
        assertFalse(LibraryIdentityPolicy.protects(1, null, "Library"))
    }
}
