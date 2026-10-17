/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers

/** Same validated forms are used by the editor and category manager. Errors keep entered text. */
internal object TagForms {
    fun category(ui: TagUi, repository: TagRepository, disposables: CompositeDisposable, category: TagCategory? = null, saved: () -> Unit) {
        val input = ui.input("Category name").apply { setText(category?.name ?: "") }
        val content = ui.column().apply {
            setPadding(ui.dp(20), 0, ui.dp(20), 0)
            addView(input, ui.params())
        }
        val dialog = ui.dialog().setTitle(if (category == null) "New category" else "Rename category")
            .setView(content).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                try {
                    TagNames.clean(value)
                } catch (error: Exception) {
                    input.error = error.message
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                disposables.add(
                    Single.fromCallable {
                        if (category == null) {
                            repository.createCategory(value)
                        } else {
                            repository.renameCategory(category.id, value)
                            category.id
                        }
                    }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({
                        dialog.dismiss()
                        saved()
                    }, { error ->
                        dialog.setCancelable(true)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        input.error = ui.errorMessage(error)
                    })
                )
            }
        }
        dialog.show()
    }

    fun tag(ui: TagUi, repository: TagRepository, definitions: TagDefinitions, disposables: CompositeDisposable, tag: TagDefinition? = null, saved: (Long) -> Unit) {
        val input = ui.input("Tag name").apply { setText(tag?.name ?: "") }
        val categories = Spinner(ui.context).apply {
            adapter = ui.spinnerAdapter(listOf("Uncategorized") + definitions.categories.map { it.name })
            setSelection(definitions.categories.indexOfFirst { it.id == tag?.categoryId } + 1)
        }
        val content = ui.column().apply {
            setPadding(ui.dp(20), 0, ui.dp(20), 0)
            addView(input, ui.params())
            ui.space(this)
            addView(ui.text("Category (optional)", 12f, color = ui.secondary), ui.params())
            addView(categories, ui.params())
        }
        val dialog = ui.dialog().setTitle(if (tag == null) "New tag" else "Edit tag")
            .setView(content).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString()
                val category = definitions.categories.getOrNull(categories.selectedItemPosition - 1)?.id
                try {
                    TagNames.clean(name)
                } catch (error: Exception) {
                    input.error = error.message
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                disposables.add(
                    Single.fromCallable {
                        if (tag == null) {
                            repository.createTag(name, category)
                        } else {
                            repository.editTag(tag.id, name, category)
                            tag.id
                        }
                    }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ id ->
                        dialog.dismiss()
                        saved(id)
                    }, { error ->
                        dialog.setCancelable(true)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        input.error = ui.errorMessage(error)
                    })
                )
            }
        }
        dialog.show()
    }
}
