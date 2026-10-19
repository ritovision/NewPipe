/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

/** Complete semantic change list; rendering pages it, never substitutes sample totals for actual changes. */
internal object CloudChangeExplorer {
    data class Item(val kind: String, val title: String, val detail: String)
    data class Group(val id: String, val title: String, val items: List<Item>)
    fun groups(before: CloudCatalog, after: CloudCatalog, hints: Map<CloudKey, CloudMediaHint>, orphans: List<CloudOrphanAssignment> = emptyList()): List<Group> {
        val d = CloudCatalogCodec.diff(before, after)
        val groups = ArrayList<Group>()
        fun title(key: CloudKey): String = hints[key]?.title?.takeIf { it.isNotBlank() } ?: key.url
        fun video(kind: String, key: CloudKey) = Item(kind, title(key), "Service ${key.service} · ${key.url}")
        if (d.added.isNotEmpty()) groups.add(Group("added", "Added to Library", d.added.map { video("Added", it) }))
        if (d.removed.isNotEmpty()) groups.add(Group("removed", "Removed from Library and all local playlists", d.removed.map { video("Removed", it) }))
        if (d.unavailableAdded.isNotEmpty() || d.unavailableRemoved.isNotEmpty()) {
            groups.add(
                Group(
                    "unavailable",
                    "Unavailable marks",
                    d.unavailableAdded.map { video("Marked", it) } + d.unavailableRemoved.map { video("Unmarked", it) }
                )
            )
        }
        fun order(a: List<CloudKey>, b: List<CloudKey>): List<Item> {
            val positions = HashMap<CloudKey, ArrayDeque<Int>>()
            a.forEachIndexed { index, key -> positions.getOrPut(key) { ArrayDeque() }.add(index + 1) }
            return b.mapIndexedNotNull { index, key ->
                val old = positions[key]?.removeFirstOrNull()
                if (old == null || old == index + 1) null else Item("Moved", title(key), "Position $old → ${index + 1} · ${key.url}")
            }
        }
        if (d.librarySequenceChanged) order(before.library, after.library).takeIf { it.isNotEmpty() }?.let { groups.add(Group("library-order", "Library ordering", it)) }
        d.playlists.forEach { p ->
            val was = p.before
            val now = p.after
            val items = ArrayList<Item>()
            if (was == null) items.add(Item("Created", now?.name ?: "Untitled playlist", "New local playlist"))
            if (now == null) items.add(Item("Deleted", was?.name ?: "Untitled playlist", "The playlist is removed; only Library removals delete videos everywhere"))
            if (was != null && now != null && was.name != now.name) items.add(Item("Renamed", now.name ?: "Untitled playlist", "Previously: ${was.name ?: "Untitled playlist"}"))
            items.addAll(p.added.map { video("Added", it) })
            items.addAll(p.removed.map { video("Removed", it) })
            if (was != null && now != null) {
                if (was.artwork != now.artwork) items.add(Item("Artwork", "Permanent thumbnail changed", "${was.artwork?.let(::title) ?: "Automatic"} → ${now.artwork?.let(::title) ?: "Automatic"}"))
                if (p.sequenceChanged) items.addAll(order(was.items, now.items))
            }
            groups.add(Group("playlist-${p.id}", "Playlist: ${now?.name ?: was?.name ?: "Untitled"}", items))
        }
        if (d.playlistOrderChanged) {
            val old = before.playlists.mapIndexed { i, p -> p.id to i + 1 }.toMap()
            val moved = after.playlists.mapIndexedNotNull { i, p -> old[p.id]?.takeIf { it != i + 1 }?.let { Item("Moved", p.name ?: "Untitled playlist", "Playlist position $it → ${i + 1}") } }
            if (moved.isNotEmpty()) groups.add(Group("playlist-order", "Playlist menu order", moved))
        }
        val oldTags = before.tags.associateBy { it.id }
        val newTags = after.tags.associateBy { it.id }
        val oldCategories = before.categories.associateBy { it.id }
        val newCategories = after.categories.associateBy { it.id }
        val definitions = ArrayList<Item>()
        d.tags.added.forEach { definitions.add(Item("Added", newTags.getValue(it).name, "Tag")) }
        d.tags.removed.forEach { definitions.add(Item("Removed", oldTags.getValue(it).name, "Tag")) }
        d.tags.changed.forEach {
            val a = oldTags.getValue(it)
            val b = newTags.getValue(it)
            definitions.add(Item("Changed", b.name, "Tag: ${a.name} → ${b.name}; category: ${a.category?.let { id -> oldCategories[id]?.name } ?: "None"} → ${b.category?.let { id -> newCategories[id]?.name } ?: "None"}"))
        }
        d.categories.added.forEach { definitions.add(Item("Added", newCategories.getValue(it).name, "Category")) }
        d.categories.removed.forEach { definitions.add(Item("Removed", oldCategories.getValue(it).name, "Category")) }
        d.categories.changed.forEach { definitions.add(Item("Renamed", newCategories.getValue(it).name, "Category: ${oldCategories.getValue(it).name}")) }
        if (definitions.isNotEmpty()) groups.add(Group("definitions", "Tags and categories", definitions))
        if (d.assignments.isNotEmpty()) {
            groups.add(
                Group(
                    "assignments",
                    "Video tag assignments",
                    d.assignments.map { a ->
                        Item("Changed", title(a.key), "Added: ${a.added.joinToString { newTags[it]?.name ?: it }.ifEmpty { "None" }}\nRemoved: ${a.removed.joinToString { oldTags[it]?.name ?: it }.ifEmpty { "None" }}\n${a.key.url}")
                    }
                )
            )
        }
        val active = after.library.toHashSet()
        val assignments = after.assignments.associate { it.key to it.tags.toSet() }
        val displaced = orphans.filter { o -> !newTags.keys.containsAll(o.tagIds) || o.key in active && o.tagIds.toSet() != assignments[o.key].orEmpty() }
        if (displaced.isNotEmpty()) {
            groups.add(
                Group(
                    "orphaned-tags",
                    "Recoverable local tags affected",
                    displaced.map { o ->
                        Item("Reconciled", title(o.key), "Local recoverable tags: ${o.tagIds.joinToString { oldTags[it]?.name ?: it }}\nCloud assignments replace this metadata. A local recovery backup is kept.\n${o.key.url}")
                    }
                )
            )
        }
        return groups
    }
    fun search(groups: List<Group>, query: String): List<Group> {
        val q = query.trim()
        if (q.isEmpty()) return groups
        return groups.mapNotNull { group ->
            val items = if (group.title.contains(q, true)) group.items else group.items.filter { it.title.contains(q, true) || it.detail.contains(q, true) || it.kind.contains(q, true) }
            if (items.isEmpty()) null else group.copy(items = items)
        }
    }
}
