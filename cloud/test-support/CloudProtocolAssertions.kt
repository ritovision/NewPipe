/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.schabi.newpipe.custom.tagging.TagNames

/** Exactly the same independent fixtures run on JVM and real Android SQLite-capable emulator. */
class CloudProtocolAssertions(json: String) {
    private val root = JsonParser.`object`().from(json)
    private fun records(field: String) = root.getArray(field).map { it as JsonObject }
    private val catalogs = records("catalogs").associateBy { it.getString("name") }
    private fun decode(o: JsonObject) = CloudCatalogCodec.read(JsonWriter.string().`object`(o).done())
    private fun catalog(name: String) = decode(checkNotNull(catalogs[name]).getObject("catalog"))
    private fun head(o: JsonObject?): CloudHead? = o?.let {
        CloudHead(it.getString("serviceGeneration"), it.getString("library"), it.getString("epoch"), it.getString("revision"), it.getString("headCounter"), it.getString("fingerprint"))
    }
    fun golden() {
        for (record in catalogs.values) {
            val c = decode(record.getObject("catalog"))
            assertEquals(record.getString("name"), record.getString("sha256"), CloudCatalogCodec.fingerprint(c))
            assertEquals(record.getString("name"), record.getInt("bytes"), CloudCatalogCodec.canonicalBytes(c).size)
        }
        assertEquals(CloudCatalogCodec.fingerprint(catalog("base")), CloudCatalogCodec.fingerprint(catalog("semantic-order")))
        assertNotEquals(CloudCatalogCodec.fingerprint(catalog("base")), CloudCatalogCodec.fingerprint(catalog("reorder")))
    }
    fun rejectsInvalidCatalogs() {
        for (record in records("invalid")) {
            assertThrows(record.getString("name"), IllegalArgumentException::class.java) { decode(record.getObject("catalog")) }
        }
    }
    fun namesAndCounters() {
        root.getArray("normalization").forEach { row ->
            val pair = row as com.grack.nanojson.JsonArray
            assertEquals(pair.getString(0), pair.getString(1), TagNames.storedKey(pair.getString(0)))
        }
        root.getArray("validCounters").forEach { assertEquals(it, CloudCounter.parse(it as String).toString()) }
        root.getArray("invalidCounters").forEach {
            assertThrows(it as String, IllegalArgumentException::class.java) { CloudCounter.parse(it) }
        }
    }
    fun policy() {
        records("policies").forEach { row ->
            val s = row.getObject("input")
            val input = CloudSyncInput(
                s.getBoolean("enabled"),
                s.getBoolean("identityReady"),
                s.getBoolean("unresolvedOperation"),
                s.getString("localFingerprint"),
                head(s["baseline"] as? JsonObject),
                head(s["remote"] as? JsonObject),
                head(s["highestObserved"] as? JsonObject)
            )
            assertEquals(row.getString("name"), row.getString("expected"), CloudSyncPolicy.decide(input).name)
        }
    }
    fun exactDifferences() {
        records("diffs").forEach { r ->
            val delta = CloudCatalogCodec.diff(catalog(r.getString("before")), catalog(r.getString("after")))
            val label = r.getString("after")
            assertEquals(label, r.getInt("added"), delta.added.size)
            assertEquals(label, r.getInt("removed"), delta.removed.size)
            assertEquals(label, r.getInt("playlists"), delta.playlists.size)
            assertEquals(label, r.getInt("occurrencesAdded"), delta.playlists.sumOf { it.added.size })
            assertEquals(label, r.getInt("occurrencesRemoved"), delta.playlists.sumOf { it.removed.size })
            assertEquals(label, r.getInt("tagsAdded"), delta.tags.added.size)
            assertEquals(label, r.getInt("tagsRemoved"), delta.tags.removed.size)
            assertEquals(label, r.getInt("categoriesRemoved"), delta.categories.removed.size)
            assertEquals(label, r.getInt("assignments"), delta.assignments.size)
        }
        val base = catalog("base")
        val removal = CloudCatalogCodec.diff(base, catalog("global-remove"))
        assertEquals(listOf(base.library.first()), removal.removed)
        assertEquals(listOf(base.library.first(), base.library.first()), removal.playlists.single().removed)
        assertTrue(CloudCatalogCodec.diff(base, catalog("reorder")).librarySequenceChanged)
        assertEquals(base.playlists.single().id, CloudCatalogCodec.diff(base, catalog("rename")).playlists.single().id)
    }
    fun previewCannotWiden() {
        val h = head(records("policies").first { it.getString("name") == "unchanged" }.getObject("input").getObject("remote"))!!
        val p = CloudPreview("USE_CLOUD", "1", "incarnation-a", h.fingerprint, h, h.fingerprint, "1000")
        assertTrue(CloudSyncPolicy.previewStillMatches(p, p.copy(), "999"))
        assertFalse(CloudSyncPolicy.previewStillMatches(p, p, "1000"))
        val changes = listOf(
            p.copy(action = "REPLACE_CLOUD"),
            p.copy(connectionGeneration = "2"),
            p.copy(incarnation = "incarnation-b"),
            p.copy(localFingerprint = "a".repeat(64)),
            p.copy(remote = h.copy(revision = "11", headCounter = "11")),
            p.copy(candidateDigest = "b".repeat(64)),
            p.copy(expiresAt = "1001")
        )
        changes.forEach { assertFalse(CloudSyncPolicy.previewStillMatches(p, it, "999")) }
    }
    fun callerMutationAndLimits() {
        val items = catalog("base").library.toMutableList()
        val snap = CloudCatalogCodec.snapshot(CloudCatalog(library = items))
        val before = CloudCatalogCodec.fingerprint(snap)
        items.clear()
        assertEquals(before, CloudCatalogCodec.fingerprint(snap))
        assertEquals(2, snap.library.size)
        val tooLong = CloudKey(0, "x".repeat(CloudCatalogCodec.MAX_TEXT_UNITS + 1))
        assertThrows(IllegalArgumentException::class.java) { CloudCatalogCodec.snapshot(CloudCatalog(library = listOf(tooLong))) }
        assertThrows(IllegalArgumentException::class.java) { CloudCatalogCodec.snapshot(CloudCatalog(library = List(CloudCatalogCodec.MAX_STREAMS + 1) { CloudKey(0, "x$it") })) }
    }
    fun largeCatalog() {
        val start = System.nanoTime()
        val keys = (0 until 20_000).map { CloudKey(0, "https://example.test/scale/$it") }
        val playlists = (1..5).map { CloudPlaylist("30000000-0000-0000-0000-${it.toString().padStart(12, '0')}", "Synthetic $it", null, keys) }
        val tag = CloudTag("20000000-0000-0000-0000-000000000001", "Scale", "scale", null)
        val c = CloudCatalog(library = keys, playlists = playlists, tags = listOf(tag), assignments = keys.map { CloudAssignment(it, listOf(tag.id)) })
        val bytes = CloudCatalogCodec.canonicalBytes(c)
        assertEquals(64, CloudCatalogCodec.fingerprint(c).length)
        val changed = c.copy(library = keys.drop(1), playlists = playlists.map { it.copy(items = keys.drop(1)) }, assignments = c.assignments.drop(1))
        val delta = CloudCatalogCodec.diff(c, changed)
        assertEquals(listOf(keys.first()), delta.removed)
        assertEquals(5, delta.playlists.size)
        assertEquals(5, delta.playlists.sumOf { it.removed.size })
        assertEquals(listOf(tag.id), delta.assignments.single().removed)
        println("CLOUD_SCALE streams=20000 ordinary_memberships=100000 assignments=20000 canonical_bytes=${bytes.size} duration_ms=${(System.nanoTime() - start) / 1_000_000}")
    }
}
