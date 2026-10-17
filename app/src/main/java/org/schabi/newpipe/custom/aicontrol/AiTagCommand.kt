/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Optional tag capability family. Main Room schema remains NP9/RV1 (9001). */
@Serializable
internal sealed class AiTagCommand : AiCatalogCommand() {
    @Serializable
    @SerialName("list_tag_categories")
    data class Categories(val after: Long = 0, val limit: Int = 50) : AiTagCommand()

    @Serializable
    @SerialName("list_tags")
    data class Tags(val after: Long = 0, val limit: Int = 50, @SerialName("category_id") val categoryId: Long? = null) : AiTagCommand()

    @Serializable
    @SerialName("get_item_tags")
    data class ItemTags(val item: AiStreamRef) : AiTagCommand()

    @Serializable
    @SerialName("search_tagged_library")
    data class Search(
        val query: String = "",
        val included: List<Long> = emptyList(),
        val excluded: List<Long> = emptyList(),
        @SerialName("match_all") val matchAll: Boolean = true,
        val untagged: Boolean = false,
        val sort: String = "library",
        val offset: Int = 0,
        val limit: Int = 50,
        @SerialName("expected_revision") val expectedRevision: String? = null
    ) : AiTagCommand()

    @Serializable
    @SerialName("create_tag_category")
    data class CreateCategory(val name: String) : AiTagCommand()

    @Serializable
    @SerialName("rename_tag_category")
    data class RenameCategory(@SerialName("category_id") val categoryId: Long, val name: String, @SerialName("expected_revision") val expectedRevision: Long) : AiTagCommand()

    @Serializable
    @SerialName("delete_tag_category")
    data class DeleteCategory(@SerialName("category_id") val categoryId: Long, @SerialName("expected_revision") val expectedRevision: Long) : AiTagCommand()

    @Serializable
    @SerialName("create_tag")
    data class CreateTag(val name: String, @SerialName("category_id") val categoryId: Long? = null) : AiTagCommand()

    @Serializable
    @SerialName("update_tag")
    data class UpdateTag(@SerialName("tag_id") val tagId: Long, val name: String, @SerialName("category_id") val categoryId: Long?, @SerialName("expected_revision") val expectedRevision: Long) : AiTagCommand()

    @Serializable
    @SerialName("delete_tag")
    data class DeleteTag(@SerialName("tag_id") val tagId: Long, @SerialName("expected_revision") val expectedRevision: Long) : AiTagCommand()

    @Serializable
    @SerialName("set_item_tags")
    data class SetTags(val item: AiStreamRef, @SerialName("tag_ids") val tagIds: List<Long>, @SerialName("expected_revision") val expectedRevision: Long) : AiTagCommand()
}

internal object AiTagProtocol {
    val capabilities = listOf("list_tag_categories", "list_tags", "get_item_tags", "search_tagged_library", "create_tag_category", "rename_tag_category", "delete_tag_category", "create_tag", "update_tag", "delete_tag", "set_item_tags")

    fun mutation(command: AiTagCommand) = command !is AiTagCommand.Categories && command !is AiTagCommand.Tags && command !is AiTagCommand.ItemTags && command !is AiTagCommand.Search

    fun scopes(command: AiTagCommand): Set<AiScope> = when (command) {
        is AiTagCommand.Categories, is AiTagCommand.Tags -> setOf(AiScope.TAGS_READ)
        is AiTagCommand.ItemTags, is AiTagCommand.Search -> setOf(AiScope.TAGS_READ, AiScope.LIBRARY_READ)
        is AiTagCommand.SetTags -> setOf(AiScope.TAGS_WRITE, AiScope.LIBRARY_READ)
        is AiTagCommand.DeleteTag, is AiTagCommand.DeleteCategory -> setOf(AiScope.TAGS_DELETE)
        else -> setOf(AiScope.TAGS_WRITE)
    }

    fun validate(command: AiTagCommand) {
        fun id(value: Long) = AiProtocol.checkInput(value > 0 && value <= 9_007_199_254_740_991L)
        fun revision(value: Long) = AiProtocol.checkInput(value >= 0 && value <= 9_007_199_254_740_991L)
        fun name(value: String) = AiProtocol.checkInput(value.trim().isNotEmpty() && value.length <= 80 && org.schabi.newpipe.custom.tagging.TagNames.validCharacters(value))
        fun page(after: Long, limit: Int) {
            revision(after)
            AiProtocol.checkInput(limit in 1..50)
        }
        fun refs(value: List<Long>) {
            AiProtocol.checkInput(value.size <= 500 && value.distinct().size == value.size)
            value.forEach(::id)
        }
        fun item(value: AiStreamRef) {
            id(value.streamId)
            AiProtocol.checkInput(value.service.length in 1..64)
            AiPublicUrlPolicy.validate(value.url)
        }
        when (command) {
            is AiTagCommand.Categories -> page(command.after, command.limit)

            is AiTagCommand.Tags -> {
                page(command.after, command.limit)
                command.categoryId?.let(::id)
            }

            is AiTagCommand.ItemTags -> item(command.item)

            is AiTagCommand.Search -> {
                AiProtocol.checkInput(command.query.length <= 200 && command.offset in 0..1000000 && command.limit in 1..50)
                refs(command.included)
                refs(command.excluded)
                AiProtocol.checkInput(command.included.intersect(command.excluded.toSet()).isEmpty())
                AiProtocol.checkInput(!command.untagged || command.included.isEmpty())
                AiProtocol.checkInput(command.sort in listOf("library", "title_asc", "title_desc"))
                AiProtocol.checkInput(command.expectedRevision == null || command.expectedRevision.matches(Regex("[0-9a-f]{64}")))
                AiProtocol.checkInput(command.offset == 0 || command.expectedRevision != null, "REVISION_REQUIRED")
            }

            is AiTagCommand.CreateCategory -> name(command.name)

            is AiTagCommand.RenameCategory -> {
                id(command.categoryId)
                name(command.name)
                revision(command.expectedRevision)
            }

            is AiTagCommand.DeleteCategory -> {
                id(command.categoryId)
                revision(command.expectedRevision)
            }

            is AiTagCommand.CreateTag -> {
                name(command.name)
                command.categoryId?.let(::id)
            }

            is AiTagCommand.UpdateTag -> {
                id(command.tagId)
                name(command.name)
                command.categoryId?.let(::id)
                revision(command.expectedRevision)
            }

            is AiTagCommand.DeleteTag -> {
                id(command.tagId)
                revision(command.expectedRevision)
            }

            is AiTagCommand.SetTags -> {
                item(command.item)
                refs(command.tagIds)
                revision(command.expectedRevision)
            }
        }
    }
}
