/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.custom.library.LibraryBindingStore

class CloudBindingMarkerTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private fun props(marker: String? = id, sequence: String? = "3") = Properties().apply {
        marker?.let { setProperty("cloud_incarnation", it) }
        sequence?.let { setProperty("cloud_playlist_sequence", it) }
    }

    @Test fun legacyBindingHasNoCloudAuthority() {
        assertEquals(null to null, LibraryBindingStore.cloudMarker(Properties()))
    }

    @Test fun pairedMarkerPreservesExact64BitHighWater() {
        assertEquals(id to Long.MAX_VALUE, LibraryBindingStore.cloudMarker(props(sequence = Long.MAX_VALUE.toString())))
    }

    @Test fun missingHalfOfMarkerNeverDefaultsOrGuesses() {
        assertThrows(IllegalStateException::class.java) { LibraryBindingStore.cloudMarker(props(marker = null)) }
        assertThrows(IllegalStateException::class.java) { LibraryBindingStore.cloudMarker(props(sequence = null)) }
    }

    @Test fun malformedMarkersAndNoncanonicalSequencesAreRejected() {
        for (seq in listOf("-1", "03", "+3", " 3", "3.0", "9223372036854775808", "", "0x3")) {
            assertThrows(IllegalStateException::class.java) { LibraryBindingStore.cloudMarker(props(sequence = seq)) }
        }
        for (marker in listOf("", "wrong", id.uppercase().replace('0', 'A'), id + "x")) {
            assertThrows(IllegalStateException::class.java) { LibraryBindingStore.cloudMarker(props(marker = marker)) }
        }
    }

    @Test fun stagedScrubPreservesRolesWithoutRuntimeIdentity() {
        val file = Files.createTempFile("cloud-binding-marker-", ".properties")
        try {
            val p = props().apply {
                setProperty("playlist_id", "1")
                setProperty("name", "Library")
                setProperty("unavailable_playlist_id", "2")
            }
            Files.newOutputStream(file).use { p.store(it, "fixture") }
            LibraryBindingStore.stripCloudMarkerFromCopy(file)
            val result = Properties().also { values -> Files.newInputStream(file).use { values.load(it) } }
            assertEquals("1", result.getProperty("playlist_id"))
            assertEquals("Library", result.getProperty("name"))
            assertEquals("2", result.getProperty("unavailable_playlist_id"))
            assertFalse(result.containsKey("cloud_incarnation"))
            assertFalse(result.containsKey("cloud_playlist_sequence"))
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
