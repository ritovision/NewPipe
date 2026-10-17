/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers

/** Native tag/category definition form with retained task, draft and one-time parent result. */
class TagDefinitionDialog : DialogFragment() {
    private lateinit var model: TagDefinitionState
    private lateinit var input: EditText
    private lateinit var feedback: TextView
    private lateinit var ui: TagUi
    private var categoryForm = false
    private var existingId = 0L
    private var revision = 0L
    private var categoryChoice: Spinner? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        categoryForm = args.getBoolean("category")
        existingId = args.getLong("id")
        revision = args.getLong("revision")
        ui = TagUi(requireContext())
        model = ViewModelProvider(this)[TagDefinitionState::class.java]
        if (!model.initialized) {
            model.initialized = true
            model.name = savedInstanceState?.getString("draft") ?: args.getString("name").orEmpty()
            model.categoryId = (savedInstanceState?.getLong("chosen_category") ?: args.getLong("category_id")).takeIf { it > 0 }
            model.error = savedInstanceState?.getString("error")
            if (savedInstanceState?.getBoolean("was_saving") == true) {
                model.error = "The prior save was interrupted and may have finished. Check existing definitions before retrying. Your draft is retained."
            }
        }
        input = ui.input(if (categoryForm) "Category name" else "Tag name").apply { setText(model.name) }
        val body = ui.column().apply {
            setPadding(ui.dp(20), 0, ui.dp(20), 0)
            addView(input, ui.params())
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                model.name = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        if (!categoryForm) {
            ui.space(body)
            body.addView(ui.text("Category (optional)", 12f, color = ui.secondary), ui.params())
            val ids = args.getLongArray("category_ids") ?: longArrayOf()
            val names = args.getStringArray("category_names")?.toList().orEmpty()
            val categories = Spinner(requireContext()).apply {
                adapter = ui.spinnerAdapter(listOf("Uncategorized") + names)
                setSelection(ids.indexOf(model.categoryId ?: 0) + 1)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        model.categoryId = ids.getOrNull(position - 1)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
            categoryChoice = categories
            body.addView(categories, ui.params())
        }
        feedback = ui.text(model.error.orEmpty(), 12f, color = ui.secondary)
        body.addView(feedback, ui.params())
        val title = if (categoryForm) {
            if (existingId == 0L) "New category" else "Rename category"
        } else {
            if (existingId == 0L) "New tag" else "Edit tag"
        }
        val dialog = ui.dialog().setTitle(title).setView(body).setNegativeButton("Cancel") { _, _ -> dismiss() }.setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
            render(model.state.value ?: TagDefinitionState.Result.Idle)
        }
        model.state.observe(this) { render(it) }
        return dialog
    }

    private fun save() {
        val name = model.name
        try {
            TagNames.clean(name)
        } catch (error: Exception) {
            input.error = error.message
            return
        }
        input.error = null
        val repository = TagRuntime.get(requireContext())
        val id = existingId
        val expected = revision
        val categoryId = model.categoryId
        val category = categoryForm
        model.apply {
            if (category) {
                if (id == 0L) {
                    repository.createCategory(name)
                } else {
                    repository.renameCategory(id, name, expected)
                    id
                }
            } else {
                if (id == 0L) {
                    repository.createTag(name, categoryId)
                } else {
                    repository.editTag(id, name, categoryId, expected)
                    id
                }
            }
        }
    }

    private fun render(state: TagDefinitionState.Result) {
        if (!::input.isInitialized) return
        val busy = state is TagDefinitionState.Result.Saving || state is TagDefinitionState.Result.Saved
        isCancelable = !busy
        input.isEnabled = !busy
        categoryChoice?.isEnabled = !busy
        (dialog as? AlertDialog)?.apply {
            getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
            getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !busy
        }
        when (state) {
            is TagDefinitionState.Result.Failed -> {
                model.error = ui.errorMessage(state.error)
                input.error = model.error
                feedback.text = model.error
            }

            TagDefinitionState.Result.Saving -> feedback.text = "Saving…"

            is TagDefinitionState.Result.Saved -> {
                feedback.text = "Saved"
                deliverWhenResumed()
            }

            else -> feedback.text = model.error.orEmpty()
        }
    }

    private fun deliverWhenResumed() {
        val saved = model.state.value as? TagDefinitionState.Result.Saved ?: return
        if (!isAdded || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || parentFragmentManager.isStateSaved) return
        if (!model.delivered) {
            model.delivered = true
            parentFragmentManager.setFragmentResult(
                TagForms.RESULT,
                Bundle().apply {
                    putBoolean("category", categoryForm)
                    putBoolean("created", existingId == 0L)
                    putLong("id", saved.id)
                }
            )
        }
        dismiss()
    }

    override fun onResume() {
        super.onResume()
        input.post { deliverWhenResumed() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft", model.name)
        outState.putLong("chosen_category", model.categoryId ?: 0)
        outState.putString("error", model.error)
        outState.putBoolean("was_saving", model.state.value is TagDefinitionState.Result.Saving)
    }
}

class TagDefinitionState : ViewModel() {
    sealed class Result {
        data object Idle : Result()
        data object Saving : Result()
        data class Saved(val id: Long) : Result()
        data class Failed(val error: Throwable) : Result()
    }
    val state = MutableLiveData<Result>(Result.Idle)
    var initialized = false
    var name = ""
    var categoryId: Long? = null
    var error: String? = null
    var delivered = false
    private var task: Disposable? = null
    fun apply(operation: () -> Long) {
        if (state.value is Result.Saving || state.value is Result.Saved) return
        state.value = Result.Saving
        task = Single.fromCallable(operation).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe({ state.value = Result.Saved(it) }, { state.value = Result.Failed(it) })
    }
    override fun onCleared() {
        task?.dispose()
    }
}
