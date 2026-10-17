/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import java.util.Locale

/** Exact upstream identity. Do not normalize a URL or substitute a local row UID. */
data class StreamKey(val serviceId: Int, val url: String) {
    init {
        require(serviceId >= 0 && url.isNotBlank()) { "Invalid saved stream identity" }
    }
}

data class TagCategory(val id: Long, val name: String)
data class TagDefinition(val id: Long, val name: String, val categoryId: Long?)
data class TagDefinitions(val categories: List<TagCategory>, val tags: List<TagDefinition>, val version: Long)
data class TagEditSnapshot(val definitions: TagDefinitions, val assigned: Set<Long>, val revision: Long)
data class TagRemovalIntent(val key: StreamKey, val libraryId: Long)
data class TagDeletePlan(val id: Long, val category: Boolean, val tags: Int, val assignments: Int, val version: Long)

/** A stale native form can display current values and retain its draft for an explicit retry. */
class TagDefinitionConflict(val current: TagDefinitions) : IllegalStateException("Tag definitions changed. Review the current values before saving your draft again.")
internal class TagReceiptCapacityException : IllegalStateException("Tag command history is full; retry after old receipts expire")

object TagNames {
    fun clean(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Enter a name" }
        require(it.length <= 80) { "Use a name of 80 characters or fewer" }
        require(it.none { ch -> ch.isISOControl() || Character.getType(ch) == Character.FORMAT.toInt() }) { "Names cannot contain invisible control or direction-changing characters" }
    }

    fun key(value: String): String = clean(value).lowercase(Locale.ROOT)
}

enum class TagSort { LIBRARY, TITLE_ASC, TITLE_DESC }

data class TagFilter(
    val query: String = "",
    val included: Set<Long> = emptySet(),
    val excluded: Set<Long> = emptySet(),
    val matchAll: Boolean = true,
    val untagged: Boolean = false,
    val sort: TagSort = TagSort.LIBRARY
) {
    fun select(items: List<TagRepository.Item>): List<TagRepository.Item> {
        val matched = items.filter { matches(it.stream.title, it.stream.uploader, it.tagIds) }
        return when (sort) {
            TagSort.LIBRARY -> matched
            TagSort.TITLE_ASC -> matched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.stream.title })
            TagSort.TITLE_DESC -> matched.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.stream.title })
        }
    }

    fun matches(title: String, uploader: String, tags: Set<Long>): Boolean {
        val text = query.trim()
        if (text.isNotEmpty() && !title.contains(text, ignoreCase = true) && !uploader.contains(text, ignoreCase = true)) return false
        if (untagged && tags.isNotEmpty()) return false
        if (excluded.any { it in tags }) return false
        return included.isEmpty() || if (matchAll) included.all { it in tags } else included.any { it in tags }
    }
}
