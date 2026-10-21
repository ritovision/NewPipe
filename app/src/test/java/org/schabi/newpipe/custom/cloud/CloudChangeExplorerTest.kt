/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Different persisted choices must remain distinguishable even when display hints coincide. */
class CloudChangeExplorerTest {
    private val category = CloudCategory("10000000-0000-0000-0000-000000000001", "Genre", "genre")
    private val otherCategory = CloudCategory("10000000-0000-0000-0000-000000000002", "Mood", "mood")
    private val tag = CloudTag("20000000-0000-0000-0000-000000000001", "Keep", "keep", category.id)
    private val key = CloudKey(0, "https://example.test/art")
    private val otherKey = key.copy(service = 1)
    private val playlist = CloudPlaylist("30000000-0000-0000-0000-000000000001", "List", key, emptyList())
    private val hints = listOf(key, otherKey).associateWith { CloudMediaHint(it, "Same title", "", null, 0, "VIDEO_STREAM") }
    private fun groups(before: CloudCatalog, after: CloudCatalog) = CloudChangeExplorer.groups(before, after, hints)

    @Test fun addedTagsExposeExistingCategoryAndUncategorizedChoices() {
        val before = CloudCatalog(categories = listOf(category, otherCategory))
        val first = groups(before, before.copy(tags = listOf(tag)))
        val second = groups(before, before.copy(tags = listOf(tag.copy(category = otherCategory.id))))
        val none = groups(before, before.copy(tags = listOf(tag.copy(category = null))))
        assertTrue(first.single().items.single().detail.contains("Genre"))
        assertNotEquals(first, second)
        assertNotEquals(first, none)
        assertEquals(first, CloudChangeExplorer.search(first, "Genre"))
        assertTrue(none.single().items.single().detail.contains("None"))
    }

    @Test fun createdPlaylistsExposeArtworkIdentityDespiteIdenticalHints() {
        val before = CloudCatalog()
        val first = groups(before, CloudCatalog(playlists = listOf(playlist)))
        val second = groups(before, CloudCatalog(playlists = listOf(playlist.copy(artwork = otherKey))))
        val artwork = first.single().items.single { it.kind == "Artwork" }
        assertTrue(artwork.detail.contains(key.url))
        assertTrue(artwork.detail.contains("Service 0"))
        assertNotEquals(first, second)
        assertEquals(listOf(artwork), CloudChangeExplorer.search(first, key.url).single().items)
    }

    @Test fun createdPlaylistWithAutomaticArtworkIsExplicit() {
        val result = groups(CloudCatalog(), CloudCatalog(playlists = listOf(playlist.copy(artwork = null))))
        assertEquals("Automatic", result.single().items.single { it.kind == "Artwork" }.detail)
    }

    @Test fun changedArtworkShowsBothExactMediaIdentities() {
        val before = CloudCatalog(playlists = listOf(playlist))
        val after = CloudCatalog(playlists = listOf(playlist.copy(artwork = otherKey)))
        val detail = groups(before, after).single().items.single { it.kind == "Artwork" }.detail
        assertTrue(detail.contains("Service 0"))
        assertTrue(detail.contains("Service 1"))
        assertTrue(detail.contains(key.url))
        val automatic = groups(after, CloudCatalog(playlists = listOf(playlist.copy(artwork = null))))
        assertTrue(automatic.single().items.single { it.kind == "Artwork" }.detail.endsWith("Automatic"))
    }

    @Test fun unavailableOnlyReorderingIsVisibleAndSearchableByExactServiceIdentity() {
        val before = CloudCatalog(library = listOf(key, otherKey), unavailable = listOf(key, otherKey))
        val after = before.copy(unavailable = listOf(otherKey, key))
        assertNotEquals(CloudCatalogCodec.fingerprint(before), CloudCatalogCodec.fingerprint(after))
        val result = groups(before, after)
        assertEquals("unavailable-order", result.single().id)
        assertEquals(2, result.single().items.size)
        assertEquals(1, CloudChangeExplorer.search(result, "Service 1").single().items.size)
        assertTrue(groups(before, before).isEmpty())
    }

    @Test fun unavailableMembershipAndReorderingBothAppear() {
        val third = CloudKey(0, "https://example.test/third")
        val before = CloudCatalog(library = listOf(key, otherKey, third), unavailable = listOf(key, otherKey))
        val after = before.copy(unavailable = listOf(otherKey, third))
        val result = groups(before, after)
        assertEquals(setOf("unavailable", "unavailable-order"), result.map { it.id }.toSet())
        assertTrue(CloudChangeExplorer.search(result, "third").isNotEmpty())
    }
}
