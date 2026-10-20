/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider

/** Complete source/destination changes and named backup rotation precede the only publish button. */
class CloudReplacementDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val model = ViewModelProvider(requireActivity())[CloudReplacementScreenModel::class.java]
        val context = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val summary = TextView(context).apply {
            textSize = 14f
            setPadding(0, 0, 0, padding / 2)
        }
        content.addView(summary)
        fun button(title: String, action: () -> Unit) = Button(context).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }
            content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val review = button("Review replacement") { model.prepare() }
        val refresh = button("Refresh") { model.inspect() }
        val resume = button("Resume approved attempt") { model.resume() }
        val resolve = button("Resolve for a fresh preview") { model.resolveForRetry() }
        val stop = button("Stop this attempt") {
            AlertDialog.Builder(context).setTitle("Resolve and stop this replacement?")
                .setMessage("A publication already committed will be resolved as completed. Otherwise its incoming request is retired and unfinished backup cleanup must be confirmed. Verified recovery copies are kept.")
                .setNegativeButton("Keep attempt", null)
                .setPositiveButton("Resolve and stop") { _, _ -> model.stop() }.show()
        }
        val cancel = button("Cancel preview") { model.cancelPreview() }
        val confirm = button("Replace cloud with this catalog") { model.state.value?.preview?.let(model::confirm) }
        val search = EditText(context).apply {
            hint = "Search all changes to the cloud"
            contentDescription = hint
            setSingleLine()
            isSaveEnabled = false
            // The activity model retains the filter when this editor is recreated.
            setText(model.query)
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString().orEmpty()
                    if (query != model.query) model.search(query)
                }
            })
        }
        content.addView(search)
        val adapter = CloudChangePreviewDialog.Rows {
            when (it.action) {
                "expand", "collapse" -> model.toggle(it.group)
                "more" -> model.more(it.group)
            }
        }
        val list = CloudChangePreviewDialog.scrollingContent(content, adapter, "Exact replacement changes and retained recovery copies")
        model.details.observe(this) {
            // A fresh preview can reset the filter while this dialog stays open.
            if (search.text.toString() != model.query) {
                search.setText(model.query)
                search.setSelection(search.length())
            }
            adapter.update(it)
        }
        model.state.observe(this) { s ->
            summary.text = s.message
            val reviewing = s.preview != null
            review.visibility = if (!reviewing) View.VISIBLE else View.GONE
            review.isEnabled = !s.busy && s.canPrepare
            refresh.visibility = review.visibility
            resume.visibility = if (!reviewing && s.pending && !s.stale) View.VISIBLE else View.GONE
            resolve.visibility = resume.visibility
            stop.visibility = if (!reviewing && s.pending) View.VISIBLE else View.GONE
            confirm.visibility = if (reviewing) View.VISIBLE else View.GONE
            cancel.visibility = confirm.visibility
            search.visibility = confirm.visibility
            for (b in listOf(refresh, resume, resolve, stop, confirm, cancel)) b.isEnabled = !s.busy
        }
        if (model.state.value?.loaded == false) model.inspect()
        return AlertDialog.Builder(context).setTitle("Replace cloud catalog").setView(list)
            .setNegativeButton("Close") { _, _ -> if (model.state.value?.preview != null && model.state.value?.busy != true) model.cancelPreview() }.create()
    }
    override fun onStart() {
        super.onStart()
        dialog?.let { CloudChangePreviewDialog.fitWindow(it, requireActivity().window.decorView) }
    }
    companion object {
        const val TAG = "cloud-replacement"
    }
}
