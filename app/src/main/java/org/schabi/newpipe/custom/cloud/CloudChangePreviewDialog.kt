/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R

/** Expandable native change explorer. Exact contents stay in the activity ViewModel, not saved-state bundles. */
class CloudChangePreviewDialog : DialogFragment() {
    private lateinit var model: CloudScreenModel
    private lateinit var summary: TextView
    private lateinit var adapter: Rows
    private var submitted = false
    private var previewId: String? = null
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        model = ViewModelProvider(requireActivity())[CloudScreenModel::class.java]
        previewId = model.state.value?.previewId
        val context = requireContext()
        val padding = (18 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        summary = TextView(context).apply {
            textSize = 14f
            setPadding(0, padding / 2, 0, padding / 2)
        }
        content.addView(summary)
        val search = EditText(context).apply {
            hint = getString(R.string.cloud_preview_search)
            setSingleLine()
            textSize = 15f
            isSaveEnabled = false
            contentDescription = getString(R.string.cloud_preview_search)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    model.search(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        content.addView(search)
        adapter = Rows { row ->
            when (row.action) {
                "expand", "collapse" -> model.toggle(row.group)
                "more" -> model.more(row.group)
            }
        }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@CloudChangePreviewDialog.adapter
            contentDescription = "Exact cloud library changes"
        }
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val dialog = AlertDialog.Builder(context).setTitle(R.string.cloud_preview_title).setView(content)
            .setNegativeButton(R.string.cancel) { _, _ -> model.cancelPreview() }
            .setPositiveButton(model.state.value?.confirmLabel ?: getString(R.string.ok), null).create()
        model.state.observe(this) { state ->
            summary.text = if (state.previewId == null) getString(R.string.cloud_preview_expired) else state.previewSummary
            (this.dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                text = state.confirmLabel
                isEnabled = state.previewId != null && state.previewId == previewId && !state.busy
            }
            if (submitted && !state.busy) {
                dismissAllowingStateLoss()
            } else if (state.previewId == null && !state.busy && isAdded) {
                dismissAllowingStateLoss()
            }
        }
        model.details.observe(this) { adapter.update(it) }
        return dialog
    }
    override fun onStart() {
        super.onStart()
        val dialog = dialog as? AlertDialog ?: return
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.94f).toInt())
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = previewId != null && model.state.value?.busy != true
            setOnClickListener {
                val id = previewId ?: return@setOnClickListener
                submitted = true
                isEnabled = false
                model.confirm(id)
            }
        }
    }
    override fun onCancel(dialog: DialogInterface) {
        if (!submitted) model.cancelPreview()
        super.onCancel(dialog)
    }
    private class Rows(private val clicked: (CloudScreenModel.Row) -> Unit) : RecyclerView.Adapter<Rows.Holder>() {
        private var rows: List<CloudScreenModel.Row> = emptyList()
        fun update(value: List<CloudScreenModel.Row>) {
            rows = value
            notifyDataSetChanged()
        }
        override fun getItemCount(): Int = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val p = (12 * parent.resources.displayMetrics.density).toInt()
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(p, p, p, p)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minimumHeight = p * 5
            }
            val title = TextView(context).apply { textSize = 16f }
            val detail = TextView(context).apply {
                textSize = 13f
                setPadding(0, p / 3, 0, 0)
            }
            row.addView(title)
            row.addView(detail)
            return Holder(row, title, detail)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val heading = row.action in setOf("expand", "collapse", "more")
            val prefix = when (row.action) {
                "expand" -> "▸ "
                "collapse" -> "▾ "
                "more" -> "+ "
                else -> if (row.kind.isBlank()) "" else "${row.kind} · "
            }
            holder.title.text = prefix + row.title
            holder.title.setTypeface(null, if (heading) Typeface.BOLD else Typeface.NORMAL)
            holder.detail.text = row.detail
            holder.detail.visibility = if (row.detail.isBlank()) View.GONE else View.VISIBLE
            holder.itemView.isClickable = heading
            holder.itemView.isFocusable = heading
            holder.itemView.setOnClickListener(if (heading) View.OnClickListener { clicked(row) } else null)
            holder.itemView.contentDescription = "${holder.title.text}. ${row.detail}"
        }
        class Holder(view: View, val title: TextView, val detail: TextView) : RecyclerView.ViewHolder(view)
    }
    companion object {
        const val TAG = "cloud-change-preview"
    }
}
