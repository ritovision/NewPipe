/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.schabi.newpipe.custom.tagging.TagNames

/** Portable semantic content only. Not a Room ID, network client or native write path. */
data class CloudKey(val service: Int, val url: String)
data class CloudCategory(val id: String, val name: String, val nameKey: String)
data class CloudTag(val id: String, val name: String, val nameKey: String, val category: String?)
data class CloudPlaylist(val id: String, val name: String?, val artwork: CloudKey?, val items: List<CloudKey>)
data class CloudAssignment(val key: CloudKey, val tags: List<String>)
data class CloudCatalog(
    val library: List<CloudKey> = emptyList(),
    val unavailable: List<CloudKey> = emptyList(),
    val playlists: List<CloudPlaylist> = emptyList(),
    val categories: List<CloudCategory> = emptyList(),
    val tags: List<CloudTag> = emptyList(),
    val assignments: List<CloudAssignment> = emptyList(),
    val version: Int = 1,
    val serviceMap: Int = 1
)

data class CloudDefinitionChanges(val added: List<String>, val removed: List<String>, val changed: List<String>)
data class CloudPlaylistChange(
    val id: String,
    val before: CloudPlaylist?,
    val after: CloudPlaylist?,
    val added: List<CloudKey>,
    val removed: List<CloudKey>,
    val sequenceChanged: Boolean
)
data class CloudAssignmentChange(val key: CloudKey, val added: List<String>, val removed: List<String>)
data class CloudDelta(
    val added: List<CloudKey>,
    val removed: List<CloudKey>,
    val librarySequenceChanged: Boolean,
    val unavailableAdded: List<CloudKey>,
    val unavailableRemoved: List<CloudKey>,
    val unavailableSequenceChanged: Boolean,
    val playlistOrderChanged: Boolean,
    val playlists: List<CloudPlaylistChange>,
    val categories: CloudDefinitionChanges,
    val tags: CloudDefinitionChanges,
    val assignments: List<CloudAssignmentChange>
)

/** Codec limits are format ceilings, not permission to upload a scale fixture to a Free D1 account. */
object CloudCatalogCodec {
    const val MAX_STREAMS = 50_000
    const val MAX_MEMBERSHIPS = 500_000
    const val MAX_PLAYLISTS = 2_000
    const val MAX_TAGS = 20_000
    const val MAX_CATEGORIES = 2_000
    const val MAX_TEXT_UNITS = 8_192
    const val MAX_BYTES = 64 * 1024 * 1024
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val keyOrder = compareBy<CloudKey> { it.service }.thenBy { it.url }

    private fun valid(condition: Boolean, label: String) {
        require(condition) { "INVALID_CATALOG:$label" }
    }
    private fun text(raw: Any?): String {
        val s = raw as? String ?: throw IllegalArgumentException("INVALID_CATALOG:text")
        valid(s.length <= MAX_TEXT_UNITS, "text limit")
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c.isHighSurrogate()) {
                valid(i < s.length && s[i].isLowSurrogate(), "unpaired surrogate")
                i++
            } else {
                valid(!c.isLowSurrogate(), "unpaired surrogate")
            }
        }
        return s
    }
    private fun id(raw: Any?): String = text(raw).also { valid(uuid.matches(it), "id") }
    private fun key(k: CloudKey): CloudKey = k.also {
        valid(it.service >= 0 && text(it.url).isNotBlank(), "saved key")
    }
    private fun objectFields(raw: Any?, vararg fields: String): JsonObject {
        val o = raw as? JsonObject ?: throw IllegalArgumentException("INVALID_CATALOG:object")
        valid(o.keys == fields.toSet(), "fields")
        return o
    }
    private fun array(raw: Any?, max: Int): JsonArray {
        val a = raw as? JsonArray ?: throw IllegalArgumentException("INVALID_CATALOG:array")
        valid(a.size <= max, "collection limit")
        return a
    }
    private fun int(raw: Any?): Int {
        valid(raw is Number, "integer")
        val n = raw as Number
        val value = n.toLong()
        valid(value in 0..Int.MAX_VALUE.toLong() && value.toDouble() == n.toDouble(), "integer range")
        return value.toInt()
    }
    private fun readKey(raw: Any?): CloudKey {
        val o = objectFields(raw, "service", "url")
        return key(CloudKey(int(o["service"]), text(o["url"])))
    }
    private fun readKeys(raw: Any?, max: Int) = array(raw, max).map(::readKey)

    /** The caller must bound transport bytes before parsing; this also bounds direct fixture inputs. */
    fun read(json: String): CloudCatalog {
        valid(json.length <= MAX_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES, "input byte limit")
        return readObject(JsonParser.`object`().from(json))
    }

    /** Decode an already bounded parsed object without serializing/parsing the catalog again. */
    internal fun readObject(raw: JsonObject): CloudCatalog {
        val o = objectFields(raw, "version", "serviceMap", "library", "unavailable", "playlists", "categories", "tags", "assignments")
        val c = CloudCatalog(
            version = int(o["version"]),
            serviceMap = int(o["serviceMap"]),
            library = readKeys(o["library"], MAX_STREAMS),
            unavailable = readKeys(o["unavailable"], MAX_STREAMS),
            playlists = array(o["playlists"], MAX_PLAYLISTS).map { raw ->
                val p = objectFields(raw, "id", "name", "artwork", "items")
                CloudPlaylist(id(p["id"]), p["name"]?.let(::text), p["artwork"]?.let(::readKey), readKeys(p["items"], MAX_MEMBERSHIPS))
            },
            categories = array(o["categories"], MAX_CATEGORIES).map { raw ->
                val d = objectFields(raw, "id", "name", "nameKey")
                CloudCategory(id(d["id"]), text(d["name"]), text(d["nameKey"]))
            },
            tags = array(o["tags"], MAX_TAGS).map { raw ->
                val d = objectFields(raw, "id", "name", "nameKey", "category")
                CloudTag(id(d["id"]), text(d["name"]), text(d["nameKey"]), d["category"]?.let(::id))
            },
            assignments = array(o["assignments"], MAX_STREAMS).map { raw ->
                val a = objectFields(raw, "key", "tags")
                CloudAssignment(readKey(a["key"]), array(a["tags"], MAX_TAGS).map(::id))
            }
        )
        return snapshot(c)
    }

    /** Deep-copy collections before validation/encoding; no caller-owned mutable list is retained. */
    fun snapshot(source: CloudCatalog): CloudCatalog {
        valid(source.version == 1 && source.serviceMap == 1, "unsupported codec/service map")
        valid(source.library.size <= MAX_STREAMS && source.unavailable.size <= MAX_STREAMS, "stream limit")
        valid(source.playlists.size <= MAX_PLAYLISTS && source.categories.size <= MAX_CATEGORIES && source.tags.size <= MAX_TAGS, "definition limit")
        valid(source.assignments.size <= MAX_STREAMS, "assignment key limit")
        val c = source.copy(
            library = source.library.toList(),
            unavailable = source.unavailable.toList(),
            playlists = source.playlists.map { it.copy(items = it.items.toList()) },
            categories = source.categories.toList(),
            tags = source.tags.toList(),
            assignments = source.assignments.map { it.copy(tags = it.tags.sorted()) }
        )
        val library = c.library.onEach(::key).toSet()
        valid(library.size == c.library.size, "duplicate Library key")
        valid(c.unavailable.toSet().size == c.unavailable.size && c.unavailable.all { key(it) in library }, "Unavailable subset/duplicates")
        val membershipCount = c.library.size.toLong() + c.unavailable.size + c.playlists.sumOf { it.items.size.toLong() }
        valid(membershipCount <= MAX_MEMBERSHIPS, "membership limit")
        valid(c.playlists.map { id(it.id) }.toSet().size == c.playlists.size, "duplicate playlist id")
        c.playlists.forEach { p ->
            p.name?.let { valid(TagNames.storedKey(text(it)) != "unavailable", "reserved Unavailable name") }
            p.artwork?.let(::key) // Artwork is a reference; it does not imply Library membership.
            valid(p.items.all { key(it) in library }, "playlist item outside Library")
        }
        val categoryIds = c.categories.map { id(it.id) }.toSet()
        val tagIds = c.tags.map { id(it.id) }.toSet()
        valid(categoryIds.size == c.categories.size && tagIds.size == c.tags.size, "duplicate definition id")
        c.categories.forEach { valid(text(it.nameKey) == TagNames.storedKey(text(it.name)), "stored category key") }
        c.tags.forEach {
            valid(text(it.nameKey) == TagNames.storedKey(text(it.name)), "stored tag key")
            valid(it.category == null || id(it.category) in categoryIds, "unknown category")
        }
        valid(c.categories.map { it.nameKey }.toSet().size == c.categories.size && c.tags.map { it.nameKey }.toSet().size == c.tags.size, "duplicate normalized name")
        valid(c.assignments.map { it.key }.toSet().size == c.assignments.size, "duplicate assignment key")
        valid(c.assignments.sumOf { it.tags.size.toLong() } <= MAX_MEMBERSHIPS, "assignment limit")
        c.assignments.forEach {
            valid(key(it.key) in library, "orphan assignment is not portable")
            valid(it.tags.size == it.tags.toSet().size && it.tags.all { t -> id(t) in tagIds }, "unknown/duplicate assigned tag")
        }
        return c
    }

    /** Canonical UTF-8 netstring token stream. JSON field order/escaping cannot change its digest. */
    fun canonicalBytes(source: CloudCatalog): ByteArray {
        val c = snapshot(source)
        val out = ByteArrayOutputStream()
        out.write("rv-cloud/1\n".toByteArray(Charsets.UTF_8))
        fun token(value: Any) {
            val b = value.toString().toByteArray(Charsets.UTF_8)
            val prefix = "${b.size}:".toByteArray(Charsets.US_ASCII)
            valid(out.size().toLong() + prefix.size + b.size + 1 <= MAX_BYTES, "encoded byte limit")
            out.write(prefix)
            out.write(b)
            out.write(','.code)
        }
        fun writeKey(k: CloudKey) {
            token(k.service)
            token(k.url)
        }
        fun keys(items: List<CloudKey>) {
            token(items.size)
            items.forEach(::writeKey)
        }
        fun nullable(value: String?) {
            token(if (value == null) 0 else 1)
            value?.let(::token)
        }
        token(c.version)
        token(c.serviceMap)
        token("library")
        keys(c.library)
        token("unavailable")
        keys(c.unavailable)
        token("playlists")
        token(c.playlists.size)
        c.playlists.forEach { p ->
            token(p.id)
            nullable(p.name)
            token(if (p.artwork == null) 0 else 1)
            p.artwork?.let(::writeKey)
            keys(p.items)
        }
        token("categories")
        token(c.categories.size)
        c.categories.sortedBy { it.id }.forEach {
            token(it.id)
            token(it.name)
            token(it.nameKey)
        }
        token("tags")
        token(c.tags.size)
        c.tags.sortedBy { it.id }.forEach {
            token(it.id)
            token(it.name)
            token(it.nameKey)
            nullable(it.category)
        }
        val assignments = c.assignments.filter { it.tags.isNotEmpty() }.sortedWith(compareBy(keyOrder) { it.key })
        token("assignments")
        token(assignments.size)
        assignments.forEach { a ->
            writeKey(a.key)
            token(a.tags.size)
            a.tags.forEach(::token)
        }
        return out.toByteArray()
    }
    fun fingerprint(c: CloudCatalog): String = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(c)).joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }

    private fun excess(a: List<CloudKey>, b: List<CloudKey>): List<CloudKey> {
        val counts = b.groupingBy { it }.eachCount().toMutableMap()
        return a.filter {
            val count = counts[it] ?: 0
            if (count == 0) {
                true
            } else {
                counts[it] = count - 1
                false
            }
        }
    }
    private fun <T> definitions(a: List<T>, b: List<T>, id: (T) -> String): CloudDefinitionChanges {
        val old = a.associateBy(id)
        val next = b.associateBy(id)
        return CloudDefinitionChanges(
            (next.keys - old.keys).sorted(),
            (old.keys - next.keys).sorted(),
            next.keys.filter { it in old && old[it] != next[it] }.sorted()
        )
    }

    /** Full exact details are retained; there is no first-page-only count or implicit merge. */
    fun diff(before: CloudCatalog, after: CloudCatalog): CloudDelta {
        val a = snapshot(before)
        val b = snapshot(after)
        val old = a.playlists.associateBy { it.id }
        val next = b.playlists.associateBy { it.id }
        val playlists = (old.keys + next.keys).sorted().mapNotNull { id ->
            val was = old[id]
            val now = next[id]
            if (was == now) {
                null
            } else {
                CloudPlaylistChange(
                    id,
                    was,
                    now,
                    excess(now?.items.orEmpty(), was?.items.orEmpty()),
                    excess(was?.items.orEmpty(), now?.items.orEmpty()),
                    was?.items.orEmpty() != now?.items.orEmpty()
                )
            }
        }
        val oldTags = a.assignments.filter { it.tags.isNotEmpty() }.associateBy { it.key }
        val newTags = b.assignments.filter { it.tags.isNotEmpty() }.associateBy { it.key }
        val assignments = (oldTags.keys + newTags.keys).sortedWith(keyOrder).mapNotNull { key ->
            val was = oldTags[key]?.tags.orEmpty()
            val now = newTags[key]?.tags.orEmpty()
            if (was == now) null else CloudAssignmentChange(key, now - was.toSet(), was - now.toSet())
        }
        return CloudDelta(
            excess(b.library, a.library), excess(a.library, b.library), a.library != b.library,
            excess(b.unavailable, a.unavailable), excess(a.unavailable, b.unavailable), a.unavailable != b.unavailable,
            a.playlists.map { it.id } != b.playlists.map { it.id }, playlists,
            definitions(a.categories, b.categories) { it.id }, definitions(a.tags, b.tags) { it.id }, assignments
        )
    }
}
