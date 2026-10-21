/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import org.schabi.newpipe.R
import org.schabi.newpipe.custom.cloud.CloudChangePreviewDialog
import org.schabi.newpipe.custom.cloud.CloudScreenModel

/** Independent fork settings screen. Credential inputs are never persisted by preferences or saved state. */
class CloudSyncSettingsFragment : BasePreferenceFragment() {
    private lateinit var model: CloudScreenModel
    private var credentialDialog: AlertDialog? = null
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        model = ViewModelProvider(requireActivity())[CloudScreenModel::class.java]
        addPreferencesFromResourceRegistry()
        preference<Preference>("cloud_connect").setOnPreferenceClickListener {
            connectDialog()
            true
        }
        preference<Preference>("cloud_reconnect").setOnPreferenceClickListener {
            if (model.state.value?.reconnectPending == true) model.resumeReconnect() else connectDialog(reconnect = true)
            true
        }
        preference<Preference>("cloud_check").setOnPreferenceClickListener {
            model.inspect()
            true
        }
        preference<Preference>("cloud_now").setOnPreferenceClickListener {
            model.syncNow()
            true
        }
        preference<Preference>("cloud_restore").setOnPreferenceClickListener {
            model.prepare(false)
            true
        }
        preference<Preference>("cloud_pending").setOnPreferenceClickListener {
            model.resolve()
            true
        }
        preference<SwitchPreferenceCompat>("cloud_enabled").setOnPreferenceChangeListener { _, value ->
            if (value == true) model.prepare(true) else model.disable()
            false // The switch changes only after durable admission, never before confirmation.
        }
        preference<Preference>("cloud_forget").setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext()).setTitle(R.string.cloud_forget)
                .setMessage(R.string.cloud_forget_confirmation).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.cloud_forget) { _, _ -> model.forget() }.show()
            true
        }
        preference<Preference>("cloud_remote_recovery").setOnPreferenceClickListener {
            if (parentFragmentManager.findFragmentByTag(org.schabi.newpipe.custom.cloud.CloudRecoveryDialog.TAG) == null) {
                org.schabi.newpipe.custom.cloud.CloudRecoveryDialog().show(parentFragmentManager, org.schabi.newpipe.custom.cloud.CloudRecoveryDialog.TAG)
            }
            true
        }
        preference<Preference>("cloud_replace").setOnPreferenceClickListener {
            if (parentFragmentManager.findFragmentByTag(org.schabi.newpipe.custom.cloud.CloudReplacementDialog.TAG) == null) {
                org.schabi.newpipe.custom.cloud.CloudReplacementDialog().show(parentFragmentManager, org.schabi.newpipe.custom.cloud.CloudReplacementDialog.TAG)
            }
            true
        }
        preference<Preference>("cloud_local_restore").setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext()).setTitle(R.string.cloud_local_recovery)
                .setMessage(R.string.cloud_local_recovery_warning).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ -> model.restoreLocalRecovery() }.show()
            true
        }
    }
    private fun <T : Preference> preference(key: String): T = checkNotNull(findPreference<T>(key))
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.state.observe(viewLifecycleOwner, ::render)
    }
    override fun onResume() {
        super.onResume()
        model.refresh()
        model.state.value?.let(::render)
    }
    private fun render(state: CloudScreenModel.Screen) {
        preference<Preference>("cloud_status").summary = state.message
        preference<Preference>("cloud_connect").apply {
            isEnabled = !state.connected && !state.busy
            summary = if (state.connected) "${state.origin}\nDevice: ${state.device}" else getString(R.string.cloud_connection_summary)
        }
        preference<Preference>("cloud_reconnect").apply {
            isVisible = state.connected
            isEnabled = state.connected && !state.busy
            setTitle(if (state.reconnectPending) R.string.cloud_resume_reconnect else R.string.cloud_reconnect)
            setSummary(if (state.reconnectPending) R.string.cloud_resume_reconnect_summary else R.string.cloud_reconnect_summary)
        }
        preference<SwitchPreferenceCompat>("cloud_enabled").apply {
            isChecked = state.enabled
            isEnabled = state.connected && !state.reconnectPending && (state.enabled || !state.busy)
        }
        for (key in listOf("cloud_check", "cloud_restore", "cloud_forget", "cloud_remote_recovery", "cloud_replace")) preference<Preference>(key).isEnabled = state.connected && !state.busy && !state.reconnectPending
        preference<Preference>("cloud_now").isEnabled = state.connected && state.enabled && !state.busy
        preference<Preference>("cloud_last").summary = state.lastSync
        preference<Preference>("cloud_pending").apply {
            isVisible = state.pending && !state.reconnectPending
            isEnabled = state.connected && !state.busy && !state.reconnectPending
        }
        preference<Preference>("cloud_local_restore").isEnabled = state.hasLocalRecovery && !state.busy && !state.reconnectPending
        if (state.previewId != null && !state.reconnectPending && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !parentFragmentManager.isStateSaved &&
            parentFragmentManager.findFragmentByTag(CloudChangePreviewDialog.TAG) == null
        ) {
            CloudChangePreviewDialog().show(parentFragmentManager, CloudChangePreviewDialog.TAG)
        }
    }
    private fun connectDialog(reconnect: Boolean = false) {
        if (credentialDialog?.isShowing == true) return
        val context = requireContext()
        val padding = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val origin = EditText(context).apply {
            hint = getString(R.string.cloud_worker_origin)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(if (reconnect) model.state.value?.origin.orEmpty() else "")
            isEnabled = !reconnect
            isSaveEnabled = false
            contentDescription = getString(R.string.cloud_worker_origin)
        }
        val token = EditText(context).apply {
            hint = getString(R.string.cloud_device_token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            isSaveEnabled = false
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            contentDescription = getString(R.string.cloud_device_token)
        }
        layout.addView(origin)
        layout.addView(token)
        layout.addView(
            TextView(context).apply {
                text = getString(if (reconnect) R.string.cloud_reconnect_explanation else R.string.cloud_token_explanation)
                setPadding(0, padding / 2, 0, padding / 2)
            }
        )
        val dialog = AlertDialog.Builder(context).setTitle(if (reconnect) R.string.cloud_reconnect else R.string.cloud_connection_title).setView(ScrollView(context).apply { addView(layout) })
            .setNegativeButton(R.string.cancel, null).setPositiveButton(if (reconnect) R.string.cloud_reconnect_button else R.string.cloud_connect_button, null).create()
        // Protect the window before its first visible frame, not in a later show callback.
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = origin.text.toString()
                val secret = token.text.toString().trim()
                if (url.isBlank() || secret.isBlank()) {
                    token.error = getString(R.string.cloud_device_token)
                    return@setOnClickListener
                }
                if (reconnect) model.reconnect(secret) else model.connect(url, secret)
                token.text.clear()
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            token.text.clear()
            credentialDialog = null
        }
        credentialDialog = dialog
        dialog.show()
    }
    override fun onDestroyView() {
        credentialDialog?.dismiss()
        credentialDialog = null
        super.onDestroyView()
    }
}
