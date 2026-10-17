/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.app.Dialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.util.ThemeHelper

/** Native management sheet. Categories own tags; deletion never removes saved media. */
class TagManagerFragment : BottomSheetDialogFragment() {
    companion object {
        @JvmStatic fun show(manager: FragmentManager, included: LongArray, excluded: LongArray) {
            if (!manager.isStateSaved && manager.findFragmentByTag("rv-tag-manager") == null) {
                TagManagerFragment().apply {
                    arguments = Bundle().apply {
                        putLongArray("included", included)
                        putLongArray("excluded", excluded)
                    }
                }.show(manager, "rv-tag-manager")
            }
        }
    }

    private lateinit var ui: TagUi
    private lateinit var repository: TagRepository
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var groups: LinearLayout
    private val disposables = CompositeDisposable()
    private var definitions = TagDefinitions(emptyList(), emptyList(), 0)
    private val expanded = HashSet<Long>()
    private var firstLoad = true
    private var included = emptySet<Long>()
    private var excluded = emptySet<Long>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        ui = TagUi(requireContext())
        repository = TagRuntime.get(requireContext())
        expanded.addAll(savedInstanceState?.getLongArray("expanded")?.toList().orEmpty())
        firstLoad = savedInstanceState == null
        included = arguments?.getLongArray("included")?.toSet().orEmpty()
        excluded = arguments?.getLongArray("excluded")?.toSet().orEmpty()
        val dialog = BottomSheetDialog(requireContext(), ThemeHelper.getDialogTheme(requireContext()))
        content = ui.column()
        content.addView(ui.text("Manage tags & categories", 21f, true), ui.params())
        status = ui.text("Loading…", 12f, color = ui.secondary)
        content.addView(status, ui.params())
        ui.space(content)
        val create = ui.row()
        create.addView(ui.button("+ Category") { TagForms.category(this, definitions) }, LinearLayout.LayoutParams(0, -2, 1f))
        create.addView(ui.button("+ Tag", true) { TagForms.tag(this, definitions) }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(create, ui.params())
        ui.space(content)
        val toggles = ui.row()
        toggles.addView(
            ui.button("Expand all") {
                expanded.addAll(definitions.categories.map { it.id })
                expanded.add(0)
                render()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        toggles.addView(
            ui.button("Collapse all") {
                expanded.clear()
                render()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        content.addView(toggles, ui.params())
        ui.space(content)
        groups = ui.column()
        content.addView(groups, ui.params())
        ui.space(content)
        content.addView(ui.button("Done", true) { dismiss() }, ui.params())
        ui.prepareSheet(dialog, content)
        TagForms.listen(this) { _, _, _ -> refresh() }
        refresh()
        return dialog
    }

    private fun refresh() {
        status.text = "Loading…"
        disposables.add(
            Single.fromCallable { repository.definitions() }
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ loaded ->
                    if (!isAdded) return@subscribe
                    definitions = loaded
                    if (firstLoad) {
                        loaded.categories.firstOrNull()?.let { expanded.add(it.id) }
                        firstLoad = false
                    }
                    status.text = "${loaded.tags.size} tags · ${loaded.categories.size} categories"
                    render()
                }, { status.text = ui.errorMessage(it) })
        )
    }

    private fun render() {
        groups.removeAllViews()
        for (category in definitions.categories) addGroup(category.id, category.name, category)
        addGroup(0, "Uncategorized", null)
        if (definitions.tags.isEmpty() && definitions.categories.isEmpty()) {
            groups.addView(ui.text("Create a category or start with flat tags.", color = ui.secondary), ui.params())
        }
    }

    private fun addGroup(id: Long, name: String, category: TagCategory?) {
        val tags = definitions.tags.filter { (it.categoryId ?: 0) == id }
        if (category == null && tags.isEmpty()) return
        val header = ui.row()
        val toggle = ui.button("${if (id in expanded) "▾" else "▸"} $name (${tags.size})") {
            if (!expanded.add(id)) expanded.remove(id)
            render()
        }
        toggle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        header.addView(toggle, LinearLayout.LayoutParams(0, -2, 1f))
        if (category != null) {
            header.addView(
                ui.button("⋮") {
                    ui.dialog().setTitle(category.name).setItems(arrayOf("Rename", "Delete category")) { _, choice ->
                        if (choice == 0) {
                            TagForms.category(this, definitions, category)
                        } else {
                            confirmDeletion(category.id, true, category.name)
                        }
                    }.show()
                }.apply { contentDescription = "Manage ${category.name}" },
                ui.params(48, -2)
            )
        }
        groups.addView(header, ui.params())
        if (id in expanded) {
            for (tag in tags) {
                val row = ui.row().apply { setPadding(ui.dp(12), 0, 0, 0) }
                row.addView(ui.text(tag.name, 15f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(
                    ui.button("Edit") {
                        ui.dialog().setTitle(tag.name).setItems(arrayOf("Rename or move", "Delete tag")) { _, action ->
                            if (action == 0) {
                                TagForms.tag(this, definitions, tag)
                            } else {
                                confirmDeletion(tag.id, false, tag.name)
                            }
                        }.show()
                    }.apply { contentDescription = "Edit ${tag.name} tag" },
                    ui.params(64, -2)
                )
                groups.addView(row, ui.params())
            }
            if (tags.isEmpty()) groups.addView(ui.text("No tags in this category.", 12f, color = ui.secondary), ui.params())
        } else {
            val active = tags.filter { it.id in included || it.id in excluded }
            if (active.isNotEmpty()) {
                val chips = TagFlowLayout(requireContext())
                for (tag in active) {
                    chips.addView(
                        ui.button((if (tag.id in included) "+ " else "− ") + tag.name) {
                            TagForms.tag(this, definitions, tag)
                        },
                        android.view.ViewGroup.MarginLayoutParams(-2, -2).apply {
                            rightMargin = ui.dp(7)
                            topMargin = ui.dp(6)
                        }
                    )
                }
                groups.addView(chips, ui.params())
            }
        }
        ui.space(groups)
    }

    private fun confirmDeletion(id: Long, category: Boolean, name: String) {
        disposables.add(
            Single.fromCallable { repository.deletionPlan(id, category) }
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ plan ->
                    if (!isAdded || parentFragmentManager.isStateSaved) return@subscribe
                    val confirmation = ui.dialog().setTitle("Delete $name?")
                        .setMessage("Deletes ${if (category) "this category and all ${plan.tags} contained tags" else "this tag"}, removing ${plan.assignments} tag assignments.\n\nVideos and playlist memberships are NOT deleted.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete", null).create()
                    confirmation.setOnShowListener {
                        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            confirmation.setCancelable(false)
                            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                            disposables.add(
                                Completable.fromAction { repository.delete(plan) }
                                    .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({
                                        confirmation.dismiss()
                                        refresh()
                                    }, { error ->
                                        confirmation.dismiss()
                                        status.text = ui.errorMessage(error)
                                    })
                            )
                        }
                    }
                    confirmation.show()
                }, { status.text = ui.errorMessage(it) })
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray("expanded", expanded.toLongArray())
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }
}
