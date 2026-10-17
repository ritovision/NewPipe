/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.os.Bundle
import androidx.fragment.app.Fragment

/** Child DialogFragments retain drafts/results across recreation, without retaining UI callbacks. */
internal object TagForms {
    const val RESULT = "rv-tag-definition-saved"
    const val DIALOG = "rv-tag-definition-dialog"

    fun listen(owner: Fragment, saved: (category: Boolean, created: Boolean, id: Long) -> Unit) {
        owner.childFragmentManager.setFragmentResultListener(RESULT, owner) { _, result ->
            saved(result.getBoolean("category"), result.getBoolean("created"), result.getLong("id"))
        }
    }

    fun category(owner: Fragment, definitions: TagDefinitions, category: TagCategory? = null) = show(owner, definitions, true, category?.id, category?.name.orEmpty(), null)
    fun tag(owner: Fragment, definitions: TagDefinitions, tag: TagDefinition? = null) = show(owner, definitions, false, tag?.id, tag?.name.orEmpty(), tag?.categoryId)

    private fun show(owner: Fragment, definitions: TagDefinitions, category: Boolean, id: Long?, name: String, categoryId: Long?) {
        val manager = owner.childFragmentManager
        if (manager.isStateSaved || manager.findFragmentByTag(DIALOG) != null) return
        TagDefinitionDialog().apply {
            arguments = Bundle().apply {
                putBoolean("category", category)
                putLong("id", id ?: 0)
                putString("name", name)
                putLong("category_id", categoryId ?: 0)
                putLong("revision", definitions.version)
                putLongArray("category_ids", definitions.categories.map { it.id }.toLongArray())
                putStringArray("category_names", definitions.categories.map { it.name }.toTypedArray())
            }
        }.show(manager, DIALOG)
    }
}
