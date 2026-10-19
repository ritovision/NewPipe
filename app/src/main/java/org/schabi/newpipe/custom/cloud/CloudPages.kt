/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

/** Native implementation of the versioned packed-page protocol; no network or native database writes. */
internal object CloudPages {
    const val MAX_PAGES = 2048
    const val MAX_BYTES = 32 * 1024 * 1024
    const val PAGE_BYTES = 32 * 1024
    const val MAX_ENTRIES = 1_100_000
    data class Page(val id: String, val body: String)
    data class Change(val id: String, val body: String?)
    data class Entry(val name: String, val value: Any?)
    data class Decoded(val catalog: CloudCatalog, val hints: List<CloudMediaHint>, val entries: List<Entry>)
    data class Upload(val header: Map<String, Any?>, val chunks: List<List<Map<String, Any?>>>) {
        val reference: Map<String, Any?> get() = mapOf("sequence" to header.getValue("sequence"), "digest" to header.getValue("digest"))
    }
    private val hash = Regex("[0-9a-f]{64}")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val types = setOf("NONE", "VIDEO_STREAM", "AUDIO_STREAM", "LIVE_STREAM", "AUDIO_LIVE_STREAM", "POST_LIVE_STREAM", "POST_LIVE_AUDIO_STREAM")
    private fun tuple(value: Any?, size: Int): List<*> = (value as? List<*> ?: error("INVALID_PAGE_TUPLE")).also { require(it.size == size) { "INVALID_PAGE_TUPLE" } }
    private fun text(raw: Any?): String = (raw as? String ?: error("INVALID_PAGE_TEXT")).also {
        require(it.length <= CloudCatalogCodec.MAX_TEXT_UNITS) { "PAGE_TEXT_LIMIT" }
        CloudWireJson.encode(it)
    }
    private fun key(raw: Any?): CloudKey {
        val row = tuple(raw, 2)
        return CloudKey(CloudWireJson.integer(row[0]).toInt(), text(row[1])).also { require(it.url.isNotBlank()) { "INVALID_PAGE_URL" } }
    }
    private fun pair(key: CloudKey): List<Any> = listOf(key.service, key.url)
    fun keyId(key: CloudKey): String = CloudWireJson.encode(pair(key))
    fun hintMap(h: CloudMediaHint): Map<String, Any?> = linkedMapOf(
        "key" to linkedMapOf("service" to h.key.service, "url" to h.key.url),
        "title" to h.title,
        "uploader" to h.uploader,
        "duration" to h.duration.toString(),
        "streamType" to h.streamType,
        "thumbnail" to h.thumbnail
    )
    private fun hint(raw: Any?): CloudMediaHint {
        val o = CloudWireJson.fields(raw, "key", "title", "uploader", "duration", "streamType", "thumbnail")
        val k = CloudWireJson.fields(o["key"], "service", "url")
        val durationText = o["duration"] as? String ?: error("INVALID_HINT_DURATION")
        val duration = if (durationText == "-1") -1L else CloudCounter.parse(durationText)
        val type = text(o["streamType"])
        require(type in types) { "INVALID_HINT_TYPE" }
        return CloudMediaHint(
            key(listOf(k["service"], k["url"])),
            text(o["title"]),
            text(o["uploader"]),
            o["thumbnail"]?.let(::text),
            duration,
            type
        )
    }
    fun parsePage(page: Page): List<Entry> {
        require(hash.matches(page.id) && CloudWireJson.bytes(page.body) <= PAGE_BYTES) { "INVALID_PAGE" }
        val values = CloudWireJson.array(page.body)
        require(values.size in 1..128 && CloudWireJson.encode(values) == page.body) { "NONCANONICAL_PAGE" }
        var previous: String? = null
        return values.map { raw ->
            val row = tuple(raw, 2)
            val name = row[0] as? String ?: error("INVALID_ENTRY_KEY")
            require(name.isNotEmpty() && name.length <= 16600 && (previous == null || previous!! < name)) { "PAGE_ORDER_OR_DUPLICATE" }
            previous = name
            Entry(name, row[1])
        }
    }
    fun decode(pages: List<Page>): Decoded {
        require(pages.size <= MAX_PAGES && pages.map { it.id }.toSet().size == pages.size) { "CATALOG_PAGE_LIMIT_OR_DUPLICATE" }
        var bytes = 0L
        val entries = ArrayList<Entry>()
        for (page in pages) {
            bytes += CloudWireJson.bytes(page.body)
            require(bytes <= MAX_BYTES) { "CATALOG_BYTE_LIMIT" }
            val rows = parsePage(page)
            require(page.id == CloudWireJson.sha256(rows.first().name)) { "PAGE_ID_MISMATCH" }
            require(entries.size + rows.size <= MAX_ENTRIES) { "CATALOG_ENTRY_LIMIT" }
            entries.addAll(rows)
        }
        entries.sortBy { it.name }
        for (i in 1 until entries.size) require(entries[i - 1].name != entries[i].name) { "DUPLICATE_CATALOG_ENTRY" }
        val categories = ArrayList<CloudCategory>()
        val tags = ArrayList<CloudTag>()
        val playlists = linkedMapOf<String, Pair<CloudPlaylist, String>>()
        val sequences = HashMap<String, MutableList<CloudKey>>()
        val assignments = linkedMapOf<CloudKey, MutableList<String>>()
        val hints = ArrayList<CloudMediaHint>()
        var metadata = false
        for ((name, value) in entries) {
            when {
                name == "v" -> {
                    require(!metadata && CloudWireJson.encode(value) == "[1,1]") { "INVALID_CATALOG_VERSION" }
                    metadata = true
                }

                name.startsWith("c/") -> {
                    val v = tuple(value, 2)
                    categories.add(CloudCategory(name.drop(2), text(v[0]), text(v[1])))
                }

                name.startsWith("t/") -> {
                    val v = tuple(value, 3)
                    tags.add(CloudTag(name.drop(2), text(v[0]), text(v[1]), v[2]?.let(::text)))
                }

                name.startsWith("p/") -> {
                    val v = tuple(value, 3)
                    val rank = text(v[2])
                    CloudWireSequence.rank(rank)
                    val id = name.drop(2)
                    playlists[id] = CloudPlaylist(id, v[0]?.let(::text), v[1]?.let(::key), emptyList()) to rank
                }

                name.startsWith("s/") -> {
                    val parts = name.split('/')
                    require(parts.size == 3) { "INVALID_SEQUENCE_KEY" }
                    val owner = parts[1]
                    require(owner in setOf("library", "unavailable") || uuid.matches(owner)) { "INVALID_SEQUENCE_OWNER" }
                    CloudWireSequence.rank(parts[2])
                    sequences.getOrPut(owner) { ArrayList() }.add(key(value))
                }

                name.startsWith("a/") -> {
                    val v = tuple(value, 3)
                    val k = key(v.take(2))
                    val tag = text(v[2])
                    require(uuid.matches(tag) && name == "a/${keyId(k)}/$tag") { "INVALID_ASSIGNMENT_KEY" }
                    assignments.getOrPut(k) { ArrayList() }.add(tag)
                }

                name.startsWith("h/") -> {
                    val h = hint(value)
                    require(name == "h/${keyId(h.key)}") { "INVALID_HINT_KEY" }
                    hints.add(h)
                }

                else -> error("UNKNOWN_CATALOG_ENTRY")
            }
        }
        require(metadata) { "MISSING_CATALOG_VERSION" }
        require(sequences.keys.all { it in setOf("library", "unavailable") || it in playlists }) { "ORPHAN_SEQUENCE" }
        require(playlists.values.map { it.second }.toSet().size == playlists.size) { "DUPLICATE_PLAYLIST_RANK" }
        val catalog = CloudCatalogCodec.snapshot(
            CloudCatalog(
                library = sequences["library"].orEmpty(),
                unavailable = sequences["unavailable"].orEmpty(),
                playlists = playlists.values.sortedBy { it.second }.map { (p, _) -> p.copy(items = sequences[p.id].orEmpty()) },
                categories = categories,
                tags = tags,
                assignments = assignments.map { CloudAssignment(it.key, it.value) }
            )
        )
        val hintable = catalog.library.toHashSet()
        catalog.playlists.mapNotNullTo(hintable) { it.artwork }
        require(hints.all { it.key in hintable }) { "ORPHAN_MEDIA_HINT" }
        return Decoded(catalog, hints, entries)
    }
    fun encode(input: CloudCatalog, previous: List<Page> = emptyList(), media: Collection<CloudMediaHint> = emptyList()): List<Page> {
        val c = CloudCatalogCodec.snapshot(input)
        val old = if (previous.isEmpty()) emptyList() else decode(previous).entries
        val bySequence = HashMap<String, MutableList<CloudWireSequence.Row>>()
        val ranks = linkedMapOf<String, String>()
        for ((name, value) in old) {
            if (name.startsWith("s/")) {
                val p = name.split('/')
                bySequence.getOrPut(p[1]) { ArrayList() }.add(CloudWireSequence.Row(p[2], key(value)))
            } else if (name.startsWith("p/")) {
                ranks[name.drop(2)] = tuple(value, 3)[2] as String
            }
        }
        val entries = arrayListOf(Entry("v", listOf(1, 1)))
        fun sequence(owner: String, keys: List<CloudKey>) {
            CloudWireSequence.plan(bySequence[owner].orEmpty(), keys).forEach { entries.add(Entry("s/$owner/${it.rank}", pair(it.key))) }
        }
        sequence("library", c.library)
        sequence("unavailable", c.unavailable)
        val oldOrder = ranks.entries.sortedBy { it.value }.map { CloudWireSequence.Row(it.value, CloudKey(0, it.key)) }
        val order = CloudWireSequence.plan(oldOrder, c.playlists.map { CloudKey(0, it.id) })
        c.playlists.forEachIndexed { index, p ->
            entries.add(Entry("p/${p.id}", listOf(p.name, p.artwork?.let(::pair), order[index].rank)))
            sequence(p.id, p.items)
        }
        c.categories.forEach { entries.add(Entry("c/${it.id}", listOf(it.name, it.nameKey))) }
        c.tags.forEach { entries.add(Entry("t/${it.id}", listOf(it.name, it.nameKey, it.category))) }
        c.assignments.forEach { a -> a.tags.forEach { entries.add(Entry("a/${keyId(a.key)}/$it", pair(a.key) + it)) } }
        media.forEach { entries.add(Entry("h/${keyId(it.key)}", hintMap(it))) }
        val result = pack(entries, previous)
        decode(result)
        return result
    }
    private fun pack(values: List<Entry>, previous: List<Page>): List<Page> {
        val entries = values.sortedBy { it.name }
        require(entries.size <= MAX_ENTRIES) { "CATALOG_ENTRY_LIMIT" }
        for (i in 1 until entries.size) require(entries[i - 1].name != entries[i].name) { "DUPLICATE_CATALOG_ENTRY" }
        val parsed = previous.map { parsePage(it) to CloudWireJson.bytes(it.body) }.sortedBy { it.first.first().name }
        val cuts = parsed.filterIndexed { i, (rows, size) -> i == 0 || rows.size >= 64 || size >= PAGE_BYTES / 2 }.map { it.first.first().name }
        val pages = ArrayList<Page>()
        var segment = ArrayList<List<Any?>>()
        var size = 2
        var cut = 0
        var total = 0L
        fun flush() {
            if (segment.isEmpty()) return
            val body = CloudWireJson.encode(segment)
            total += CloudWireJson.bytes(body)
            require(total <= MAX_BYTES && pages.size < MAX_PAGES) { "CATALOG_STORAGE_LIMIT" }
            pages.add(Page(CloudWireJson.sha256(segment.first()[0] as String), body))
            segment = ArrayList()
            size = 2
        }
        for (entry in entries) {
            while (cut < cuts.size && entry.name >= cuts[cut]) {
                flush()
                cut++
            }
            val row = listOf(entry.name, entry.value)
            val bytes = CloudWireJson.bytes(CloudWireJson.encode(row))
            require(bytes + 2 <= PAGE_BYTES) { "ENTRY_BYTE_LIMIT" }
            if (segment.size >= 128 || size + bytes + (if (segment.isNotEmpty()) 1 else 0) > PAGE_BYTES) flush()
            size += bytes + if (segment.isNotEmpty()) 1 else 0
            segment.add(row)
        }
        flush()
        return pages.sortedBy { it.id }
    }
    fun changes(before: List<Page>, after: List<Page>): List<Change> {
        val a = before.associate { it.id to it.body }
        val b = after.associate { it.id to it.body }
        return (a.keys + b.keys).sorted().filter { a[it] != b[it] }.map { Change(it, b[it]) }
    }
    fun manifest(pages: List<Page>): String = CloudWireJson.sha256("rv-cloud-pages/1\n" + pages.sortedBy { it.id }.joinToString("\n") { "${it.id}:${CloudWireJson.sha256(it.body)}" })
    fun upload(mode: String, sequence: String, before: CloudHead, target: String, changes: List<Change>): Upload {
        require(mode in setOf("initialize", "sync") && CloudCounter.parse(sequence) > 0 && hash.matches(target)) { "INVALID_UPLOAD" }
        require(changes.isNotEmpty() && changes.size <= 4096 && changes.map { it.id }.sorted().distinct() == changes.map { it.id }) { "INVALID_CHANGES" }
        val chunks = ArrayList<List<Map<String, Any?>>>()
        var current = ArrayList<Map<String, Any?>>()
        fun encoded(values: List<Map<String, Any?>>) = CloudWireJson.bytes(CloudWireJson.encode(mapOf("sequence" to sequence, "index" to chunks.size, "changes" to values)))
        changes.forEach { change ->
            require(hash.matches(change.id))
            change.body?.let { parsePage(Page(change.id, it)) }
            val p = linkedMapOf<String, Any?>("id" to change.id, "body" to change.body)
            require(encoded(listOf(p)) <= 65536) { "CHUNK_BYTE_LIMIT" }
            if (encoded(current + p) > 65536) {
                chunks.add(current)
                current = ArrayList()
            }
            current.add(p)
        }
        if (current.isNotEmpty()) chunks.add(current)
        val bytes = changes.sumOf { it.body?.let(CloudWireJson::bytes)?.toLong() ?: 0L }
        require(bytes <= MAX_BYTES) { "UPLOAD_BYTE_LIMIT" }
        val digest = CloudWireJson.sha256(
            CloudWireJson.encode(
                listOf(
                    "rv-upload/1", mode, sequence,
                    listOf(before.serviceGeneration, before.library, before.epoch, before.revision, before.headCounter, before.fingerprint),
                    target, chunks.size, changes.size, bytes, changes.map { listOf(it.id, it.body?.let(CloudWireJson::sha256)) }
                )
            )
        )
        return Upload(
            linkedMapOf(
                "mode" to mode,
                "sequence" to sequence,
                "before" to CloudApplyPlanJson.head(before),
                "targetFingerprint" to target,
                "chunkCount" to chunks.size,
                "changeCount" to changes.size,
                "payloadBytes" to bytes,
                "digest" to digest
            ),
            chunks
        )
    }
}
