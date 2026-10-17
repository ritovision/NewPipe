/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.util.ThemeHelper

/** Reusable SAME-SCREEN persistent editor for details, playlist menus and the Tags page. */
class TagEditorFragment : BottomSheetDialogFragment() {
    companion object {
        private const val FRAGMENT_TAG = "rv-persistent-tag-editor"

        @JvmStatic fun show(manager: FragmentManager, stream: StreamEntity) {
            if (manager.isStateSaved || manager.findFragmentByTag(FRAGMENT_TAG) != null) return
            TagEditorFragment().apply {
                arguments = Bundle().apply { putSerializable("stream", stream.copy()) }
            }.show(manager, FRAGMENT_TAG)
        }
    }

    private val disposables = CompositeDisposable()
    private val chosen = LinkedHashSet<Long>()
    private lateinit var ui: TagUi
    private lateinit var repository: TagRepository
    private lateinit var stream: StreamEntity
    private lateinit var choices: LinearLayout
    private lateinit var message: TextView
    private lateinit var applyButton: TextView
    private lateinit var cancelButton: TextView
    private lateinit var newTagButton: TextView
    private lateinit var newCategoryButton: TextView
    private var snapshot: TagEditSnapshot? = null
    private var search = ""
    private var restored: Bundle? = null

    @Suppress("DEPRECATION")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        stream = requireArguments().getSerializable("stream") as StreamEntity
        restored = savedInstanceState
        ui = TagUi(requireContext())
        repository = TagRuntime.get(requireContext())
        val sheet = BottomSheetDialog(requireContext(), ThemeHelper.getDialogTheme(requireContext()))
        val content = ui.column()
        content.addView(ui.text("Edit tags", 21f, true), ui.params())
        ui.space(content, 4)
        content.addView(ui.text(stream.title, 15f, color = ui.secondary), ui.params())
        message = ui.text("Loading saved tags…", 12f, color = ui.secondary)
        content.addView(message, ui.params())
        val find = ui.input("Find tags")
        content.addView(find, ui.params())
        choices = ui.column()
        content.addView(choices, ui.params())
        newTagButton = ui.button("+ New tag") {
            snapshot?.let { old ->
                TagForms.tag(ui, repository, old.definitions, disposables) { id ->
                    chosen.add(id)
                    reloadDefinitions()
                }
            }
        }.apply { isEnabled = false }
        newCategoryButton = ui.button("+ New category") {
            TagForms.category(ui, repository, disposables) { reloadDefinitions() }
        }.apply { isEnabled = false }
        content.addView(newTagButton, ui.params())
        ui.space(content)
        content.addView(newCategoryButton, ui.params())
        ui.space(content)
        content.addView(ui.text("Apply saves assignments. New tag/category definitions are saved when created.", 11f, color = ui.secondary), ui.params())
        ui.space(content)
        applyButton = ui.button("Apply", true) { applySelection() }.apply { isEnabled = false }
        content.addView(applyButton, ui.params())
        ui.space(content)
        cancelButton = ui.button("Cancel") { dismiss() }
        content.addView(cancelButton, ui.params())
        find.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                search = s?.toString().orEmpty()
                renderChoices()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        ui.prepareSheet(sheet, content)
        load()
        return sheet
    }

    private fun load() {
        disposables.add(
            Single.fromCallable { repository.editor(StreamKey(stream.serviceId, stream.url)) }
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ state ->
                    if (!isAdded) return@subscribe
                    snapshot = state
                    val pending = restored
                    if (pending?.getBoolean("loaded") == true) {
                        chosen.addAll(pending.getLongArray("selected")?.toList().orEmpty())
                        snapshot = state.copy(revision = pending.getLong("revision"))
                    } else {
                        chosen.addAll(state.assigned)
                    }
                    restored = null
                    message.text = "${chosen.size} selected · Apply to save"
                    applyButton.isEnabled = true
                    newTagButton.isEnabled = true
                    newCategoryButton.isEnabled = true
                    renderChoices()
                }, { error -> message.text = ui.errorMessage(error) })
        )
    }

    private fun reloadDefinitions() {
        disposables.add(
            Single.fromCallable { repository.definitions() }
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ definitions ->
                    snapshot = snapshot?.copy(definitions = definitions)
                    renderChoices()
                }, { error -> message.text = ui.errorMessage(error) })
        )
    }

    private fun renderChoices() {
        if (!::choices.isInitialized) return
        choices.removeAllViews()
        val state = snapshot ?: return
        val categories = state.definitions.categories.associate { it.id to it.name }
        val visible = state.definitions.tags.filter { it.name.contains(search, ignoreCase = true) }
        for (tag in visible) {
            choices.addView(
                AppCompatCheckBox(requireContext()).apply {
                    text = "${tag.name} · ${categories[tag.categoryId] ?: "Uncategorized"}"
                    textSize = 15f
                    setTextColor(ui.foreground)
                    buttonTintList = ColorStateList.valueOf(ui.accent)
                    minHeight = ui.dp(48)
                    isChecked = tag.id in chosen
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) chosen.add(tag.id) else chosen.remove(tag.id)
                        message.text = "${chosen.size} selected · Apply to save"
                    }
                },
                ui.params()
            )
        }
        if (visible.isEmpty()) choices.addView(ui.text("No matching tags. Create one below.", color = ui.secondary), ui.params())
    }

    private fun applySelection() {
        val state = snapshot ?: return
        val selected = chosen.toSet()
        applyButton.isEnabled = false
        newTagButton.isEnabled = false
        newCategoryButton.isEnabled = false
        cancelButton.isEnabled = false
        for (i in 0 until choices.childCount) choices.getChildAt(i).isEnabled = false
        isCancelable = false
        message.text = "Saving…"
        disposables.add(
            Completable.fromAction { repository.apply(stream, selected, state.revision) }
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({
                    if (isAdded) dismiss()
                }, { error ->
                    isCancelable = true
                    cancelButton.isEnabled = true
                    for (i in 0 until choices.childCount) choices.getChildAt(i).isEnabled = true
                    applyButton.isEnabled = true
                    newTagButton.isEnabled = true
                    newCategoryButton.isEnabled = true
                    message.text = ui.errorMessage(error)
                })
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        snapshot?.let {
            outState.putBoolean("loaded", true)
            outState.putLongArray("selected", chosen.toLongArray())
            outState.putLong("revision", it.revision)
        }
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }
}
