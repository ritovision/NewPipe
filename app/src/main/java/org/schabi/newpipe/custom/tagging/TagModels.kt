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

object TagNames {
    fun clean(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Enter a name" }
        require(it.length <= 80) { "Use a name of 80 characters or fewer" }
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
    fun matches(title: String, uploader: String, tags: Set<Long>): Boolean {
        val text = query.trim()
        if (text.isNotEmpty() && !title.contains(text, ignoreCase = true) && !uploader.contains(text, ignoreCase = true)) return false
        if (untagged && tags.isNotEmpty()) return false
        if (excluded.any { it in tags }) return false
        return included.isEmpty() || if (matchAll) included.all { it in tags } else included.any { it in tags }
    }
}
