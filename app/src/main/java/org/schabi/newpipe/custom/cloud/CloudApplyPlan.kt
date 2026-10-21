/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.Kind
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.NativeId

internal data class CloudStreamPlan(val key: CloudKey, val nativeId: Long, val create: Boolean, val hint: CloudMediaHint?)
internal data class CloudDisplaySlot(val remote: Boolean, val nativeId: Long)
internal data class CloudApplyPlan(
    val operation: String,
    val context: CloudIdentityStore.Context,
    val beforeFingerprint: String,
    val targetHead: CloudHead,
    val target: CloudCatalog,
    val mappings: Map<NativeId, String>,
    val streams: List<CloudStreamPlan>,
    val deletePlaylists: List<Long>,
    val displayOrder: List<CloudDisplaySlot>,
    val preservedOrphans: List<CloudOrphanAssignment>,
    val affectedTagKeys: List<CloudKey>,
    val tagRevision: Long,
    val historicalRestore: Boolean = false
)

/** Explicit JSON shape; every persisted native 64-bit value is decimal text, never a double. */
internal object CloudApplyPlanJson {
    private fun key(k: CloudKey) = linkedMapOf("service" to k.service, "url" to k.url)
    fun catalog(c: CloudCatalog): Map<String, Any?> = linkedMapOf(
        "version" to c.version,
        "serviceMap" to c.serviceMap,
        "library" to c.library.map(::key),
        "unavailable" to c.unavailable.map(::key),
        "playlists" to c.playlists.map { linkedMapOf("id" to it.id, "name" to it.name, "artwork" to it.artwork?.let(::key), "items" to it.items.map(::key)) },
        "categories" to c.categories.map { linkedMapOf("id" to it.id, "name" to it.name, "nameKey" to it.nameKey) },
        "tags" to c.tags.map { linkedMapOf("id" to it.id, "name" to it.name, "nameKey" to it.nameKey, "category" to it.category) },
        "assignments" to c.assignments.map { linkedMapOf("key" to key(it.key), "tags" to it.tags) }
    )
    fun head(h: CloudHead): Map<String, String> = linkedMapOf(
        "serviceGeneration" to h.serviceGeneration,
        "library" to h.library,
        "epoch" to h.epoch,
        "revision" to h.revision,
        "headCounter" to h.headCounter,
        "fingerprint" to h.fingerprint
    )
    fun headJson(h: CloudHead): String = JsonWriter.string().`object`(head(h)).done()
    fun readHead(h: JsonObject): CloudHead {
        require(h.keys == setOf("serviceGeneration", "library", "epoch", "revision", "headCounter", "fingerprint")) { "INVALID_PLAN_HEAD" }
        return CloudHead(
            h.getString("serviceGeneration"),
            h.getString("library"),
            h.getString("epoch"),
            h.getString("revision"),
            h.getString("headCounter"),
            h.getString("fingerprint")
        )
    }
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun encode(p: CloudApplyPlan): ByteArray {
        val root = linkedMapOf<String, Any?>(
            "version" to if (p.historicalRestore) 2 else 1, "operation" to p.operation,
            "context" to listOf(p.context.scope.service, p.context.scope.library, p.context.incarnation, p.context.libraryId.toString(), p.context.unavailableId.toString()),
            "before" to p.beforeFingerprint, "head" to head(p.targetHead), "catalog" to catalog(p.target),
            "mappings" to p.mappings.entries.sortedWith(compareBy({ it.key.kind.name }, { it.key.id })).map { listOf(it.key.kind.name, it.key.id.toString(), it.value) },
            "streams" to p.streams.map { s ->
                listOf(key(s.key), s.nativeId.toString(), s.create, s.hint?.let { listOf(it.title, it.uploader, it.thumbnail, it.duration.toString(), it.streamType) })
            },
            "deletePlaylists" to p.deletePlaylists.map(Long::toString),
            "displayOrder" to p.displayOrder.map { listOf(it.remote, it.nativeId.toString()) },
            "orphans" to p.preservedOrphans.map { listOf(key(it.key), it.tagIds) },
            "affected" to p.affectedTagKeys.map(::key), "tagRevision" to p.tagRevision.toString()
        )
        if (p.historicalRestore) root["mode"] = "historical-local"
        val output = object : java.io.ByteArrayOutputStream() {
            override fun write(value: Int) {
                require(count < CloudApplyStore.payloadLimit()) { "CLOUD_PLAN_MEMORY_BUDGET" }
                super.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(count.toLong() + length <= CloudApplyStore.payloadLimit()) { "CLOUD_PLAN_MEMORY_BUDGET" }
                super.write(bytes, offset, length)
            }
        }
        JsonWriter.on(output).`object`(root).done()
        return output.toByteArray()
    }
    fun decode(bytes: ByteArray): CloudApplyPlan {
        require(bytes.size in 1..CloudApplyStore.payloadLimit()) { "CLOUD_PLAN_MEMORY_BUDGET" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val r = java.io.InputStreamReader(java.io.ByteArrayInputStream(bytes), decoder).use { JsonParser.`object`().from(it) }
        val version = r.getInt("version")
        val historical = version == 2
        val fields = setOf("version", "operation", "context", "before", "head", "catalog", "mappings", "streams", "deletePlaylists", "displayOrder", "orphans", "affected", "tagRevision")
        require(version in 1..2 && r.keys == fields + if (historical) setOf("mode") else emptySet<String>()) { "INVALID_CLOUD_PLAN" }
        require(!historical || r.getString("mode") == "historical-local") { "INVALID_CLOUD_PLAN" }
        val c = r.getArray("context")
        require(c.size == 5)
        val context = CloudIdentityStore.Context(CloudIdentityStore.Scope(c.getString(0), c.getString(1)), c.getString(2), positive(c.getString(3)), positive(c.getString(4)))
        require(context.libraryId != context.unavailableId)
        val head = readHead(r.getObject("head"))
        val target = CloudCatalogCodec.readObject(r.getObject("catalog"))
        require(CloudCatalogCodec.fingerprint(target) == head.fingerprint && (historical || context.scope.service == head.serviceGeneration) && context.scope.library == head.library) { "INVALID_CLOUD_PLAN_TARGET" }
        val maps = r.getArray("mappings").map { raw ->
            val row = array(raw, 3)
            NativeId(Kind.valueOf(row.getString(0)), positive(row.getString(1))) to row.getString(2).also { require(uuid.matches(it)) { "INVALID_PLAN_PORTABLE_ID" } }
        }
        require(maps.size <= CloudCatalogCodec.MAX_PLAYLISTS + CloudCatalogCodec.MAX_CATEGORIES + CloudCatalogCodec.MAX_TAGS && maps.map { it.first }.toSet().size == maps.size)
        require(maps.map { it.first.kind to it.second }.toSet().size == maps.size)
        val streams = r.getArray("streams").map { raw ->
            val a = array(raw, 4)
            val k = readKey(a[0])
            val hint = a[3]?.let { rawHint ->
                val t = array(rawHint, 5)
                val duration = t.getString(3).toLong()
                require(duration >= -1)
                CloudMediaHint(k, t.getString(0), t.getString(1), t[2] as String?, duration, t.getString(4)).also(CloudApplyCompiler::validateHint)
            }
            require(a[2] is Boolean)
            CloudStreamPlan(k, positive(a.getString(1)), a.getBoolean(2), hint)
        }
        require(streams.size <= CloudCatalogCodec.MAX_STREAMS + CloudCatalogCodec.MAX_PLAYLISTS && streams.map { it.key }.toSet().size == streams.size && streams.map { it.nativeId }.toSet().size == streams.size)
        val delete = r.getArray("deletePlaylists").map { positive(it as String) }
        require(delete.size <= CloudCatalogCodec.MAX_PLAYLISTS && delete.toSet().size == delete.size)
        val display = r.getArray("displayOrder").map { raw ->
            val a = array(raw, 2)
            require(a[0] is Boolean)
            CloudDisplaySlot(a.getBoolean(0), positive(a.getString(1)))
        }
        require(display.size <= 10_000 && display.toSet().size == display.size)
        val orphans = r.getArray("orphans").map { raw ->
            val a = array(raw, 2)
            CloudOrphanAssignment(readKey(a[0]), (a[1] as JsonArray).map { it as String })
        }
        val affected = r.getArray("affected").map(::readKey)
        require(orphans.size <= CloudCatalogCodec.MAX_MEMBERSHIPS && affected.size <= CloudCatalogCodec.MAX_MEMBERSHIPS + CloudCatalogCodec.MAX_STREAMS * 2)
        val before = r.getString("before")
        val operation = r.getString("operation")
        require(Regex("[0-9a-f]{64}").matches(before) && uuid.matches(operation) && uuid.matches(context.incarnation))
        val wanted = target.playlists.map { Kind.PLAYLIST to it.id } + target.categories.map { Kind.CATEGORY to it.id } + target.tags.map { Kind.TAG to it.id }
        require(maps.map { it.first.kind to it.second }.toSet() == wanted.toSet()) { "INVALID_PLAN_IDENTITY_SET" }
        val roleIds = setOf(context.libraryId, context.unavailableId)
        val playlistIds = maps.filter { it.first.kind == Kind.PLAYLIST }.map { it.first.id }.toSet()
        require(playlistIds.intersect(roleIds).isEmpty() && delete.none { it in roleIds || it in playlistIds }) { "INVALID_PLAN_PROTECTED_ID" }
        require(display.filter { !it.remote }.map { it.nativeId }.toSet() == playlistIds + roleIds) { "INVALID_PLAN_DISPLAY" }
        val desiredKeys = target.library.toSet() + target.playlists.mapNotNull { it.artwork }
        require(streams.map { it.key }.toSet() == desiredKeys) { "INVALID_PLAN_STREAMS" }
        require(affected.toSet().size == affected.size && affected.toSet().containsAll(target.library)) { "INVALID_PLAN_REVISIONS" }
        val targetTags = target.tags.mapTo(HashSet()) { it.id }
        val savedKeys = target.library.toHashSet()
        require(orphans.map { it.key }.toSet().size == orphans.size && orphans.none { it.key in savedKeys }) { "INVALID_PLAN_ORPHANS" }
        require(orphans.all { it.tagIds.size == it.tagIds.toSet().size && targetTags.containsAll(it.tagIds) }) { "INVALID_PLAN_ORPHAN_TAGS" }
        CloudApplyBounds.requireAssignmentBudget(
            orphans.sumOf { it.tagIds.size.toLong() },
            target.assignments.sumOf { it.tags.size.toLong() }
        )
        return CloudApplyPlan(operation, context, before, head, target, maps.toMap(), streams, delete, display, orphans, affected, CloudCounter.parse(r.getString("tagRevision")), historical)
    }
    private fun readKey(raw: Any?): CloudKey {
        val o = raw as? JsonObject ?: error("INVALID_PLAN_KEY")
        require(o.keys == setOf("service", "url"))
        val number = (o["service"] as? Number) ?: error("INVALID_PLAN_KEY")
        val service = number.toLong()
        require(service in 0..Int.MAX_VALUE.toLong() && number.toDouble() == service.toDouble())
        val key = CloudKey(service.toInt(), o.getString("url"))
        // Full content keys have already passed CloudCatalogCodec; orphans must be bounded too.
        CloudCatalogCodec.snapshot(CloudCatalog(library = listOf(key)))
        return key
    }
    private fun array(raw: Any?, length: Int): JsonArray = (raw as? JsonArray ?: error("INVALID_PLAN_ARRAY")).also { require(it.size == length) }
    private fun positive(raw: String): Long = CloudCounter.parse(raw).also { require(it > 0) { "INVALID_PLAN_NATIVE_ID" } }
}
