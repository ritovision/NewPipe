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
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider

/** Native, retained recovery browser with exact rotation and local-restore confirmation. */
class CloudRecoveryDialog : DialogFragment() {
    private lateinit var model: CloudRecoveryScreenModel
    private lateinit var summary: TextView
    private lateinit var settings: CheckBox
    private lateinit var actions: LinearLayout
    private lateinit var pending: LinearLayout
    private lateinit var search: EditText
    private lateinit var confirm: Button
    private lateinit var cancel: Button
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        model = ViewModelProvider(requireActivity())[CloudRecoveryScreenModel::class.java]
        val context = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        summary = TextView(context).apply {
            textSize = 14f
            setPadding(0, 0, 0, padding / 2)
        }
        content.addView(summary)
        fun row(): LinearLayout = LinearLayout(context).also { content.addView(it) }
        fun button(parent: LinearLayout, title: String, action: () -> Unit): Button = Button(context).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        actions = row()
        button(actions, "Back up device") { model.prepare("device-zip") }
        button(actions, "Archive cloud") { model.prepare("cloud-catalog") }
        button(actions, "Refresh") { model.inspect() }
        pending = row()
        button(pending, "Resume upload") { model.resume() }
        button(pending, "Stop upload") {
            AlertDialog.Builder(context).setTitle("Stop this unfinished backup?")
                .setMessage("Cleanup must be confirmed before its space can be reused. An already approved rotation may still remove the oldest copy; the other retained backups are kept.")
                .setNegativeButton("Keep upload", null).setPositiveButton("Stop upload") { _, _ -> model.abort() }.show()
        }
        settings = CheckBox(context).apply {
            text = "Also restore settings"
            isSaveEnabled = false
        }
        content.addView(settings)
        val confirmation = row()
        cancel = button(confirmation, "Cancel preview") { model.cancel() }
        confirm = button(confirmation, "Confirm") { model.state.value?.preview?.let { model.confirm(it, settings.isChecked) } }
        search = EditText(context).apply {
            hint = "Search all catalog changes"
            setSingleLine()
            isSaveEnabled = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    model.search(s?.toString().orEmpty())
                }
            })
        }
        content.addView(search)
        val adapter = CloudChangePreviewDialog.Rows {
            when (it.action) {
                "restore" -> model.prepareRestore(it.group)
                "expand", "collapse" -> model.toggle(it.group)
                "more" -> model.more(it.group)
            }
        }
        val list = CloudChangePreviewDialog.scrollingContent(content, adapter, "Recovery archives and exact catalog changes")
        model.details.observe(this) { adapter.update(it) }
        model.state.observe(this) { s ->
            summary.text = s.message
            actions.visibility = if (s.preview == null) View.VISIBLE else View.GONE
            pending.visibility = if (s.pending && s.preview == null) View.VISIBLE else View.GONE
            for (row in listOf(actions, pending)) for (i in 0 until row.childCount) row.getChildAt(i).isEnabled = !s.busy
            confirm.visibility = if (s.preview != null) View.VISIBLE else View.GONE
            cancel.visibility = confirm.visibility
            confirm.text = s.confirm
            confirm.isEnabled = !s.busy && s.preview != null
            cancel.isEnabled = !s.busy
            settings.visibility = if (s.nativeRestore) View.VISIBLE else View.GONE
            settings.isEnabled = !s.busy
            search.visibility = if (s.preview != null && !s.nativeRestore && s.confirm == "Restore on this device") View.VISIBLE else View.GONE
        }
        if (model.state.value?.loaded == false && model.state.value?.preview == null) model.inspect()
        return AlertDialog.Builder(context).setTitle("Cloud recovery backups").setView(list).setNegativeButton("Close", null).create()
    }
    override fun onStart() {
        super.onStart()
        dialog?.let {
            CloudChangePreviewDialog.fitWindow(it, requireActivity().window.decorView)
            it.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    companion object {
        const val TAG = "cloud-recovery"
    }
}
